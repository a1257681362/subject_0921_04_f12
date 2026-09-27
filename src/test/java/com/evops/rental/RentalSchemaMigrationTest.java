package com.evops.rental;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租车单表结构版本管理机制（rental-schema-1）测试，逐条对应交付约定：
 * <ul>
 *   <li>基线与存量结构一致：V1 在全新库建出的结构与存量脚本完全一致；</li>
 *   <li>升级可重跑：重复执行迁移不出错、不产生变更；失败修复后 repair 再跑可收敛；</li>
 *   <li>存量库平滑接管：无留档表的老库自动打基线并收敛到同一结构；</li>
 *   <li>变更记录留档：flyway_schema_history 逐次记录版本/脚本/校验和/耗时/成败；</li>
 *   <li>已执行脚本不许改：校验和不一致直接拒绝启动；</li>
 *   <li>失败定位到脚本行：报错含脚本名、行号、失败语句，失败行同样留档。</li>
 * </ul>
 * 纯 JDBC + Flyway API，不起 Spring 上下文，跑得快。
 */
class RentalSchemaMigrationTest {

    /** 归 Flyway 管理的租车单相关七表（与 docs/schema/README.md 范围一致） */
    private static final List<String> RENTAL_TABLES = Arrays.asList(
            "T_VEHICLE", "T_RENTAL_ORDER", "T_RENTAL_HANDOVER", "T_RENTAL_ORDER_EVENT",
            "T_RENTAL_ORDER_TRANSITION", "T_RENTAL_ORDER_NAME_INDEX", "T_IDEMPOTENT_REQUEST");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String LEGACY_SCRIPT = "/db/legacy/rental-legacy.sql";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 基线与存量结构一致
    // ------------------------------------------------------------------
    @Test
    void baselineMatchesLegacyStructureOnFreshDatabase() throws Exception {
        String legacyUrl = newMemUrl();
        runSqlScript(legacyUrl, LEGACY_SCRIPT);

        String migratedUrl = newMemUrl();
        newFlyway(migratedUrl, MIGRATION_LOCATION).migrate();

        assertEquals(schemaSignature(legacyUrl), schemaSignature(migratedUrl),
                "V1 基线建出的租车表结构必须与存量脚本完全一致");
    }

    // ------------------------------------------------------------------
    // 升级可重跑：重复执行是空操作，结构不漂移
    // ------------------------------------------------------------------
    @Test
    void migrateIsRerunnable() throws Exception {
        String url = newMemUrl();
        MigrateResult first = newFlyway(url, MIGRATION_LOCATION).migrate();
        assertEquals(1, first.migrationsExecuted, "首次执行应应用 V1 一个脚本");

        // 模拟重启后再跑：无新脚本时零变更、不报错
        MigrateResult second = newFlyway(url, MIGRATION_LOCATION).migrate();
        assertEquals(0, second.migrationsExecuted, "重复执行不应再应用任何脚本");

        String legacyUrl = newMemUrl();
        runSqlScript(legacyUrl, LEGACY_SCRIPT);
        assertEquals(schemaSignature(legacyUrl), schemaSignature(url), "重跑后结构不得漂移");
    }

    // ------------------------------------------------------------------
    // 存量库接管：老脚本建成的库（无留档表）自动打基线并收敛
    // ------------------------------------------------------------------
    @Test
    void migrateConvergesPreExistingDatabase() throws Exception {
        // 存量库：由 Flyway 接管前的老脚本建成，没有 flyway_schema_history
        String existingUrl = newMemUrl();
        runSqlScript(existingUrl, LEGACY_SCRIPT);

        MigrateResult result = newFlyway(existingUrl, MIGRATION_LOCATION).migrate();
        assertEquals(1, result.migrationsExecuted, "存量库应幂等执行 V1 完成收敛");

        // 接管后结构与全新迁移库一致
        String freshUrl = newMemUrl();
        newFlyway(freshUrl, MIGRATION_LOCATION).migrate();
        assertEquals(schemaSignature(freshUrl), schemaSignature(existingUrl),
                "存量库接管后的结构必须与全新迁移库一致");

        // 留档：基线标记（version 0）+ V1 执行记录（排除建表标记行 type=TABLE）
        try (Connection conn = DriverManager.getConnection(existingUrl, "sa", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"version\", \"type\", \"success\" FROM \"flyway_schema_history\""
                             + " WHERE \"type\" <> 'TABLE' ORDER BY \"installed_rank\"")) {
            assertTrue(rs.next(), "应存在基线标记行");
            assertEquals("0", rs.getString("version"));
            assertEquals("BASELINE", rs.getString("type"));
            assertTrue(rs.next(), "应存在 V1 执行记录");
            assertEquals("1", rs.getString("version"));
            assertEquals("SQL", rs.getString("type"));
            assertTrue(rs.getBoolean("success"));
            assertFalse(rs.next(), "留档不应有多余行");
        }
    }

    // ------------------------------------------------------------------
    // 变更记录留档：版本/描述/脚本名/校验和/耗时/成败逐次可查
    // ------------------------------------------------------------------
    @Test
    void historyArchivesEveryExecution() throws Exception {
        String url = newMemUrl();
        newFlyway(url, MIGRATION_LOCATION).migrate();

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"version\", \"description\", \"script\", \"checksum\", \"installed_on\", \"execution_time\", \"success\""
                             + " FROM \"flyway_schema_history\" WHERE \"type\" <> 'TABLE'")) {
            assertTrue(rs.next(), "留档表应有 V1 执行记录");
            assertEquals("1", rs.getString("version"));
            assertEquals("rental baseline", rs.getString("description"));
            assertEquals("V1__rental_baseline.sql", rs.getString("script"));
            assertNotNull(rs.getObject("checksum"), "校验和必须留档（已执行脚本不许改的核对依据）");
            assertNotNull(rs.getTimestamp("installed_on"));
            assertTrue(rs.getInt("execution_time") >= 0);
            assertTrue(rs.getBoolean("success"));
            assertFalse(rs.next(), "全新库只应有一行 V1 留档");
        }
    }

    // ------------------------------------------------------------------
    // 已执行脚本不许改：校验和不一致直接拒绝
    // ------------------------------------------------------------------
    @Test
    void tamperedExecutedScriptIsRejected() throws Exception {
        Path script = tempDir.resolve("V1__rental_baseline.sql");
        Files.copy(Paths.get(getClass().getResource("/db/migration/V1__rental_baseline.sql").toURI()), script);

        String url = newMemUrl();
        newFlyway(url, "filesystem:" + tempDir).migrate();

        // 篡改已执行脚本（哪怕只是追加一行注释，校验和也会变）
        Files.write(script, "\n-- tampered after execution\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        FlywayException ex = assertThrows(FlywayException.class,
                () -> newFlyway(url, "filesystem:" + tempDir).migrate(),
                "已执行脚本被篡改必须拒绝启动");
        assertTrue(ex.getMessage().toLowerCase().contains("checksum"),
                "报错应指明校验和不符: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 失败定位到脚本行 + 失败后可重跑：
    // 报错含脚本名/行号/失败语句；留档表记失败行；未修复直接重跑被校验拒绝；
    // 修复脚本（未成功执行的脚本允许改）后 repair 清失败行，重跑收敛。
    // 注：Flyway 在 H2 上不按事务包裹迁移（supportsDdlTransactions=false），
    //     失败时已执行语句会留在库中——脚本全幂等正是重跑能收敛的前提。
    // ------------------------------------------------------------------
    @Test
    void failureLocatesScriptAndLineAndRerunConverges() throws Exception {
        Path broken = tempDir.resolve("V1__broken.sql");
        Files.write(broken, Arrays.asList(
                "CREATE TABLE t_broken_demo (id INT);",
                "THIS IS NOT VALID SQL;"), StandardCharsets.UTF_8);

        String url = newMemUrl();
        String location = "filesystem:" + tempDir;

        // 1) 失败报错：脚本名 + 行号 + 失败语句
        FlywayException ex = assertThrows(FlywayException.class,
                () -> newFlyway(url, location).migrate());
        String message = ex.getMessage();
        assertTrue(message.contains("V1__broken.sql"), "报错应含脚本名: " + message);
        assertTrue(message.matches("(?s).*Line\\s*:\\s*2\\b.*"), "报错应含失败行号 2: " + message);
        assertTrue(message.contains("THIS IS NOT VALID SQL"), "报错应含失败语句: " + message);

        // 2) 留档表记下失败行（success=false），变更历史完整可查
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"success\" FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
            assertTrue(rs.next(), "失败也必须有留档行");
            assertFalse(rs.getBoolean("success"), "失败行 success 应为 false");
        }

        // 3) 未修复直接重跑：校验拒绝并提示 repair
        FlywayException blocked = assertThrows(FlywayException.class,
                () -> newFlyway(url, location).migrate());
        assertTrue(blocked.getMessage().contains("Detected failed migration"),
                "存在失败行时应拒绝重跑: " + blocked.getMessage());

        // 4) 修复脚本（第一句已留库，修复稿必须用幂等写法）→ repair 清失败行 → 重跑收敛
        Files.write(broken, Arrays.asList(
                "CREATE TABLE IF NOT EXISTS t_broken_demo (id INT);",
                "CREATE INDEX IF NOT EXISTS idx_broken_demo ON t_broken_demo(id);"),
                StandardCharsets.UTF_8);
        Flyway fixed = newFlyway(url, location);
        fixed.repair();
        MigrateResult rerun = fixed.migrate();
        assertEquals(1, rerun.migrationsExecuted, "修复后重跑应应用修复稿");

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'T_BROKEN_DEMO'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "重跑后目标表应存在");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String newMemUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    /** 与应用配置（application.yml spring.flyway）保持一致 */
    private static Flyway newFlyway(String url, String location) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(location)
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .encoding("UTF-8")
                .load();
    }

    private static void runSqlScript(String url, String classpathResource) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             InputStream in = RentalSchemaMigrationTest.class.getResourceAsStream(classpathResource)) {
            assertNotNull(in, "测试夹具缺失: " + classpathResource);
            RunScript.execute(conn, new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /**
     * 库结构签名：H2 SCRIPT NODATA 导出租车七表的全部 DDL 语句，
     * 归一化 H2 自动生成的约束/索引名后排序，供两库逐一比对。
     */
    private static List<String> schemaSignature(String url) throws Exception {
        List<String> statements = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SCRIPT NODATA")) {
            while (rs.next()) {
                statements.add(rs.getString(1));
            }
        }
        return statements.stream()
                .map(s -> s.replaceAll("\\s+", " ").trim())
                .filter(RentalSchemaMigrationTest::isRentalStatement)
                .map(s -> s.replaceAll("CONSTRAINT_INDEX_\\d+", "CONSTRAINT_INDEX_#")
                        .replaceAll("CONSTRAINT_\\d+", "CONSTRAINT_#"))
                .sorted()
                .collect(Collectors.toList());
    }

    private static boolean isRentalStatement(String statement) {
        for (String table : RENTAL_TABLES) {
            if (statement.contains(table)) {
                return true;
            }
        }
        return false;
    }
}
