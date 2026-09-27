package com.evops.photo;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 文字水印渲染：把“网点标识 + 订单号”绘制成透明底水印图，
 * 交给 Thumbnailator 以 30% 不透明度叠加（watermark 的 opacity 参数）。
 *
 * 口径：
 * - 内容取网点编码（branch_code）与订单号，均为 ASCII，任何服务器字体都能正常显示；
 *   网点未分派时以 NO-BRANCH 占位（不用中文“未分派”，避免服务器缺中文字体出方块）；
 * - 字号随图宽自适应（图宽的 1/18，限定 10-48px），超长订单号逐号缩字号到不超出图宽 90%。
 */
final class Watermarks {

    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 48;
    private static final int PADDING = 12;

    private Watermarks() {}

    /** 水印文字：网点标识 + 空格 + 订单号。 */
    static String text(String branchCode, String orderNo) {
        String branch = branchCode == null || branchCode.trim().isEmpty()
                ? "NO-BRANCH" : branchCode.trim();
        return branch + " " + orderNo;
    }

    /** 渲染透明底文字水印图，宽度按目标图宽自适应。 */
    static BufferedImage render(String text, int imageWidth) {
        int fontSize = Math.max(MIN_FONT_SIZE + 2, Math.min(MAX_FONT_SIZE, imageWidth / 18));
        Font font = new Font(Font.DIALOG, Font.BOLD, fontSize);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm;
        try {
            fm = metrics(pg, font);
            // 超长内容（如 40 位订单号）逐号缩字号，直到不超出图宽 90% 或到最小字号
            while (fm.stringWidth(text) > imageWidth * 0.9 && font.getSize() > MIN_FONT_SIZE) {
                font = font.deriveFont((float) font.getSize() - 1);
                fm = metrics(pg, font);
            }
        } finally {
            pg.dispose();
        }

        int width = Math.max(1, fm.stringWidth(text) + PADDING * 2);
        int height = Math.max(1, fm.getHeight() + PADDING);
        BufferedImage mark = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mark.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.WHITE);
            g.drawString(text, PADDING, PADDING / 2 + fm.getAscent());
        } finally {
            g.dispose();
        }
        return mark;
    }

    private static FontMetrics metrics(Graphics2D g, Font font) {
        g.setFont(font);
        return g.getFontMetrics();
    }
}
