package com.evops.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 登录会话：每次登录一行，多端并存（WEB/APP/...）。
 * 刷新令牌一次性轮换：当前令牌哈希在 refresh_token_hash（唯一），
 * 被换下来的旧哈希挪到 prev_refresh_token_hash——
 * 旧令牌再次出现即判重放（疑似被盗），整段会话立刻吊销。
 */
@TableName("t_login_session")
public class LoginSession {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String REASON_USER_DISABLED = "USER_DISABLED";
    public static final String REASON_REPLAY_DETECTED = "REPLAY_DETECTED";

    public static final String CLIENT_WEB = "WEB";
    public static final String CLIENT_APP = "APP";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String clientType;
    private String deviceName;
    private String refreshTokenHash;
    private String prevRefreshTokenHash;
    private String currentAccessJti;
    private String status;
    private Integer rotateCount;
    private LocalDateTime loginAt;
    private LocalDateTime lastRefreshAt;
    private LocalDateTime lastRotatedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String revokeReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getClientType() { return clientType; }
    public void setClientType(String clientType) { this.clientType = clientType; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public void setRefreshTokenHash(String refreshTokenHash) { this.refreshTokenHash = refreshTokenHash; }
    public String getPrevRefreshTokenHash() { return prevRefreshTokenHash; }
    public void setPrevRefreshTokenHash(String prevRefreshTokenHash) { this.prevRefreshTokenHash = prevRefreshTokenHash; }
    public String getCurrentAccessJti() { return currentAccessJti; }
    public void setCurrentAccessJti(String currentAccessJti) { this.currentAccessJti = currentAccessJti; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRotateCount() { return rotateCount; }
    public void setRotateCount(Integer rotateCount) { this.rotateCount = rotateCount; }
    public LocalDateTime getLoginAt() { return loginAt; }
    public void setLoginAt(LocalDateTime loginAt) { this.loginAt = loginAt; }
    public LocalDateTime getLastRefreshAt() { return lastRefreshAt; }
    public void setLastRefreshAt(LocalDateTime lastRefreshAt) { this.lastRefreshAt = lastRefreshAt; }
    public LocalDateTime getLastRotatedAt() { return lastRotatedAt; }
    public void setLastRotatedAt(LocalDateTime lastRotatedAt) { this.lastRotatedAt = lastRotatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
