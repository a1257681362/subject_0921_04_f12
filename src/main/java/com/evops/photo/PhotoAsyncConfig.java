package com.evops.photo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 交车照片后台处理线程池（photo-1）。
 * 上传接口只登记即返回，摆正/缩放/水印在本池异步执行，不卡上传；
 * 队列满时由调用线程兜底（CallerRunsPolicy），不丢任务。
 */
@Configuration
@EnableAsync
public class PhotoAsyncConfig {

    @Bean("photoExecutor")
    public ThreadPoolTaskExecutor photoExecutor(PhotoProperties properties) {
        PhotoProperties.Executor cfg = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCoreSize());
        executor.setMaxPoolSize(cfg.getMaxSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix("photo-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
