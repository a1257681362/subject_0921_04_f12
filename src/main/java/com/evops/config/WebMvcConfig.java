package com.evops.config;

import com.evops.auth.RoleInterceptor;
import com.evops.idempotent.IdempotentInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 登录/刷新：登录前没有操作人与请求头，且刷新一次性由会话表保证，不走三件套与入口幂等 */
    private static final String[] AUTH_ANONYMOUS_PATH = {
            "/api/auth/login", "/api/auth/refresh"
    };

    /** 其余登录态接口（登出/改密）也不走入口幂等：登出天然幂等，改密重复提交会因原密码已变更被拒 */
    private static final String[] AUTH_ALL_PATH = {"/api/auth/**"};

    private final RequestContextInterceptor requestContextInterceptor;
    private final IdempotentInterceptor idempotentInterceptor;
    private final RoleInterceptor roleInterceptor;

    public WebMvcConfig(RequestContextInterceptor requestContextInterceptor,
                        IdempotentInterceptor idempotentInterceptor,
                        RoleInterceptor roleInterceptor) {
        this.requestContextInterceptor = requestContextInterceptor;
        this.idempotentInterceptor = idempotentInterceptor;
        this.roleInterceptor = roleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestContextInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(AUTH_ALL_PATH)
                .order(1);
        registry.addInterceptor(idempotentInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health")
                .excludePathPatterns(AUTH_ALL_PATH)
                .order(2);
        // 角色判定：认证（JWT 过滤器）之后，按 @RequireRole 注解校验
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(AUTH_ANONYMOUS_PATH)
                .order(3);
    }
}
