package com.evops.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动自愈：上次停机遗留的 PENDING/PROCESSING 照片重新投入后台处理。
 * 自愈失败不阻断启动，仅记录错误日志。
 */
@Component
@Order(1)
public class PhotoRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PhotoRecoveryRunner.class);

    private final PhotoService photoService;

    public PhotoRecoveryRunner(PhotoService photoService) {
        this.photoService = photoService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = photoService.requeueStuck();
            if (count > 0) {
                log.info("启动自愈：重新投入 {} 张待处理交车照片", count);
            }
        } catch (Exception e) {
            log.error("启动自愈：待处理照片重投失败，请人工排查后走重试接口", e);
        }
    }
}
