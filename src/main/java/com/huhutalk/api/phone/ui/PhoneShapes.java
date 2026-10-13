package com.huhutalk.api.phone.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * 手机页面的<b>公开图形基元</b>——圆角矩形及其变体。
 *
 * <h2>为什么它在 api 包里</h2>
 * 本类原先叫 {@code com.huhutalk.phone.UiShapes}，住在非 api 包下。
 * 但整条公开工具链都在用它（{@link PhoneRow}、{@link PhoneDropdown}、{@link PhoneSlider}、
 * {@link PhoneSwitch}、{@link PhoneTextInput}），于是"只用 {code api/phone/ui} 写页面"
 * 这条标准在圆角矩形这一项上天然做不到 —— 必须去 import 一个非公开类。
 * 现在实现搬到这里，公开包自洽。
 * <p>
 * 旧的 {@code UiShapes} 保留为<b>转发壳</b>（已标注 {@code @Deprecated}），
 * 所以既有页面一行都不用改；它们可以之后再慢慢迁过来。
 *
 * <h2>实现说明</h2>
 * 圆角矩形采用逐行 {@code g.fill} 扫描实现（与 {@code PhoneOverlay} 同源）：
 * 该方式在手机（GUI Layer）渲染环境下已验证可正常显示（手机壳/水印栏/底部导航同源），
 * 避免 Tessellator 直绘在该上下文下的兼容问题。
 */
public final class PhoneShapes {

    private PhoneShapes() {
    }

    /** 填充圆角矩形方框（圆角半径 r 均匀，钳制到不超过宽/高的 1/2）。 */
    public static void fillRoundedRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        float rf = r;
        for (int py = y; py < y + h; py++) {
            int[] cut = cornerCut(r, rf, py, y, h);
            g.fill(x + cut[0], py, x + w - cut[1], py + 1, color);
        }
    }

    /**
     * 填充"圆角矩形外框 + 内部矩形镂空"（纯色版）。镂空矩形
     * {@code (holeX,holeY,holeW,holeH)} 不会被填充，其余区域按圆角外框逐行填充。
     * 用于手机壳等"框住一块内容"的边框绘制。
     */
    public static void fillRoundedRectFrame(GuiGraphics g, int x, int y, int w, int h,
                                            int holeX, int holeY, int holeW, int holeH,
                                            int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float rf = r;
        int holeRight = holeX + holeW;
        int holeBottom = holeY + holeH;
        for (int py = y; py < y + h; py++) {
            int[] cut = cornerCut(r, rf, py, y, h);
            int rowStart = x + cut[0];
            int rowEnd = x + w - cut[1];
            if (py < holeY || py >= holeBottom) {
                // 整行（未与镂空重叠）
                g.fill(rowStart, py, rowEnd, py + 1, color);
            } else {
                // 左右两段，跳过镂空
                if (holeX > rowStart) g.fill(rowStart, py, Math.min(holeX, rowEnd), py + 1, color);
                if (holeRight < rowEnd) g.fill(Math.max(holeRight, rowStart), py, rowEnd, py + 1, color);
            }
        }
    }

    /**
     * 填充"圆角矩形外框 + 内部矩形镂空"（纹理版）。与 {@link #fillRoundedRectFrame} 同外形，
     * 但用 {@code sprite} 按 16px 平铺填充外框，供自定义材质（方块壳）使用。
     */
    public static void fillRoundedRectFrameTiled(GuiGraphics g, TextureAtlasSprite sprite,
                                                 int x, int y, int w, int h,
                                                 int holeX, int holeY, int holeW, int holeH,
                                                 int r) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float rf = r;
        int holeRight = holeX + holeW;
        int holeBottom = holeY + holeH;
        for (int py = y; py < y + h; py++) {
            int[] cut = cornerCut(r, rf, py, y, h);
            int rowStart = x + cut[0];
            int rowEnd = x + w - cut[1];
            if (py < holeY || py >= holeBottom) {
                tileRow(g, sprite, rowStart, rowEnd, py);
            } else {
                if (holeX > rowStart) tileRow(g, sprite, rowStart, Math.min(holeX, rowEnd), py);
                if (holeRight < rowEnd) tileRow(g, sprite, Math.max(holeRight, rowStart), rowEnd, py);
            }
        }
    }

    /** 计算某一行在圆角外框下的左/右内收量（切角），返回 {leftCut, rightCut}。 */
    private static int[] cornerCut(int r, float rf, int py, int y, int h) {
        int left = 0, right = 0;
        if (r > 0) {
            if (py < y + r) {
                float dy = py - y;
                float inset = rf - (float) Math.sqrt(Math.max(0f, rf * rf - (rf - dy) * (rf - dy)));
                int cut = (int) Math.ceil(inset);
                left = Math.max(left, cut);
                right = Math.max(right, cut);
            }
            if (py >= y + h - r) {
                float dy = y + h - py;
                float inset = rf - (float) Math.sqrt(Math.max(0f, rf * rf - (rf - dy) * (rf - dy)));
                int cut = (int) Math.ceil(inset);
                left = Math.max(left, cut);
                right = Math.max(right, cut);
            }
        }
        return new int[]{left, right};
    }

    /** 在指定行从左到右按 16px 平铺贴图（1 像素高的横向条）。 */
    private static void tileRow(GuiGraphics g, TextureAtlasSprite sprite, int x1, int x2, int y) {
        if (x2 <= x1) return;
        int px = x1;
        while (px < x2) {
            int w = Math.min(16, x2 - px);
            g.blit(px, y, 0, w, 1, sprite);
            px += w;
        }
    }
}
