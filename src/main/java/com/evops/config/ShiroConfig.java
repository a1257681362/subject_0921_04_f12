package com.evops.config;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shiro 链：业务 API（/api/**）的认证改由 JwtAuthFilter（无状态 JWT）负责，
 * Shiro 对 /api/** 一律 anon 放行、不再介入；
 * 账号体系见 t_sys_user（PBKDF2 密码 + 角色），登录见 POST /api/auth/login。
 * 保留 Shiro 链仅为非 /api 资源的兜底（当前无后台页面）。
 */
@Configuration
public class ShiroConfig {

    @Bean
    public Realm localRealm() {
        // 不再内置静态账号（原 bootstrap/bootstrap 已随登录态体系下线移除）；
        // 第一个管理员由引导初始化（evops.auth.bootstrap，库中无账号时创建）。
        return new SimpleAccountRealm();
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        // /api/** 认证授权全部走 JWT（JwtAuthFilter + RoleInterceptor），Shiro 不拦截
        chain.addPathDefinition("/api/**", "anon");
        chain.addPathDefinition("/error", "anon");
        chain.addPathDefinition("/**", "anon");
        return chain;
    }
}
