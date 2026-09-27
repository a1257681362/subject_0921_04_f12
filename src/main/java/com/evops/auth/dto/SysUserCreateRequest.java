package com.evops.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 管理员创建账号请求（仅 ADMIN 角色可用）。
 */
public class SysUserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过 32")
    private String username;

    @Size(max = 64, message = "显示名长度不能超过 64")
    private String displayName;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 128, message = "初始密码长度需为 8-128 位")
    private String password;

    @NotEmpty(message = "至少分配一个角色")
    private List<String> roles;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}
