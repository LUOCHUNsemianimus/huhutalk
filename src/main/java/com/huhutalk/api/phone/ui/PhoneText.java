package com.huhutalk.api.phone.ui;

import com.huhutalk.phone.UiFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/**
 * 手机页面里画文字的公开工具。
 *
 * <h2>为什么不直接 {@code g.drawString(...)}</h2>
 * 手机 UI 的字号要跟着玩家的「字体倍率」设置走（{@code UiFont}），
 * 而 {@code GuiGraphics} 只能按 1 倍画。既有页面每处都自己抄了一遍
 * {@code pushPose/scale/popPose} 的换算（那份代码被复制了十几遍），这里收成一个入口。
 *
 * <h2>尺寸换算</h2>
 * {@link #scale(float)} 已经是「相对倍率 × 全局字体倍率」，直接传给本类的绘制方法即可；
 * 需要量文字占多大地方用 {@link #width}/{@link #height}，不要自己拿 {@code font.width} 乘，
 * 否则很容易漏掉全局倍率。
 */
public final class PhoneText {

    private PhoneText() {
    }

    /** 相对倍率 × 全局字体倍率。 */
    public static float scale(float relative) {
        return UiFont.sc(relative);
    }

    /** 把一个固定像素量（行高、留白）按全局字体倍率放大，至少为 1。 */
    public static int px(int pixels) {
        return UiFont.sh(pixels);
    }

    /** 文字按 {@code scale} 绘制后的宽度。 */
    public static int width(Font font, String text, float scale) {
        return (int) (font.width(text) * scale);
    }

    /** 文字按 {@code scale} 绘制后的行高。 */
    public static int height(Font font, float scale) {
        return (int) (font.lineHeight * scale);
    }

    /**
     * {@link FormattedCharSequence} 按 {@code scale} 绘制后的宽度。
     * <p>
     * 需要它是因为 emoji / {@code Component} 走的是 {@code getVisualOrderText()}，
     * 拿到的是视觉序而不是 {@code String}；宽度必须用同一条换算，否则截断会算错。
     */
    public static int width(Font font, FormattedCharSequence text, float scale) {
        return (int) (font.width(text) * scale);
    }

    /** 以任意倍率绘制文字（无阴影）。 */
    public static void draw(GuiGraphics g, Font font, String text, float x, float y,
                            int color, float scale) {
        if (text == null || text.isEmpty() || scale <= 0f) {
            return;
        }
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    /** 以任意倍率绘制文字（带阴影描边）。 */
    public static void drawShaded(GuiGraphics g, Font font, String text, float x, float y,
                                  int color, float scale) {
        if (text == null || text.isEmpty() || scale <= 0f) {
            return;
        }
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, true);
        g.pose().popPose();
    }

    // ==================== FormattedCharSequence 重载 ====================
    //
    // 为什么需要这一组：emoji / Component.literal(..).getVisualOrderText() / 已排版的富文本
    // 拿到的都不是 String，而既有页面（ChatPage 气泡、PhoneOverlay 状态栏与 Dock）各自抄了
    // 一份 FormattedCharSequence 版的缩放绘制。换算与 String 版完全一致（1/scale 反算坐标）。

    /** 以任意倍率绘制 {@link FormattedCharSequence}（无阴影）。 */
    public static void draw(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y,
                            int color, float scale) {
        if (text == null || scale <= 0f) {
            return;
        }
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    /** 以任意倍率绘制 {@link FormattedCharSequence}（带阴影描边）。 */
    public static void drawShaded(GuiGraphics g, Font font, FormattedCharSequence text,
                                  float x, float y, int color, float scale) {
        if (text == null || scale <= 0f) {
            return;
        }
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, true);
        g.pose().popPose();
    }

    /**
     * 以任意倍率绘制 {@link FormattedCharSequence}，并在 {@code (x, y, w, h)} 矩形内水平+垂直居中。
     */
    public static void drawCenteredIn(GuiGraphics g, Font font, FormattedCharSequence text,
                                      int x, int y, int w, int h, int color, float scale) {
        if (text == null) {
            return;
        }
        draw(g, font, text, x + (w - width(font, text, scale)) / 2f,
                y + (h - height(font, scale)) / 2f, color, scale);
    }

    /**
     * 以任意倍率绘制文字，并在 {@code (x, y, w, h)} 矩形内<b>水平居中、垂直居中</b>。
     * 按钮、开关、列表行里最常用。
     */
    public static void drawCenteredIn(GuiGraphics g, Font font, String text,
                                      int x, int y, int w, int h, int color, float scale) {
        if (text == null || text.isEmpty()) {
            return;
        }
        draw(g, font, text, x + (w - width(font, text, scale)) / 2f,
                y + (h - height(font, scale)) / 2f, color, scale);
    }

    /**
     * 以任意倍率绘制文字，在 {@code (x, y, w, h)} 矩形内<b>垂直居中、左对齐</b>（左侧留 {@code padLeft}）。
     * <p>
     * 超出可用宽度会自动截断并补「…」——既有页面里各自手写了一份同样的
     * {@code truncateUi}，这里收成一处。
     */
    public static void drawLeftIn(GuiGraphics g, Font font, String text,
                                  int x, int y, int w, int h, int padLeft, int color, float scale) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int avail = w - padLeft - px(4);
        String shown = avail > 0 ? truncate(font, text, avail, scale) : text;
        draw(g, font, shown, x + padLeft, y + (h - height(font, scale)) / 2f, color, scale);
    }

    /**
     * 按可用像素宽度截断文本，超出部分补「…」。宽度会算上全局字体倍率。
     *
     * @param maxWidth 可用宽度（屏幕像素）
     */
    public static String truncate(Font font, String text, int maxWidth, float scale) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return text;
        }
        if (width(font, text, scale) <= maxWidth) {
            return text;
        }
        int room = maxWidth - width(font, "…", scale);
        if (room <= 0 || scale <= 0f) {
            return "…";
        }
        String cut = font.plainSubstrByWidth(text, Math.max(1, (int) (room / scale)));
        return cut.isEmpty() ? "…" : cut + "…";
    }
}
