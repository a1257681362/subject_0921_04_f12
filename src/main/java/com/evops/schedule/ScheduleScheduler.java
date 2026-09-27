package com.evops.schedule;

import com.evops.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 档期超时占位扫描（schedule-1）：周期释放到锁位截止（默认 25 分钟）仍未确认的 HELD。
 *
 * 默认每分钟扫描一次（evops.schedule.release-cron）。逐条独立事务 + 乐观条件更新，
 * 与现场确认/取消并发时只有一方生效；单条失败不影响其余。
 * evops.schedule.enabled=false 为应急总开关（不影响手工 POST /api/schedules/release-overdue）。
 *
 * 另外读取路径（占位/可用性校验）在本车事务内做了懒释放，即便扫描延迟，
 * 超时占位也不会挡住新预约。
 */
@Component
public class ScheduleScheduler {

    /** 系统自动释放的操作人（与周转率/对账作业同一命名风格）。 */
    public static final String SYS_OPERATOR = "SYS_SCHEDULE";

    private static final Logger log = LoggerFactory.getLogger(ScheduleScheduler.class);

    private final ScheduleService scheduleService;

    @Value("${evops.schedule.enabled:true}")
    private boolean enabled;

    public ScheduleScheduler(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Scheduled(cron = "${evops.schedule.release-cron:0 * * * * ?}")
    public void releaseOverdueHolds() {
        if (!enabled) {
            return;
        }
        RequestContext.Ctx previous = currentContext();
        RequestContext.set(SYS_OPERATOR, LocalDateTime.now(),
                "JOB-SCHEDULE-RELEASE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try {
            int released = scheduleService.releaseOverdueHolds();
            if (released > 0) {
                log.info("档期超时占位自动释放完成，本次释放 {} 个占位", released);
            }
        } catch (Exception ex) {
            log.error("档期超时占位扫描失败", ex);
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
