package com.evops.auth;

import com.evops.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 首次启动引导：库中没有任何账号时，按 evops.auth.bootstrap 配置创建引导管理员，
 * 解决“登录体系刚上线、第一个账号从哪来”。生产应在首次登录后立即改密或建专属账号。
 */
@Component
@Order(0)
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final SysUserService userService;
    private final AuthProperties properties;

    public BootstrapAdminRunner(SysUserService userService, AuthProperties properties) {
        this.userService = userService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        AuthProperties.Bootstrap cfg = properties.getBootstrap();
        boolean existed = userService.count() > 0;
        // 与对账作业相同的做法：系统写入自建 RequestContext，结束后清理。
        RequestContext.Ctx previous = currentContext();
        try {
            RequestContext.set("SYSTEM", LocalDateTime.now(), "BOOTSTRAP-ADMIN-INIT");
            if (userService.bootstrapIfEmpty(cfg)) {
                log.warn("未检测到系统账号，已创建引导管理员 [{}]，请尽快登录并修改密码！", cfg.getUsername());
            } else if (!existed) {
                log.info("引导管理员创建已关闭（evops.auth.bootstrap.enabled=false），当前无可用账号");
            }
        } finally {
            restore(previous);
        }
    }

    private static RequestContext.Ctx currentContext() {
        try {
            return RequestContext.get();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private static void restore(RequestContext.Ctx previous) {
        if (previous == null) {
            RequestContext.clear();
        } else {
            RequestContext.set(previous.getOperator(), previous.getBizTime(), previous.getRequestNo());
        }
    }
}
