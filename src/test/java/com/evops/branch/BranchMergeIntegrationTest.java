package com.evops.branch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.branch.dto.MergeRequest;
import com.evops.common.RequestContext;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import com.evops.turnover.TurnoverStatsMapper;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 区域合并（branch-2）回归用例。
 *
 * 背景缺陷：合并只搬树不改派归属时，被并网点名下车辆/订单仍挂在源编码上，
 * 而周转率等报表按 branch_code 扁平汇总（不按闭包层级上卷），源编码停用后
 * 这块数据从区域汇总里整块消失（数据其实还在）。本类守护修复后的口径：
 * 合并 = 整棵子树归属改派 + 子树迁移，同一事务，失败整体回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:branch-merge-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class BranchMergeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BranchService branchService;
    @Autowired
    private BranchMapper branchMapper;
    @Autowired
    private BranchClosureMapper closureMapper;
    @Autowired
    private BranchEventMapper eventMapper;
    @Autowired
    private VehicleMapper vehicleMapper;
    @Autowired
    private RentalOrderMapper orderMapper;
    @Autowired
    private TurnoverStatsMapper statsMapper;

    private final LocalDate today = LocalDate.now();
    private final String bizTs = today + "T10:00:00";

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 带幂等三件套的写请求。 */
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createBranch(String code, String name, Long parentId) throws Exception {
        StringBuilder body = new StringBuilder("{\"branchCode\":\"" + code + "\",\"name\":\"" + name + "\"");
        if (parentId != null) {
            body.append(",\"parentId\":").append(parentId);
        }
        MvcResult result = mockMvc.perform(write(post("/api/branches"), reqNo())
                        .content(body.append("}").toString()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createVehicle(String plateNo, String branchCode) throws Exception {
        String body = "{\"plateNo\":\"" + plateNo + "\",\"model\":\"M\",\"branchCode\":"
                + (branchCode == null ? "null" : "\"" + branchCode + "\"") + "}";
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"), reqNo()).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long vehicleId, String branchCode) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"张三\",\"branchCode\":"
                + (branchCode == null ? "null" : "\"" + branchCode + "\"") + "}";
        MvcResult result = mockMvc.perform(write(post("/api/orders"), reqNo())
                        .header("X-Biz-Time", bizTs).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void merge(long sourceId, long targetId) throws Exception {
        mockMvc.perform(write(post("/api/branches/" + sourceId + "/merge"), reqNo())
                        .content("{\"targetId\":" + targetId + ",\"reason\":\"区域合并\"}"))
                .andExpect(status().isOk());
    }

    /** 报表汇总行（车辆计数）按网点编码取值，大小写不敏感（与 TurnoverService 同口径）。 */
    private static Map<String, Object> bucketOf(List<Map<String, Object>> rows, String branchCode) {
        for (Map<String, Object> row : rows) {
            Object code = getIgnoreCase(row, "branchCode");
            if (code != null && branchCode.equalsIgnoreCase(String.valueOf(code))) {
                return row;
            }
        }
        return null;
    }

    private static int num(Map<String, Object> row, String key) {
        Object value = getIgnoreCase(row, key);
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Object getIgnoreCase(Map<String, Object> row, String key) {
        Object direct = row.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 核心回归：合并后归属不丢、报表按区域汇总一块不少
    // ------------------------------------------------------------------
    @Test
    void mergeReassignsSubtreeOwnershipAndKeepsReportWhole() throws Exception {
        String p = "M1-" + reqNo().substring(0, 8);
        // 树：R0 -> R1 -> A；R2 独立。合并 R1 整棵子树进 R2
        long r0 = createBranch(p + "-R0", "上级区域", null);
        long r1 = createBranch(p + "-R1", "被并区域", r0);
        long a = createBranch(p + "-A", "被并区域下辖网点", r1);
        long r2 = createBranch(p + "-R2", "目标区域", null);

        // 车辆：R1 两台、A 一台、R2 一台、未分派一台
        long v1 = createVehicle(p + "-V1", p + "-R1");
        createVehicle(p + "-V2", p + "-R1");
        createVehicle(p + "-V3", p + "-A");
        createVehicle(p + "-V4", p + "-R2");
        createVehicle(p + "-V5", null);
        // 租车单：R1/A/R2 各一笔（今日 CREATE 事件，供报表分子核对）
        long o1 = createOrder(p + "-O1", v1, p + "-R1");
        long o2 = createOrder(p + "-O2", v1, p + "-A");
        long o3 = createOrder(p + "-O3", v1, p + "-R2");

        // 合并前基线：各编码各自成桶
        List<Map<String, Object>> before = statsMapper.vehicleCountsByBranch();
        assertEquals(2, num(bucketOf(before, p + "-R1"), "idleCount"));
        assertEquals(1, num(bucketOf(before, p + "-A"), "idleCount"));
        assertEquals(1, num(bucketOf(before, p + "-R2"), "idleCount"));
        assertEquals(1, num(bucketOf(before, "NO-BRANCH"), "idleCount"));

        merge(r1, r2);

        // 归属改派：源子树（R1+A）车辆全部改挂 R2；R2 自身与未分派不动
        assertEquals(p + "-R2", vehicleMapper.selectById(v1).getBranchCode());
        Long r2Vehicles = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                .eq(Vehicle::getBranchCode, p + "-R2"));
        assertEquals(4L, r2Vehicles.longValue());
        Long r1Vehicles = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                .eq(Vehicle::getBranchCode, p + "-R1"));
        Long aVehicles = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                .eq(Vehicle::getBranchCode, p + "-A"));
        assertEquals(0L, r1Vehicles.longValue());
        assertEquals(0L, aVehicles.longValue());
        Long unassigned = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                .isNull(Vehicle::getBranchCode));
        assertEquals(1L, unassigned.longValue());

        // 租车单同步改派（在途/历史都随母表走，历史快照表不动）
        assertEquals(p + "-R2", orderMapper.selectById(o1).getBranchCode());
        assertEquals(p + "-R2", orderMapper.selectById(o2).getBranchCode());
        assertEquals(p + "-R2", orderMapper.selectById(o3).getBranchCode());

        // 树：R1 挂到 R2 下，A 跟随；R0 旧链路清干净；子树内部链路保留
        assertEquals(r2, branchMapper.selectById(r1).getParentId().longValue());
        assertEquals(1L, closureMapper.countLink(r2, r1));
        assertEquals(1L, closureMapper.countLink(r2, a));
        assertEquals(1L, closureMapper.countLink(r1, a));
        assertEquals(0L, closureMapper.countLink(r0, r1));
        assertEquals(0L, closureMapper.countLink(r0, a));

        // 报表口径（缺陷点）：R2 桶 = 自身 1 + 改派 3，源编码不再成桶，未分派不受影响
        List<Map<String, Object>> after = statsMapper.vehicleCountsByBranch();
        assertEquals(4, num(bucketOf(after, p + "-R2"), "idleCount"));
        assertNull(bucketOf(after, p + "-R1"), "被并网点编码不应再出现在汇总里");
        assertNull(bucketOf(after, p + "-A"), "被并子网点编码不应再出现在汇总里");
        assertEquals(1, num(bucketOf(after, "NO-BRANCH"), "idleCount"));
        // 分子（今日下单事件）同步归集到 R2
        List<Map<String, Object>> events = statsMapper.eventCountsByBranch(today);
        assertEquals(3, num(bucketOf(events, p + "-R2"), "createdCount"));
        assertNull(bucketOf(events, p + "-R1"));
        assertNull(bucketOf(events, p + "-A"));

        // 接口可见归属已变；MERGE 事件留痕含改派行数（审计可按数核对）
        mockMvc.perform(get("/api/vehicles/" + v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.branchCode").value(p + "-R2"));
        List<BranchEvent> merges = eventMapper.selectList(new LambdaQueryWrapper<BranchEvent>()
                .eq(BranchEvent::getBranchId, r1)
                .eq(BranchEvent::getEventType, "MERGE"));
        assertEquals(1, merges.size());
        assertTrue(merges.get(0).getDetail().contains("改派车辆 3 台、租车单 2 笔"),
                "留痕应含改派行数: " + merges.get(0).getDetail());
    }

    // ------------------------------------------------------------------
    // 迁移事务内完成：中途失败整体回滚（归属不留半截改派、树上不留半截链路）
    // ------------------------------------------------------------------
    @Test
    void mergeRollbackOnMidwayFailure() throws Exception {
        String p = "M2-" + reqNo().substring(0, 8);
        long r0 = createBranch(p + "-R0", "上级区域", null);
        long r1 = createBranch(p + "-R1", "被并区域", r0);
        long a = createBranch(p + "-A", "下辖网点", r1);
        long r2 = createBranch(p + "-R2", "目标区域", null);
        long v1 = createVehicle(p + "-V1", p + "-R1");
        long o1 = createOrder(p + "-O1", v1, p + "-A");

        // 直接调服务层：留痕处制造失败（原因超长触发 detail 列长度约束）。
        // 期望：同事务里已执行的归属改派、闭包链路、parent_id 全部回滚。
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            MergeRequest merge = new MergeRequest();
            merge.setTargetId(r2);
            StringBuilder reason = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                reason.append('调');
            }
            merge.setReason(reason.toString());
            assertThrows(Exception.class, () -> branchService.merge(r1, merge));

            // 归属未动（改派已回滚）
            assertEquals(p + "-R1", vehicleMapper.selectById(v1).getBranchCode());
            assertEquals(p + "-A", orderMapper.selectById(o1).getBranchCode());
            // 树未动：旧链路还在、新链路没接上、parent_id 未变
            assertEquals(1L, closureMapper.countLink(r0, r1));
            assertEquals(1L, closureMapper.countLink(r0, a));
            assertEquals(0L, closureMapper.countLink(r2, r1));
            assertEquals(r0, branchMapper.selectById(r1).getParentId().longValue());
            // 无 MERGE 事件
            Long merges = eventMapper.selectCount(new LambdaQueryWrapper<BranchEvent>()
                    .eq(BranchEvent::getBranchId, r1)
                    .eq(BranchEvent::getEventType, "MERGE"));
            assertEquals(0L, merges.longValue());
        } finally {
            RequestContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // 合并校验：自身/目标不存在/目标在源子树内（成环）一律拒绝，归属不动
    // ------------------------------------------------------------------
    @Test
    void mergeValidationRejected() throws Exception {
        String p = "M3-" + reqNo().substring(0, 8);
        long r1 = createBranch(p + "-R1", "区域一", null);
        long a = createBranch(p + "-A", "下辖网点", r1);
        long v1 = createVehicle(p + "-V1", p + "-R1");

        // 合并到自身
        mockMvc.perform(write(post("/api/branches/" + r1 + "/merge"), reqNo())
                        .content("{\"targetId\":" + r1 + "}"))
                .andExpect(status().isConflict());
        // 目标不存在
        mockMvc.perform(write(post("/api/branches/" + r1 + "/merge"), reqNo())
                        .content("{\"targetId\":999999}"))
                .andExpect(status().isConflict());
        // 目标落在源子树内（成环）
        mockMvc.perform(write(post("/api/branches/" + r1 + "/merge"), reqNo())
                        .content("{\"targetId\":" + a + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("成环")));
        // 缺 targetId -> 参数校验 400
        mockMvc.perform(write(post("/api/branches/" + r1 + "/merge"), reqNo())
                        .content("{\"reason\":\"缺目标\"}"))
                .andExpect(status().isBadRequest());

        // 归属与树保持原样
        assertEquals(p + "-R1", vehicleMapper.selectById(v1).getBranchCode());
        assertEquals(1L, closureMapper.countLink(r1, a));
        assertEquals(0, branchMapper.selectById(r1).getParentId().longValue());
        Long merges = eventMapper.selectCount(new LambdaQueryWrapper<BranchEvent>()
                .eq(BranchEvent::getEventType, "MERGE"));
        assertEquals(0L, merges.longValue());
    }
}
