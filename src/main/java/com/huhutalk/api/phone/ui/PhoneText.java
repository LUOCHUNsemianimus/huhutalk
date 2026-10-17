package com.huhutalk.api.phone.ui;

import com.huhutalk.common.SystemText;
import com.huhutalk.phone.UiFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
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

    /**
     * <b>手机 UI 唯一的翻译入口</b>：把内置文案键按玩家当前语言解析成显示字符串。
     *
     * <h2>为什么只留这一个入口</h2>
     * 页面里任何"自己判断 {@code Minecraft.getInstance().getLanguageManager()} 再挑中/英"的写法
     * 都会随页面数量膨胀，而且很容易把解析结果缓存进 {@code static} 字段，切语言后不再刷新。
     * 这里每次都委托 {@link I18n#get}，所以在渲染/测量时调用就能拿到当前语言，切语言下一帧即生效。
     *
     * @param key  语言文件里的键（本模组新增键统一以 {@code gui.huhutalk.} 开头）
     * @param args 占位符参数，与键里的 {@code %s} 一一对应
     */
    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    /**
     * 同 {@link #tr}，但键在当前语言里不存在时返回 {@code fallback}。
     * <p>
     * 用于「内置数据 + 玩家自建数据」混在一起的场景：内置条目的名称在语言文件里有键，
     * 而玩家自建的站点等没有键 —— 后者的原始字符串必须原样显示，不能被 {@link I18n}
     * 回退成键名本身。判据是「解析结果是否等于键」，不需要额外的语言表查询 API。
     */
    public static String trOr(String key, String fallback, Object... args) {
        String translated = I18n.get(key, args);
        return key.equals(translated) ? fallback : translated;
    }

    /**
     * 模组自有系统文案的运行时显示入口：把 {@link SystemText} 编码的可翻译表示解析成当前语言。
     * <p>
     * 状态缓存、错误提示、物品效果等系统字段以 String 存储/传输，服务端只保存稳定语言键；
     * 这里在显示边界委托 {@link SystemText#render} 并复用既有 {@link #tr} —— 不新增另一套语言选择，
     * 切语言下一帧即生效。普通字符串（历史缓存、玩家自建内容、AI 回复）不认识标记，原样返回。
     */
    public static String system(String encoded) {
        return SystemText.render(encoded, PhoneText::tr);
    }

    // ==================== 内置识图平台的显示文案 ====================
    //
    // VisionProvider / VisionProviders 是客户端与服务端共用的纯数据（站点种子、模型预设），
    // 其 displayName / tagline / note 是**中文原始数据**，不能改，也不该在服务端被翻译。
    // 所以译文只在客户端的显示边界按「稳定 siteId + 字段」解析；查不到键（玩家自建站点、
    // 或未翻译的语言）就原样回退 —— 与 SettingsAppPage / VisionModelSettingsPage 共用同一入口。

    /** 内置识图平台显示名；未知站点原样返回 {@code fallback}。 */
    public static String visionProviderName(String siteId, String fallback) {
        return trOr(visionKey(siteId, "name"), fallback);
    }

    /** 内置识图平台一句话说明；未知站点原样返回 {@code fallback}。 */
    public static String visionProviderTagline(String siteId, String fallback) {
        return trOr(visionKey(siteId, "tagline"), fallback);
    }

    /** 内置识图平台注意事项；未知站点原样返回 {@code fallback}。 */
    public static String visionProviderNote(String siteId, String fallback) {
        return trOr(visionKey(siteId, "note"), fallback);
    }

    private static String visionKey(String siteId, String field) {
        return "gui.huhutalk.vision." + siteId + "." + field;
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
        if (avail <= 0) {
            // 没有可用宽度就什么都不画：画原文会直接溢出这个矩形（英文新文案更长，必现）
            return;
        }
        draw(g, font, truncate(font, text, avail, scale),
                x + padLeft, y + (h - height(font, scale)) / 2f, color, scale);
    }

    /**
     * 按可用像素宽度截断文本，超出部分补「…」。宽度会算上全局字体倍率。
     * <p>
     * {@code maxWidth <= 0} 表示<b>没有可用宽度</b>，返回空串而不是原文 ——
     * 否则极窄的英文两列布局会重新溢出（截断与绘制都当空串处理）。{@code null}/空串仍原样返回。
     *
     * @param maxWidth 可用宽度（屏幕像素）
     */
    public static String truncate(Font font, String text, int maxWidth, float scale) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (width(font, text, scale) <= maxWidth) {
            return text;
        }
        // 容不下省略号本身时返回空串：旧实现直接返回「…」，maxWidth 仅 1px 时
        // 省略号（约 6px）反而越界。先量省略号宽度再决定，保证任何正 maxWidth 下都不超宽。
        int ellipsisWidth = width(font, "…", scale);
        if (maxWidth < ellipsisWidth) {
            return "";
        }
        int room = maxWidth - ellipsisWidth;
        if (room <= 0 || scale <= 0f) {
            return "…";
        }
        String cut = font.plainSubstrByWidth(text, Math.max(1, (int) (room / scale)));
        return cut.isEmpty() ? "…" : cut + "…";
    }
}
