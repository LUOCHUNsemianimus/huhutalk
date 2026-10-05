package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.UiShapes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * 胶囊开关（开/关）。形态与内置「设置」页一致：右对齐胶囊，激活时圆点滑到右侧。
 *
 * <pre>{@code
 * PhoneSwitch sw = new PhoneSwitch()
 *         .on(HuhuTalkClientSettings.isXxx())
 *         .onChange(v -> HuhuTalkClientSettings.setXxx(v));
 * PhoneRow row = PhoneRow.of("开启某功能", sw).bounds(0, y, w, rowH);
 * }</pre>
 */
public final class PhoneSwitch extends PhoneWidget {

    /** 控件高度占字体行高的比例（与 SettingsPage 的 contH 一致） */
    private static final float HEIGHT_SCALE = 0.9f;
    /** 圆点颜色（白） */
    private static final int KNOB_COLOR = 0xFFFFFFFF;

    private boolean value;
    private Consumer<Boolean> onChanged;
    private Integer colorOn;
    private Integer colorOff;

    /** 开关高度：跟随字体倍率，保证「字体倍率」设置对它同样生效。 */
    public static int preferredHeightFor(Font font) {
        return Math.max(1, (int) (font.lineHeight * PhoneText.scale(HEIGHT_SCALE)));
    }

    /** 初始状态。 */
    public PhoneSwitch on(boolean initial) {
        this.value = initial;
        return this;
    }

    public boolean value() {
        return value;
    }

    /** 直接改值（<b>不会</b>触发 {@link #onChange} 回调）。 */
    public PhoneSwitch setValue(boolean v) {
        this.value = v;
        return this;
    }

    /** 用户拨动时回调（代码改值不触发）。 */
    public PhoneSwitch onChange(Consumer<Boolean> listener) {
        this.onChanged = listener;
        return this;
    }

    /** 自定义激活/未激活底色；不设则取当前主题的 {@code accent} 与 {@code neutralBg}。 */
    public PhoneSwitch colors(int on, int off) {
        this.colorOn = on;
        this.colorOff = off;
        return this;
    }

    @Override
    public int preferredWidth(Font font) {
        return preferredHeightFor(font) * 2;
    }

    @Override
    public int preferredHeight(Font font) {
        return preferredHeightFor(font);
    }

    @Override
    public void render(GuiGraphics g, int originX, int originY) {
        int w = width();
        int h = height();
        if (w <= 0 || h <= 0) {
            return;
        }
        int x = originX + x();
        int y = originY + y();
        int on = colorOn != null ? colorOn : ThemeManager.getActive().accent();
        int off = colorOff != null ? colorOff : ThemeManager.getActive().neutralBg();

        UiShapes.fillRoundedRect(g, x, y, w, h, h / 2, value ? on : off);
        int knob = value ? x + w - h + 1 : x + 1;
        int knobSize = Math.max(1, h - 2);
        UiShapes.fillRoundedRect(g, knob, y + 1, knobSize, knobSize, knobSize / 2, KNOB_COLOR);
    }

    @Override
    public boolean mousePressed(double px, double py, int button) {
        if (!enabled() || !isPrimaryPress(button) || !hit(px, py)) {
            return false;
        }
        value = !value;
        if (onChanged != null) {
            onChanged.accept(value);
        }
        return true;
    }
}
