package com.evops.turnover;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 周转率日快照（一网点一业务日一行）。
 * OPEN：业务日当天可重算刷新；CLOSED：凌晨关账后冻结，拒绝改写，
 *       数据修正只能追加 t_turnover_correction 冲正单。
 * 比率字段由计数字段独立可复算（turnover_rate=completed/total），审计可逐行勾稽。
 */
@TableName("t_turnover_daily")
public class TurnoverDaily {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String branchCode;
    private LocalDate bizDate;
    private Integer totalVehicles;
    private Integer idleVehicles;
    private Integer rentingVehicles;
    private Integer disabledVehicles;
    private Integer completedOrders;
    private Integer createdOrders;
    private BigDecimal turnoverRate;
    private BigDecimal rentalRate;
    private String status;
    private Integer recomputeCount;
    private Long lastJobRunId;
    private String generatedBy;
    private LocalDateTime generatedAt;
    private LocalDateTime lastComputedAt;
    private String closedBy;
    private LocalDateTime closedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Integer getTotalVehicles() { return totalVehicles; }
    public void setTotalVehicles(Integer totalVehicles) { this.totalVehicles = totalVehicles; }
    public Integer getIdleVehicles() { return idleVehicles; }
    public void setIdleVehicles(Integer idleVehicles) { this.idleVehicles = idleVehicles; }
    public Integer getRentingVehicles() { return rentingVehicles; }
    public void setRentingVehicles(Integer rentingVehicles) { this.rentingVehicles = rentingVehicles; }
    public Integer getDisabledVehicles() { return disabledVehicles; }
    public void setDisabledVehicles(Integer disabledVehicles) { this.disabledVehicles = disabledVehicles; }
    public Integer getCompletedOrders() { return completedOrders; }
    public void setCompletedOrders(Integer completedOrders) { this.completedOrders = completedOrders; }
    public Integer getCreatedOrders() { return createdOrders; }
    public void setCreatedOrders(Integer createdOrders) { this.createdOrders = createdOrders; }
    public BigDecimal getTurnoverRate() { return turnoverRate; }
    public void setTurnoverRate(BigDecimal turnoverRate) { this.turnoverRate = turnoverRate; }
    public BigDecimal getRentalRate() { return rentalRate; }
    public void setRentalRate(BigDecimal rentalRate) { this.rentalRate = rentalRate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRecomputeCount() { return recomputeCount; }
    public void setRecomputeCount(Integer recomputeCount) { this.recomputeCount = recomputeCount; }
    public Long getLastJobRunId() { return lastJobRunId; }
    public void setLastJobRunId(Long lastJobRunId) { this.lastJobRunId = lastJobRunId; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getLastComputedAt() { return lastComputedAt; }
    public void setLastComputedAt(LocalDateTime lastComputedAt) { this.lastComputedAt = lastComputedAt; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
