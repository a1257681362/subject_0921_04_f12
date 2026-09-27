package com.evops.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.auth.dto.ChangePasswordRequest;
import com.evops.auth.dto.LoginRequest;
import com.evops.auth.dto.TokenPair;
import com.evops.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 登录态服务：
 *
 * - 登录：校验账号密码后创建端会话，下发 访问令牌(JWT) + 刷新令牌(随机串)；
 * - 刷新：刷新令牌一次性轮换，CAS 原子更新保证并发/重放只有一次成功；
 *   已轮换的旧令牌再次出现视为被盗，整段会话立即吊销（吊销走独立事务，
 *   即便随后抛 401 回滚刷新事务，吊销也已落库）；
 * - 登出：吊销当前端会话，该会话已签发访问令牌下一次请求即被拦截（立即失效）；
 * - 改密：token_version + 1 并吊销该账号全部端会话，所有端强制下线。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String CODE_BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String CODE_USER_DISABLED = "USER_DISABLED";
    public static final String CODE_REFRESH_INVALID = "REFRESH_INVALID";
    public static final String CODE_REFRESH_EXPIRED = "REFRESH_EXPIRED";
    public static final String CODE_REFRESH_REPLAY = "REFRESH_REPLAY";
    public static final String CODE_REFRESH_CONFLICT = "REFRESH_CONFLICT";

    private final SysUserMapper userMapper;
    private final LoginSessionService sessionService;
    private final PasswordCodec codec;
    private final JwtService jwtService;
    private final AuthProperties properties;

    public AuthService(SysUserMapper userMapper, LoginSessionService sessionService,
                       PasswordCodec codec, JwtService jwtService, AuthProperties properties) {
        this.userMapper = userMapper;
        this.sessionService = sessionService;
        this.codec = codec;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 登录

    @Transactional(rollbackFor = Exception.class)
    public TokenPair login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername().trim()));
        if (user == null || !codec.matches(request.getPassword(), user.getPasswordHash())) {
            // 用户名/密码错误同一出口，不暴露账号是否存在
            throw new UnauthorizedException(CODE_BAD_CREDENTIALS, "用户名或密码错误");
        }
        if (!SysUser.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new UnauthorizedException(CODE_USER_DISABLED, "账号已停用，请联系管理员");
        }

        LocalDateTime now = LocalDateTime.now();
        String refreshToken = codec.randomToken();
        String accessJti = codec.randomJti();

        LoginSession session = new LoginSession();
        session.setUserId(user.getId());
        session.setUsername(user.getUsername());
        session.setClientType(normalizeClientType(request.getClientType()));
        session.setDeviceName(request.getDeviceName());
        session.setRefreshTokenHash(codec.sha256Hex(refreshToken));
        session.setCurrentAccessJti(accessJti);
        session.setStatus(LoginSession.STATUS_ACTIVE);
        session.setRotateCount(0);
        session.setLoginAt(now);
        session.setLastRefreshAt(now);
        session.setExpiresAt(now.plusDays(properties.getRefresh().getTtlDays()));
        session.setCreateTime(now);
        session.setUpdateTime(now);
        sessionService.insertSession(session);

        user.setLastLoginAt(now);
        userMapper.updateById(user);

        log.info("登录成功 userId={} username={} sid={} client={}",
                user.getId(), user.getUsername(), session.getId(), session.getClientType());
        return buildTokenPair(user, session, accessJti, refreshToken);
    }

    // ---------------------------------------------------------- 刷新令牌轮换

    /**
     * 用刷新令牌换新一对令牌。旧刷新令牌一次性失效：
     * 1) 命中上一个哈希 -> 重放，独立事务吊销整段会话后抛 401；
     * 2) 命中当前哈希 -> CAS 轮换（WHERE 带旧哈希+ACTIVE，并发只有一方成功）；
     * 3) 都不命中 -> 无效令牌。
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenPair refresh(String rawRefreshToken) {
        String tokenHash = codec.sha256Hex(rawRefreshToken);
        LocalDateTime now = LocalDateTime.now();

        LoginSession byPrev = sessionService.findByPrevRefreshHash(tokenHash);
        if (byPrev != null) {
            // 已被轮换掉的旧令牌再次出现：正常客户端不会发生，判为令牌泄漏/重放。
            // 吊销用独立事务，确保随后抛异常回滚本刷新事务时吊销已落库。
            sessionService.revokeInNewTx(byPrev, LoginSession.REASON_REPLAY_DETECTED, now);
            log.warn("刷新令牌重放，已吊销会话 sid={} userId={}", byPrev.getId(), byPrev.getUserId());
            throw new UnauthorizedException(CODE_REFRESH_REPLAY,
                    "刷新令牌已被使用过，为安全起见会话已被吊销，请重新登录");
        }

        LoginSession session = sessionService.findByCurrentRefreshHash(tokenHash);
        if (session == null) {
            throw new UnauthorizedException(CODE_REFRESH_INVALID, "刷新令牌无效，请重新登录");
        }
        if (LoginSession.STATUS_REVOKED.equals(session.getStatus())) {
            throw new UnauthorizedException(CODE_REFRESH_INVALID, "会话已注销，请重新登录");
        }
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) {
            sessionService.markExpiredInNewTx(session.getId(), now);
            throw new UnauthorizedException(CODE_REFRESH_EXPIRED, "登录已过期，请重新登录");
        }

        SysUser user = userMapper.selectById(session.getUserId());
        if (user == null || !SysUser.STATUS_ACTIVE.equals(user.getStatus())) {
            sessionService.revokeInNewTx(session, LoginSession.REASON_USER_DISABLED, now);
            throw new UnauthorizedException(CODE_USER_DISABLED, "账号不可用，请联系管理员");
        }

        String newRefreshToken = codec.randomToken();
        String newAccessJti = codec.randomJti();
        String newHash = codec.sha256Hex(newRefreshToken);

        int updated = sessionService.casRotate(session.getId(), tokenHash, newHash, newAccessJti, now);
        if (updated != 1) {
            throw new UnauthorizedException(CODE_REFRESH_CONFLICT, "刷新请求并发冲突，请重试");
        }

        session.setRefreshTokenHash(newHash);
        session.setCurrentAccessJti(newAccessJti);
        return buildTokenPair(user, session, newAccessJti, newRefreshToken);
    }

    // ------------------------------------------------------------------ 登出

    /** 吊销当前会话；访问令牌绑定 sid，下一次请求即被认证过滤器拒绝（旧令牌立即失效）。 */
    @Transactional(rollbackFor = Exception.class)
    public void logout(Long sessionId) {
        LoginSession session = sessionService.getById(sessionId);
        if (session != null && LoginSession.STATUS_ACTIVE.equals(session.getStatus())) {
            sessionService.revokeInNewTx(session, LoginSession.REASON_LOGOUT, LocalDateTime.now());
            log.info("登出成功 sid={} userId={}", sessionId, session.getUserId());
        }
    }

    // -------------------------------------------------------------- 修改密码

    /**
     * 修改本人密码：校验原密码后更新密码摘要、token_version + 1，
     * 并吊销该账号全部 ACTIVE 会话（含当前端）——密码修改后所有端强制下线，需重新登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, ChangePasswordRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("账号不存在");
        }
        if (!codec.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BizException("新密码不能与原密码相同");
        }
        LocalDateTime now = LocalDateTime.now();
        user.setPasswordHash(codec.encode(request.getNewPassword()));
        user.setTokenVersion(user.getTokenVersion() == null ? 1 : user.getTokenVersion() + 1);
        userMapper.updateById(user);
        // 独立事务批量吊销，保证即便本方法后续有异常也已下线
        sessionService.revokeAllInNewTx(userId, LoginSession.REASON_PASSWORD_CHANGED, now);
        log.warn("密码已修改，账号全部端会话已吊销 userId={} revokedAt={}", userId, now);
    }

    // --------------------------------------------------------- 访问令牌准入校验

    /**
     * 认证过滤器每请求调用：签名与过期已在 JwtService 校验，这里做服务端状态校验——
     * 账号存在且启用、tokenVersion 一致（改密后旧 tv 立刻失配）、会话 ACTIVE（登出/重放吊销即拒）。
     */
    public SysUser validateAccess(JwtClaims claims) {
        if (claims.getUserId() == null || claims.getSessionId() == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "令牌内容不完整");
        }
        SysUser user = userMapper.selectById(claims.getUserId());
        if (user == null || !SysUser.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new UnauthorizedException("UNAUTHORIZED", "账号不可用，请重新登录");
        }
        if (user.getTokenVersion() == null || user.getTokenVersion() != claims.getTokenVersion()) {
            throw new UnauthorizedException("TOKEN_VERSION_CHANGED", "登录态已失效，请重新登录");
        }
        LoginSession session = sessionService.getById(claims.getSessionId());
        if (session == null || !LoginSession.STATUS_ACTIVE.equals(session.getStatus())
                || !session.getUserId().equals(user.getId())) {
            throw new UnauthorizedException("SESSION_REVOKED", "会话已失效，请重新登录");
        }
        return user;
    }

    // -------------------------------------------------------------- 辅助方法

    public static List<String> parseRoles(String roles) {
        if (roles == null || roles.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private TokenPair buildTokenPair(SysUser user, LoginSession session,
                                     String accessJti, String refreshToken) {
        List<String> roles = parseRoles(user.getRoles());
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), roles,
                user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                session.getId(), accessJti);
        TokenPair pair = new TokenPair();
        pair.setAccessToken(accessToken);
        pair.setRefreshToken(refreshToken);
        pair.setTokenType("Bearer");
        pair.setExpiresIn(properties.getJwt().getAccessTokenTtlSeconds());
        pair.setUserId(user.getId());
        pair.setUsername(user.getUsername());
        pair.setDisplayName(user.getDisplayName());
        pair.setRoles(roles);
        pair.setSessionId(session.getId());
        return pair;
    }

    private static String normalizeClientType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (value.length() > 16) {
            value = value.substring(0, 16);
        }
        return value;
    }
}
