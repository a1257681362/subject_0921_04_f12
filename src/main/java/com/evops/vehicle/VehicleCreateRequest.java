package com.evops.vehicle;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class VehicleCreateRequest {

    @NotBlank(message = "车牌号不能为空")
    @Size(max = 32, message = "车牌号长度不能超过 32")
    private String plateNo;

    @Size(max = 64, message = "车型长度不能超过 64")
    private String model;

    /** 归属网点，可空=未分派（周转率统计并入 NO-BRANCH） */
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
}
