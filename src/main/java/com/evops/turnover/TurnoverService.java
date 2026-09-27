package com.evops.turnover;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周转率统计服务。
 *
 * 审计口径（原始计数均可在业务表独立复算）：
 * - 在役车辆数 = t_vehicle 中 IDLE+RENTING（DISABLED 不计分母，单列披露）；
 * - 完成订单数 = 事件流水 biz_time 落在业务日的 RETURN 行数（还车结算）；
 * - 周转率 = 完成订单数 / 在役车辆数（4 位小数，分母 0 为 null）；
 * - 出租率 = 快照时点 RENTING / 在役车辆数；
 * - 车辆/订单未分派网点并入 NO-BRANCH。
 *
 * 生命周期：当天 OPEN 可重算（快照原地刷新，recompute_count 自增）；
 * 凌晨关账置 CLOSED 冻结，拒绝改写；修正只能追加冲正单（原因必填），
 * 查询返回 基线 + 冲正合计 = 最终值。
 */
@Service
public class TurnoverService {

    private static final Logger log = LoggerFactory.getLogger(TurnoverService.class);

    public static final String NO_BRANCH = "NO-BRANCH";
    public static final String SYS_OPERATOR = "SYS_TURNOVER";
    private static final int RATE_SCALE = 4;

    private final TurnoverStatsMapper statsMapper;
    private final TurnoverDailyMapper dailyMapper;
    private final TurnoverCorrectionMapper correctionMapper;

    public TurnoverService(TurnoverStatsMapper statsMapper,
                           TurnoverDailyMapper dailyMapper,
                           TurnoverCorrectionMapper correctionMapper) {
        this.statsMapper = statsMapper;
        this.dailyMapper = dailyMapper;
        this.correctionMapper = correctionMapper;
    }

    /** 单网点单业务日结果（含原始计数，供作业批量生成使用）。 */
    public static class BranchStat {
        public String branchCode;
        public int idle;
        public int renting;
        public int disabled;
        public int completed;
        public int created;
    }

    /**
     * 计算/刷新某业务日全部活跃网点（当日有车辆台账或有完成/下单事件的网点）快照。
     *
     * @param requireOpen true=手工重算，遇 CLOSED 抛 409；false=关账作业，已 CLOSED 跳过
     * @return 生成或刷新的网点编码（被跳过的不含在内）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> computeDay(LocalDate bizDate, boolean requireOpen, Long jobRunId) {
        Map<String, BranchStat> stats = loadStats(bizDate);
        LocalDateTime now = LocalDateTime.now();
        String operator = currentOperator();
        List<String> touched = new ArrayList<>();

        for (BranchStat stat : stats.values()) {
            TurnoverDaily existing = dailyMapper.selectOne(new LambdaQueryWrapper<TurnoverDaily>()
                    .eq(TurnoverDaily::getBranchCode, stat.branchCode)
                    .eq(TurnoverDaily::getBizDate, bizDate));
            if (existing != null && TurnoverDaily.STATUS_CLOSED.equals(existing.getStatus())) {
                if (requireOpen) {
                    throw new BizException("业务日 " + bizDate + " 网点 " + stat.branchCode
                            + " 已关账冻结，不能重算；如需修正请追加冲正单");
                }
                continue;
            }
            TurnoverDaily row = existing != null ? existing : new TurnoverDaily();
            row.setBranchCode(stat.branchCode);
            row.setBizDate(bizDate);
            row.setIdleVehicles(stat.idle);
            row.setRentingVehicles(stat.renting);
            row.setDisabledVehicles(stat.disabled);
            int total = stat.idle + stat.renting;
            row.setTotalVehicles(total);
            row.setCompletedOrders(stat.completed);
            row.setCreatedOrders(stat.created);
            row.setTurnoverRate(rate(stat.completed, total));
            row.setRentalRate(rate(stat.renting, total));
            row.setStatus(TurnoverDaily.STATUS_OPEN);
            row.setLastJobRunId(jobRunId);
            row.setLastComputedAt(now);
            if (existing == null) {
                row.setRecomputeCount(0);
                row.setGeneratedBy(operator);
                row.setGeneratedAt(now);
                row.setCreateTime(now);
                row.setUpdateTime(now);
                dailyMapper.insert(row);
            } else {
                row.setRecomputeCount((row.getRecomputeCount() == null ? 0 : row.getRecomputeCount()) + 1);
                row.setUpdateTime(now);
                dailyMapper.updateById(row);
            }
            touched.add(stat.branchCode);
        }
        log.info("周转率快照已生成/刷新 bizDate={} 网点数={} requireOpen={} jobRunId={}",
                bizDate, touched.size(), requireOpen, jobRunId);
        return touched;
    }

    /**
     * 关账：重算全部网点后把该业务日仍为 OPEN 的快照置 CLOSED。
     * 已 CLOSED 的网点跳过（幂等，重复关账不报错）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int[] closeDay(LocalDate bizDate, Long jobRunId) {
        List<String> touched = computeDay(bizDate, false, jobRunId);
        LocalDateTime now = LocalDateTime.now();
        String operator = currentOperator();
        int closed = dailyMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TurnoverDaily>()
                .eq(TurnoverDaily::getBizDate, bizDate)
                .eq(TurnoverDaily::getStatus, TurnoverDaily.STATUS_OPEN)
                .set(TurnoverDaily::getStatus, TurnoverDaily.STATUS_CLOSED)
                .set(TurnoverDaily::getClosedBy, operator)
                .set(TurnoverDaily::getClosedAt, now)
                .set(TurnoverDaily::getLastJobRunId, jobRunId)
                .set(TurnoverDaily::getUpdateTime, now));
        return new int[]{touched.size(), closed};
    }

    /** 查询快照（不含冲正视图）。 */
    public List<TurnoverDaily> listSnapshots(String branchCode, LocalDate from, LocalDate to) {
        return dailyMapper.selectList(new LambdaQueryWrapper<TurnoverDaily>()
                .eq(branchCode != null && !branchCode.trim().isEmpty(),
                        TurnoverDaily::getBranchCode, branchCode == null ? null : branchCode.trim())
                .ge(from != null, TurnoverDaily::getBizDate, from)
                .le(to != null, TurnoverDaily::getBizDate, to)
                .orderByAsc(TurnoverDaily::getBizDate)
                .orderByAsc(TurnoverDaily::getBranchCode));
    }

    /**
     * 审计视图：每行返回快照基线、冲正合计与最终值（最终比率由最终计数复算，不直接相加比率）。
     */
    public List<Map<String, Object>> report(String branchCode, LocalDate from, LocalDate to) {
        List<TurnoverDaily> rows = listSnapshots(branchCode, from, to);
        List<TurnoverCorrection> corrections = correctionMapper.selectList(
                new LambdaQueryWrapper<TurnoverCorrection>()
                        .eq(branchCode != null && !branchCode.trim().isEmpty(),
                                TurnoverCorrection::getBranchCode,
                                branchCode == null ? null : branchCode.trim())
                        .ge(from != null, TurnoverCorrection::getBizDate, from)
                        .le(to != null, TurnoverCorrection::getBizDate, to)
                        .orderByAsc(TurnoverCorrection::getId));
        Map<String, List<TurnoverCorrection>> correctionMap = new LinkedHashMap<>();
        for (TurnoverCorrection c : corrections) {
            correctionMap.computeIfAbsent(key(c.getBranchCode(), c.getBizDate()), k -> new ArrayList<>()).add(c);
        }
        List<Map<String, Object>> view = new ArrayList<>();
        for (TurnoverDaily row : rows) {
            List<TurnoverCorrection> cs = correctionMap.getOrDefault(key(row.getBranchCode(), row.getBizDate()),
                    new ArrayList<>());
            int completedDelta = cs.stream().mapToInt(c -> nz(c.getCompletedDelta())).sum();
            int totalDelta = cs.stream().mapToInt(c -> nz(c.getTotalVehiclesDelta())).sum();
            int finalCompleted = nz(row.getCompletedOrders()) + completedDelta;
            int finalTotal = nz(row.getTotalVehicles()) + totalDelta;

            // 视图统一四位小数口径（库内 DECIMAL(10,4) 精确存储；H2/驱动回读时 scale 可能被规范化）
            row.setTurnoverRate(scale4(row.getTurnoverRate()));
            row.setRentalRate(scale4(row.getRentalRate()));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("snapshot", row);
            item.put("correctionCount", cs.size());
            item.put("corrections", cs);
            item.put("completedDelta", completedDelta);
            item.put("totalVehiclesDelta", totalDelta);
            item.put("finalCompletedOrders", finalCompleted);
            item.put("finalTotalVehicles", finalTotal);
            item.put("finalTurnoverRate", rate(finalCompleted, finalTotal));
            view.add(item);
        }
        return view;
    }

    /**
     * 追加冲正单：只允许对已关账（CLOSED）业务日，原因必填，调整后分母不得为负。
     * append-only：不修改快照行，审计永远能同时看到基线、每笔冲正和最终值。
     */
    @Transactional(rollbackFor = Exception.class)
    public TurnoverCorrection addCorrection(String branchCode, LocalDate bizDate,
                                            Integer completedDelta, Integer totalDelta, String reason) {
        if (branchCode == null || branchCode.trim().isEmpty()) {
            throw new BizException("网点编码不能为空");
        }
        if (bizDate == null) {
            throw new BizException("业务日不能为空");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException("冲正原因必填");
        }
        int cDelta = completedDelta == null ? 0 : completedDelta;
        int tDelta = totalDelta == null ? 0 : totalDelta;
        if (cDelta == 0 && tDelta == 0) {
            throw new BizException("冲正调整量不能全为 0");
        }
        TurnoverDaily snapshot = dailyMapper.selectOne(new LambdaQueryWrapper<TurnoverDaily>()
                .eq(TurnoverDaily::getBranchCode, branchCode.trim())
                .eq(TurnoverDaily::getBizDate, bizDate));
        if (snapshot == null) {
            throw new BizException("该网点业务日无快照，不能冲正");
        }
        if (!TurnoverDaily.STATUS_CLOSED.equals(snapshot.getStatus())) {
            throw new BizException("业务日未关账，请直接重算快照而非冲正");
        }
        if (nz(snapshot.getTotalVehicles()) + tDelta < 0) {
            throw new BizException("冲正后在役车辆数不能为负");
        }
        TurnoverCorrection correction = new TurnoverCorrection();
        correction.setBranchCode(branchCode.trim());
        correction.setBizDate(bizDate);
        correction.setCompletedDelta(cDelta);
        correction.setTotalVehiclesDelta(tDelta);
        correction.setReason(reason.trim());
        correctionMapper.insert(correction);
        log.warn("周转率冲正已登记 branch={} bizDate={} completedDelta={} totalDelta={} operator={}",
                branchCode.trim(), bizDate, cDelta, tDelta, RequestContext.operator());
        return correction;
    }

    // ---------------------------------------------------------------- 私有

    private Map<String, BranchStat> loadStats(LocalDate bizDate) {
        Map<String, BranchStat> map = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.vehicleCountsByBranch()) {
            String branch = str(getIgnoreCase(row, "branchCode"));
            BranchStat stat = map.computeIfAbsent(branch, b -> {
                BranchStat s = new BranchStat();
                s.branchCode = b;
                return s;
            });
            stat.idle = num(getIgnoreCase(row, "idleCount"));
            stat.renting = num(getIgnoreCase(row, "rentingCount"));
            stat.disabled = num(getIgnoreCase(row, "disabledCount"));
        }
        for (Map<String, Object> row : statsMapper.eventCountsByBranch(bizDate)) {
            String branch = str(getIgnoreCase(row, "branchCode"));
            BranchStat stat = map.computeIfAbsent(branch, b -> {
                BranchStat s = new BranchStat();
                s.branchCode = b;
                return s;
            });
            stat.completed = num(getIgnoreCase(row, "completedCount"));
            stat.created = num(getIgnoreCase(row, "createdCount"));
        }
        // 当日全零、又无车辆的网点（事件 0 且无台账）不会出现；只保留有实际踪迹的网点
        return map;
    }

    private static BigDecimal rate(int numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 统一比率展示口径为四位小数（null 透传，分母 0 无比率）。 */
    private static BigDecimal scale4(BigDecimal value) {
        return value == null ? null : value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static int num(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    /** H2/MyBatis 返回的 Map 列标签大小写形式不固定，按忽略大小写取值。 */
    private static Object getIgnoreCase(Map<String, Object> row, String key) {
        Object direct = row.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String str(Object value) {
        if (value == null) {
            return NO_BRANCH;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? NO_BRANCH : s;
    }

    private static String key(String branch, LocalDate date) {
        return branch + "|" + date;
    }

    private static String currentOperator() {
        try {
            return RequestContext.operator();
        } catch (IllegalStateException ex) {
            return SYS_OPERATOR;
        }
    }
}
