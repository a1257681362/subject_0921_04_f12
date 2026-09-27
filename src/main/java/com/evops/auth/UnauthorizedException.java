package com.evops.auth;

/**
 * 认证失败（未登录/令牌无效或过期/账号停用/刷新令牌失效等），统一对应 HTTP 401。
 */
public class UnauthorizedException extends RuntimeException {

    private final String code;

    public UnauthorizedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
