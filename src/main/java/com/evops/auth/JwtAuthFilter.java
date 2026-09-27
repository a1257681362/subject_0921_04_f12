package com.evops.auth;

import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 访问令牌认证过滤器（/api/** 的第一道关）：
 *
 * - 解析 Authorization: Bearer &lt;accessToken&gt;，校验签名/类型/过期；
 * - 再查服务端状态：账号启用、tokenVersion 一致（改密全端下线）、会话 ACTIVE（登出/重放立即失效）；
 * - 通过则写入 {@link LoginContext}（请求结束清理）；
 * - 失败直接写 401 JSON：TOKEN_EXPIRED 表示“令牌只是过期”，前端拦截后调
 *   /api/auth/refresh 无感换新并重试原请求；INVALID_TOKEN/SESSION_REVOKED 等须重新登录。
 *
 * evops.auth.enabled=false 时整体放行（本地/测试应急），登录态相关接口不受保护。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String BEARER_PREFIX = "Bearer ";

    private static final List<String> ANONYMOUS_PATHS = Arrays.asList(
            "/api/health",
            "/api/auth/login",
            "/api/auth/refresh"
    );

    private final JwtService jwtService;
    private final AuthService authService;
    private final AuthProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthFilter(JwtService jwtService, AuthService authService,
                         AuthProperties properties, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean anonymous = isAnonymous(path);
        if (!properties.isEnabled() || anonymous) {
            // 认证关闭（应急/测试）或匿名接口：不建立登录态直接放行
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "UNAUTHORIZED", "缺少访问令牌，请先登录");
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        JwtClaims claims;
        try {
            claims = jwtService.parse(token);
        } catch (JwtVerificationException ex) {
            writeUnauthorized(response, ex.getCode(), ex.getMessage());
            return;
        }
        try {
            authService.validateAccess(claims);
        } catch (UnauthorizedException ex) {
            writeUnauthorized(response, ex.getCode(), ex.getMessage());
            return;
        }
        LoginContext.set(claims);
        try {
            filterChain.doFilter(request, response);
        } finally {
            LoginContext.clear();
        }
    }

    private boolean isAnonymous(String path) {
        for (String pattern : ANONYMOUS_PATHS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code, message)));
    }
}
