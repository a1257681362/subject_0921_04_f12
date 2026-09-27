package com.evops.schedule;

import com.evops.common.RequestContext;
import com.evops.schedule.dto.ScheduleHoldRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 车辆档期集成测试（schedule-1）：内存 H2 全链路。
 * 关闭周期扫描（evops.schedule.enabled=false），超时释放显式调服务，保证用例确定可控。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:schedule-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.schedule.enabled=false"
})
class ScheduleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ScheduleService scheduleService;
    @Autowired private VehicleScheduleMapper scheduleMapper;
    @Autowired private com.evops.vehicle.VehicleMapper vehicleMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String no) {
        return b.header("X-Operator", "tester").header("X-Request-No", no)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle() throws Exception {
        String body = "{\"plateNo\":\"档期-" + reqNo().substring(0, 8) + "\",\"model\":\"M\"}";
        MvcResult r = mockMvc.perform(write(post("/api/vehicles"), reqNo()).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private String holdBody(long vehicleId, String start, String end) {
        return "{\"vehicleId\":" + vehicleId + ",\"startTime\":\"" + start + "\",\"endTime\":\"" + end
                + "\",\"customerName\":\"张三\"}";
    }

    private long holdViaApi(long vehicleId, String start, String end) throws Exception {
        MvcResult r = mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(vehicleId, start, end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    // ---- 占位 / 锁位截止 ----

    @Test
    void holdCreatesHeldWithDeadline() throws Exception {
        long vid = createVehicle();
        MvcResult r = mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(vid, "2026-12-01T10:00:00", "2026-12-01T11:00:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andExpect(jsonPath("$.data.heldDeadline").isNotEmpty())
                .andReturn();

        VehicleSchedule saved = scheduleMapper.selectById(
                objectMapper.readTree(r.getResponse().getContentAsString()).get("data").get("id").asLong());
        // 锁位截止 ≈ 占位时刻 + 25 分钟
        assertNotNull(saved.getHeldDeadline());
        long minutes = java.time.Duration.between(LocalDateTime.now(), saved.getHeldDeadline()).toMinutes();
        assertTrue(minutes >= 24 && minutes <= 25, "锁位截止应为约 25 分钟后，实际：" + minutes);
    }

    @Test
    void endBeforeStartRejected() throws Exception {
        long vid = createVehicle();
        mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(vid, "2026-12-01T11:00:00", "2026-12-01T10:00:00")))
                .andExpect(status().isConflict());
    }

    // ---- 区间重叠（含前后缓冲 12 分钟） ----

    @Test
    void exactOverlapRejectedWithSuggestion() throws Exception {
        long vid = createVehicle();
        holdViaApi(vid, "2026-12-01T10:00:00", "2026-12-01T11:00:00");

        // 同一时段再占 -> 409，且推荐最近空档 = 11:00 + 12 分钟缓冲 = 11:12 起，时长不变 1 小时
        mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(vid, "2026-12-01T10:00:00", "2026-12-01T11:00:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.data.suggestedStart").value("2026-12-01T11:12:00"))
                .andExpect(jsonPath("$.data.suggestedEnd").value("2026-12-01T12:12:00"));
    }

    @Test
    void bufferBoundaryTwelveAllowedElevenRejected() throws Exception {
        // 既有占用 10:00~11:00，前后各 12 分钟缓冲 => 禁占 [09:48, 11:12)。
        // 每个边界用独立车辆、围绕同一占用，避免其它已放占位干扰判定。

        // 前侧间隔恰为 12 分钟：新单结束于 09:48 -> 允许
        long v1 = createVehicle();
        holdViaApi(v1, "2026-12-01T10:00:00", "2026-12-01T11:00:00");
        mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(v1, "2026-12-01T08:00:00", "2026-12-01T09:48:00")))
                .andExpect(status().isOk());

        // 前侧间隔 11 分钟：新单结束于 09:49 -> 拒绝
        long v2 = createVehicle();
        holdViaApi(v2, "2026-12-01T10:00:00", "2026-12-01T11:00:00");
        mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(v2, "2026-12-01T08:00:00", "2026-12-01T09:49:00")))
                .andExpect(status().isConflict());

        // 后侧间隔恰为 12 分钟：11:12 开始 -> 允许
        long v3 = createVehicle();
        holdViaApi(v3, "2026-12-01T10:00:00", "2026-12-01T11:00:00");
        mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(v3, "2026-12-01T11:12:00", "2026-12-01T12:00:00")))
                .andExpect(status().isOk());

        // 后侧间隔 11 分钟：11:11 开始 -> 拒绝
        long v4 = createVehicle();
        holdViaApi(v4, "2026-12-01T10:00:00", "2026-12-01T11:00:00");
        mockMvc.perform(write(post("/api/schedules"), reqNo())
                        .content(holdBody(v4, "2026-12-01T11:11:00", "2026-12-01T12:00:00")))
                .andExpect(status().isConflict());
    }

    @Test
    void availabilityReflectsConflictAndSuggestion() throws Exception {
        long vid = createVehicle();
        holdViaApi(vid, "2026-12-01T10:00:00", "2026-12-01T11:00:00");

        mockMvc.perform(get("/api/schedules/availability")
                        .param("vehicleId", String.valueOf(vid))
                        .param("start", "2026-12-01T11:12:00").param("end", "2026-12-01T12:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));

        mockMvc.perform(get("/api/schedules/availability")
                        .param("vehicleId", String.valueOf(vid))
                        .param("start", "2026-12-01T10:30:00").param("end", "2026-12-01T11:30:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.suggestedStart").value("2026-12-01T11:12:00"));
    }

    // ---- 确认 / 取消 / 超时释放 ----

    @Test
    void confirmHeldSucceeds() throws Exception {
        long vid = createVehicle();
        long id = holdViaApi(vid, "2026-12-05T10:00:00", "2026-12-05T11:00:00");
        mockMvc.perform(write(post("/api/schedules/" + id + "/confirm"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedTime").isNotEmpty());
        // 确认幂等：再次确认不报错
        mockMvc.perform(write(post("/api/schedules/" + id + "/confirm"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void confirmAfterLockTtlRejectedAndExpired() throws Exception {
        long vid = createVehicle();
        long id = holdViaApi(vid, "2026-12-06T10:00:00", "2026-12-06T11:00:00");
        pushDeadlinePast(id);

        // 超过锁位 25 分钟未确认 -> 拒绝，且该占位已被置 EXPIRED
        mockMvc.perform(write(post("/api/schedules/" + id + "/confirm"), reqNo()))
                .andExpect(status().isConflict());
        assertEquals(VehicleSchedule.EXPIRED, scheduleMapper.selectById(id).getStatus());
    }

    @Test
    void cancelReleasesSlotForAnotherBooking() throws Exception {
        long vid = createVehicle();
        long id = holdViaApi(vid, "2026-12-07T10:00:00", "2026-12-07T11:00:00");
        mockMvc.perform(write(post("/api/schedules/" + id + "/cancel"), reqNo())
                        .content("{\"reason\":\"客户改期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("客户改期"));

        // 默认查询只看在占，已取消不出现
        mockMvc.perform(get("/api/schedules").param("vehicleId", String.valueOf(vid)))
                .andExpect(jsonPath("$.data.length()").value(0));
        // 取消后同一时段可重新占位
        holdViaApi(vid, "2026-12-07T10:00:00", "2026-12-07T11:00:00");
    }

    @Test
    void scheduledJobReleasesOverdueHolds() throws Exception {
        long vid = createVehicle();
        long id = holdViaApi(vid, "2026-12-08T10:00:00", "2026-12-08T11:00:00");
        pushDeadlinePast(id);

        // 模拟周期扫描（默认每分钟）：超时占位被置 EXPIRED
        int released = scheduleService.releaseOverdueHolds();
        assertEquals(1, released);
        VehicleSchedule expired = scheduleMapper.selectById(id);
        assertEquals(VehicleSchedule.EXPIRED, expired.getStatus());
        assertNotNull(expired.getReleasedTime());

        // 释放后同一时段可约
        mockMvc.perform(get("/api/schedules/availability")
                        .param("vehicleId", String.valueOf(vid))
                        .param("start", "2026-12-08T10:00:00").param("end", "2026-12-08T11:00:00"))
                .andExpect(jsonPath("$.data.available").value(true));

        // 再次扫描幂等，不重复释放
        assertEquals(0, scheduleService.releaseOverdueHolds());
    }

    @Test
    void overdueHoldLazilyExpiredOnNewHold() throws Exception {
        long vid = createVehicle();
        long id = holdViaApi(vid, "2026-12-09T10:00:00", "2026-12-09T11:00:00");
        pushDeadlinePast(id);

        // 不等扫描，新占位在本车事务内懒释放超时占位后直接成功
        long id2 = holdViaApi(vid, "2026-12-09T10:00:00", "2026-12-09T11:00:00");
        assertEquals(VehicleSchedule.EXPIRED, scheduleMapper.selectById(id).getStatus());
        assertEquals(VehicleSchedule.HELD, scheduleMapper.selectById(id2).getStatus());
    }

    // ---- 并发：同一辆车同一时段只有一个占位成功（行锁串行化判定） ----

    @Test
    void concurrentHoldsOnlyOneSucceeds() throws Exception {
        long vid = createVehicle();
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
                    try {
                        ScheduleHoldRequest req = new ScheduleHoldRequest();
                        req.setVehicleId(vid);
                        req.setStartTime(LocalDateTime.of(2026, 12, 10, 10, 0));
                        req.setEndTime(LocalDateTime.of(2026, 12, 10, 11, 0));
                        scheduleService.hold(req);
                        success.incrementAndGet();
                    } catch (ScheduleConflictException ex) {
                        conflict.incrementAndGet();
                    } finally {
                        RequestContext.clear();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "并发占位线程未在时限内结束");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get(), "应恰有一个占位成功");
        assertEquals(1, conflict.get(), "败者应收到档期冲突");
    }

    @Test
    void missingOrDisabledVehicleCannotHold() throws Exception {
        // 不存在车辆 -> 拒绝
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            ScheduleHoldRequest req = new ScheduleHoldRequest();
            req.setVehicleId(999999L);
            req.setStartTime(LocalDateTime.of(2026, 12, 11, 10, 0));
            req.setEndTime(LocalDateTime.of(2026, 12, 11, 11, 0));
            assertThrows(com.evops.common.BizException.class, () -> scheduleService.hold(req));
        } finally {
            RequestContext.clear();
        }

        // 停用车辆 -> 拒绝
        long vid = createVehicle();
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            com.evops.vehicle.Vehicle vehicle = vehicleMapper.selectById(vid);
            vehicle.setStatus("DISABLED");
            vehicleMapper.updateById(vehicle);

            ScheduleHoldRequest req = new ScheduleHoldRequest();
            req.setVehicleId(vid);
            req.setStartTime(LocalDateTime.of(2026, 12, 12, 10, 0));
            req.setEndTime(LocalDateTime.of(2026, 12, 12, 11, 0));
            assertThrows(com.evops.common.BizException.class, () -> scheduleService.hold(req));
        } finally {
            RequestContext.clear();
        }
    }

    /** 把某占位的锁位截止拨到过去，模拟锁位超时。 */
    private void pushDeadlinePast(long id) {
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            VehicleSchedule s = scheduleMapper.selectById(id);
            s.setHeldDeadline(LocalDateTime.now().minusMinutes(1));
            scheduleMapper.updateById(s);
        } finally {
            RequestContext.clear();
        }
    }
}
