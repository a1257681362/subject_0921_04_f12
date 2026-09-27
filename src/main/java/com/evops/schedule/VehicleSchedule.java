package com.evops.schedule;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * 车辆档期占用（schedule-1）。
 *
 * 状态：HELD 占位（锁位中，到期未确认自动释放）→ CONFIRMED 已确认；
 *       HELD/CONFIRMED 可取消为 CANCELLED；HELD 超时由作业置 EXPIRED。
 * CANCELLED/EXPIRED 行保留留痕但不再占用档期。
 * 占用按半开区间 [startTime, endTime) 比较，冲突判定在服务层叠加前后缓冲。
 */
@TableName("t_vehicle_schedule")
public class VehicleSchedule extends BizEntity {

    public static final String HELD = "HELD";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    /** 占用车辆 */
    private Long vehicleId;

    /** 占用开始（半开区间，含） */
    private LocalDateTime startTime;

    /** 占用结束（半开区间，不含） */
    private LocalDateTime endTime;

    /** HELD / CONFIRMED / CANCELLED / EXPIRED */
    private String status;

    /** 预约客户（可空） */
    private String customerName;

    /** 关联租车单（本期预约可暂不挂单；审批/下单下期接通） */
    private Long orderId;

    /** HELD 锁位截止（占位时刻 + lock-ttl-minutes） */
    private LocalDateTime heldDeadline;

    /** 确认时间 */
    private LocalDateTime confirmedTime;

    /** 取消原因 */
    private String cancelReason;

    /** 取消/超时释放时间 */
    private LocalDateTime releasedTime;

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public LocalDateTime getHeldDeadline() { return heldDeadline; }
    public void setHeldDeadline(LocalDateTime heldDeadline) { this.heldDeadline = heldDeadline; }
    public LocalDateTime getConfirmedTime() { return confirmedTime; }
    public void setConfirmedTime(LocalDateTime confirmedTime) { this.confirmedTime = confirmedTime; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public LocalDateTime getReleasedTime() { return releasedTime; }
    public void setReleasedTime(LocalDateTime releasedTime) { this.releasedTime = releasedTime; }
}
