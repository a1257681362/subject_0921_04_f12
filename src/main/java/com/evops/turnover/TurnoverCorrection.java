package com.evops.turnover;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDate;

/**
 * 关账后冲正单：append-only，原因必填。
 * 最终值 = 快照基线 + 冲正合计；基线行永不被改写，审计可同时看到原值、每笔冲正与现值。
 */
@TableName("t_turnover_correction")
public class TurnoverCorrection extends BizEntity {

    private String branchCode;
    private LocalDate bizDate;
    /** 完成订单数调整（正补负冲） */
    private Integer completedDelta;
    /** 在役车辆数调整 */
    private Integer totalVehiclesDelta;

    private String reason;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Integer getCompletedDelta() { return completedDelta; }
    public void setCompletedDelta(Integer completedDelta) { this.completedDelta = completedDelta; }
    public Integer getTotalVehiclesDelta() { return totalVehiclesDelta; }
    public void setTotalVehiclesDelta(Integer totalVehiclesDelta) { this.totalVehiclesDelta = totalVehiclesDelta; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
