package com.evops.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 登录态配置（evops.auth）：
 * - JWT 访问令牌 HS256 密钥与有效期；
 * - 刷新令牌（会话）有效期；
 * - 引导管理员（库中无账号时启动自动创建，仅用于初始化，之后应改密/停用）。
 */
@Component
@ConfigurationProperties(prefix = "evops.auth")
public class AuthProperties {

    /** 开发期默认密钥。生产必须通过配置覆盖（启动检测到默认值会打 WARN）。 */
    public static final String DEV_DEFAULT_SECRET =
            "evops-dev-only-secret-key-please-change-0123456789";

    private boolean enabled = true;

    private final Jwt jwt = new Jwt();

    private final Refresh refresh = new Refresh();

    private final Bootstrap bootstrap = new Bootstrap();

    @PostConstruct
    public void validate() {
        if (jwt.getSecret() == null || jwt.getSecret().getBytes().length < 32) {
            throw new IllegalStateException(
                    "evops.auth.jwt.secret 必须配置且不少于 32 字节（HS256）");
        }
        if (DEV_DEFAULT_SECRET.equals(jwt.getSecret())) {
            // 不阻断启动（本地开发零配置可用），但显著提示。
            System.err.println("[EVOPS][WARN] evops.auth.jwt.secret 正在使用开发默认密钥，"
                    + "生产部署必须在 application.yml/环境变量中覆盖");
        }
        if (jwt.getAccessTokenTtlSeconds() <= 0 || refresh.getTtlDays() <= 0) {
            throw new IllegalStateException("令牌有效期配置必须为正数");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Jwt getJwt() { return jwt; }
    public Refresh getRefresh() { return refresh; }
    public Bootstrap getBootstrap() { return bootstrap; }

    public static class Jwt {
        /** HS256 签名密钥原文（UTF-8，≥32 字节） */
        private String secret = DEV_DEFAULT_SECRET;
        /** 访问令牌有效期（秒），默认 30 分钟：短期有效，过期走刷新接口无感换新 */
        private long accessTokenTtlSeconds = 1800;
        private String issuer = "evops";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getAccessTokenTtlSeconds() { return accessTokenTtlSeconds; }
        public void setAccessTokenTtlSeconds(long v) { this.accessTokenTtlSeconds = v; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
    }

    public static class Refresh {
        /** 刷新令牌/会话有效期（天），默认 7 天 */
        private int ttlDays = 7;

        public int getTtlDays() { return ttlDays; }
        public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }
    }

    public static class Bootstrap {
        /** 库中无任何账号时，启动自动创建引导管理员 */
        private boolean enabled = true;
        private String username = "admin";
        private String password = "admin123";
        private String displayName = "引导管理员";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }
}
