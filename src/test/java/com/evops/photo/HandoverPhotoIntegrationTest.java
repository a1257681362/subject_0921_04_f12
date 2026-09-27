package com.evops.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交车照片标识（photo-1）集成测试：内存 H2，照片目录指向临时目录。
 *
 * 覆盖：
 * - 上传即返回 PENDING（处理在后台，不卡上传），随后异步置 DONE，耗时落库可见；
 * - 竖拍照片按 EXIF 方向摆正（方向 6 / 8），无 EXIF 不旋转；
 * - 小图不放大（PNG 无 EXIF 路径）；
 * - 水印：深色底图上右下角出现白色文字痕迹（30% 透明度）；
 * - 边界：40 位超长订单号 + 无网点、非图片上传拒绝、坏图 FAILED 与重试、
 *   同订单重复上传同一文件幂等、预览图未完成不可下载、订单不存在拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:photo-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.recon.enabled=false",
        "evops.photo.dir=${java.io.tmpdir}/evops-photo-test/photos"
})
class HandoverPhotoIntegrationTest {

    private static final String PHOTO_DIR =
            System.getProperty("java.io.tmpdir") + "/evops-photo-test/photos";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HandoverPhotoMapper photoMapper;
    @Autowired
    private PhotoProcessor photoProcessor;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Operator", "dispatcher-li")
                .header("X-Request-No", reqNo())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode data(MvcResult result) throws Exception {
        // 用字节数组解析：响应无 charset 时 getContentAsString() 会按 ISO-8859-1 解码，中文变乱码
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    private long createVehicle(String plate) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"))
                        .content("{\"plateNo\":\"" + plate + "\",\"model\":\"Model-A\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("id").asLong();
    }

    private long createOrder(String plate, String orderNo, String branchCode) throws Exception {
        long vehicleId = createVehicle(plate);
        StringBuilder body = new StringBuilder("{\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"张三\"");
        if (orderNo != null) {
            body.append(",\"orderNo\":\"").append(orderNo).append("\"");
        }
        if (branchCode != null) {
            body.append(",\"branchCode\":\"").append(branchCode).append("\"");
        }
        body.append("}");
        MvcResult created = mockMvc.perform(write(post("/api/orders")).content(body.toString()))
                .andExpect(status().isOk()).andReturn();
        return data(created).get("id").asLong();
    }

    private MvcResult upload(long orderId, String fileName, String contentType, byte[] content)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, contentType, content);
        return mockMvc.perform(multipart("/api/orders/" + orderId + "/photos").file(file)
                        .header("X-Operator", "dispatcher-li")
                        .header("X-Request-No", reqNo()))
                .andReturn();
    }

    /** 轮询照片状态直到进入目标状态（后台异步处理），超时 15 秒。 */
    private JsonNode awaitStatus(long photoId, String... statuses) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/photos/" + photoId))
                    .andExpect(status().isOk()).andReturn();
            JsonNode photo = data(result);
            for (String s : statuses) {
                if (s.equals(photo.get("status").asText())) {
                    return photo;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("照片处理超时，未进入状态：" + String.join("/", statuses));
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    /** 左半红右半蓝的测试图（验证旋转后色块位置）。 */
    private static BufferedImage halfRedHalfBlue(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, x < width / 2 ? 0xFF0000 : 0x0000FF);
            }
        }
        return img;
    }

    /**
     * 生成带 EXIF 方向标签的 JPEG：ImageIO 出图后在 SOI 之后插入 APP1(Exif) 段，
     * TIFF 为 little-endian，IFD0 单条目 0x0112(SHORT)=orientation。
     */
    private static byte[] jpegWithExifOrientation(BufferedImage img, int orientation)
            throws IOException {
        byte[] jpeg = jpeg(img);
        byte[] tiff = {
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,   // II, 42, IFD0 偏移 8
                0x01, 0x00,                                     // 1 个条目
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, // tag 0x0112, SHORT, count 1
                (byte) orientation, 0x00, 0x00, 0x00,           // 值内联
                0x00, 0x00, 0x00, 0x00                          // 无下一 IFD
        };
        int segLen = 6 + tiff.length + 2;   // 长度含自身 2 字节
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg[0]);
        out.write(jpeg[1]);                                     // SOI
        out.write(0xFF);
        out.write(0xE1);                                        // APP1
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.write(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static BufferedImage readPreview(JsonNode photo) throws IOException {
        String previewPath = photo.get("previewPath").asText();
        assertTrue(Files.exists(Paths.get(previewPath)), "预览图文件应存在");
        return ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(Paths.get(previewPath))));
    }

    // =====================================================================
    // 主流程：上传不卡（PENDING 即返回），后台处理置 DONE，耗时可见
    // =====================================================================

    @Test
    void uploadReturnsPendingThenProcessedWithCostVisible() throws Exception {
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        byte[] content = jpeg(solid(800, 600, 0x000000));   // 纯黑底，便于验证白色水印

        MvcResult uploaded = upload(orderId, "damage.jpg", "image/jpeg", content);
        JsonNode photo = data(uploaded);
        // 上传接口只登记即返回：PENDING、耗时未回填 —— 处理在后台，不卡上传
        assertEquals("PENDING", photo.get("status").asText());
        assertTrue(photo.get("processCostMs").isNull());
        assertEquals("BJ01", photo.get("branchCode").asText());
        long photoId = photo.get("id").asLong();

        JsonNode done = awaitStatus(photoId, "DONE");
        assertEquals(800, done.get("srcWidth").asInt());
        assertEquals(600, done.get("srcHeight").asInt());
        assertTrue(done.get("exifOrientation").isNull(), "无 EXIF 方向应为空");
        assertFalse(done.get("rotated").asBoolean());
        // 处理耗时可见：完成即回填，非负
        assertNotNull(done.get("processCostMs"));
        assertTrue(done.get("processCostMs").asLong() >= 0);

        // 280px 预览图：长边 280，等比
        BufferedImage preview = readPreview(done);
        assertEquals(280, preview.getWidth());
        assertEquals(210, preview.getHeight());

        // 水印：黑底上右下角应出现白色文字痕迹（30% 白 ≈ 灰 76）
        int maxBright = 0;
        for (int y = preview.getHeight() / 2; y < preview.getHeight(); y++) {
            for (int x = preview.getWidth() / 2; x < preview.getWidth(); x++) {
                maxBright = Math.max(maxBright, preview.getRGB(x, y) & 0xFF);
            }
        }
        assertTrue(maxBright > 40, "右下角应存在水印文字痕迹，实际最亮值：" + maxBright);

        // 订单照片列表可见
        MvcResult list = mockMvc.perform(get("/api/orders/" + orderId + "/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        assertEquals(photoId, data(list).get(0).get("id").asLong());
    }

    // =====================================================================
    // 竖拍照片按 EXIF 方向摆正（方向 6：顺时针 90°；方向 8：顺时针 270°）
    // =====================================================================

    @Test
    void portraitPhotoRotatedByExifOrientation() throws Exception {
        // 方向 6：存储 200x100（左红右蓝）-> 摆正后 100x200，上红下蓝
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        byte[] content = jpegWithExifOrientation(halfRedHalfBlue(200, 100), 6);
        JsonNode photo = data(upload(orderId, "portrait6.jpg", "image/jpeg", content));
        JsonNode done = awaitStatus(photo.get("id").asLong(), "DONE");

        assertEquals(6, done.get("exifOrientation").asInt());
        assertTrue(done.get("rotated").asBoolean());
        assertEquals(200, done.get("srcWidth").asInt());
        assertEquals(100, done.get("srcHeight").asInt());

        BufferedImage preview = readPreview(done);
        assertEquals(100, preview.getWidth(), "摆正后应为竖图（宽 100）");
        assertEquals(200, preview.getHeight(), "摆正后应为竖图（高 200）");
        int top = preview.getRGB(50, 25);
        int bottom = preview.getRGB(50, 175);
        assertTrue((top >> 16 & 0xFF) > 150 && (top & 0xFF) < 100,
                "方向 6 摆正后上半应为红色");
        assertTrue((bottom & 0xFF) > 150 && (bottom >> 16 & 0xFF) < 100,
                "方向 6 摆正后下半应为蓝色");

        // 方向 8：存储 200x100（左红右蓝）-> 摆正后 100x200，上蓝下红
        long orderId2 = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        byte[] content2 = jpegWithExifOrientation(halfRedHalfBlue(200, 100), 8);
        JsonNode photo2 = data(upload(orderId2, "portrait8.jpg", "image/jpeg", content2));
        JsonNode done2 = awaitStatus(photo2.get("id").asLong(), "DONE");

        assertEquals(8, done2.get("exifOrientation").asInt());
        BufferedImage preview2 = readPreview(done2);
        assertEquals(100, preview2.getWidth());
        assertEquals(200, preview2.getHeight());
        int top2 = preview2.getRGB(50, 25);
        int bottom2 = preview2.getRGB(50, 175);
        assertTrue((top2 & 0xFF) > 150 && (top2 >> 16 & 0xFF) < 100,
                "方向 8 摆正后上半应为蓝色");
        assertTrue((bottom2 >> 16 & 0xFF) > 150 && (bottom2 & 0xFF) < 100,
                "方向 8 摆正后下半应为红色");
    }

    // =====================================================================
    // 边界：小图不放大（PNG 无 EXIF 路径）
    // =====================================================================

    @Test
    void smallImageNotUpscaled() throws Exception {
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        byte[] content = png(solid(100, 50, 0x336699));
        JsonNode photo = data(upload(orderId, "small.png", "image/png", content));
        JsonNode done = awaitStatus(photo.get("id").asLong(), "DONE");

        assertTrue(done.get("exifOrientation").isNull(), "PNG 无 EXIF，方向应为空");
        BufferedImage preview = readPreview(done);
        assertEquals(100, preview.getWidth(), "小图不应被放大");
        assertEquals(50, preview.getHeight());
    }

    // =====================================================================
    // 边界：40 位超长订单号 + 无网点（去年同期极端值口径：schema 上限）
    // =====================================================================

    @Test
    void longOrderNoAndNullBranchStillWatermarked() throws Exception {
        String longOrderNo = "RO20260926EXTREME0900001234567890ABCDEFG";   // 40 位，顶满 order_no 上限
        assertEquals(40, longOrderNo.length());
        long orderId = createOrder("京P" + reqNo().substring(0, 6), longOrderNo, null);
        JsonNode photo = data(upload(orderId, "long.jpg", "image/jpeg",
                jpeg(solid(400, 300, 0x202020))));
        assertTrue(photo.get("branchCode").isNull(), "未分派网点快照应为空（水印以 NO-BRANCH 占位）");
        assertEquals(longOrderNo, photo.get("orderNo").asText());

        // 超长水印文字缩字号后仍能出图
        JsonNode done = awaitStatus(photo.get("id").asLong(), "DONE");
        BufferedImage preview = readPreview(done);
        assertEquals(280, preview.getWidth());
    }

    // =====================================================================
    // 幂等与拦截
    // =====================================================================

    @Test
    void duplicateUploadReturnsFirstRecord() throws Exception {
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        byte[] content = jpeg(solid(320, 240, 0x808080));
        JsonNode first = data(upload(orderId, "a.jpg", "image/jpeg", content));
        JsonNode second = data(upload(orderId, "a-again.jpg", "image/jpeg", content));

        assertEquals(first.get("id").asLong(), second.get("id").asLong(), "同一文件应幂等返回首次记录");
        assertTrue(second.get("duplicate").asBoolean());
        awaitStatus(first.get("id").asLong(), "DONE");

        mockMvc.perform(get("/api/orders/" + orderId + "/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void nonImageUploadRejected() throws Exception {
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        MvcResult result = upload(orderId, "not-a-photo.jpg", "image/jpeg",
                "这不是图片".getBytes("UTF-8"));
        assertEquals(409, result.getResponse().getStatus());
    }

    @Test
    void uploadRejectsUnknownOrderAndEmptyFile() throws Exception {
        MvcResult notFound = upload(999999L, "x.jpg", "image/jpeg",
                jpeg(solid(10, 10, 0x000000)));
        assertEquals(409, notFound.getResponse().getStatus());

        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        MvcResult empty = upload(orderId, "empty.jpg", "image/jpeg", new byte[0]);
        assertEquals(409, empty.getResponse().getStatus());
    }

    // =====================================================================
    // 失败路径：坏图 FAILED（原因与耗时可见）-> 重试仍 FAILED 且次数累计
    // =====================================================================

    @Test
    void corruptFileFailsAndRetryCounts() throws Exception {
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        // 直接登记一条原图已损坏的记录（模拟落盘后文件损坏/丢失），走处理主体
        Path corrupt = Paths.get(PHOTO_DIR, String.valueOf(orderId), "orig_corrupt.jpg");
        Files.createDirectories(corrupt.getParent());
        Files.write(corrupt, "损坏的图片内容".getBytes("UTF-8"));

        HandoverPhoto photo = new HandoverPhoto();
        photo.setOrderId(orderId);
        photo.setOrderNo("RO-CORRUPT-1");
        photo.setBranchCode("BJ01");
        photo.setFileName("corrupt.jpg");
        photo.setFileHash(reqNo() + reqNo());   // 64 位假哈希，避免与真实上传冲突
        photo.setOriginalPath(corrupt.toString());
        photo.setStatus(HandoverPhoto.STATUS_PENDING);
        photo.setRotated(false);
        photo.setRetryCount(0);
        photoMapper.insert(photo);

        photoProcessor.process(photo.getId());   // 同步调用处理主体
        HandoverPhoto failed = photoMapper.selectById(photo.getId());
        assertEquals(HandoverPhoto.STATUS_FAILED, failed.getStatus());
        assertNotNull(failed.getErrorMessage(), "失败原因应可见");
        assertNotNull(failed.getProcessCostMs(), "失败同样回填耗时");

        // 预览图未生成不可下载
        mockMvc.perform(get("/api/photos/" + photo.getId() + "/preview"))
                .andExpect(status().isConflict());

        // 重试：文件仍损坏 -> 仍 FAILED，重试次数累计
        mockMvc.perform(write(post("/api/photos/" + photo.getId() + "/retry")))
                .andExpect(status().isOk());
        awaitStatus(photo.getId(), "FAILED");
        HandoverPhoto retried = photoMapper.selectById(photo.getId());
        assertEquals(1, retried.getRetryCount().intValue());
        assertEquals(HandoverPhoto.STATUS_FAILED, retried.getStatus());
    }

    // =====================================================================
    // 预览图下载（对外唯一出口）
    // =====================================================================

    @Test
    void previewDownloadableOnlyWhenDone() throws Exception {
        long orderId = createOrder("京P" + reqNo().substring(0, 6), null, "BJ01");
        JsonNode photo = data(upload(orderId, "ok.jpg", "image/jpeg",
                jpeg(solid(640, 480, 0x123456))));
        long photoId = photo.get("id").asLong();
        JsonNode done = awaitStatus(photoId, "DONE");

        MvcResult downloaded = mockMvc.perform(get("/api/photos/" + photoId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        byte[] body = downloaded.getResponse().getContentAsByteArray();
        byte[] onDisk = Files.readAllBytes(Paths.get(done.get("previewPath").asText()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(onDisk, body, "下载内容应与落盘预览图一致");
    }
}
