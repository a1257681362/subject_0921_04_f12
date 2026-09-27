package com.evops.photo;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 交车照片后台处理器（photo-1）。
 *
 * 上传接口只登记 PENDING 即返回，真正的处理在本类线程池（photoExecutor）异步执行，
 * 不卡上传。处理链：读原图 -> 按 EXIF 方向摆正 -> Thumbnailator 缩放 280px 预览 ->
 * 叠加 30% 透明度水印（网点标识 + 订单号）-> 落盘 preview_<hash>.jpg -> 回填状态与耗时。
 *
 * 结果留痕：状态（DONE/FAILED）、EXIF 方向值、是否摆正、原图尺寸、处理耗时毫秒、
 * 失败原因全部落 t_handover_photo，处理耗时经接口可见。
 */
@Component
public class PhotoProcessor {

    private static final Logger log = LoggerFactory.getLogger(PhotoProcessor.class);

    private final HandoverPhotoMapper photoMapper;
    private final PhotoProperties properties;

    public PhotoProcessor(HandoverPhotoMapper photoMapper, PhotoProperties properties) {
        this.photoMapper = photoMapper;
        this.properties = properties;
    }

    /** 异步入口：上传登记后触发，在 photoExecutor 线程池执行。 */
    @Async("photoExecutor")
    public void processAsync(Long photoId) {
        process(photoId);
    }

    /**
     * 处理一张照片（同步主体，异步入口与启动自愈、手工重试共用）。
     * 幂等：已完成（DONE）或正在处理（PROCESSING）的直接跳过，重复触发不产生副作用。
     */
    public void process(Long photoId) {
        HandoverPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            log.warn("照片处理跳过：记录不存在 id={}", photoId);
            return;
        }
        if (HandoverPhoto.STATUS_DONE.equals(photo.getStatus())
                || HandoverPhoto.STATUS_PROCESSING.equals(photo.getStatus())) {
            return;
        }

        long start = System.currentTimeMillis();
        photo.setStatus(HandoverPhoto.STATUS_PROCESSING);
        photoMapper.updateById(photo);
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(photo.getOriginalPath()));
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) {
                throw new IllegalStateException("图片无法解码（文件损坏或格式不支持）");
            }

            // 竖拍照片按 EXIF 方向摆正，缩放与水印都在摆正后的图上做
            Integer orientation = ExifOrientations.readOrientation(bytes);
            BufferedImage oriented = ExifOrientations.apply(src, orientation);

            BufferedImage watermark = Watermarks.render(
                    Watermarks.text(photo.getBranchCode(), photo.getOrderNo()), oriented.getWidth());
            Path preview = previewPath(photo);
            Files.createDirectories(preview.getParent());
            // Thumbnailator 的 size() 对小图也会放大到目标尺寸，故仅超出预览尺寸时
            // 缩到 280 内；小图显式按原尺寸出预览（不放大、不糊化），水印照常叠加
            Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(oriented);
            if (oriented.getWidth() > properties.getPreviewSize()
                    || oriented.getHeight() > properties.getPreviewSize()) {
                builder.size(properties.getPreviewSize(), properties.getPreviewSize());
            } else {
                builder.size(oriented.getWidth(), oriented.getHeight());
            }
            builder.watermark(Positions.BOTTOM_RIGHT, watermark,
                            (float) properties.getWatermarkOpacity())
                    .outputFormat("jpg")
                    .toFile(preview.toFile());

            photo.setStatus(HandoverPhoto.STATUS_DONE);
            photo.setPreviewPath(preview.toString());
            photo.setExifOrientation(orientation);
            photo.setRotated(orientation != null && orientation != 1);
            photo.setSrcWidth(src.getWidth());
            photo.setSrcHeight(src.getHeight());
            photo.setErrorMessage(null);
        } catch (Exception ex) {
            photo.setStatus(HandoverPhoto.STATUS_FAILED);
            photo.setErrorMessage(truncate(ex.getMessage() != null
                    ? ex.getMessage() : ex.getClass().getSimpleName()));
            log.error("照片处理失败 id={} 订单={} 原因={}", photo.getId(), photo.getOrderNo(),
                    photo.getErrorMessage());
        } finally {
            // 完成/失败均回填耗时，处理耗时可见
            photo.setProcessCostMs(System.currentTimeMillis() - start);
            photoMapper.updateById(photo);
        }
        log.info("照片处理完成 id={} 订单={} 状态={} EXIF方向={} 摆正={} 耗时={}ms",
                photo.getId(), photo.getOrderNo(), photo.getStatus(),
                photo.getExifOrientation(), photo.getRotated(), photo.getProcessCostMs());
    }

    /** 预览图路径：与原图同目录（按订单分目录），统一 jpg。 */
    private Path previewPath(HandoverPhoto photo) {
        return Paths.get(properties.getDir(), String.valueOf(photo.getOrderId()),
                "preview_" + photo.getFileHash().substring(0, 16) + ".jpg");
    }

    private static String truncate(String message) {
        return message != null && message.length() > 480 ? message.substring(0, 480) : message;
    }
}
