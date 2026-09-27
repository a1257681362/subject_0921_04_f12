package com.evops.auth;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * 系统账号（调度员/主管/管理员）。
 * token_version 是全端下线开关：修改密码（或管理员重置密码）即 +1，
 * 所有已签发访问令牌因 tv 不一致立刻失效。
 */
@TableName("t_sys_user")
public class SysUser extends BizEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_ADMIN = "ADMIN";

    private String username;
    private String displayName;
    private String passwordHash;
    /** 角色逗号分隔 */
    private String roles;
    private String status;
    private Integer tokenVersion;
    private LocalDateTime lastLoginAt;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(Integer tokenVersion) { this.tokenVersion = tokenVersion; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
