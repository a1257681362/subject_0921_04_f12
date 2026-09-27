package com.evops.telematics;

import java.time.LocalDateTime;

/**
 * 入站设备回执报文（平台 -> 我方）：单台设备一条，元素与 CSV 导入列同口径。
 */
@XmlRoot("deviceReport")
public class DeviceReportMessage {

    @XmlField(name = "plateNo", required = true)
    private String plateNo;

    @XmlField(name = "deviceNo", required = true)
    private String deviceNo;

    /** ONLINE / OFFLINE */
    @XmlField(name = "deviceStatus", required = true)
    private String deviceStatus;

    @XmlField(name = "reportTime", required = true)
    private LocalDateTime reportTime;

    @XmlField(name = "remark")
    private String remark;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }
    public String getDeviceStatus() { return deviceStatus; }
    public void setDeviceStatus(String deviceStatus) { this.deviceStatus = deviceStatus; }
    public LocalDateTime getReportTime() { return reportTime; }
    public void setReportTime(LocalDateTime reportTime) { this.reportTime = reportTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
