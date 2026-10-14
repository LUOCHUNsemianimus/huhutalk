package com.huhutalk.api.phone.ui;

import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 多列<b>网格</b>列表：只画可见的那几格，自动处理滚动、裁剪、悬停与点击命中。
 *
 * <h2>它和 {@link PhoneList} 的关系</h2>
 * {@link PhoneList} 是单列、行高可变的「列表」；本类是<b>列数固定、格子等大</b>的「网格」。
 * 两者是同一条纪律的两个形状，不是替代关系：
 * <ul>
 *   <li>手机壳方块选择（4 列）、传文件的背包格（5 列）、图库缩略图（3 列）→ <b>本类</b>；</li>
 *   <li>消息列表、联系人（带分组标题）、设置条目 → {@link PhoneList}。</li>
 * </ul>
 * 之所以不是「给 {@link PhoneList} 加一列 columns 参数」：网格的命中是<b>整除取余</b>（O(1)），
 * 列表的命中是<b>前缀和二分</b>（可变行高），两套数学不一样，硬塞进一个类只会让两边都难读。
 *
 * <h2>它真正解决的问题</h2>
 * 和 {@link PhoneList} 一样：既有页面里「第 i 格画在哪」与「点在哪算第 i 格」是两处独立计算，
 * 网格更甚——渲染里写 {@code col * (cell + gap)}、点击里再写一遍，而且网格还要处理
 * 「点在间距里算什么」这种边界。本类把它收敛成<b>一对互逆方法</b>：
 * {@link #cellX(int)} / {@link #cellY(int)}（渲染用）与 {@link #indexAt(double, double)}（命中用）。
 *
 * <h2>坐标系</h2>
 * 视口用<b>内容坐标</b>（见 {@link PhonePage}：原点在顶栏下方）声明；
 * {@link #render} 把视口叠加上内容区的屏幕原点，所以 {@link #renderCell} 收到的是
 * <b>屏幕坐标</b>，可以直接画。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * private final PhoneGrid<String> grid = new PhoneGrid<>() {
 *     @Override protected List<String> items() { return blocks; }
 *     @Override protected int columns() { return 4; }
 *     @Override protected int cellWidth() { return (width() - PhoneText.px(24)) / 4; }
 *     @Override protected int cellHeight() { return PhoneText.px(34); }
 *     @Override protected int gapX() { return PhoneText.px(4); }
 *     @Override protected int gapY() { return PhoneText.px(4); }
 *     @Override protected int padLeft() { return PhoneText.px(4); }
 *     @Override protected int padTop() { return PhoneText.px(4); }
 *     @Override protected void renderCell(GuiGraphics g, int i, String id, int x, int y, int w, int h, boolean hovered) {
 *         if (hovered) PhoneShapes.fillRoundedRect(g, x, y, w, h, 4, 0x22000000);
 *         // ... 画方块图标与短名
 *     }
 *     @Override protected void onCellClick(int i, String id) { pick(id); }
 * };
 *
 * // 渲染（内容坐标下的视口，布局只写这一处）
 * grid.setViewport(0, 0, w, h);
 * grid.render(g, originX, originY);
 *
 * // 命中 / 滚轮 / 悬停
 * grid.mouseClicked(localX, localY, button, action);
 * grid.mouseScrolled(delta);
 * grid.mouseMoved(localX, localY);
 * }</pre>
 *
 * <h2>格子尺寸按视口算的注意点</h2>
 * {@link #cellWidth()} 之类可以读页面宽度（上面例子就是），但<b>它们会在每帧被多次调用</b>
 * （渲染、命中、总高各一次），所以要写成纯计算、不要在里面做重活。
 */
public abstract class PhoneGrid<T> {

    /** 1 单位滚轮 = 多少逻辑像素（与既有页面和 {@link PhoneList} 的 10 保持一致） */
    private static final int SCROLL_SPEED = 10;

    /** 视口（内容坐标） */
    private int viewX, viewY, viewW, viewH;
    /** 滚动偏移（逻辑像素） */
    private int scrollOffset = 0;
    /** 悬停格下标，-1 = 无 */
    private int hoveredIndex = -1;

    // ==================== 子类实现 ====================

    /** 数据源。按行优先展开：下标 {@code i} 落在第 {@code i / columns()} 行、第 {@code i % columns()} 列。 */
    protected abstract List<T> items();

    /** 列数，必须 &gt; 0。 */
    protected abstract int columns();

    /** 格子宽（像素），必须 &gt; 0。 */
    protected abstract int cellWidth();

    /** 格子高（像素），必须 &gt; 0。 */
    protected abstract int cellHeight();

    /** 列间距（像素）。默认 0。 */
    protected int gapX() {
        return 0;
    }

    /** 行间距（像素）。默认 0。 */
    protected int gapY() {
        return 0;
    }

    /** 网格整体相对视口左边的额外缩进（内容坐标）。默认 0。 */
    protected int padLeft() {
        return 0;
    }

    /** 网格整体相对视口顶边的额外缩进（内容坐标）。默认 0。 */
    protected int padTop() {
        return 0;
    }

    /**
     * 画一格。
     *
     * @param x,y,w,h 该格的<b>屏幕坐标</b>矩形（已含滚动）
     * @param hovered 鼠标是否悬停在本格（需要 {@link #mouseMoved} 被调用才有值）
     */
    protected abstract void renderCell(GuiGraphics g, int index, T item,
                                       int x, int y, int w, int h, boolean hovered);

    /** 格子被点击（按下即触发，与既有页面一致）。 */
    protected void onCellClick(int index, T item) {
    }

    /**
     * 哪些鼠标键算「点击格子」。默认只有左键——右键在手机里被约定为「返回」，
     * 若不挡掉，右键返回的同时还会顺带点开一格。
     */
    protected boolean acceptsButton(int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
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

    /** 格子数 */
    public final int itemCount() {
        return items().size();
    }

    /** 行数（最后一行的残缺部分也算一行）。 */
    public final int rowCount() {
        int cols = columns();
        if (cols <= 0) {
            return 0;
        }
        return (itemCount() + cols - 1) / cols;
    }

    /** 一格占的横向步长（格宽 + 列间距）。 */
    public final int colStep() {
        return cellWidth() + gapX();
    }

    /** 一格占的纵向步长（格高 + 行间距）。 */
    public final int rowStep() {
        return cellHeight() + gapY();
    }

    /** 全部内容高度（含 {@link #padTop()}，不含尾部多余的行间距）。 */
    public final int totalHeight() {
        int rows = rowCount();
        if (rows <= 0) {
            return Math.max(0, padTop());
        }
        return padTop() + rows * cellHeight() + (rows - 1) * gapY();
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

    /** 声明「格子尺寸或列数变了」，让滚动偏移在下次查询时重新钳制。 */
    public final void invalidateLayout() {
        setScrollOffset(this.scrollOffset);
    }

    // ==================== 唯一映射（渲染与命中都走这里） ====================

    /** 第 {@code index} 格的<b>内容坐标</b> X（不含滚动——横向不滚）。 */
    public final int cellX(int index) {
        int cols = columns();
        if (cols <= 0) {
            return viewX + padLeft();
        }
        return viewX + padLeft() + Math.floorMod(index, cols) * colStep();
    }

    /** 第 {@code index} 格的<b>内容坐标</b> Y（已含滚动）。 */
    public final int cellY(int index) {
        int cols = columns();
        if (cols <= 0) {
            return viewY + padTop() - scrollOffset;
        }
        return viewY + padTop() + (index / cols) * rowStep() - scrollOffset;
    }

    /**
     * 内容坐标点落在哪一格；不在任何格上返回 -1（包括落在<b>间距里</b>）。
     * <p>
     * 与 {@link #cellX(int)} / {@link #cellY(int)} 严格互逆。
     */
    public final int indexAt(double localX, double localY) {
        if (localX < viewX || localX >= viewX + viewW) {
            return -1;
        }
        if (localY < viewY || localY >= viewY + viewH) {
            return -1;
        }
        int cols = columns();
        int cw = cellWidth();
        int ch = cellHeight();
        int cStep = colStep();
        int rStep = rowStep();
        if (cols <= 0 || cw <= 0 || ch <= 0 || cStep <= 0 || rStep <= 0) {
            return -1;
        }
        double rx = localX - viewX - padLeft();
        double ry = localY - viewY - padTop() + scrollOffset;
        if (rx < 0 || ry < 0) {
            return -1;
        }
        int col = (int) Math.floor(rx / cStep);
        int row = (int) Math.floor(ry / rStep);
        if (col < 0 || col >= cols) {
            return -1;
        }
        // 落在间距里不算命中——否则"点空白也选中"会让多选网格误触
        if (rx - col * (double) cStep >= cw || ry - row * (double) rStep >= ch) {
            return -1;
        }
        int index = row * cols + col;
        return index < itemCount() ? index : -1;
    }

    // ==================== 渲染 / 交互 ====================

    /**
     * 画出可见的格子并裁剪到视口。
     *
     * @param originX,originY 内容区左上角的<b>屏幕</b>坐标（来自 {@link PhonePage#renderContent}）
     */
    public final void render(GuiGraphics g, int originX, int originY) {
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        setScrollOffset(scrollOffset); // 格子尺寸可能因窗口缩放而变，先重新钳制
        List<T> list = items();
        if (list.isEmpty()) {
            return;
        }
        int cw = cellWidth();
        int ch = cellHeight();
        if (cw <= 0 || ch <= 0) {
            return;
        }
        int left = originX + viewX;
        int top = originY + viewY;

        g.enableScissor(left, top, left + viewW, top + viewH);
        try {
            for (int i = 0; i < list.size(); i++) {
                int y = originY + cellY(i); // ← 唯一映射
                if (y + ch < top || y > top + viewH) {
                    continue; // 视口外，跳过
                }
                int x = originX + cellX(i);
                renderCell(g, i, list.get(i), x, y, cw, ch, i == hoveredIndex);
            }
        } finally {
            g.disableScissor();
        }
    }

    /** 内容坐标点击。按下即触发 {@link #onCellClick}。 */
    public final boolean mouseClicked(double localX, double localY, int button, int action) {
        if (action != GLFW.GLFW_PRESS || !acceptsButton(button)) {
            return false;
        }
        int index = indexAt(localX, localY); // ← 唯一映射的逆
        if (index < 0) {
            return false;
        }
        onCellClick(index, items().get(index));
        return true;
    }

    /** 滚轮。可滚时消费。 */
    public final boolean mouseScrolled(double scrollDelta) {
        if (maxScroll() <= 0) {
            return false;
        }
        scrollBy(scrollDelta);
        return true;
    }

    /** 内容坐标移动：更新悬停格。 */
    public final void mouseMoved(double localX, double localY) {
        this.hoveredIndex = indexAt(localX, localY);
    }
}
