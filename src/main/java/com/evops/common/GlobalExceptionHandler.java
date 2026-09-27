package com.evops.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 认证失败（MVC 层抛出；JWT 过滤器内的 401 由过滤器自行写 JSON） */
    @ExceptionHandler(com.evops.auth.UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(com.evops.auth.UnauthorizedException ex) {
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    /** 已登录但角色不足 */
    @ExceptionHandler(com.evops.auth.ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleForbidden(com.evops.auth.ForbiddenException ex) {
        return ApiResponse.fail("FORBIDDEN", ex.getMessage());
    }

    /** 业务规则冲突（状态流转非法、物理删除被拒、单据不存在等） */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleBiz(BizException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 档期冲突：409 且在 data 回传系统推荐的最近空档，供现场直接改约 */
    @ExceptionHandler(com.evops.schedule.ScheduleConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<java.util.Map<String, Object>> handleScheduleConflict(
            com.evops.schedule.ScheduleConflictException ex) {
        java.util.Map<String, Object> data = null;
        if (ex.getSuggestedStart() != null && ex.getSuggestedEnd() != null) {
            data = new java.util.LinkedHashMap<>();
            data.put("suggestedStart", ex.getSuggestedStart());
            data.put("suggestedEnd", ex.getSuggestedEnd());
        }
        return ApiResponse.fail("SCHEDULE_CONFLICT", ex.getMessage(), data);
    }

    /** 入口幂等/库层唯一约束冲突（如订单号重复） */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        return ApiResponse.fail("数据唯一性冲突，疑似重复提交");
    }

    /** CHECK 约束等数据完整性问题 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ApiResponse.fail("数据不满足约束条件");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return ApiResponse.fail(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        return ApiResponse.fail("系统处理失败");
    }
}
