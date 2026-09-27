package com.evops.auth.dto;

import javax.validation.constraints.NotBlank;

public class RefreshRequest {

    /** 登录时下发的刷新令牌；一次性，使用后旧令牌立即失效 */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
