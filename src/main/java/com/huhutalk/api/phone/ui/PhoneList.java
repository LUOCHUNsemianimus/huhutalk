package com.huhutalk.api.phone.ui;

import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 行式<b>虚拟列表</b>：只画可见的那几行，自动处理滚动、裁剪、悬停与点击命中。
 *
 * <h2>它真正解决的问题</h2>
 * 既有页面里，"第 i 行画在哪"和"点在哪算第 i 行"是<b>两处独立计算</b>
 * （渲染里算一遍 {@code listTop + i * ITEM_HEIGHT - scrollOffset}，
 * 点击里再算一遍）。两处一旦不同步就是「点不准」的 bug，而且极难发现。
 * <p>
 * 本类把这件事收敛成<b>一对互逆方法</b>：{@link #itemY(int)}（渲染用）与
 * {@link #indexAt(double, double)}（命中用）。渲染和命中都从这一对走，
 * <b>结构上不可能不同步</b>。
 *
 * <h2>坐标系</h2>
 * 视口用<b>内容坐标</b>（见 {@link PhonePage}：原点在顶栏下方）声明；
 * {@link #render} 时把视口叠加上内容区的屏幕原点，所以
 * {@link #renderItem} 收到的是<b>屏幕坐标</b>，可以直接画。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * private final PhoneList<Foo> list = new PhoneList<>() {
 *     @Override protected List<Foo> items() { return myItems; }
 *     @Override protected int itemHeight() { return 24; }
 *     @Override protected void renderItem(GuiGraphics g, int i, Foo f, int x, int y, int w, int h, boolean hovered) {
 *         if (hovered) g.fill(x, y, x + w, y + h, 0x22FFFFFF);
 *         g.drawString(Minecraft.getInstance().font, f.name(), x + 6, y + 4, 0xFFFFFFFF);
 *     }
 *     @Override protected void onItemClick(int i, Foo f) { PhoneOverlay.pushPage(new FooPage(f)); }
 * };
 *
 * // 渲染（内容坐标下的视口，布局只写这一处）
 * list.setViewport(0, 0, w, h);
 * list.render(g, originX, originY);
 *
 * // 命中
 * list.mouseClicked(localX, localY, button, action);
 * // 滚轮
 * list.mouseScrolled(delta);
 * }</pre>
 */
public abstract class PhoneList<T> {

    /** 1 单位滚轮 = 多少逻辑像素（与既有页面的 10 保持一致） */
    private static final int SCROLL_SPEED = 10;

    /** 视口（内容坐标） */
    private int viewX, viewY, viewW, viewH;
    /** 滚动偏移（逻辑像素） */
    private int scrollOffset = 0;
    /** 悬停行下标，-1 = 无 */
    private int hoveredIndex = -1;

    // ==================== 子类实现 ====================

    /** 数据源。每次渲染/命中会调用一次，直接返回你的 List 字段即可。 */
    protected abstract List<T> items();

    /** 行高（像素），必须 > 0。 */
    protected abstract int itemHeight();

    /**
     * 画一行。
     *
     * @param x,y,w,h 该行的屏幕坐标矩形
     * @param hovered 鼠标是否悬停在本行（需要 {@link #mouseMoved} 被调用才有值）
     */
    protected abstract void renderItem(GuiGraphics g, int index, T item,
                                       int x, int y, int w, int h, boolean hovered);

    /** 行被点击（按下即触发，与既有页面一致）。 */
    protected void onItemClick(int index, T item) {
    }

    /** 行分隔线颜色（ARGB）。alpha 为 0 时不画。默认极淡的黑，压在页面背景上不抢眼。 */
    protected int separatorColor() {
        return 0x22000000;
    }

    // ==================== 视口与滚动 ====================

    /** 设置视口（内容坐标）。在 {@link PhonePage#renderContent} 里按布局调一次即可。 */
    public final void setViewport(int x, int y, int w, int h) {
        this.viewX = x;
        this.viewY = y;
        this.viewW = Math.max(0, w);
        this.viewH = Math.max(0, h);
        setScrollOffset(this.scrollOffset); // 视口变了要重新钳制
    }

    /** 行数 */
    public final int itemCount() {
        return items().size();
    }

    /** 全部内容高度 */
    public final int totalHeight() {
        return itemCount() * itemHeight();
    }

    /** 最大可滚动距离 */
    public final int maxScroll() {
        return Math.max(0, totalHeight() - viewH);
    }

    public final int scrollOffset() {
        return scrollOffset;
    }

    /** 设置滚动偏移，自动钳制到 [0, maxScroll]。 */
    public final void setScrollOffset(int value) {
        this.scrollOffset = Math.max(0, Math.min(value, maxScroll()));
    }

    /** 按滚轮量滚动（跟手：正值 = 手势往下 = 内容跟着往下）。 */
    public final void scrollBy(double scrollDelta) {
        setScrollOffset(scrollOffset - (int) (scrollDelta * SCROLL_SPEED));
    }

    // ==================== 唯一映射（渲染与命中都走这里） ====================

    /** 第 {@code index} 行的<b>内容坐标</b> Y。 */
    public final int itemY(int index) {
        return viewY + index * itemHeight() - scrollOffset;
    }

    /**
     * 内容坐标点落在哪一行；不在任何行上返回 -1。
     * <p>
     * 与 {@link #itemY(int)} 严格互逆——这两个方法就是本类存在的意义。
     */
    public final int indexAt(double localX, double localY) {
        if (localX < viewX || localX >= viewX + viewW) {
            return -1;
        }
        if (localY < viewY || localY >= viewY + viewH) {
            return -1;
        }
        int h = itemHeight();
        if (h <= 0) {
            return -1;
        }
        int index = (int) Math.floor((localY - viewY + scrollOffset) / (double) h);
        return (index >= 0 && index < items().size()) ? index : -1;
    }

    // ==================== 渲染 / 交互 ====================

    /**
     * 画出可见行并裁剪到视口。
     *
     * @param originX,originY 内容区左上角的<b>屏幕</b>坐标（来自 {@link PhonePage#renderContent}）
     */
    public final void render(GuiGraphics g, int originX, int originY) {
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        int h = itemHeight();
        if (h <= 0) {
            return;
        }
        List<T> list = items();
        int left = originX + viewX;
        int top = originY + viewY;

        g.enableScissor(left, top, left + viewW, top + viewH);
        try {
            int sep = separatorColor();
            for (int i = 0; i < list.size(); i++) {
                int y = top + itemY(i); // ← 唯一映射
                if (y + h < top || y > top + viewH) {
                    continue; // 视口外，跳过
                }
                renderItem(g, i, list.get(i), left, y, viewW, h, i == hoveredIndex);
                if (((sep >>> 24) & 0xFF) != 0 && i < list.size() - 1) {
                    g.fill(left, y + h - 1, left + viewW, y + h, sep);
                }
            }
        } finally {
            g.disableScissor();
        }
    }

    /** 内容坐标点击。按下即触发 {@link #onItemClick}。默认只接受左键。 */
    public final boolean mouseClicked(double localX, double localY, int button, int action) {
        if (action != GLFW.GLFW_PRESS || !acceptsButton(button)) {
            return false;
        }
        int index = indexAt(localX, localY); // ← 唯一映射的逆
        if (index < 0) {
            return false;
        }
        onItemClick(index, items().get(index));
        return true;
    }

    /**
     * 哪些鼠标键算「点击行」。默认只有左键——右键在手机里被约定为「返回」，
     * 若不挡掉，右键返回的同时还会顺带点开一行。
     */
    protected boolean acceptsButton(int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
    }

    /** 滚轮。视口内且可滚时消费。 */
    public final boolean mouseScrolled(double scrollDelta) {
        if (maxScroll() <= 0) {
            return false;
        }
        scrollBy(scrollDelta);
        return true;
    }

    /** 内容坐标移动：更新悬停行。 */
    public final void mouseMoved(double localX, double localY) {
        this.hoveredIndex = indexAt(localX, localY);
    }
}
