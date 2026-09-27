package com.evops.auth;

import java.util.Collections;
import java.util.List;

/**
 * 当前请求的登录态：由 JwtAuthFilter 校验访问令牌后写入，角色拦截器/业务代码读取，
 * 请求结束（过滤器 finally）清理。与 com.evops.common.RequestContext（业务操作人/请求号）分工。
 */
public final class LoginContext {

    private static final ThreadLocal<JwtClaims> HOLDER = new ThreadLocal<>();

    private LoginContext() {}

    public static void set(JwtClaims claims) { HOLDER.set(claims); }

    public static JwtClaims get() { return HOLDER.get(); }

    public static boolean isAuthenticated() { return HOLDER.get() != null; }

    public static Long userId() {
        JwtClaims c = HOLDER.get();
        return c == null ? null : c.getUserId();
    }

    public static String username() {
        JwtClaims c = HOLDER.get();
        return c == null ? null : c.getUsername();
    }

    public static Long sessionId() {
        JwtClaims c = HOLDER.get();
        return c == null ? null : c.getSessionId();
    }

    public static List<String> roles() {
        JwtClaims c = HOLDER.get();
        return c == null ? Collections.emptyList() : c.getRoles();
    }

    public static void clear() { HOLDER.remove(); }
}
