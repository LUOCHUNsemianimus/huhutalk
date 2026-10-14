package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Supplier;

/**
 * 一行「标签 + 右侧控件」——设置类页面里最常见的那一格。
 * 内置「设置」页里 5 种行（开关 / stepper / 滑块 / 按钮 / 纯文本）都是这个形，
 * 这里把它收成一个可复用的对象，顺带把控件布局与命中判定绑成同一份计算。
 *
 * <h2>两种排布（由控件的 {@link PhoneWidget#layoutHint()} 决定）</h2>
 * <ul>
 *   <li>{@code INLINE_RIGHT}：标签在左、控件右对齐（开关、stepper）</li>
 *   <li>{@code STACKED}：标签占一行、控件占满下一行（滑块、下拉）</li>
 * </ul>
 * 高度会自动算，用 {@link #layout(Font, int, int, int)} 取代手写 {@code bounds}：
 * <pre>{@code
 * PhoneRow row = PhoneRow.of(() -> "屏幕亮度：" + pct(), slider);
 * row.layout(font, 0, y, w);
 * y += row.height();
 * }</pre>
 *
 * <h2>拖动由行代管</h2>
 * 页面只要把三态转给行即可，不需要自己维护 {@code dragging} 布尔量：
 * <pre>{@code
 * @Override protected boolean onContentClick(double px, double py, int button, int action) {
 *     for (PhoneRow r : rows) {
 *         if (action == GLFW.GLFW_PRESS   && r.mousePressed(px, py, button))  return true;
 *         if (action == GLFW.GLFW_RELEASE && r.mouseReleased(px, py, button)) return true;
 *     }
 *     return false;
 * }
 * @Override protected void onContentMoved(double px, double py) {
 *     for (PhoneRow r : rows) r.mouseDragged(px, py);
 * }
 * }</pre>
 */
public final class PhoneRow {

    /** 上下内边距（像素，随字体倍率缩放） */
    private static final int V_PAD_PX = 5;
    /** 标签与下方控件之间的间距（STACKED 排布用） */
    private static final int GAP_PX = 3;
    /** 左右内边距（像素，随字体倍率缩放） */
    private static final int DEFAULT_PAD_PX = 12;

    private final Supplier<String> label;
    private final PhoneWidget widget;

    private int x, y, w, h;
    private int background = 0;
    private int radius = 6;
    private int labelColor = 0;
    private float labelScale = 1.0f;
    private int padPx = DEFAULT_PAD_PX;
    /** 强制行高（基准像素）；0 = 按内容自动算。见 {@link #rowHeight(int)} */
    private int fixedHeightPx = 0;
    /** 被按下并正在拖动的控件（由本行代管） */
    private PhoneWidget captured;

    private PhoneRow(Supplier<String> label, PhoneWidget widget) {
        this.label = label;
        this.widget = widget;
    }

    /** 纯文本行。 */
    public static PhoneRow of(String label) {
        return new PhoneRow(() -> label, null);
    }

    /** 动态文本行（标签里带当前值）。 */
    public static PhoneRow of(Supplier<String> label) {
        return new PhoneRow(label, null);
    }

    /** 标签 + 右侧控件。 */
    public static PhoneRow of(String label, PhoneWidget widget) {
        return new PhoneRow(() -> label, widget);
    }

    /** 动态标签 + 右侧控件（例如「每字耗时：3」跟着 stepper 变）。 */
    public static PhoneRow of(Supplier<String> label, PhoneWidget widget) {
        return new PhoneRow(label, widget);
    }

    /** 展开类控件（下拉）占用的额外高度；其余为 0。 */
    public int extraHeight() {
        return widget instanceof PhoneDropdown d ? d.expandedHeight() : 0;
    }

    /** 行高（不含展开额外高度）。 */
    public int baseHeight(Font font) {
        int vpad = PhoneText.px(V_PAD_PX);
        int labelH = PhoneText.height(font, labelScale);
        if (widget == null) {
            return labelH + vpad * 2;
        }
        int wh = widgetHeight(font);
        if (widget.layoutHint() == PhoneWidget.LayoutHint.STACKED) {
            return labelH + PhoneText.px(GAP_PX) + wh + vpad * 2;
        }
        return Math.max(labelH, wh) + vpad * 2;
    }

    /**
     * 按内容坐标摆放本行，<b>自动计算高度</b>（含展开额外高度），并顺带把控件也摆好。
     * 返回自身便于链式调用。
     * <p>
     * <b>每帧调一次</b>（在 {@code renderContent} 里画之前）。控件的矩形只在这里算，
     * 渲染与命中共用同一份结果，不会出现"画在一处、点在另一处"。
     */
    public PhoneRow layout(Font font, int x, int y, int w) {
        this.x = x;
        this.y = y;
        this.w = Math.max(0, w);
        int base = baseHeight(font);
        if (fixedHeightPx > 0) {
            // 固定行高不能矮到把内容挤在一起：按「标签 + 控件 + 留白」算一个下界再取较大者。
            // 否则字体倍率调小时固定像素的行高会先撑不住（滑块的高度是固定像素、不随倍率缩放）。
            int vpad = PhoneText.px(V_PAD_PX);
            int labelH = PhoneText.height(font, labelScale);
            int min = labelH + vpad * 2;
            if (widget != null) {
                int wh = widgetHeight(font);
                min = Math.max(min, widget.layoutHint() == PhoneWidget.LayoutHint.STACKED
                        ? labelH + vpad + wh              // 标签贴顶 + 控件贴底
                        : wh + vpad * 2);
            }
            base = Math.max(PhoneText.px(fixedHeightPx), min);
        }
        this.h = base + extraHeight();
        if (widget != null) {
            layoutWidget(font, PhoneText.height(font, labelScale));
        }
        return this;
    }

    /**
     * <b>固定行高</b>（基准像素，随全局字体倍率缩放）。
     * <p>
     * 默认是按内容自动算，于是同一种「标签 + 控件」形态会因为控件不同而得到不同的行高：
     * 开关 8px、胶囊按钮 8px、stepper 18px、滑块 13px 且要另起一行 —— 一页里能出现 4 种行高，
     * 看起来"每行都不一样高"。给一个固定值就统一了。
     * <p>
     * 固定之后三种控件的摆法（这是本方法真正的价值）：
     * <ul>
     *   <li>开关 / stepper / 按钮（{@code INLINE_RIGHT}）：<b>标签与控件一起在行内垂直居中</b>；</li>
     *   <li>滑块 / 下拉（{@code STACKED}）：<b>标签贴顶、控件贴底</b> ——
     *       与旧设置页「标签在上、滑块在下」的观感一致，也保证滑块的轨道不会横穿标签文字。</li>
     * </ul>
     * <p>
     * ⚠ 太矮会把它们挤在一起：滑块行要求
     * {@code 固定行高 ≥ 上留白 + 滑块控件高 + 下留白}，否则轨道会切过标签。
     * 默认字体倍率下，滑块（控件高 13px + 上下留白 5px）需要 <b>≥ 27px</b>。
     *
     * @param pixels 固定行高（基准像素）；{@code <= 0} 表示恢复自动计算
     */
    public PhoneRow rowHeight(int pixels) {
        this.fixedHeightPx = Math.max(0, pixels);
        return this;
    }

    /** 行底色（ARGB）；不设则不画底。设置页里通常是主题的 {@code cardBg}。 */
    public PhoneRow background(int argb) {
        this.background = argb;
        return this;
    }

    public PhoneRow background(int argb, int radius) {
        this.background = argb;
        this.radius = Math.max(0, radius);
        return this;
    }

    /** 标签颜色；不设则取主题的 {@code textPrimary}。 */
    public PhoneRow labelColor(int argb) {
        this.labelColor = argb;
        return this;
    }

    /** 标签字号倍率；分组标题之类可以调小。 */
    public PhoneRow labelScale(float scale) {
        this.labelScale = scale;
        return this;
    }

    /** 左右内边距（像素，会随字体倍率缩放）。 */
    public PhoneRow pad(int pixels) {
        this.padPx = Math.max(0, pixels);
        return this;
    }
    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return w;
    }

    /** 行高（含展开额外高度）。 */
    public int height() {
        return h;
    }

    /** 点是否落在本行范围内（含展开的下拉列表区域）。 */
    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    public void render(GuiGraphics g, int originX, int originY) {
        Font font = Minecraft.getInstance().font;
        int baseH = h - extraHeight();
        int sx = originX + x;
        int sy = originY + y;

        if (background != 0 && baseH > 0) {
            PhoneShapes.fillRoundedRect(g, sx, sy, w, baseH, radius, background);
        }

        int color = labelColor != 0 ? labelColor : ThemeManager.getActive().textPrimary();
        int vpad = PhoneText.px(V_PAD_PX);
        int labelH = PhoneText.height(font, labelScale);
        // 固定行高 + 右侧控件：标签与控件一起在行内居中；
        // 固定行高 + 贴底控件（滑块/下拉）：标签留在顶部，控件在下方
        int labelY = (fixedHeightPx > 0 && widget != null
                && widget.layoutHint() != PhoneWidget.LayoutHint.STACKED)
                ? sy + (baseH - labelH) / 2
                : sy + vpad;
        PhoneText.drawLeftIn(g, font, label.get(), sx, labelY, w, labelH,
                PhoneText.px(padPx), color, PhoneText.scale(labelScale));

        if (widget != null) {
            widget.render(g, originX, originY);
        }
    }

    /** 控件布局（内容坐标）。只由 {@link #layout} 调用，渲染与命中共用同一份结果。 */
    private void layoutWidget(Font font, int labelH) {
        int pad = PhoneText.px(padPx);
        int vpad = PhoneText.px(V_PAD_PX);
        int wh = widgetHeight(font);
        int baseH = h - extraHeight();

        if (widget.layoutHint() == PhoneWidget.LayoutHint.STACKED) {
            // 固定行高：控件贴底（标签贴顶）—— 与旧设置页「标签在上、滑块在下」一致，
            // 也保证轨道落在标签文字下方而不是穿过它
            int wy = fixedHeightPx > 0
                    ? y + Math.max(0, baseH - vpad - wh)
                    : y + vpad + labelH + PhoneText.px(GAP_PX);
            widget.bounds(x + pad, wy, Math.max(1, w - pad * 2), wh);
            return;
        }
        int pw = widget.preferredWidth(font);
        if (pw <= 0) {
            pw = Math.max(1, w - pad * 2);
        }
        int wx = x + w - pw - pad;
        // 固定行高：控件在整行内居中；否则与标签那一行对齐
        int wy = fixedHeightPx > 0
                ? y + (baseH - wh) / 2
                : y + vpad + (labelH - wh) / 2;
        widget.bounds(wx, wy, pw, wh);
    }

    /** 控件高度：优先取控件自报的，否则跟随字体。 */
    private int widgetHeight(Font font) {
        int ph = widget.preferredHeight(font);
        return ph > 0 ? ph : PhoneText.height(font, 1.0f) + PhoneText.px(4);
    }

    /** 转交按下；被消费则记下拖动目标。 */
    public boolean mousePressed(double px, double py, int button) {
        if (widget == null) {
            return false;
        }
        if (!widget.mousePressed(px, py, button)) {
            return false;
        }
        captured = widget;
        return true;
    }

    /** 转交按住移动（只有按下的那个控件会收到）。 */
    public void mouseDragged(double px, double py) {
        if (captured != null) {
            captured.mouseDragged(px, py);
        }
    }

    /** 转交松开；结束拖动。 */
    public boolean mouseReleased(double px, double py, int button) {
        if (captured == null) {
            return false;
        }
        PhoneWidget w = captured;
        captured = null;
        w.mouseReleased(px, py, button);
        return true;
    }

    /** 转交滚轮（下拉展开时用来选择项）。 */
    public boolean mouseScrolled(double delta) {
        return widget != null && widget.mouseScrolled(delta);
    }
}
