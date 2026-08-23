package com.huhutalk.phone;

import com.huhutalk.client.settings.HuhuTalkClientSettings;

/**
 * 手机 UI 全局字体倍率的统一入口。
 * <p>
 * 所有页面用 {@link #uiScale()} 作为字体倍率（可选 0.5/0.75/1.0/1.5/2.0）。
 * <ul>
 *   <li>{@link #sh(int)}：把一个「固定像素高度/留白」等比例放大，避免文字放大后被容器夹住溢出。</li>
 *   <li>{@link #sc(float)}：把一个「相对字体的倍率」再乘上全局字体倍率，用于文字绘制与基于文字宽度的定位。</li>
 * </ul>
 * 纯装饰图形（未读红点、信号条、在线状态小方块等）保持固定尺寸，不调用本类。
 */
public final class UiFont {
    private UiFont() {
    }

    /** 当前全局字体倍率 */
    public static float uiScale() {
        return HuhuTalkClientSettings.getUiFontScale();
    }

    /** 缩放一个固定像素量（高度/留白），至少为 1，保证不为 0 */
    public static int sh(int px) {
        return Math.max(1, Math.round(px * uiScale()));
    }

    /** 把相对字体的倍率乘上全局字体倍率 */
    public static float sc(float scale) {
        return scale * uiScale();
    }
}
