package com.evops.schedule.dto;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 档期占位请求：在 [startTime, endTime) 占用一辆车，先落 HELD 占位（锁位中）。
 * 与相邻占用间隔小于缓冲（默认 12 分钟）即冲突，接口返回 409 并推荐最近空档。
 */
public class ScheduleHoldRequest {

    @NotNull(message = "车辆ID不能为空")
    private Long vehicleId;

    @NotNull(message = "占用开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "占用结束时间不能为空")
    private LocalDateTime endTime;

    /** 预约客户（可选） */
    @Size(max = 64, message = "客户姓名长度不能超过 64")
    private String customerName;

    /** 关联租车单（可选；审批/下单接通后回填） */
    private Long orderId;

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
