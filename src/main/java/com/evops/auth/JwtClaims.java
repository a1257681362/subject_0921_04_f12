package com.evops.auth;

import java.util.List;

/**
 * 访问令牌载荷（JWT claims 的强类型视图）。
 */
public class JwtClaims {

    /** 令牌类型，访问令牌固定 ACCESS（防止刷新令牌被当作访问令牌使用） */
    public static final String TYPE_ACCESS = "ACCESS";

    private Long userId;
    private String username;
    private List<String> roles;
    private int tokenVersion;
    private Long sessionId;
    private String jti;
    private long issuedAt;
    private long expiresAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(long issuedAt) { this.issuedAt = issuedAt; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
