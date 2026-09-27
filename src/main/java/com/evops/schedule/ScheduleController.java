package com.evops.schedule;

import com.evops.common.ApiResponse;
import com.evops.schedule.dto.ScheduleCancelRequest;
import com.evops.schedule.dto.ScheduleHoldRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 车辆档期（schedule-1）。
 *
 * 写接口遵循三件套（X-Operator / X-Request-No / 可选 X-Biz-Time）与入口幂等。
 * 冲突按区间重叠（前后缓冲 12 分钟）判定，占位锁位 25 分钟未确认自动释放，
 * 冲突响应在 data 中推荐最近空档。本期不含审批流。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 占位：在 [startTime,endTime) 占用车辆，先落 HELD（锁位中）。冲突 409 并推荐最近空档。 */
    @PostMapping
    public ApiResponse<VehicleSchedule> hold(@Valid @RequestBody ScheduleHoldRequest request) {
        return ApiResponse.ok(scheduleService.hold(request));
    }

    /** 确认占位：HELD 须在锁位时限内确认，超时未确认已被自动释放则拒绝。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<VehicleSchedule> confirm(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.confirm(id));
    }

    /** 取消：释放档期（HELD/CONFIRMED -> CANCELLED），全量留痕。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<VehicleSchedule> cancel(@PathVariable Long id,
                                               @RequestBody(required = false) ScheduleCancelRequest request) {
        String reason = request != null ? request.getReason() : null;
        return ApiResponse.ok(scheduleService.cancel(id, reason));
    }

    /** 档期记录详情。 */
    @GetMapping("/{id}")
    public ApiResponse<VehicleSchedule> detail(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.getById(id));
    }

    /**
     * 车辆档期查询：默认仅在占（HELD/CONFIRMED），includeAll=true 含取消/超时留痕。
     */
    @GetMapping
    public ApiResponse<List<VehicleSchedule>> list(
            @RequestParam Long vehicleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @RequestParam(defaultValue = "false") boolean includeAll) {
        return ApiResponse.ok(scheduleService.list(vehicleId, from, to, includeAll));
    }

    /** 档期可用性校验：返回是否可约；冲突时携带最近空档建议。 */
    @GetMapping("/availability")
    public ApiResponse<ScheduleService.Availability> availability(
            @RequestParam Long vehicleId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ApiResponse.ok(scheduleService.availability(vehicleId, start, end));
    }

    /** 推荐最近空档：保持原时长，从请求开始时刻向后找最早可约区间。 */
    @GetMapping("/next-slot")
    public ApiResponse<Map<String, LocalDateTime>> nextSlot(
            @RequestParam Long vehicleId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        LocalDateTime[] slot = scheduleService.nearestFreeSlot(vehicleId, start, end);
        if (slot == null) {
            return ApiResponse.ok(Collections.emptyMap());
        }
        java.util.Map<String, LocalDateTime> data = new java.util.LinkedHashMap<>();
        data.put("suggestedStart", slot[0]);
        data.put("suggestedEnd", slot[1]);
        return ApiResponse.ok(data);
    }

    /** 手工触发超时占位释放（应急/补跑；周期作业默认每分钟自动执行）。 */
    @PostMapping("/release-overdue")
    public ApiResponse<Map<String, Integer>> releaseOverdue() {
        return ApiResponse.ok(Collections.singletonMap("released", scheduleService.releaseOverdueHolds()));
    }
}
