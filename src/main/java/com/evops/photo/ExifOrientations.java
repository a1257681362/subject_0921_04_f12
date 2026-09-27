package com.evops.photo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * EXIF 方向（Orientation 标签 0x0112）读取与摆正。
 *
 * 手机竖拍照片像素按传感器方向存储、真实朝向写在 EXIF 里，直接缩放/加水印会得到
 * “躺倒”的图。这里只解析 JPEG APP1(Exif) 段（手机照片即 JPEG；PNG 等无 EXIF 按
 * 不旋转处理），读出方向值后按标准映射把图像摆正，后续缩放/水印都在摆正后的图上做。
 *
 * 方向值映射（EXIF 规范）：
 *   1=正常  2=水平翻转  3=旋转180  4=垂直翻转
 *   5=旋转90°顺时针+水平翻转  6=旋转90°顺时针（竖拍最常见）
 *   7=旋转90°顺时针+垂直翻转  8=旋转270°顺时针
 */
final class ExifOrientations {

    private ExifOrientations() {}

    /** 读取 JPEG 的 EXIF 方向值（1-8）；非 JPEG、无 EXIF、无该标签或数据异常均返回 null。 */
    static Integer readOrientation(byte[] data) {
        if (data == null || data.length < 4
                || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;    // 非 JPEG（SOI 不符）
        }
        int pos = 2;
        while (pos + 4 <= data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return null;    // 段标记损坏
            }
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xDA) {
                return null;    // SOS：图像数据开始，EXIF 只可能在此前
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;       // 无长度段
                continue;
            }
            int len = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > data.length) {
                return null;    // 段长损坏
            }
            if (marker == 0xE1) {
                // APP1：仅认 "Exif\0\0" 头（XMP 等其他 APP1 不含方向标签）
                int payload = pos + 4;
                if (len >= 8 && data[payload] == 'E' && data[payload + 1] == 'x'
                        && data[payload + 2] == 'i' && data[payload + 3] == 'f'
                        && data[payload + 4] == 0 && data[payload + 5] == 0) {
                    return parseTiff(data, payload + 6, pos + 2 + len);
                }
                return null;
            }
            pos += 2 + len;
        }
        return null;
    }

    /** 解析 TIFF 头与 IFD0，找方向标签 0x0112（SHORT）。所有读取带边界检查，异常即无方向。 */
    private static Integer parseTiff(byte[] data, int tiffStart, int end) {
        if (tiffStart < 0 || tiffStart + 8 > end) {
            return null;
        }
        boolean littleEndian;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return null;
        }
        int ifdOffset = readU32(data, tiffStart + 4, littleEndian);
        long ifdPosL = (long) tiffStart + ifdOffset;
        if (ifdOffset < 8 || ifdPosL + 2 > end) {
            return null;
        }
        int ifdPos = (int) ifdPosL;
        int entries = readU16(data, ifdPos, littleEndian);
        long entriesEnd = (long) ifdPos + 2 + (long) entries * 12;
        if (entriesEnd > end) {
            return null;
        }
        for (int i = 0; i < entries; i++) {
            int entry = ifdPos + 2 + i * 12;
            int tag = readU16(data, entry, littleEndian);
            if (tag != 0x0112) {
                continue;
            }
            int type = readU16(data, entry + 2, littleEndian);
            if (type != 3) {    // 方向标签应为 SHORT，异常按无方向处理
                return null;
            }
            // SHORT count=1：值内联在 4 字节值域的前 2 字节（字节序相关）
            int value = readU16(data, entry + 8, littleEndian);
            return value >= 1 && value <= 8 ? value : null;
        }
        return null;
    }

    private static int readU16(byte[] data, int pos, boolean littleEndian) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        return littleEndian ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static int readU32(byte[] data, int pos, boolean littleEndian) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        int b2 = data[pos + 2] & 0xFF;
        int b3 = data[pos + 3] & 0xFF;
        return littleEndian
                ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /** 按方向值摆正；null 或 1 原样返回。 */
    static BufferedImage apply(BufferedImage src, Integer orientation) {
        if (orientation == null) {
            return src;
        }
        switch (orientation) {
            case 2:  return flipHorizontal(src);
            case 3:  return rotate180(src);
            case 4:  return flipVertical(src);
            case 5:  return flipHorizontal(rotate90CW(src));
            case 6:  return rotate90CW(src);
            case 7:  return flipVertical(rotate90CW(src));
            case 8:  return rotate270CW(src);
            default: return src;
        }
    }

    /** 顺时针 90°：输出宽=源高、高=源宽（竖拍转正的关键路径）。 */
    private static BufferedImage rotate90CW(BufferedImage src) {
        AffineTransform tx = centerRotate(src, Math.PI / 2, src.getHeight(), src.getWidth());
        return transform(src, tx, src.getHeight(), src.getWidth());
    }

    private static BufferedImage rotate180(BufferedImage src) {
        AffineTransform tx = centerRotate(src, Math.PI, src.getWidth(), src.getHeight());
        return transform(src, tx, src.getWidth(), src.getHeight());
    }

    /** 顺时针 270°（= 逆时针 90°）。 */
    private static BufferedImage rotate270CW(BufferedImage src) {
        AffineTransform tx = centerRotate(src, -Math.PI / 2, src.getHeight(), src.getWidth());
        return transform(src, tx, src.getHeight(), src.getWidth());
    }

    /** 以目标图中心为轴旋转：先平移到目标中心，旋转后再按源中心平移回去。 */
    private static AffineTransform centerRotate(BufferedImage src, double radians, int destW, int destH) {
        AffineTransform tx = new AffineTransform();
        tx.translate(destW / 2.0, destH / 2.0);
        tx.rotate(radians);
        tx.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
        return tx;
    }

    private static BufferedImage flipHorizontal(BufferedImage src) {
        AffineTransform tx = AffineTransform.getTranslateInstance(src.getWidth(), 0);
        tx.scale(-1, 1);
        return transform(src, tx, src.getWidth(), src.getHeight());
    }

    private static BufferedImage flipVertical(BufferedImage src) {
        AffineTransform tx = AffineTransform.getTranslateInstance(0, src.getHeight());
        tx.scale(1, -1);
        return transform(src, tx, src.getWidth(), src.getHeight());
    }

    private static BufferedImage transform(BufferedImage src, AffineTransform tx, int destW, int destH) {
        int type = src.getType() != BufferedImage.TYPE_CUSTOM ? src.getType() : BufferedImage.TYPE_INT_RGB;
        BufferedImage dest = new BufferedImage(destW, destH, type);
        Graphics2D g = dest.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dest;
    }
}
