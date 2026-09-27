package com.evops.turnover;

import com.evops.auth.RequireRole;
import com.evops.auth.SysUser;
import com.evops.common.ApiResponse;
import com.evops.turnover.dto.TurnoverCorrectionRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 周转率统计接口：
 *
 * | GET  | /api/turnover/daily        | 快照+冲正审计视图（网点/日期区间） |
 * | POST | /api/turnover/run          | 手工重算当天（或指定业务日，未关账） |
 * | POST | /api/turnover/close        | 手工关账（重算并冻结指定业务日） |
 * | POST | /api/turnover/corrections  | 关账后追加冲正单（原因必填，append-only） |
 * | GET  | /api/turnover/job-runs     | 作业运行记录（凌晨关账/手工重算留痕） |
 *
 * 查询登录即可；重算/关账/冲正需 MANAGER 或 ADMIN。
 * 写请求遵循三件套（X-Operator/X-Request-No）与入口幂等；作业内以 SYS_TURNOVER 留痕。
 */
@RestController
@RequestMapping("/api/turnover")
public class TurnoverController {

    private final TurnoverService turnoverService;
    private final TurnoverJobRunner jobRunner;

    public TurnoverController(TurnoverService turnoverService, TurnoverJobRunner jobRunner) {
        this.turnoverService = turnoverService;
        this.jobRunner = jobRunner;
    }

    /** 审计视图：快照基线、每笔冲正、冲正合计与最终值（最终比率由最终计数复算）。 */
    @GetMapping("/daily")
    public ApiResponse<List<Map<String, Object>>> daily(
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(turnoverService.report(branchCode, from, to));
    }

    /** 手工重算（默认当天）：OPEN 快照原地刷新；已关账业务日拒绝（请走冲正）。 */
    @PostMapping("/run")
    @RequireRole({SysUser.ROLE_MANAGER, SysUser.ROLE_ADMIN})
    public ApiResponse<TurnoverJobRun> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        LocalDate target = bizDate != null ? bizDate : LocalDate.now();
        return ApiResponse.ok(jobRunner.runRecompute(target));
    }

    /** 手工关账（默认前一业务日）：重算全部网点后置 CLOSED 冻结；重复关账幂等跳过。 */
    @PostMapping("/close")
    @RequireRole({SysUser.ROLE_MANAGER, SysUser.ROLE_ADMIN})
    public ApiResponse<TurnoverJobRun> close(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        LocalDate target = bizDate != null ? bizDate : LocalDate.now().minusDays(1);
        return ApiResponse.ok(jobRunner.runClose(target));
    }

    /** 关账后冲正：只追加不改写，原因必填，审计视图同时呈现基线/冲正/最终值。 */
    @PostMapping("/corrections")
    @RequireRole({SysUser.ROLE_MANAGER, SysUser.ROLE_ADMIN})
    public ApiResponse<TurnoverCorrection> addCorrection(@Valid @RequestBody TurnoverCorrectionRequest request) {
        return ApiResponse.ok(turnoverService.addCorrection(
                request.getBranchCode(), request.getBizDate(),
                request.getCompletedDelta(), request.getTotalVehiclesDelta(), request.getReason()));
    }

    @GetMapping("/job-runs")
    public ApiResponse<List<TurnoverJobRun>> jobRuns(
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(jobRunner.list(jobType, bizDate));
    }
}
