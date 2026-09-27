package com.evops.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 车辆档期配置（schedule-1）。
 *
 * - buffer-minutes：每段占用前后各留的缓冲分钟数，新区间与相邻占用间隔小于该值即冲突；
 * - lock-ttl-minutes：占位（HELD）锁位时限，到期未确认由周期作业自动释放（EXPIRED）；
 * - release-cron：超时占位扫描周期；
 * - enabled：应急总开关，关闭后只停周期扫描（手工触发接口不受影响）。
 */
@Component
@ConfigurationProperties(prefix = "evops.schedule")
public class ScheduleProperties {

    /** 每段占用前后缓冲（分钟），默认 12 */
    private int bufferMinutes = 12;

    /** 占位锁位时限（分钟），默认 25 */
    private int lockTtlMinutes = 25;

    /** 周期扫描开关（应急总开关，不影响手工释放接口） */
    private boolean enabled = true;

    /** 超时占位扫描 cron，默认每分钟 */
    private String releaseCron = "0 * * * * ?";

    @PostConstruct
    public void validate() {
        if (bufferMinutes < 0) {
            throw new IllegalStateException("evops.schedule.buffer-minutes 不能为负：" + bufferMinutes);
        }
        if (lockTtlMinutes <= 0) {
            throw new IllegalStateException("evops.schedule.lock-ttl-minutes 必须为正数：" + lockTtlMinutes);
        }
    }

    public int getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(int bufferMinutes) { this.bufferMinutes = bufferMinutes;
    }
    public int getLockTtlMinutes() { return lockTtlMinutes; }
    public void setLockTtlMinutes(int lockTtlMinutes) { this.lockTtlMinutes = lockTtlMinutes;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getReleaseCron() { return releaseCron; }
    public void setReleaseCron(String releaseCron) { this.releaseCron = releaseCron; }
}
