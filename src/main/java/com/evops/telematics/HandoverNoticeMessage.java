package com.evops.telematics;

import java.time.LocalDateTime;

/**
 * 出站交接通知报文（我方 -> 平台）：一交接一条，平台据此启停定位跟踪。
 * 元素与 CSV 导出列同口径；字段为 null 时元素缺省，空串写为空元素。
 */
@XmlRoot("handoverNotice")
public class HandoverNoticeMessage {

    @XmlField(name = "orderNo", required = true)
    private String orderNo;

    @XmlField(name = "plateNo", required = true)
    private String plateNo;

    /** PICKUP / RETURN */
    @XmlField(name = "handoverType", required = true)
    private String handoverType;

    @XmlField(name = "handoverTime", required = true)
    private LocalDateTime handoverTime;

    /** 油量表读数（0-100） */
    @XmlField(name = "fuelReading")
    private Integer fuelReading;

    /** 车损照片地址，多个以逗号分隔 */
    @XmlField(name = "damagePhotos")
    private String damagePhotos;

    @XmlField(name = "remark")
    private String remark;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getHandoverType() { return handoverType; }
    public void setHandoverType(String handoverType) { this.handoverType = handoverType; }
    public LocalDateTime getHandoverTime() { return handoverTime; }
    public void setHandoverTime(LocalDateTime handoverTime) { this.handoverTime = handoverTime; }
    public Integer getFuelReading() { return fuelReading; }
    public void setFuelReading(Integer fuelReading) { this.fuelReading = fuelReading; }
    public String getDamagePhotos() { return damagePhotos; }
    public void setDamagePhotos(String damagePhotos) { this.damagePhotos = damagePhotos; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
