package com.evops.schedule;

import com.evops.common.BizException;

import java.time.LocalDateTime;

/**
 * 档期冲突：新区间与既有占用（含前后缓冲）重叠。
 * 除冲突说明外，携带系统推荐的最近空档，供前端直接提示现场改约。
 * 仍按业务冲突走 HTTP 409（见 GlobalExceptionHandler 对本异常的处理）。
 */
public class ScheduleConflictException extends BizException {

    private final LocalDateTime suggestedStart;
    private final LocalDateTime suggestedEnd;

    public ScheduleConflictException(String message, LocalDateTime suggestedStart, LocalDateTime suggestedEnd) {
        super(message);
        this.suggestedStart = suggestedStart;
        this.suggestedEnd = suggestedEnd;
    }

    public LocalDateTime getSuggestedStart() { return suggestedStart; }
    public LocalDateTime getSuggestedEnd() { return suggestedEnd; }
}
