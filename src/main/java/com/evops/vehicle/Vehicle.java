package com.evops.vehicle;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 车辆台账。
 */
@TableName("t_vehicle")
public class Vehicle extends BizEntity {

    /** 车牌号（库层唯一） */
    private String plateNo;

    /** 车型 */
    private String model;

    /** IDLE 在库 / RENTING 出租中 / DISABLED 停用 */
    private String status;

    /** 归属网点（turnover-1：周转率按网点归集分母；可空=未分派，统计并入 NO-BRANCH） */
    private String branchCode;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
}
