package com.evops.auth;

import com.evops.auth.dto.SysUserCreateRequest;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 账号管理（仅 ADMIN）：创建调度员/主管账号、账号列表（不回传密码摘要）。
 * 遵循业务写请求三件套（X-Operator/X-Request-No）与入口幂等。
 */
@RestController
@RequestMapping("/api/system/users")
@RequireRole(SysUser.ROLE_ADMIN)
public class SystemUserController {

    private final SysUserService userService;

    public SystemUserController(SysUserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<SysUser> create(@Valid @RequestBody SysUserCreateRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @GetMapping
    public ApiResponse<List<SysUser>> list() {
        return ApiResponse.ok(userService.list());
    }
}
