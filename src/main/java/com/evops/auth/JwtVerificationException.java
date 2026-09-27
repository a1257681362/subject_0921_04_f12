package com.evops.auth;

/**
 * JWT 校验失败（过滤器据此返回 401）。
 * code=TOKEN_EXPIRED 表示签名可信但访问令牌已过期——前端据此调刷新接口无感换新；
 * 其余 code（INVALID_TOKEN）一律走重新登录。
 */
public class JwtVerificationException extends RuntimeException {

    private final String code;

    public JwtVerificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public JwtVerificationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}
