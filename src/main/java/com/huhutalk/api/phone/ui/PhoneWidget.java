package com.huhutalk.api.phone.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * 手机页面里的控件基类（开关 / 增减 / 下拉 / 滑块）。
 *
 * <h2>坐标</h2>
 * 控件的 {@code (x, y)} 用<b>内容坐标</b>（见 {@link PhonePage}：原点在顶栏下方），
 * 与页面自己的布局同一套坐标系。{@link #render} 会拿到内容区的屏幕原点，
 * 因此控件内部算屏幕位置就是 {@code origin + x}。
 *
 * <h2>三态交互</h2>
 * 需要拖动的控件（滑块）由页面驱动这三步：
 * <pre>{@code
 * // 在 PhonePage.onContentClick 里
 * if (action == GLFW.GLFW_PRESS)   return row.mousePressed(localX, localY, button);
 * if (action == GLFW.GLFW_RELEASE) return row.mouseReleased(localX, localY, button);
 * // 在 PhonePage.onContentMoved 里
 * row.mouseDragged(localX, localY);
 * }</pre>
 * 「谁按下了」由 {@link PhoneRow} 记住，页面不需要自己维护 {@code dragging} 布尔量
 * （既有页面每个滑块都写了一个，这正是重复的来源）。
 *
 * @see PhoneSwitch
 * @see PhoneStepper
 * @see PhoneDropdown
 * @see PhoneSlider
 * @see PhoneRow
 */
public abstract class PhoneWidget {

    /** 控件在内容坐标里的矩形 */
    private int x, y, w, h;
    /** 是否可用（不可用时不响应输入；渲染上由子类决定是否置灰） */
    private boolean enabled = true;

    /** 设置控件矩形（内容坐标）。页面的布局代码里调一次。 */
    public final void bounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
    }

    public final int x() {
        return x;
    }

    public final int y() {
        return y;
    }

    public final int width() {
        return w;
    }

    public final int height() {
        return h;
    }

    /** 点是否落在控件矩形内（内容坐标）。 */
    public final boolean hit(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    public final boolean enabled() {
        return enabled;
    }

    public final void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 控件在 {@link PhoneRow} 里的排布偏好。
     */
    public enum LayoutHint {
        /** 标签在左、控件右对齐（开关、stepper） */
        INLINE_RIGHT,
        /** 标签占一行、控件占满下一行（滑块、下拉——它们需要横向空间） */
        STACKED
    }

    /** 排布偏好，见 {@link LayoutHint}。 */
    public LayoutHint layoutHint() {
        return LayoutHint.INLINE_RIGHT;
    }

    /**
     * 建议宽度（像素）。
     * <p>
     * {@code 0} 是特殊值，表示「撑满可用宽度」——滑块、下拉这类控件用它，
     * {@link PhoneRow} 会据此决定把自己拉满还是把控件右对齐。
     */
    public int preferredWidth(Font font) {
        return 0;
    }

    /** 建议高度（像素）。{@code 0} 表示跟随行高。 */
    public int preferredHeight(Font font) {
        return 0;
    }

    /**
     * 绘制控件。
     *
     * @param originX,originY 内容区左上角的屏幕坐标（来自 {@link PhonePage#renderContent}）
     */
    public abstract void render(GuiGraphics graphics, int originX, int originY);

    /**
     * 鼠标按下。
     *
     * @return true 表示本控件消费了这次按下（{@link PhoneRow} 会把它记为拖动目标）
     */
    public boolean mousePressed(double px, double py, int button) {
        return false;
    }

    /** 鼠标按住移动。只有被 {@link #mousePressed} 捕获后才会收到。 */
    public void mouseDragged(double px, double py) {
    }

    /** 鼠标松开。 */
    public void mouseReleased(double px, double py, int button) {
    }

    /** 滚轮；返回 true 表示消费。默认不消费（下拉列表会覆盖）。 */
    public boolean mouseScrolled(double delta) {
        return false;
    }

    /** 默认的「可点击」判断：左键。 */
    protected static boolean isPrimaryPress(int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
    }
}
