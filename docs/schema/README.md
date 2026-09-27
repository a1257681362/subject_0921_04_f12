# 租车单表结构版本管理说明（rental-schema-1）

租车单相关表结构自本期起由 **Flyway** 脚本化版本管理，替代原先"单体 schema.sql 幂等脚本 +
手工版本表"的方式。本文是交接与排障的固定说明：分工、命名规范、四条硬约定、
配置项与默认值、新增变更流程、常见故障排查。

## 1. 分工：谁管哪张表

| 管理方式 | 范围 |
| --- | --- |
| Flyway 迁移脚本 `src/main/resources/db/migration/` | 租车单相关七表：`t_vehicle`、`t_rental_order`、`t_rental_handover`、`t_rental_order_event`、`t_rental_order_transition`、`t_rental_order_name_index`、`t_idempotent_request` |
| `src/main/resources/schema.sql`（`spring.sql.init`，幂等） | 其余全部表（网点/对账/定位/照片/账号/周转率/档期等）及骨架版本表 `t_evops_schema_version` |

两边**互不越界**：迁移脚本只许动租车七表；schema.sql 禁止再出现这七张表的 DDL
（文件内已有指针注释）。两套机制在同一数据源上各自独立执行，无交叉依赖。

## 2. 脚本命名规范（固定）

- 位置固定：`src/main/resources/db/migration/`。
- 文件名固定：`V<版本号>__<说明>.sql`
  - 版本号：从 1 开始的单调递增整数（`V1`、`V2`、`V3`…），不复用、不回填、不跳号占位；
  - 分隔符为**两个下划线** `__`；
  - 说明：小写英文蛇形（单词间单下划线），与特性对应，如 `V2__rental_order_add_xxx.sql`。
- 一个脚本承载一个逻辑变更；脚本头部注释写明背景与影响面。
- 当前脚本清单：

| 脚本 | 内容 |
| --- | --- |
| `V1__rental_baseline.sql` | 租车七表基线：原 schema.sql 租车切片（rental-1 / rental-name-search-1 / rental-fsm-1 及 recon-1、turnover-1 补列）原样收编 |

## 3. 四条硬约定

### 3.1 已执行脚本不许改

- `spring.flyway.validate-on-migrate: true`：每次启动比对留档校验和与脚本内容，
  **被改过的已执行脚本会直接拒绝启动**（报 `Migration checksum mismatch`）。
- 正确做法：任何变更一律**新增** `V<下一版本>__<说明>.sql`，旧脚本一个字节都不动
  （包括"只改个注释"——校验和同样会变）。

### 3.2 升级可重跑

- 迁移在应用启动时自动执行（`spring.flyway.enabled: true`），无需手工步骤；
  已成功迁移的库重复启动是空操作（留档比对，无新脚本不执行）。
- 脚本内全部语句幂等：`CREATE ... IF NOT EXISTS`、`ALTER ... IF NOT EXISTS / IF EXISTS`、
  `MERGE INTO`，重复执行结果不变。
- **失败后的重跑**（重要，与直觉不同）：Flyway 在 H2 上不按事务包裹迁移
  （`supportsDdlTransactions=false`），脚本中途失败时**已执行的语句会留在库中**，
  且留档表记入一行 `success=false`。此时直接重启会被校验拦下
  （`Detected failed migration to version N`）。恢复步骤：
  1. 按报错行号修复脚本（未成功执行的脚本允许改）；
  2. 执行 `mvn flyway:repair` 清除失败留档行
     （目标库默认开发文件库，其他环境用 `-Devops.flyway.db.url=...` 指定）；
  3. 重启应用自动重跑——因为语句全幂等，库里已存在的半截对象不会导致二次失败，
     重跑后结构与全新库一致（有测试守护）。
- 正因 H2 上失败会留半截，**脚本语句必须幂等**是硬性要求，新增脚本评审时逐条检查。

### 3.3 变更记录留档

- 每次执行写入 `flyway_schema_history`（库内可查）：

| 列 | 含义 |
| --- | --- |
| `installed_rank` | 执行顺序号 |
| `version` / `description` | 版本号 / 描述（取自文件名） |
| `type` | `BASELINE`（基线标记）/ `SQL`（迁移脚本） |
| `script` | 脚本文件名 |
| `checksum` | 脚本校验和（"不许改"的核对依据） |
| `installed_by` / `installed_on` | 执行人 / 执行时间 |
| `execution_time` | 耗时（毫秒） |
| `success` | 是否成功 |

- 骨架表 `t_evops_schema_version` 继续作为**特性交付档案**（rental-1 等历史行保留），
  与 Flyway 留档互补：一个记"交付了什么特性"，一个记"结构什么时候怎么变的"。

### 3.4 失败定位到脚本行

迁移失败时启动报错直接给出**脚本名、行号、失败语句**（真实输出示例）：

```
Migration V1__broken.sql failed
-------------------------------
SQL State  : 42001
Error Code : 42001
Message    : Syntax error in SQL statement "[*]THIS IS NOT VALID SQL"; ...
Location   : .../db/migration/V1__broken.sql
Line       : 2
Statement  : THIS IS NOT VALID SQL
```

排查步骤：按 `Line` 定位到脚本行 → 修复脚本（未成功执行的脚本可直接改）→
`mvn flyway:repair` 清失败留档行 → 重启自动重跑（见 3.2）。

## 4. 基线与存量结构一致

- `V1__rental_baseline.sql` 是存量 schema.sql 租车切片的**原样收编**（语句与顺序一致），
  新库直接建成最终结构，老库经幂等补列收敛到同一结构。
- 存量库接管：`baseline-on-migrate: true` + `baseline-version: 0` —— 无 `flyway_schema_history`
  的非空老库先写入基线标记（version 0），再幂等执行 V1 完成收敛，全程不动存量数据。
- 该一致性由 `RentalSchemaMigrationTest` 持续守护（存量脚本快照
  `src/test/resources/db/legacy/rental-legacy.sql` 为基准，逐语句比对两库结构签名）。

## 5. 配置项与默认值（交付说明）

应用配置位于 `application.yml` 的 `spring.flyway`（测试配置同）：

| 配置项 | 本工作区取值 | 框架默认值 | 说明 |
| --- | --- | --- | --- |
| `spring.flyway.enabled` | `true` | `true`（引入 flyway-core 即启用） | 启动时自动执行迁移 |
| `spring.flyway.locations` | `classpath:db/migration` | `classpath:db/migration` | 迁移脚本目录 |
| `spring.flyway.table` | `flyway_schema_history` | `flyway_schema_history` | 变更留档表名 |
| `spring.flyway.baseline-on-migrate` | `true` | `false` | 存量非空库自动打基线标记后接管 |
| `spring.flyway.baseline-version` | `0` | `1` | 基线标记版本号；取 0 使 V1 在老库也幂等执行、收敛结构 |
| `spring.flyway.validate-on-migrate` | `true` | `true` | 已执行脚本校验和核对，被篡改拒绝启动 |
| `spring.flyway.clean-disabled` | `true` | `false` | 禁用 clean，防误清库 |
| `spring.flyway.encoding` | `UTF-8` | `UTF-8` | 脚本编码（脚本含中文注释，勿改） |

既有相关配置（未变）：`spring.sql.init.mode: always`、
`spring.sql.init.schema-locations: classpath:schema.sql` —— 继续负责租车七表以外的表。

## 6. 新增一次租车表结构变更的流程

1. 在 `src/main/resources/db/migration/` 新增 `V<下一版本>__<说明>.sql`，
   语句全部幂等（`IF NOT EXISTS` / `IF EXISTS` / `MERGE INTO`），只动租车七表；
2. 如需记特性档案，在 `schema.sql` 末尾追加 `t_evops_schema_version` 的 MERGE 行
   （注意：迁移脚本不得引用该表，它由 sql.init 建设）；
3. 本地验证：`mvn test -Dtest=RentalSchemaMigrationTest` 后跑全量 `mvn test`；
4. 交付：脚本随代码入库即完成留档，无需额外登记。

禁止事项：改已执行脚本、在 schema.sql 写租车七表 DDL、版本号回填/复用。

## 7. 常见故障与排查

| 现象 | 原因与处理 |
| --- | --- |
| 启动报 `Migration checksum mismatch for migration version N` | 已执行脚本被改动。找回原脚本内容恢复；确需变更走新增脚本。本地开发库可删 `data/` 目录重建（仅限开发库） |
| 启动报 `Migration VN__xxx.sql failed ... Line ...` | 按行号修脚本（未成功执行的脚本可直接改）；随后见下一行处理失败留档行 |
| 启动报 `Detected failed migration to version N` | 上次迁移失败留下了失败留档行。执行 `mvn flyway:repair` 清除（其他环境加 `-Devops.flyway.db.url=...`），确认脚本已修复后重启；脚本全幂等，半截对象不碍重跑 |
| 想看结构变更历史 | `SELECT "version", "description", "script", "installed_on", "execution_time", "success" FROM "flyway_schema_history" WHERE "type" <> 'TABLE' ORDER BY "installed_rank";`（H2 中该表表名与列名均为小写带引号；`type='TABLE'` 是建表标记行，查询时过滤） |
| 老库首次启动看到 `BASELINE` 行 | 正常：存量库接管标记（version 0），随后 V1 幂等收敛 |

手工修复工具：pom 中已声明 `flyway-maven-plugin`（不绑定构建阶段，仅手工调用），
目标库默认开发文件库（`evops.flyway.db.url` 属性，可用 `-Devops.flyway.db.url=...` 覆盖）。
迁移本身在应用启动时自动执行，不经过该插件。

机制验证入口：`src/test/java/com/evops/rental/RentalSchemaMigrationTest.java`
（基线一致 / 可重跑 / 存量接管 / 留档 / 篡改拒启 / 失败定位与修复重跑，共 6 用例）。
