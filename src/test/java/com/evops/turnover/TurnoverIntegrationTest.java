package com.evops.turnover;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 周转率统计集成测试：
 * 计数口径（车辆台账分母 + 订单事件流水分子）、当天可重算、关账冻结拒绝改写、
 * 关账后冲正（原因必填、append-only、基线/冲正/最终值三视图）、作业留痕、鉴权。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:turnover-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.auth.enabled=true"
})
class TurnoverIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private final LocalDate today = LocalDate.now();
    private final String ts = today.toString() + "T10:00:00";

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", reqNo())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private void loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\",\"clientType\":\"WEB\"}"))
                .andExpect(status().isOk()).andReturn();
        adminToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken);
    }

    private long createVehicle(String plateNo, String branchCode) throws Exception {
        String body = "{\"plateNo\":\"" + plateNo + "\",\"model\":\"M\",\"branchCode\":"
                + (branchCode == null ? "null" : "\"" + branchCode + "\"") + "}";
        MvcResult result = mockMvc.perform(write(auth(post("/api/vehicles"))).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long vehicleId, String branchCode) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"张三\",\"branchCode\":"
                + (branchCode == null ? "null" : "\"" + branchCode + "\"") + "}";
        MvcResult result = mockMvc.perform(
                        write(auth(post("/api/orders"))).header("X-Biz-Time", ts).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void pickup(long orderId) throws Exception {
        mockMvc.perform(write(auth(post("/api/orders/" + orderId + "/pickup")))
                        .header("X-Biz-Time", ts)
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a.jpg\"}"))
                .andExpect(status().isOk());
    }

    private void doReturn(long orderId) throws Exception {
        mockMvc.perform(write(auth(post("/api/orders/" + orderId + "/return")))
                        .header("X-Biz-Time", ts)
                        .content("{\"fuelReading\":40,\"damagePhotos\":\"oss://p/b.jpg\",\"settleAmount\":200.00}"))
                .andExpect(status().isOk());
    }

    private JsonNode runAndReport() throws Exception {
        mockMvc.perform(write(auth(post("/api/turnover/run"))).param("bizDate", today.toString()))
                .andExpect(status().isOk());
        return reportOnly();
    }

    private String rawReport() throws Exception {
        return mockMvc.perform(auth(get("/api/turnover/daily")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode reportOnly() throws Exception {
        return objectMapper.readTree(rawReport()).get("data");
    }

    private JsonNode findBranch(JsonNode report, String branchCode) {
        for (JsonNode item : report) {
            if (branchCode.equals(item.get("snapshot").get("branchCode").asText())) {
                return item;
            }
        }
        return null;
    }

    @Test
    void snapshotCountsRatesRecomputeCloseFreezeAndCorrect() throws Exception {
        loginAdmin();

        // B01：4 辆车；3 单，2 单当日还车完成，1 单交车未还（出租中），1 辆从未出租
        long v1 = createVehicle("B01-" + reqNo().substring(0, 6), "B01");
        long v2 = createVehicle("B01-" + reqNo().substring(0, 6), "B01");
        long v3 = createVehicle("B01-" + reqNo().substring(0, 6), "B01");
        createVehicle("B01-" + reqNo().substring(0, 6), "B01");
        long o1 = createOrder("TO-" + reqNo().substring(0, 8), v1, "B01");
        long o2 = createOrder("TO-" + reqNo().substring(0, 8), v2, "B01");
        long o3 = createOrder("TO-" + reqNo().substring(0, 8), v3, "B01");
        pickup(o1); doReturn(o1);
        pickup(o2); doReturn(o2);
        pickup(o3); // 仍在租

        // B02：2 辆车，零订单
        createVehicle("B02-" + reqNo().substring(0, 6), "B02");
        createVehicle("B02-" + reqNo().substring(0, 6), "B02");

        // 未分派网点：1 车 1 单当日完成
        long vx = createVehicle("NB-" + reqNo().substring(0, 6), null);
        long ox = createOrder("TO-" + reqNo().substring(0, 8), vx, null);
        pickup(ox); doReturn(ox);

        JsonNode report = runAndReport();

        JsonNode b01 = findBranch(report, "B01");
        assertNotNull(b01, "B01 快照必须存在");
        JsonNode s1 = b01.get("snapshot");
        assertEquals(4, s1.get("totalVehicles").asInt());
        assertEquals(3, s1.get("idleVehicles").asInt());
        assertEquals(1, s1.get("rentingVehicles").asInt());
        assertEquals(0, s1.get("disabledVehicles").asInt());
        assertEquals(2, s1.get("completedOrders").asInt());
        assertEquals(3, s1.get("createdOrders").asInt());
        // 周转率 2/4=0.5，出租率 1/4=0.25（HALF_UP 四位小数，可由计数独立复算）；
        // 经 JSON 数字解析会丢尾随零，用原始响应文本断言线上的四位小数口径
        assertTrue(rawReport().contains("\"turnoverRate\":0.5000"), "周转率必须保留四位小数字面量");
        assertEquals(0, s1.get("turnoverRate").decimalValue().compareTo(new java.math.BigDecimal("0.5")));
        assertEquals(0, s1.get("rentalRate").decimalValue().compareTo(new java.math.BigDecimal("0.25")));
        assertEquals("OPEN", s1.get("status").asText());
        assertEquals(0, s1.get("recomputeCount").asInt());

        JsonNode b02 = findBranch(report, "B02");
        assertNotNull(b02);
        assertEquals(2, b02.get("snapshot").get("totalVehicles").asInt());
        assertEquals(0, b02.get("snapshot").get("completedOrders").asInt());
        assertEquals(0, b02.get("snapshot").get("turnoverRate").decimalValue().compareTo(new java.math.BigDecimal("0.0")));

        JsonNode nb = findBranch(report, "NO-BRANCH");
        assertNotNull(nb, "未分派车辆/订单必须并入 NO-BRANCH");
        assertEquals(1, nb.get("snapshot").get("totalVehicles").asInt());
        assertEquals(1, nb.get("snapshot").get("completedOrders").asInt());
        assertEquals(0, nb.get("snapshot").get("turnoverRate").decimalValue().compareTo(new java.math.BigDecimal("1.0")));

        // 当天数据变化后重算：第 3 单也还车 → 完成 3、出租中 0、周转率 0.75，重算次数+1
        doReturn(o3);
        JsonNode report2 = runAndReport();
        JsonNode s2 = findBranch(report2, "B01").get("snapshot");
        assertEquals(3, s2.get("completedOrders").asInt());
        assertEquals(0, s2.get("rentingVehicles").asInt());
        assertEquals(4, s2.get("idleVehicles").asInt());
        assertEquals(0, s2.get("turnoverRate").decimalValue().compareTo(new java.math.BigDecimal("0.75")));
        assertEquals(1, s2.get("recomputeCount").asInt());
        assertEquals("OPEN", s2.get("status").asText());

        // 未关账不能冲正（应直接重算）
        mockMvc.perform(write(auth(post("/api/turnover/corrections")))
                        .content("{\"branchCode\":\"B01\",\"bizDate\":\"" + today
                                + "\",\"completedDelta\":1,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict());

        // 冲正原因必填
        mockMvc.perform(write(auth(post("/api/turnover/corrections")))
                        .content("{\"branchCode\":\"B01\",\"bizDate\":\"" + today
                                + "\",\"completedDelta\":1,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        // 关账冻结
        mockMvc.perform(write(auth(post("/api/turnover/close"))).param("bizDate", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        JsonNode afterClose = reportOnly();
        // 关账作业以最新数据重算后冻结：计数不变（期间无新数据），本次重算计入 recomputeCount
        JsonNode closed = findBranch(afterClose, "B01").get("snapshot");
        assertEquals("CLOSED", closed.get("status").asText());
        assertEquals(2, closed.get("recomputeCount").asInt());
        assertEquals(3, closed.get("completedOrders").asInt());

        // 已关账业务日手工重算必须被拒
        mockMvc.perform(write(auth(post("/api/turnover/run"))).param("bizDate", today.toString()))
                .andExpect(status().isConflict());

        // 合法冲正：补 1 单完成。基线不变，冲正合计=1，最终完成=4、最终周转率=1.0000
        mockMvc.perform(write(auth(post("/api/turnover/corrections")))
                        .content("{\"branchCode\":\"B01\",\"bizDate\":\"" + today
                                + "\",\"completedDelta\":1,\"reason\":\"事后补录一笔跨日还车，凭证 RC-2026-09\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("事后补录一笔跨日还车，凭证 RC-2026-09"));

        JsonNode report3 = reportOnly();
        JsonNode corrected = findBranch(report3, "B01");
        assertEquals(3, corrected.get("snapshot").get("completedOrders").asInt(), "关账基线不得被改写");
        assertEquals(1, corrected.get("correctionCount").asInt());
        assertEquals(1, corrected.get("completedDelta").asInt());
        assertEquals(4, corrected.get("finalCompletedOrders").asInt());
        assertEquals(4, corrected.get("finalTotalVehicles").asInt());
        assertEquals(0, corrected.get("finalTurnoverRate").decimalValue().compareTo(new java.math.BigDecimal("1.0")));
        assertEquals(0, findBranch(report3, "B02").get("correctionCount").asInt());

        // 作业留痕：关账作业 SUCCESS；被拒的关账后重算留 FAILED 记录（失败也可追溯）
        mockMvc.perform(auth(get("/api/turnover/job-runs")).param("jobType", "DAILY_CLOSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobType").value("DAILY_CLOSE"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].summary").exists());
        mockMvc.perform(auth(get("/api/turnover/job-runs")).param("jobType", "MANUAL_RUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorDetail").exists());
    }

    @Test
    void unauthenticatedAndUnauthorizedRolesBlocked() throws Exception {
        loginAdmin();
        // 未登录 401
        mockMvc.perform(get("/api/turnover/daily")).andExpect(status().isUnauthorized());

        // 调度员无重算权限 → 403
        String body = "{\"username\":\"disp_t\",\"password\":\"disp@12345\",\"displayName\":\"d\","
                + "\"roles\":[\"DISPATCHER\"]}";
        mockMvc.perform(auth(post("/api/system/users"))
                        .header("X-Operator", "admin").header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disp_t\",\"password\":\"disp@12345\",\"clientType\":\"WEB\"}"))
                .andExpect(status().isOk()).andReturn();
        String dispToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
        mockMvc.perform(write(post("/api/turnover/run"))
                        .header("Authorization", "Bearer " + dispToken))
                .andExpect(status().isForbidden());
        // 调度员可查询
        mockMvc.perform(get("/api/turnover/daily")
                        .header("Authorization", "Bearer " + dispToken))
                .andExpect(status().isOk());
    }
}
