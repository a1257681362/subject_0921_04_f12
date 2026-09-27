package com.evops.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.schedule.dto.ScheduleHoldRequest;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 车辆档期（schedule-1）。
 *
 * 占用按半开区间 {@code [startTime, endTime)} 比较，并在每段占用前后各留缓冲
 * （默认 12 分钟）：新区间落入任一既有占用的 {@code [start-buffer, end+buffer)} 即冲突。
 * 与相邻占用间隔恰为缓冲不冲突，小于缓冲拒绝。
 *
 * 占位先落 HELD，锁位截止 = 占位时刻 + lock-ttl-minutes（默认 25 分钟），到期未确认
 * 由周期作业（或读取时懒释放）置 EXPIRED；取消置 CANCELLED。CANCELLED/EXPIRED 不占档期。
 *
 * 并发：同一辆车的占位在单个事务内先对 t_vehicle 行加 FOR UPDATE 行锁再判冲突/写入，
 * 并发放置只有一个事务能进入判定，败者随后看到前者的占用而冲突，不依赖应用层加锁。
 *
 * 本期不含审批流，档期只负责时间占用本身。
 */
@Service
public class ScheduleService {

    /** 最近空档向前搜索的最远范围（天），超出视为暂无空档 */
    private static final int SUGGEST_HORIZON_DAYS = 14;

    private final VehicleScheduleMapper scheduleMapper;
    private final VehicleMapper vehicleMapper;
    private final ScheduleProperties properties;
    private final TransactionTemplate txTemplate;
    /** 独立事务：确认时发现已超时，先把 EXPIRED 落库提交，再拒绝确认（拒绝的回滚不带走释放）。 */
    private final TransactionTemplate txRequiresNew;

    public ScheduleService(VehicleScheduleMapper scheduleMapper,
                           VehicleMapper vehicleMapper,
                           ScheduleProperties properties,
                           org.springframework.transaction.PlatformTransactionManager txManager) {
        this.scheduleMapper = scheduleMapper;
        this.vehicleMapper = vehicleMapper;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txRequiresNew = new TransactionTemplate(txManager);
        this.txRequiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 占位：锁车 -> 懒释放本车超时 HELD -> 区间重叠（含缓冲）判定 -> 落 HELD。
     * 冲突抛 {@link ScheduleConflictException}（携带最近空档），整事务回滚。
     */
    public VehicleSchedule hold(ScheduleHoldRequest request) {
        LocalDateTime start = request.getStartTime();
        LocalDateTime end = request.getEndTime();
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException("占用结束时间必须晚于开始时间");
        }
        return txTemplate.execute(status -> {
            lockVehicle(request.getVehicleId());
            LocalDateTime now = LocalDateTime.now();
            expireOverdue(request.getVehicleId(), now);

            List<VehicleSchedule> blocks = activeBlocks(request.getVehicleId());
            VehicleSchedule clash = firstClash(blocks, start, end);
            if (clash != null) {
                LocalDateTime[] slot = nearestFreeSlot(blocks, start, Duration.between(start, end));
                String msg = "车辆该时段已被占用（与预约 " + clash.getStartTime() + "~" + clash.getEndTime()
                        + " 间隔不足 " + properties.getBufferMinutes() + " 分钟缓冲），请改约";
                throw new ScheduleConflictException(msg,
                        slot == null ? null : slot[0], slot == null ? null : slot[1]);
            }

            VehicleSchedule schedule = new VehicleSchedule();
            schedule.setVehicleId(request.getVehicleId());
            schedule.setStartTime(start);
            schedule.setEndTime(end);
            schedule.setCustomerName(blankToNull(request.getCustomerName()));
            schedule.setOrderId(request.getOrderId());
            schedule.setStatus(VehicleSchedule.HELD);
            schedule.setHeldDeadline(now.plusMinutes(properties.getLockTtlMinutes()));
            scheduleMapper.insert(schedule);
            return schedule;
        });
    }

    /** 确认占位：仅 HELD 可确认；已超时未确认的占位拒绝（已被/将被自动释放）。 */
    public VehicleSchedule confirm(Long id) {
        return txTemplate.execute(status -> {
            VehicleSchedule schedule = requireSchedule(id);
            if (VehicleSchedule.CANCELLED.equals(schedule.getStatus())
                    || VehicleSchedule.EXPIRED.equals(schedule.getStatus())) {
                throw new BizException("占位已释放（" + schedule.getStatus() + "），不能确认，请重新占位");
            }
            if (VehicleSchedule.CONFIRMED.equals(schedule.getStatus())) {
                return schedule; // 确认幂等
            }
            LocalDateTime now = LocalDateTime.now();
            if (schedule.getHeldDeadline() != null && !now.isBefore(schedule.getHeldDeadline())) {
                // 到点未确认：在独立事务先落 EXPIRED（提交后不被本事务回滚带走），再拒绝
                expireInNewTx(id);
                throw new BizException("占位已超过锁位时限（" + properties.getLockTtlMinutes()
                        + " 分钟）未确认，已自动释放，请重新占位");
            }
            schedule.setStatus(VehicleSchedule.CONFIRMED);
            schedule.setConfirmedTime(now);
            if (scheduleMapper.updateById(schedule) == 0) {
                throw new BizException("档期状态已被其他操作变更，确认未生效（并发冲突），请刷新后重试");
            }
            return scheduleMapper.selectById(id);
        });
    }

    /** 独立事务把指定 HELD 置 EXPIRED（条件更新，已被确认/取消则不覆盖）。 */
    private void expireInNewTx(Long id) {
        txRequiresNew.execute(status -> {
            VehicleSchedule fresh = scheduleMapper.selectById(id);
            if (fresh != null && VehicleSchedule.HELD.equals(fresh.getStatus())) {
                release(fresh, VehicleSchedule.EXPIRED, LocalDateTime.now());
            }
            return null;
        });
    }

    /** 取消占位/确认：释放档期（HELD/CONFIRMED -> CANCELLED），全量留痕不物理删除。 */
    public VehicleSchedule cancel(Long id, String reason) {
        return txTemplate.execute(status -> {
            VehicleSchedule schedule = requireSchedule(id);
            if (VehicleSchedule.CANCELLED.equals(schedule.getStatus())
                    || VehicleSchedule.EXPIRED.equals(schedule.getStatus())) {
                return schedule; // 取消幂等：已释放直接返回当前状态
            }
            String trimmed = blankToNull(reason);
            schedule.setStatus(VehicleSchedule.CANCELLED);
            schedule.setCancelReason(trimmed);
            schedule.setReleasedTime(LocalDateTime.now());
            if (scheduleMapper.updateById(schedule) == 0) {
                throw new BizException("档期状态已被其他操作变更，取消未生效（并发冲突），请刷新后重试");
            }
            return scheduleMapper.selectById(id);
        });
    }

    /**
     * 周期作业入口：释放全部到期未确认的 HELD。逐条独立事务 + 乐观锁，
     * 与现场确认/取消并发时只有一方生效，单条失败不影响其余。返回释放条数。
     */
    public int releaseOverdueHolds() {
        LocalDateTime now = LocalDateTime.now();
        List<VehicleSchedule> overdue = scheduleMapper.selectList(
                new LambdaQueryWrapper<VehicleSchedule>()
                        .eq(VehicleSchedule::getStatus, VehicleSchedule.HELD)
                        .le(VehicleSchedule::getHeldDeadline, now)
                        .orderByAsc(VehicleSchedule::getId));
        int released = 0;
        for (VehicleSchedule schedule : overdue) {
            Boolean ok = txTemplate.execute(status -> {
                VehicleSchedule fresh = scheduleMapper.selectById(schedule.getId());
                if (fresh == null || !VehicleSchedule.HELD.equals(fresh.getStatus())) {
                    return false; // 已被确认/取消，跳过
                }
                if (fresh.getHeldDeadline() == null || fresh.getHeldDeadline().isAfter(LocalDateTime.now())) {
                    return false; // 临界点：尚未超时
                }
                release(fresh, VehicleSchedule.EXPIRED, LocalDateTime.now());
                return true;
            });
            if (Boolean.TRUE.equals(ok)) {
                released++;
            }
        }
        return released;
    }

    /** 档期可用性校验：先懒释放本车超时占位，再判冲突，并给出最近空档建议。 */
    public Availability availability(Long vehicleId, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException("占用结束时间必须晚于开始时间");
        }
        return txTemplate.execute(status -> {
            lockVehicle(vehicleId);
            LocalDateTime now = LocalDateTime.now();
            expireOverdue(vehicleId, now);
            List<VehicleSchedule> blocks = activeBlocks(vehicleId);
            boolean available = firstClash(blocks, start, end) == null;
            LocalDateTime[] slot = available ? null
                    : nearestFreeSlot(blocks, start, Duration.between(start, end));
            return new Availability(vehicleId, start, end, available,
                    slot == null ? null : slot[0], slot == null ? null : slot[1]);
        });
    }

    /**
     * 推荐最近空档：保持原时长不变，从请求开始时刻向后找最早能放下的不冲突区间，
     * 最远搜索 {@link #SUGGEST_HORIZON_DAYS} 天；找不到返回 null。
     */
    public LocalDateTime[] nearestFreeSlot(Long vehicleId, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException("占用结束时间必须晚于开始时间");
        }
        return txTemplate.execute(status -> {
            lockVehicle(vehicleId);
            expireOverdue(vehicleId, LocalDateTime.now());
            return nearestFreeSlot(activeBlocks(vehicleId), start, Duration.between(start, end));
        });
    }

    /** 查询某车辆在时间窗内的档期（默认仅在占状态 HELD/CONFIRMED；includeAll 含已取消/超时留痕）。 */
    public List<VehicleSchedule> list(Long vehicleId, LocalDateTime from, LocalDateTime to, boolean includeAll) {
        LambdaQueryWrapper<VehicleSchedule> wrapper = new LambdaQueryWrapper<VehicleSchedule>()
                .eq(vehicleId != null, VehicleSchedule::getVehicleId, vehicleId)
                .ge(from != null, VehicleSchedule::getStartTime, from)
                .le(to != null, VehicleSchedule::getEndTime, to)
                .orderByAsc(VehicleSchedule::getStartTime)
                .orderByAsc(VehicleSchedule::getId);
        if (!includeAll) {
            wrapper.in(VehicleSchedule::getStatus,
                    Arrays.asList(VehicleSchedule.HELD, VehicleSchedule.CONFIRMED));
        }
        return scheduleMapper.selectList(wrapper);
    }

    public VehicleSchedule getById(Long id) {
        return requireSchedule(id);
    }

    // ---- 内部辅助（调用方须在事务内） ----

    private Vehicle lockVehicle(Long vehicleId) {
        Vehicle vehicle = vehicleMapper.selectForUpdate(vehicleId);
        if (vehicle == null) {
            throw new BizException("车辆不存在：" + vehicleId);
        }
        if ("DISABLED".equals(vehicle.getStatus())) {
            throw new BizException("车辆已停用，不能预约档期：" + vehicle.getPlateNo());
        }
        return vehicle;
    }

    /** 懒释放：本车已到锁位截止仍为 HELD 的占用置 EXPIRED。 */
    private void expireOverdue(Long vehicleId, LocalDateTime now) {
        List<VehicleSchedule> held = scheduleMapper.selectList(
                new LambdaQueryWrapper<VehicleSchedule>()
                        .eq(VehicleSchedule::getVehicleId, vehicleId)
                        .eq(VehicleSchedule::getStatus, VehicleSchedule.HELD)
                        .le(VehicleSchedule::getHeldDeadline, now));
        for (VehicleSchedule schedule : held) {
            release(schedule, VehicleSchedule.EXPIRED, now);
        }
    }

    /** 本车当前在占区间（HELD/CONFIRMED），按开始时间升序。 */
    private List<VehicleSchedule> activeBlocks(Long vehicleId) {
        return scheduleMapper.selectList(
                new LambdaQueryWrapper<VehicleSchedule>()
                        .eq(VehicleSchedule::getVehicleId, vehicleId)
                        .in(VehicleSchedule::getStatus,
                                Arrays.asList(VehicleSchedule.HELD, VehicleSchedule.CONFIRMED))
                        .orderByAsc(VehicleSchedule::getStartTime)
                        .orderByAsc(VehicleSchedule::getId));
    }

    /**
     * 区间重叠判定（含前后缓冲）。把每段既有占用扩成禁占区间
     * {@code [start-buffer, end+buffer)}（半开），新区间 {@code [s,e)} 与之相交即冲突：
     * {@code s < end+buffer && e > start-buffer}。
     * 间隔恰为缓冲（e == start-buffer）不相交，允许；间隔小于缓冲拒绝。
     */
    private VehicleSchedule firstClash(List<VehicleSchedule> blocks, LocalDateTime start, LocalDateTime end) {
        long buffer = properties.getBufferMinutes();
        for (VehicleSchedule b : blocks) {
            LocalDateTime forbidStart = b.getStartTime().minusMinutes(buffer);
            LocalDateTime forbidEnd = b.getEndTime().plusMinutes(buffer);
            boolean overlap = start.isBefore(forbidEnd) && end.isAfter(forbidStart);
            if (overlap) {
                return b;
            }
        }
        return null;
    }

    /**
     * 在合并后的禁占区间序列上，求最早可容纳时长 duration 的空档起点。
     * 候选起点 t 从请求开始时刻起，遇禁占区间 [lo,hi) 覆盖则整体右移到 hi 再继续，
     * 直至 [t,t+duration) 不再与任何禁占区间相交。
     */
    private LocalDateTime[] nearestFreeSlot(List<VehicleSchedule> blocks, LocalDateTime start, Duration duration) {
        long buffer = properties.getBufferMinutes();
        // 禁占区间 = 每段占用前后扩 buffer，用 epoch 毫秒做合并/比较（档期精度到分钟，足够）。
        List<long[]> forbids = new ArrayList<>();
        for (VehicleSchedule b : blocks) {
            long lo = java.sql.Timestamp.valueOf(b.getStartTime().minusMinutes(buffer)).getTime();
            long hi = java.sql.Timestamp.valueOf(b.getEndTime().plusMinutes(buffer)).getTime();
            forbids.add(new long[]{lo, hi});
        }
        forbids.sort((a, c) -> Long.compare(a[0], c[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] r : forbids) {
            if (merged.isEmpty() || r[0] > merged.get(merged.size() - 1)[1]) {
                merged.add(new long[]{r[0], r[1]});
            } else {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            }
        }
        long t = java.sql.Timestamp.valueOf(start).getTime();
        long d = duration.toMillis();
        long horizon = java.sql.Timestamp.valueOf(start.plusDays(SUGGEST_HORIZON_DAYS)).getTime();
        for (long[] r : merged) {
            if (r[1] <= t) {
                continue; // 禁占区间已在候选起点左侧
            }
            if (r[0] >= t + d) {
                break;    // [t,t+d) 整体在该禁占区间之前，已放下
            }
            t = r[1];     // 被挡住，候选整体移到禁占区间右端
            if (t + d > horizon) {
                return null;
            }
        }
        if (t + d > horizon) {
            return null;
        }
        LocalDateTime suggestedStart = new java.sql.Timestamp(t).toLocalDateTime();
        return new LocalDateTime[]{suggestedStart, suggestedStart.plus(duration)};
    }

    private void release(VehicleSchedule schedule, String status, LocalDateTime at) {
        // 用条件更新兜底：仅当仍是 HELD 时才释放，避免与并发确认/取消互相覆盖。
        LambdaUpdateWrapper<VehicleSchedule> wrapper = new LambdaUpdateWrapper<VehicleSchedule>()
                .eq(VehicleSchedule::getId, schedule.getId())
                .eq(VehicleSchedule::getStatus, VehicleSchedule.HELD)
                .set(VehicleSchedule::getStatus, status)
                .set(VehicleSchedule::getReleasedTime, at);
        // wrapper 更新不经实体自动填充，显式带上当前操作人/请求号（系统扫描为 SYS_SCHEDULE）。
        RequestContext.Ctx ctx = currentContext();
        if (ctx != null) {
            wrapper.set(VehicleSchedule::getOperator, ctx.getOperator())
                    .set(VehicleSchedule::getRequestNo, ctx.getRequestNo());
        }
        scheduleMapper.update(null, wrapper);
        schedule.setStatus(status);
        schedule.setReleasedTime(at);
    }

    private static RequestContext.Ctx currentContext() {
        try {
            return RequestContext.get();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private VehicleSchedule requireSchedule(Long id) {
        VehicleSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new BizException("档期记录不存在：" + id);
        }
        return schedule;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 档期可用性视图（冲突时携带推荐最近空档）。 */
    public static class Availability {
        private final Long vehicleId;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final boolean available;
        private final LocalDateTime suggestedStart;
        private final LocalDateTime suggestedEnd;

        public Availability(Long vehicleId, LocalDateTime startTime, LocalDateTime endTime,
                            boolean available, LocalDateTime suggestedStart, LocalDateTime suggestedEnd) {
            this.vehicleId = vehicleId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.available = available;
            this.suggestedStart = suggestedStart;
            this.suggestedEnd = suggestedEnd;
        }

        public Long getVehicleId() { return vehicleId; }
        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public boolean isAvailable() { return available; }
        public LocalDateTime getSuggestedStart() { return suggestedStart; }
        public LocalDateTime getSuggestedEnd() { return suggestedEnd; }
    }
}
