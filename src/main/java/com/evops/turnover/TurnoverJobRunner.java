package com.evops.turnover;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.RequestContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 周转率作业外壳（与对账 ReconJobRunner 同一套路）：
 * - 为系统/手工触发的作业建立 RequestContext（SYS_TURNOVER 或当前登录人），
 *   快照行的 generated_by / closed_by 有明确操作人；
 * - 每次触发在 t_turnover_job_run 留痕（SUCCESS/FAILED + 覆盖/成功/跳过网点数 + 摘要）。
 */
@Component
public class TurnoverJobRunner {

    private final TurnoverJobRunMapper jobRunMapper;
    private final TurnoverService turnoverService;

    public TurnoverJobRunner(TurnoverJobRunMapper jobRunMapper, TurnoverService turnoverService) {
        this.jobRunMapper = jobRunMapper;
        this.turnoverService = turnoverService;
    }

    /** 手工/定时重算（不关账）。 */
    public TurnoverJobRun runRecompute(LocalDate bizDate) {
        return execute(TurnoverJobRun.JOB_MANUAL_RUN, bizDate,
                runId -> {
                    List<String> branches = turnoverService.computeDay(bizDate, true, runId);
                    return "业务日 " + bizDate + " 重算完成，生成/刷新快照 " + branches.size() + " 个网点";
                });
    }

    /** 凌晨关账：重算并冻结。 */
    public TurnoverJobRun runClose(LocalDate bizDate) {
        return execute(TurnoverJobRun.JOB_DAILY_CLOSE, bizDate,
                runId -> {
                    int[] result = turnoverService.closeDay(bizDate, runId);
                    return "业务日 " + bizDate + " 关账完成，覆盖网点 " + result[0]
                            + " 个，本次新冻结 " + result[1] + " 个";
                });
    }

    private TurnoverJobRun execute(String jobType, LocalDate bizDate,
                                   java.util.function.Function<Long, String> job) {
        TurnoverJobRun run = new TurnoverJobRun();
        run.setJobType(jobType);
        run.setBizDate(bizDate);
        run.setFiredAt(LocalDateTime.now());
        run.setBranchCount(0);
        run.setSuccessCount(0);
        run.setSkipCount(0);
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        jobRunMapper.insert(run);

        RequestContext.Ctx previous = currentContext();
        String requestNo = "JOB-" + jobType + "-" + run.getId() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RequestContext.set(TurnoverService.SYS_OPERATOR, LocalDateTime.now(), requestNo);
        try {
            String summary = job.apply(run.getId());
            run.setStatus(TurnoverJobRun.STATUS_SUCCESS);
            run.setSummary(summary);
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdateTime(LocalDateTime.now());
            jobRunMapper.updateById(run);
            return run;
        } catch (RuntimeException ex) {
            run.setStatus(TurnoverJobRun.STATUS_FAILED);
            run.setSummary("作业失败");
            run.setErrorDetail(truncate(ex.toString(), 4000));
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdateTime(LocalDateTime.now());
            jobRunMapper.updateById(run);
            throw ex;
        } finally {
            restore(previous);
        }
    }

    public List<TurnoverJobRun> list(String jobType, LocalDate bizDate) {
        return jobRunMapper.selectList(new LambdaQueryWrapper<TurnoverJobRun>()
                .eq(jobType != null && !jobType.trim().isEmpty(),
                        TurnoverJobRun::getJobType, jobType == null ? null : jobType.trim().toUpperCase())
                .eq(bizDate != null, TurnoverJobRun::getBizDate, bizDate)
                .orderByDesc(TurnoverJobRun::getId));
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
