package com.evops.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色拦截：配合 {@link RequireRole} 注解。
 * 认证（是否登录、令牌是否有效）由 JwtAuthFilter 负责，这里只判角色。
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireRole annotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (annotation == null) {
            return true;
        }
        JwtClaims claims = LoginContext.get();
        if (claims == null) {
            // 正常走不到（过滤器已挡），兜底防御
            throw new UnauthorizedException("UNAUTHORIZED", "请先登录");
        }
        Set<String> required = new LinkedHashSet<>(Arrays.asList(annotation.value()));
        List<String> owned = claims.getRoles();
        if (owned == null || owned.stream().noneMatch(required::contains)) {
            throw new ForbiddenException("权限不足，需要角色：" + String.join("/", required));
        }
        return true;
    }
}
