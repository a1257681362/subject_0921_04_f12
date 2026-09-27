package com.evops.turnover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 周转率凌晨关账作业（默认每天 04:00，避开对账 02:00/03:00）：
 * 对前一业务日重算全部网点快照并置 CLOSED 冻结，逐次留痕 t_turnover_job_run。
 * evops.turnover.enabled=false 为应急总开关（不影响手工触发接口）。
 */
@Component
public class TurnoverScheduler {

    private static final Logger log = LoggerFactory.getLogger(TurnoverScheduler.class);

    private final TurnoverJobRunner jobRunner;

    @Value("${evops.turnover.enabled:true}")
    private boolean enabled;

    public TurnoverScheduler(TurnoverJobRunner jobRunner) {
        this.jobRunner = jobRunner;
    }

    @Scheduled(cron = "${evops.turnover.daily-close-cron:0 0 4 * * ?}")
    public void closeBeforeDawn() {
        if (!enabled) {
            return;
        }
        LocalDate bizDate = LocalDate.now().minusDays(1);
        try {
            TurnoverJobRun run = jobRunner.runClose(bizDate);
            log.info("周转率关账完成 bizDate={} runId={} summary={}",
                    bizDate, run.getId(), run.getSummary());
        } catch (Exception ex) {
            log.error("周转率关账失败 bizDate={}", bizDate, ex);
        }
    }
}
