package com.evops.photo;

import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 交车照片标识接口（photo-1）。
 * 上传只登记即返回（后台异步摆正/缩放/水印，不卡上传）；
 * 对外只提供 280px 水印预览图，原图仅内部留存。
 * 写请求统一携带 X-Operator / X-Request-No（/X-Biz-Time）三件套，过幂等拦截。
 */
@RestController
@RequestMapping("/api")
public class PhotoController {

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    /**
     * 上传交车照片（multipart，字段名 file）。登记 PENDING 即返回，
     * 处理结果（状态/耗时/EXIF 方向）经查询接口轮询可见。
     * 同一订单重复上传同一文件幂等（duplicate=true 返回首次登记结果）。
     */
    @PostMapping("/orders/{orderId}/photos")
    public ApiResponse<HandoverPhoto> upload(@PathVariable Long orderId,
                                             @RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BizException("上传文件读取失败：" + ex.getMessage());
        }
        return ApiResponse.ok(photoService.upload(orderId, file.getOriginalFilename(), content));
    }

    /** 订单照片列表：状态、EXIF 方向、是否摆正、处理耗时、失败原因逐条可见。 */
    @GetMapping("/orders/{orderId}/photos")
    public ApiResponse<List<HandoverPhoto>> listByOrder(@PathVariable Long orderId) {
        return ApiResponse.ok(photoService.listByOrder(orderId));
    }

    /** 照片处理详情（状态/耗时/失败原因）。 */
    @GetMapping("/photos/{id}")
    public ApiResponse<HandoverPhoto> detail(@PathVariable Long id) {
        return ApiResponse.ok(photoService.requirePhoto(id));
    }

    /** 下载对外预览图（280px、已按 EXIF 摆正、30% 水印带网点标识与订单号）。 */
    @GetMapping("/photos/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id) {
        HandoverPhoto photo = photoService.requirePhoto(id);
        byte[] content = photoService.readPreview(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"preview_" + photo.getOrderNo() + ".jpg\"")
                .contentType(MediaType.IMAGE_JPEG)
                .body(content);
    }

    /** 失败重试：FAILED 记录重新投入后台处理（retry_count 累计）。 */
    @PostMapping("/photos/{id}/retry")
    public ApiResponse<HandoverPhoto> retry(@PathVariable Long id) {
        return ApiResponse.ok(photoService.retry(id));
    }
}
