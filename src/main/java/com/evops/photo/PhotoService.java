package com.evops.photo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * 交车照片标识（photo-1）。
 *
 * 上传：校验订单与图片可解码后，原图落盘 + 登记 PENDING 即返回，摆正/缩放/水印
 * 由 {@link PhotoProcessor} 在后台线程池异步处理，不卡上传。
 * 幂等：同一订单重复上传同一文件（内容哈希相同）直接返回首次登记结果（duplicate=true）。
 * 对外：只提供 280px 水印预览图下载，原图仅内部留存，不提供下载出口。
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final HandoverPhotoMapper photoMapper;
    private final RentalOrderMapper orderMapper;
    private final PhotoProperties properties;
    private final PhotoProcessor photoProcessor;

    public PhotoService(HandoverPhotoMapper photoMapper,
                        RentalOrderMapper orderMapper,
                        PhotoProperties properties,
                        PhotoProcessor photoProcessor) {
        this.photoMapper = photoMapper;
        this.orderMapper = orderMapper;
        this.properties = properties;
        this.photoProcessor = photoProcessor;
    }

    /**
     * 上传交车照片：原图落盘 + 登记 PENDING 后立即返回，后台异步摆正/缩放/水印。
     * 返回的登记记录状态为 PENDING，处理结果经查询接口轮询可见（含耗时）。
     */
    public HandoverPhoto upload(Long orderId, String fileName, byte[] content) {
        RentalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException("租车单不存在：" + orderId);
        }
        if (content == null || content.length == 0) {
            throw new BizException("上传文件为空");
        }
        // 上传即校验可解码，坏图立刻反馈上传人，不进后台队列空转
        try {
            if (ImageIO.read(new ByteArrayInputStream(content)) == null) {
                throw new BizException("不是可识别的图片文件：" + fileName);
            }
        } catch (IOException ex) {
            throw new BizException("图片读取失败：" + ex.getMessage());
        }

        String hash = sha256(content);
        HandoverPhoto existing = photoMapper.selectOne(new LambdaQueryWrapper<HandoverPhoto>()
                .eq(HandoverPhoto::getOrderId, orderId)
                .eq(HandoverPhoto::getFileHash, hash));
        if (existing != null) {
            existing.setDuplicate(true);
            return existing;
        }

        Path original = Paths.get(properties.getDir(), String.valueOf(orderId),
                "orig_" + hash.substring(0, 16) + extensionOf(fileName));
        try {
            Files.createDirectories(original.getParent());
            Files.write(original, content);
        } catch (IOException ex) {
            throw new BizException("照片写入失败：" + ex.getMessage());
        }

        HandoverPhoto photo = new HandoverPhoto();
        photo.setOrderId(orderId);
        // 水印内容快照：网点标识 + 订单号，上传时定型，不随单回改
        photo.setOrderNo(order.getOrderNo());
        photo.setBranchCode(order.getBranchCode());
        photo.setFileName(fileName != null && !fileName.trim().isEmpty() ? fileName.trim() : "unknown");
        photo.setFileHash(hash);
        photo.setOriginalPath(original.toString());
        photo.setStatus(HandoverPhoto.STATUS_PENDING);
        photo.setRotated(false);
        photo.setRetryCount(0);
        try {
            photoMapper.insert(photo);
        } catch (DuplicateKeyException ex) {
            // 并发上传同一文件：库层唯一约束兜底，返回先登记者
            try {
                Files.deleteIfExists(original);
            } catch (IOException ignored) {
                // 尽力清理，清理失败由运维按目录巡检
            }
            HandoverPhoto winner = photoMapper.selectOne(new LambdaQueryWrapper<HandoverPhoto>()
                    .eq(HandoverPhoto::getOrderId, orderId)
                    .eq(HandoverPhoto::getFileHash, hash));
            if (winner == null) {
                throw ex;
            }
            winner.setDuplicate(true);
            return winner;
        } catch (RuntimeException ex) {
            // 落库失败：清理已写文件，不留无记录孤儿文件
            try {
                Files.deleteIfExists(original);
            } catch (IOException ignored) {
                // 尽力清理
            }
            throw ex;
        }

        // 登记完成即触发后台处理（异步线程池），上传请求到此返回，不卡上传
        photoProcessor.processAsync(photo.getId());
        return photo;
    }

    /** 订单照片列表（含状态与处理耗时，按登记顺序）。 */
    public List<HandoverPhoto> listByOrder(Long orderId) {
        return photoMapper.selectList(new LambdaQueryWrapper<HandoverPhoto>()
                .eq(HandoverPhoto::getOrderId, orderId)
                .orderByAsc(HandoverPhoto::getId));
    }

    public HandoverPhoto requirePhoto(Long photoId) {
        HandoverPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BizException("照片记录不存在：" + photoId);
        }
        return photo;
    }

    /** 读取对外预览图（280px、已摆正、已加水印）；未处理完成不可下载。 */
    public byte[] readPreview(Long photoId) {
        HandoverPhoto photo = requirePhoto(photoId);
        if (!HandoverPhoto.STATUS_DONE.equals(photo.getStatus()) || photo.getPreviewPath() == null) {
            throw new BizException("预览图尚未生成，当前状态：" + photo.getStatus());
        }
        try {
            return Files.readAllBytes(Paths.get(photo.getPreviewPath()));
        } catch (IOException ex) {
            throw new BizException("预览图读取失败（可能已被清理）：" + photo.getPreviewPath());
        }
    }

    /** 失败重试：FAILED 记录重置为 PENDING 并重新投入后台处理。 */
    public HandoverPhoto retry(Long photoId) {
        HandoverPhoto photo = requirePhoto(photoId);
        if (!HandoverPhoto.STATUS_FAILED.equals(photo.getStatus())) {
            throw new BizException("仅失败（FAILED）的照片可重试，当前状态：" + photo.getStatus());
        }
        photo.setStatus(HandoverPhoto.STATUS_PENDING);
        photo.setErrorMessage(null);
        photo.setRetryCount(photo.getRetryCount() + 1);
        photoMapper.updateById(photo);
        photoProcessor.processAsync(photo.getId());
        return photo;
    }

    /**
     * 启动自愈：上次停机遗留的 PENDING/PROCESSING 记录重新投入后台处理。
     * 返回重新投入的数量。
     */
    public int requeueStuck() {
        List<HandoverPhoto> stuck = photoMapper.selectList(new LambdaQueryWrapper<HandoverPhoto>()
                .in(HandoverPhoto::getStatus, Arrays.asList(
                        HandoverPhoto.STATUS_PENDING, HandoverPhoto.STATUS_PROCESSING)));
        for (HandoverPhoto photo : stuck) {
            photo.setStatus(HandoverPhoto.STATUS_PENDING);
            photoMapper.updateById(photo);
            photoProcessor.processAsync(photo.getId());
        }
        return stuck.size();
    }

    private static String extensionOf(String fileName) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot).toLowerCase();
                if (ext.length() <= 8 && ext.matches("\\.[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        return ".img";
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
