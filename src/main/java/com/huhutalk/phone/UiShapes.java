package com.huhutalk.phone;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 手机 UI 共享绘制工具（纯渲染，不影响逻辑）。
 * <p>
 * 圆角矩形采用与 {@code PhoneOverlay#fillRoundedRect} 一致的逐行 {@code g.fill} 扫描实现：
 * 该方式在手机（GUI Layer）渲染环境下已验证可正常显示（手机壳/水印栏/底部导航同源），
 * 避免 Tessellator 直绘在该上下文下的兼容问题。
 */
public final class UiShapes {

    private UiShapes() {
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
            int left = 0, right = 0;
            // 顶部圆角
            if (py < y + r) {
                float dy = py - y;
                float inset = rf - (float) Math.sqrt(Math.max(0f, rf * rf - (rf - dy) * (rf - dy)));
                int cut = (int) Math.ceil(inset);
                left = Math.max(left, cut);
                right = Math.max(right, cut);
            }
            // 底部圆角
            if (py >= y + h - r) {
                float dy = y + h - py;
                float inset = rf - (float) Math.sqrt(Math.max(0f, rf * rf - (rf - dy) * (rf - dy)));
                int cut = (int) Math.ceil(inset);
                left = Math.max(left, cut);
                right = Math.max(right, cut);
            }
            g.fill(x + left, py, x + w - right, py + 1, color);
        }
    }
}
