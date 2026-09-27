package com.evops.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.auth.dto.SysUserCreateRequest;
import com.evops.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账号管理：引导管理员初始化、管理员创建账号、账号列表。
 */
@Service
public class SysUserService {

    private static final Set<String> ALLOWED_ROLES = new LinkedHashSet<>(Arrays.asList(
            SysUser.ROLE_DISPATCHER, SysUser.ROLE_MANAGER, SysUser.ROLE_ADMIN));

    private final SysUserMapper userMapper;
    private final PasswordCodec codec;

    public SysUserService(SysUserMapper userMapper, PasswordCodec codec) {
        this.userMapper = userMapper;
        this.codec = codec;
    }

    public long count() {
        Long n = userMapper.selectCount(null);
        return n == null ? 0 : n;
    }

    /** 库中无账号时创建引导管理员。返回是否创建。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean bootstrapIfEmpty(AuthProperties.Bootstrap cfg) {
        if (cfg == null || !cfg.isEnabled() || count() > 0) {
            return false;
        }
        SysUser admin = new SysUser();
        admin.setUsername(cfg.getUsername().trim());
        admin.setDisplayName(cfg.getDisplayName());
        admin.setPasswordHash(codec.encode(cfg.getPassword()));
        admin.setRoles(SysUser.ROLE_ADMIN);
        admin.setStatus(SysUser.STATUS_ACTIVE);
        admin.setTokenVersion(0);
        userMapper.insert(admin);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysUser create(SysUserCreateRequest request) {
        String username = request.getUsername().trim();
        Long duplicated = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (duplicated != null && duplicated > 0) {
            throw new BizException("用户名已存在：" + username);
        }
        List<String> roles = normalizeRoles(request.getRoles());
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setDisplayName(request.getDisplayName());
        user.setPasswordHash(codec.encode(request.getPassword()));
        user.setRoles(String.join(",", roles));
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
        user.setPasswordHash(null);
        return user;
    }

    public List<SysUser> list() {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByAsc(SysUser::getId));
        users.forEach(u -> u.setPasswordHash(null));
        return users;
    }

    private static List<String> normalizeRoles(List<String> raw) {
        List<String> roles = new ArrayList<>();
        for (String r : raw == null ? new ArrayList<String>() : raw) {
            if (r == null) {
                continue;
            }
            String role = r.trim().toUpperCase();
            if (!ALLOWED_ROLES.contains(role)) {
                throw new BizException("非法角色：" + r + "（允许：" + String.join("/", ALLOWED_ROLES) + "）");
            }
            if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            throw new BizException("至少分配一个有效角色");
        }
        return roles.stream().distinct().collect(Collectors.toList());
    }
}
