package com.evops.telematics;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * XML 报文归档记录（收发都留痕）。
 * 出站：一交接一条，按订单号分目录落盘（file_path 唯一），content 为原报文——
 * 重发逐字节沿用归档原件，不重新生成（content_hash 与原件一致即为凭证），
 * resend_of 指向最初原件把多次重发串成一条链。
 * 入站：内容哈希幂等（dedup_key 唯一兜底），解析/校验失败置 FAILED 进异常清单，
 * error_detail 带元素名与原因。
 */
@TableName("t_telematics_xml_message")
public class TelematicsXmlMessage extends BizEntity {

    public static final String DIRECTION_OUT = "OUT";
    public static final String DIRECTION_IN = "IN";

    public static final String TYPE_HANDOVER_NOTICE = "HANDOVER_NOTICE";
    public static final String TYPE_DEVICE_REPORT = "DEVICE_REPORT";

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_FAILED = "FAILED";

    /** OUT 发出 / IN 接收 */
    private String direction;

    /** HANDOVER_NOTICE / DEVICE_REPORT */
    private String msgType;

    /** 出站报文关联订单号（归档键）；入站可空 */
    private String orderNo;

    /** 原报文（重发逐字节沿用，不重新生成） */
    private String content;

    /** 内容 SHA-256（重发与原件哈希一致即原报文凭证） */
    private String contentHash;

    /** 入站幂等键（"IN:" + 内容哈希），仅入站有值，库层唯一兜底 */
    private String dedupKey;

    /** SENT / RECEIVED / FAILED */
    private String status;

    /** 解析/校验失败明细（异常清单展示）：元素名 + 原因 */
    private String errorDetail;

    /** 出站归档落盘路径（按订单号分目录） */
    private String filePath;

    /** 重发来源：最初原件 id（重发的重发也指向原件） */
    private Long resendOf;

    /** 非库列：本次入站命中重复报文（同内容已接收过） */
    @TableField(exist = false)
    private boolean duplicate;

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Long getResendOf() { return resendOf; }
    public void setResendOf(Long resendOf) { this.resendOf = resendOf; }
    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
}
