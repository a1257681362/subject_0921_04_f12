package com.evops.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过 32")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 128, message = "密码长度不能超过 128")
    private String password;

    /** 登录端：WEB / APP（多端会话各自独立，互不顶掉） */
    @NotBlank(message = "clientType 不能为空（WEB/APP）")
    @Size(max = 16, message = "clientType 长度不能超过 16")
    private String clientType;

    /** 设备/浏览器描述，审计用，可空 */
    @Size(max = 128, message = "设备描述长度不能超过 128")
    private String deviceName;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getClientType() { return clientType; }
    public void setClientType(String clientType) { this.clientType = clientType; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
}
