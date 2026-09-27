package com.evops.turnover.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 关账后冲正请求。调整量可正（补）可负（冲），不能全为 0；原因必填。
 */
public class TurnoverCorrectionRequest {

    @NotBlank(message = "网点编码不能为空")
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    @NotNull(message = "业务日不能为空（yyyy-MM-dd）")
    private LocalDate bizDate;

    /** 完成订单数调整，可空=0 */
    private Integer completedDelta;

    /** 在役车辆数调整，可空=0 */
    private Integer totalVehiclesDelta;

    @NotBlank(message = "冲正原因必填")
    @Size(max = 500, message = "冲正原因长度不能超过 500")
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
