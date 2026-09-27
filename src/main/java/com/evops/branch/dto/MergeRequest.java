package com.evops.branch.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 区域合并请求：把源网点整棵子树的业务归属（车辆台账、租车单）改派到目标网点，
 * 源子树整体挂到目标节点下，全部写入在同一事务。
 */
public class MergeRequest {

    @NotNull(message = "合并目标网点不能为空")
    @Min(value = 1, message = "合并目标网点 id 必须为正数")
    private Long targetId;

    /** 合并原因（可选，写入事件留痕；留痕列 500 字符，为生成的明细前缀预留空间） */
    @Size(max = 240, message = "合并原因长度不能超过 240")
    private String reason;

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
