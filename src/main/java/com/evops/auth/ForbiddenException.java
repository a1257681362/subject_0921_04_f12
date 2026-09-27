package com.evops.auth;

/**
 * 已认证但角色不足，统一对应 HTTP 403。
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
