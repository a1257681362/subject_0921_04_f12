package com.evops.auth;

import com.evops.auth.dto.ChangePasswordRequest;
import com.evops.auth.dto.LoginRequest;
import com.evops.auth.dto.RefreshRequest;
import com.evops.auth.dto.TokenPair;
import com.evops.common.ApiResponse;
import com.evops.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录态接口：
 *
 * | POST | /api/auth/login    | 登录（匿名），下发访问令牌+刷新令牌 |
 * | POST | /api/auth/refresh  | 访问令牌无感换新（匿名，刷新令牌一次性轮换） |
 * | POST | /api/auth/logout   | 登出（需登录），本端会话吊销，旧令牌立即失效 |
 * | POST | /api/auth/password | 修改密码（需登录），成功后全部端强制下线 |
 * | GET  | /api/auth/me       | 当前登录态（身份+角色） |
 *
 * login/refresh 不走 X-Operator/X-Request-No 三件套（登录前没有操作人，
 * 刷新一次性由会话表 CAS 保证）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SysUserMapper userMapper;

    public AuthController(AuthService authService, SysUserMapper userMapper) {
        this.authService = authService;
        this.userMapper = userMapper;
    }

    @PostMapping("/login")
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout(LoginContext.sessionId());
        return ApiResponse.ok(null);
    }

    /** 改密：成功后 token_version 自增且全部端会话吊销，本响应返回后所有端需重新登录。 */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        withOperatorContext(() -> authService.changePassword(LoginContext.userId(), request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        JwtClaims claims = LoginContext.get();
        SysUser user = userMapper.selectById(claims.getUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("displayName", user.getDisplayName());
        data.put("roles", AuthService.parseRoles(user.getRoles()));
        data.put("sessionId", claims.getSessionId());
        return ApiResponse.ok(data);
    }

    /** 登录态写接口豁免了入口三件套，这里补建 RequestContext 供审计字段填充，结束即清理。 */
    private void withOperatorContext(Runnable action) {
        JwtClaims claims = LoginContext.get();
        RequestContext.set(claims.getUsername(), LocalDateTime.now(),
                "AUTH-" + claims.getSessionId() + "-" + System.nanoTime());
        try {
            action.run();
        } finally {
            RequestContext.clear();
        }
    }
}
