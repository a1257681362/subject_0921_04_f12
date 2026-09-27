package com.evops.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话吊销操作独立成 bean 且强制新事务（REQUIRES_NEW）：
 * 刷新流程检测到重放/账号停用后要先抛 401 回滚业务事务，
 * 但“吊销整段会话”必须独立落库提交，不能被一起回滚。
 */
@Service
public class LoginSessionService {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionService.class);

    private final LoginSessionMapper sessionMapper;

    public LoginSessionService(LoginSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    public LoginSession findByCurrentRefreshHash(String tokenHash) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<LoginSession>()
                .eq(LoginSession::getRefreshTokenHash, tokenHash)
                .last("LIMIT 1"));
    }

    public LoginSession findByPrevRefreshHash(String tokenHash) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<LoginSession>()
                .eq(LoginSession::getPrevRefreshTokenHash, tokenHash)
                .last("LIMIT 1"));
    }

    public LoginSession getById(Long sessionId) {
        return sessionMapper.selectById(sessionId);
    }

    public void insertSession(LoginSession session) {
        sessionMapper.insert(session);
    }

    public List<LoginSession> findActiveByUser(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<LoginSession>()
                .eq(LoginSession::getUserId, userId)
                .eq(LoginSession::getStatus, LoginSession.STATUS_ACTIVE));
    }

    /**
     * CAS 一次性轮换：仅当当前刷新令牌哈希匹配且会话仍 ACTIVE 时，
     * 原子换为新哈希、旧哈希挪到 prev。并发/重放影响行数 0。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int casRotate(Long sessionId, String oldHash, String newHash, String newAccessJti,
                         LocalDateTime now) {
        return sessionMapper.update(null, new LambdaUpdateWrapper<LoginSession>()
                .eq(LoginSession::getId, sessionId)
                .eq(LoginSession::getRefreshTokenHash, oldHash)
                .eq(LoginSession::getStatus, LoginSession.STATUS_ACTIVE)
                .set(LoginSession::getRefreshTokenHash, newHash)
                .set(LoginSession::getPrevRefreshTokenHash, oldHash)
                .set(LoginSession::getCurrentAccessJti, newAccessJti)
                .setSql("rotate_count = rotate_count + 1")
                .set(LoginSession::getLastRefreshAt, now)
                .set(LoginSession::getLastRotatedAt, now)
                .set(LoginSession::getUpdateTime, now));
    }

    /** 吊销单个会话（若仍 ACTIVE）。独立事务提交，不受外层回滚影响。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void revokeInNewTx(LoginSession session, String reason, LocalDateTime now) {
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<LoginSession>()
                .eq(LoginSession::getId, session.getId())
                .eq(LoginSession::getStatus, LoginSession.STATUS_ACTIVE)
                .set(LoginSession::getStatus, LoginSession.STATUS_REVOKED)
                .set(LoginSession::getRevokeReason, reason)
                .set(LoginSession::getRevokedAt, now)
                .set(LoginSession::getUpdateTime, now));
        if (updated == 1) {
            log.info("会话已吊销 sid={} userId={} reason={}", session.getId(), session.getUserId(), reason);
        }
    }

    /** 批量吊销账号全部活跃会话（改密全端下线）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void revokeAllInNewTx(Long userId, String reason, LocalDateTime now) {
        List<LoginSession> actives = findActiveByUser(userId);
        for (LoginSession session : actives) {
            revokeInNewTx(session, reason, now);
        }
        log.info("账号 {} 批量吊销 {} 个活跃会话，原因={}", userId, actives.size(), reason);
    }

    /** 标记会话过期（独立事务）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markExpiredInNewTx(Long sessionId, LocalDateTime now) {
        sessionMapper.update(null, new LambdaUpdateWrapper<LoginSession>()
                .eq(LoginSession::getId, sessionId)
                .eq(LoginSession::getStatus, LoginSession.STATUS_ACTIVE)
                .set(LoginSession::getStatus, LoginSession.STATUS_EXPIRED)
                .set(LoginSession::getUpdateTime, now));
    }
}
