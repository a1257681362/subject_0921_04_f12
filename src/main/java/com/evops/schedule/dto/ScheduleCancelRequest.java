package com.evops.schedule.dto;

import javax.validation.constraints.Size;

/**
 * 取消档期占位请求（原因可选，留痕）。
 */
public class ScheduleCancelRequest {

    @Size(max = 500, message = "取消原因长度不能超过 500")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
