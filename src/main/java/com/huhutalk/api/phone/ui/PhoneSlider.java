package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.DoubleConsumer;

/**
 * 横向滑块（轨道 + 圆角把手）。形态与内置「设置」页的「屏幕大小 / 屏幕亮度」一致。
 *
 * <h2>值域</h2>
 * 内部值恒为 {@code 0.0 ~ 1.0} 的归一化值。需要别的量程时在回调里自己映射：
 * <pre>{@code
 * PhoneSlider slider = new PhoneSlider()
 *         .setValue((HuhuTalkClientSettings.getPhoneSize() - 0.5) / 0.5)
 *         .onChange(t -> preview(0.5 + t * 0.5))          // 拖动中实时预览
 *         .onCommit(t -> HuhuTalkClientSettings.setPhoneSize(0.5f + (float) t * 0.5f)); // 松手落盘
 * }</pre>
 *
 * <h2>为什么要分 onChange / onCommit</h2>
 * 内置页面的滑块都是「拖动时实时改显示，松手才写配置」。分成两个回调后，
 * 页面不必再自己维护 {@code draggingSlider} 布尔量（既有页面每个滑块都写了一个）。
 */
public final class PhoneSlider extends PhoneWidget {

    /** 轨道粗细（纯装饰图形，按既有页面保持固定像素，不随字体倍率缩放） */
    private static final int TRACK_THICKNESS = 3;
    /** 把手尺寸与圆角（同上，固定像素） */
    private static final int HANDLE_WIDTH = 10;
    private static final int HANDLE_HEIGHT = 9;
    private static final int HANDLE_RADIUS = 4;

    private double value = 0.0;
    private DoubleConsumer onChanged;
    private DoubleConsumer onCommit;
    private boolean dragging;
    private Integer trackColor;
    private Integer handleColor;

    /** 设置归一化值（自动钳制到 0~1）；<b>不</b>触发回调。 */
    public PhoneSlider setValue(double v) {
        this.value = clamp01(v);
        return this;
    }

    /** 当前归一化值（0~1）。 */
    public double value() {
        return value;
    }

    /** 拖动中实时回调。 */
    public PhoneSlider onChange(DoubleConsumer listener) {
        this.onChanged = listener;
        return this;
    }

    /** 松手时回调一次（用于落盘）。 */
    public PhoneSlider onCommit(DoubleConsumer listener) {
        this.onCommit = listener;
        return this;
    }

    public boolean dragging() {
        return dragging;
    }

    /** 自定义轨道 / 把手颜色；不设则取主题的 {@code neutralBg} 与 {@code accent}。 */
    public PhoneSlider colors(int track, int handle) {
        this.trackColor = track;
        this.handleColor = handle;
        return this;
    }

    @Override
    public LayoutHint layoutHint() {
        return LayoutHint.STACKED;
    }

    @Override
    public int preferredHeight(Font font) {
        return HANDLE_HEIGHT + 4;
    }

    @Override
    public void render(GuiGraphics g, int originX, int originY) {
        int w = width();
        int h = height();
        if (w <= 0 || h <= 0) {
            return;
        }
        int left = originX + x();
        int top = originY + y();
        int track = trackColor != null ? trackColor : ThemeManager.getActive().neutralBg();
        int handle = handleColor != null ? handleColor : ThemeManager.getActive().accent();

        int trackY = top + (h - TRACK_THICKNESS) / 2;
        PhoneShapes.fillRoundedRect(g, left, trackY, w, TRACK_THICKNESS, 1, track);
        int handleX = left + (int) Math.round(value * w);
        PhoneShapes.fillRoundedRect(g, handleX - HANDLE_WIDTH / 2, trackY - 3,
                HANDLE_WIDTH, HANDLE_HEIGHT, HANDLE_RADIUS, handle);
    }

    @Override
    public boolean mousePressed(double px, double py, int button) {
        if (!enabled() || !isPrimaryPress(button) || !hit(px, py)) {
            return false;
        }
        dragging = true;
        updateFromX(px);
        return true;
    }

    @Override
    public void mouseDragged(double px, double py) {
        if (dragging) {
            updateFromX(px);
        }
    }

    @Override
    public void mouseReleased(double px, double py, int button) {
        if (!dragging) {
            return;
        }
        dragging = false;
        if (onCommit != null) {
            onCommit.accept(value);
        }
    }

    /** 按鼠标横坐标更新值并回调。 */
    private void updateFromX(double px) {
        int w = width();
        if (w <= 0) {
            return;
        }
        double next = clamp01((px - x()) / w);
        if (next != value) {
            value = next;
            if (onChanged != null) {
                onChanged.accept(value);
            }
        }
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }
}
