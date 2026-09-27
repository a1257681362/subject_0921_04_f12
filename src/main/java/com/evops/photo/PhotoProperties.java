package com.evops.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 交车照片标识配置（photo-1）。
 * 上传只落原图+登记，EXIF 摆正 / 缩放 / 水印在后台线程池处理，不卡上传。
 */
@Component
@ConfigurationProperties(prefix = "evops.photo")
public class PhotoProperties {

    /** 照片落盘根目录（按订单分目录） */
    private String dir = "data/photos";

    /** 预览图最长边像素（小图不放大） */
    private int previewSize = 280;

    /** 水印不透明度（0-1，0.30 = 30%） */
    private double watermarkOpacity = 0.30;

    /** 后台处理线程池 */
    private Executor executor = new Executor();

    public static class Executor {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 200;

        public int getCoreSize() { return coreSize; }
        public void setCoreSize(int coreSize) { this.coreSize = coreSize; }
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    /** 启动即校验配置合法，避免上线后处理才发现参数错误 */
    @PostConstruct
    public void validate() {
        if (previewSize <= 0) {
            throw new IllegalStateException("evops.photo.preview-size 必须为正数：" + previewSize);
        }
        if (watermarkOpacity < 0 || watermarkOpacity > 1) {
            throw new IllegalStateException("evops.photo.watermark-opacity 必须在 0-1 之间：" + watermarkOpacity);
        }
        if (executor.getCoreSize() <= 0 || executor.getMaxSize() < executor.getCoreSize()
                || executor.getQueueCapacity() < 0) {
            throw new IllegalStateException("evops.photo.executor 线程池参数非法：core="
                    + executor.getCoreSize() + ", max=" + executor.getMaxSize()
                    + ", queue=" + executor.getQueueCapacity());
        }
    }

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public int getPreviewSize() { return previewSize; }
    public void setPreviewSize(int previewSize) { this.previewSize = previewSize; }
    public double getWatermarkOpacity() { return watermarkOpacity; }
    public void setWatermarkOpacity(double watermarkOpacity) { this.watermarkOpacity = watermarkOpacity; }
    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }
}
