package com.evops.photo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 交车照片：上传只负责原图落盘 + 登记本行（PENDING），
 * EXIF 摆正 / 280px 缩放 / 30% 水印在后台线程池处理（不卡上传），
 * 处理耗时 processCostMs 落库可见。对外只提供 preview 预览图，原图仅内部留存。
 *
 * 状态机：PENDING -> PROCESSING -> DONE / FAILED；FAILED 可手工重试或启动自愈重投。
 */
@TableName("t_handover_photo")
public class HandoverPhoto extends BizEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    private Long orderId;

    /** 冗余订单号（水印内容），上传时快照，不随单回改 */
    private String orderNo;

    /** 归属网点快照（可空=未分派，水印显示“未分派”） */
    private String branchCode;

    /** 上传原始文件名 */
    private String fileName;

    /** 原图 SHA-256，同订单重复上传幂等键 */
    private String fileHash;

    /** 原图落盘路径（仅内部留存，不对外） */
    private String originalPath;

    /** 280px 预览图路径（对外唯一出口，已摆正+水印） */
    private String previewPath;

    /** PENDING / PROCESSING / DONE / FAILED */
    private String status;

    /** 原图 EXIF 方向（1-8；空=无 EXIF，按不旋转处理） */
    private Integer exifOrientation;

    /** 是否已按 EXIF 方向摆正 */
    private Boolean rotated;

    /** 原图像素宽（EXIF 摆正前） */
    private Integer srcWidth;

    /** 原图像素高（EXIF 摆正前） */
    private Integer srcHeight;

    /** 后台处理耗时（毫秒），完成/失败均回填，耗时可见 */
    private Long processCostMs;

    /** FAILED 时的失败原因 */
    private String errorMessage;

    /** 重试次数（手工重试/启动自愈累计） */
    private Integer retryCount;

    /** 非库列：同订单重复上传同一文件时置 true，返回首次登记结果 */
    @TableField(exist = false)
    private Boolean duplicate;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public String getPreviewPath() { return previewPath; }
    public void setPreviewPath(String previewPath) { this.previewPath = previewPath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getExifOrientation() { return exifOrientation; }
    public void setExifOrientation(Integer exifOrientation) { this.exifOrientation = exifOrientation; }
    public Boolean getRotated() { return rotated; }
    public void setRotated(Boolean rotated) { this.rotated = rotated; }
    public Integer getSrcWidth() { return srcWidth; }
    public void setSrcWidth(Integer srcWidth) { this.srcWidth = srcWidth; }
    public Integer getSrcHeight() { return srcHeight; }
    public void setSrcHeight(Integer srcHeight) { this.srcHeight = srcHeight; }
    public Long getProcessCostMs() { return processCostMs; }
    public void setProcessCostMs(Long processCostMs) { this.processCostMs = processCostMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Boolean getDuplicate() { return duplicate; }
    public void setDuplicate(Boolean duplicate) { this.duplicate = duplicate; }
}
