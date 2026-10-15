package com.huhutalk.api.phone.ui;

import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * 手机内页面的<b>公开骨架</b>——把「顶栏 + 返回键 + 滚动容器 + 裁剪 + 命中 + 主题取色」这些
 * 每个页面都要重写一遍的东西收进来。
 *
 * <h2>为什么要它</h2>
 * 在此之前每个页面都直接 {@code implements IPhonePage}，于是同一套逻辑被复制了十几份：
 * 滚动偏移、{@code enableScissor} 配对、顶栏与「‹ 返回」的绘制、返回键命中区域……
 * 最要命的是<b>布局数学在 {@code render} 和 {@code mouseClicked} 里各算一遍</b>，
 * 渲染与命中靠人肉保持同步，改一处忘另一处就会变成「点不准」。
 *
 * <h2>坐标系约定（很重要）</h2>
 * {@link IPhonePage} 收到的是<b>手机内容区左上角</b>为原点的坐标（已扣掉系统状态栏）。
 * 本骨架进一步把内容区再切一刀：
 * <ul>
 *   <li><b>手机坐标</b>：{@code (relX, relY)}，原点在内容区左上角</li>
 *   <li><b>内容坐标</b>：{@code (localX, localY)}，原点在<b>顶栏下方</b>的内容区左上角，
 *       即 {@code localY = relY - contentTop()}</li>
 * </ul>
 * 你只需要实现 {@link #renderContent} 和 {@link #onContentClick}，两者都收<b>内容坐标</b>，
 * 于是"同一份布局"天然就在同一个坐标系里。
 *
 * <h2>最小用法</h2>
 * <pre>{@code
 * public final class MyPage extends PhonePage {
 *     @Override protected String title() { return "我的页面"; }
 *     @Override protected int contentHeight() { return 400; }      // 比视口高才会滚
 *
 *     @Override
 *     protected void renderContent(GuiGraphics g, int ox, int oy, int w, int h, float partialTick) {
 *         int y = scrollY(0);                                       // 逻辑 y=0 → 内容坐标
 *         g.drawString(Minecraft.getInstance().font, "第一行", ox + 8, oy + y, 0xFFFFFFFF);
 *     }
 *
 *     @Override
 *     protected boolean onContentClick(double localX, double localY, int button, int action) {
 *         if (hit(8, scrollY(0), 100, 12, localX, localY)) { ...; return true; }
 *         return false;
 *     }
 * }
 * }</pre>
 *
 * <h2>需要更自由？</h2>
 * 直接 {@code implements IPhonePage} 就行。本类只是<b>可选</b>的便利层，
 * 现有的十几个页面一行都没改，继续照旧工作。
 *
 * @see PhoneList 行式虚拟列表（渲染与命中共用同一份 index→Y 映射）
 */
public abstract class PhonePage implements IPhonePage {

    /** 顶栏文字上下的留白基准像素（随全局字体倍率缩放） */
    private static final int TOP_BAR_PAD_PX = 8;
    /** 返回键命中区域宽度（自左边缘算起） */
    private static final int BACK_HIT_WIDTH = 60;
    /** 返回键文案 */
    private static final String BACK_LABEL = "< 返回";
    /** 默认滚动速度：1 单位滚轮 = 多少逻辑像素 */
    private static final int DEFAULT_SCROLL_SPEED = 10;

    /**
     * HuhuTalk 的<b>品牌顶栏色</b>（{@code #DE7E7E}）。
     * <p>
     * 聊天页、消息页、联系人页、设置页、相机页…… 内置页面的顶栏全都是这个硬编码色，
     * 主题的 {@code ColorScheme.topBarBg()} 也是同一个值（曾经是偏紫的 {@code #F06292}，已更正）。
     * 那为什么还要有本常量？因为<b>骨架页面的顶栏是"跟主题走"的</b>：
     * 以后一旦新增了别的配色方案，用骨架写的页面顶栏会变、而老页面不会 ——
     * 两者摆在一起就像两台不同的手机。想让自己的页面<b>永远</b>和内置页面一致，
     * 就覆盖 {@link #topBarBackground()} 返回本常量（图库、手册就是这么做的）。
     */
    public static final int BRAND_TOPBAR_BG = 0xFFDE7E7E;

    /** 当前滚动偏移（逻辑像素，0 = 内容顶端） */
    private int scrollOffset = 0;

    // ==================== 子类按需覆盖：外观 ====================

    /** 顶栏标题（居中）。返回空串则不画标题。 */
    protected String title() {
        return "";
    }

    /** 是否显示顶栏。关掉之后内容区从手机内容区的原点开始。 */
    protected boolean showTopBar() {
        return true;
    }

    /** 是否显示「‹ 返回」。关掉之后左上交还给 {@link #onTopBarClick}。 */
    protected boolean showBackButton() {
        return true;
    }

    /** 点返回键做什么；默认弹回上一页。 */
    protected void onBack() {
        PhoneOverlay.popPage();
    }

    /**
     * 顶栏右侧的额外内容（红点、计数、按钮…），在标题之后绘制。
     * 默认什么都不画。绘制时注意用 {@code textY} 保持与标题同一基线。
     */
    protected void renderTopBarExtras(GuiGraphics graphics, int baseX, int baseY, int w, int barH, int textY) {
    }

    // ==================== 子类按需覆盖：行为 ====================

    /**
     * 内容区总高度（逻辑像素，<b>不含</b>顶栏）。
     * <p>
     * 返回值 ≤ 视口高度时不滚动（{@link #maxScroll()} 为 0）。
     * 不需要滚动就返回 0（默认值）。
     * <p>
     * <b>用了 {@link PhoneList} 的话这里保持返回 0</b>：列表自带滚动状态，
     * 页面自己再滚一次会变成双重滚动。那种情况下把 {@code mouseScrolled} 转给列表即可，
     * 参见 {@link PhoneList} 的类注释。
     */
    protected int contentHeight() {
        return 0;
    }

    /**
     * 内容区底部预留高度（逻辑像素）——让最后一行能滑出<b>悬浮底部导航</b>的遮挡。
     *
     * <h2>为什么需要它</h2>
     * 底部导航（Dock）是<b>悬浮</b>在页面之上的，不是占位色块：{@code PhoneOverlay.getBottomNavHeight()}
     * 恒为 {@code 0}，内容区一直延伸到水印栏。于是内容滚到底时，最后一行会被 Dock 盖住。
     * 既有页面各自用 {@code maxScroll = contentH + PhoneOverlay.getBottomNavHeight() + UiFont.sh(30) - viewH}
     * 兜这件事（那个 {@code +0} 就是这么来的历史包袱）。
     * <p>
     * 本钩子把这件事收成一处：返回的像素会从 {@link #contentViewHeight()} 里扣掉，
     * 于是 {@link #maxScroll()} 自然多出一段底部留白。<b>只影响可滚动距离，不影响绘制裁剪</b>
     * ——内容仍可画到水印栏（与既有页面一致）。
     * <p>
     * 默认 {@code 0}（不留白）。带底部导航的 tab 顶层页建议返回
     * {@code PhoneOverlay.getBottomNavHeight() + PhoneText.px(30)}（与历史行为一致），
     * 子页面/App 页返回 0 即可。
     */
    protected int bottomInset() {
        return 0;
    }

    /** 滚动速度：1 单位滚轮 = 多少逻辑像素。 */
    protected double scrollSpeed() {
        return DEFAULT_SCROLL_SPEED;
    }

    /** 内容绘制。{@code (originX, originY)} 是内容区左上角的<b>屏幕</b>坐标，{@code (w, h)} 是内容区尺寸。 */
    protected void renderContent(GuiGraphics graphics, int originX, int originY, int w, int h, float partialTick) {
    }

    /**
     * 内容区点击。坐标是<b>内容坐标</b>（原点在顶栏下方）。
     *
     * @return true 表示已消费
     */
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        return false;
    }

    /**
     * 顶栏点击的额外拦截点，在返回键判定<b>之前</b>调用。
     * 用于顶栏上放自己的按钮（标题下拉、右侧开关等）。
     *
     * @return true 表示已消费，后续的返回键与内容点击都不再处理
     */
    protected boolean onTopBarClick(double relX, double relY, int button, int action) {
        return false;
    }

    // ==================== 现成能力 ====================

    /** 手机内容区宽度（GUI 单位） */
    protected final int width() {
        return PhoneOverlay.getPhoneWidth();
    }

    /** 手机内容区高度（GUI 单位，不含系统状态栏） */
    protected final int height() {
        return PhoneOverlay.getPhoneHeight();
    }

    /** 当前配色方案 */
    protected final ColorScheme colors() {
        return ThemeManager.getActive();
    }

    /**
     * 页面底色。默认取主题的 {@code pageBg}（主要页面背景）。
     * <p>
     * 「设置 / 条目 / 列表」形态的页面通常返回 {@code colors().pageBgList()}（略深一档），
     * 自带视觉风格的 App 则可以直接返回自己的颜色——色板是默认值，不是强制。
     */
    protected int pageBackground() {
        return colors().pageBg();
    }

    /**
     * 顶栏底色。默认取主题的 {@code topBarBg()}（随配色方案切换）。
     * <p>
     * 想让自己的页面和内置页面（聊天/消息/设置…）的顶栏一致，
     * 就返回 {@link #BRAND_TOPBAR_BG}。
     */
    protected int topBarBackground() {
        return colors().topBarBg();
    }

    /** 常用字体 */
    protected final Font font() {
        return Minecraft.getInstance().font;
    }

    /**
     * 顶栏高度 = 一行字（随全局字体倍率）+ 8px 留白。顶栏关闭时为 0。
     *
     * <h2>为什么是这个公式</h2>
     * 这是<b>内置页面真正在用的高度</b>：聊天页 / 消息页 / 联系人页 / 设置页 / 女仆信息页 /
     * 手机壳选择页写的都是 {@code font.lineHeight * UiFont.sc(1.0f) + UiFont.sh(8)}（默认 ≈ 17px）。
     * <p>
     * ⚠ 本方法曾经是 {@code max(height * 0.08, font.lineHeight + 4)}（默认 ≈ 25px，比内置页高 8px）。
     * 那个 {@code 0.08} 抄的是两个<b>已被删除</b>的死页面（{@code MaidListPage} / {@code MaidSettingsPage}），
     * 从未与活着的页面一致过 —— 于是已迁到骨架的页面和内置页面并排时像"两台不同的手机"。
     * 现已统一到内置页的真实高度；因为所有页面都用 {@link #contentTop()} 定位，
     * 这次改动只是让内容整体上移，<b>不需要逐页校正布局常量</b>。
     */
    protected int topBarHeight() {
        if (!showTopBar()) {
            return 0;
        }
        return topBarLineHeight() + PhoneText.px(TOP_BAR_PAD_PX);
    }

    /**
     * 顶栏里一行字的高度（随全局字体倍率缩放）。
     * <p>
     * {@code render} 与子类的顶栏绘制都该用它算垂直居中，<b>不要直接用 {@code font.lineHeight}</b>
     * ——那是 1 倍字号的行高，字体倍率调大后标题会偏下（顶栏变高但文字按 9px 居中）。
     */
    protected final int topBarLineHeight() {
        return Math.max(1, PhoneText.height(font(), PhoneText.scale(1.0f)));
    }

    /** 内容区在手机内容区坐标系里的上边界（= 顶栏高度）。 */
    protected final int contentTop() {
        return topBarHeight();
    }

    /**
     * 内容区可视高度。
     * <p>
     * 已扣掉顶栏与 {@link #bottomInset()}。
     */
    protected final int contentViewHeight() {
        return Math.max(0, height() - contentTop() - bottomInset());
    }

    /** 当前滚动偏移。 */
    protected final int scrollOffset() {
        return scrollOffset;
    }

    /** 设置滚动偏移（自动钳制到 [0, maxScroll]）。 */
    protected final void setScrollOffset(int value) {
        int max = maxScroll();
        this.scrollOffset = Math.max(0, Math.min(value, max));
    }

    /** 最大可滚动距离。 */
    protected final int maxScroll() {
        return Math.max(0, contentHeight() - contentViewHeight());
    }

    /**
     * 逻辑 y → 内容坐标 y（已含滚动）。渲染时用它；
     * {@link #logicalY(double)} 是它的逆运算，命中时用。
     * <p>
     * <b>这两个方法必须成对使用</b>：渲染走 {@code scrollY(logical)}、
     * 命中走 {@code logicalY(localY)}，就不会出现"画在一个位置、点在另一个位置"。
     */
    protected final int scrollY(int logicalY) {
        return logicalY - scrollOffset;
    }

    /** 内容坐标 y → 逻辑 y（已含滚动）。{@link #scrollY(int)} 的逆运算。 */
    protected final int logicalY(double localY) {
        return (int) localY + scrollOffset;
    }

    /** 矩形命中测试：给定内容坐标下的矩形与点击点。 */
    protected static boolean hit(int x, int y, int w, int h, double px, double py) {
        return PhoneLayout.hit(px, py, x, y, w, h);
    }

    // ==================== 模板方法 ====================

    @Override
    public final void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = width();
        int h = height();

        // 视口尺寸可能因窗口缩放而变，先钳一次偏移，避免内容被滚出可视范围外
        setScrollOffset(scrollOffset);

        // 页面背景
        graphics.fill(baseX, baseY, baseX + w, baseY + h, pageBackground());

        // 顶栏
        renderTopBar(graphics, baseX, baseY, w);

        // 内容区（裁剪到顶栏以下）
        int top = baseY + contentTop();
        int bottom = baseY + h;
        if (bottom <= top) {
            return;
        }
        graphics.enableScissor(baseX, top, baseX + w, bottom);
        try {
            renderContent(graphics, baseX, top, w, bottom - top, partialTick);
        } finally {
            graphics.disableScissor();
        }
    }

    /** 画顶栏：底色 + 返回键 + 居中标题 + {@link #renderTopBarExtras}。 */
    protected void renderTopBar(GuiGraphics graphics, int baseX, int baseY, int w) {
        if (!showTopBar()) {
            return;
        }
        int barH = topBarHeight();
        ColorScheme c = colors();
        graphics.fill(baseX, baseY, baseX + w, baseY + barH, topBarBackground());

        Font font = Minecraft.getInstance().font;
        // 用 topBarLineHeight() 而不是 font.lineHeight：字体倍率调大后顶栏变高，
        // 若还按 1 倍行高居中，标题会明显偏下
        int lineH = topBarLineHeight();
        int textY = baseY + (barH - lineH) / 2;
        if (showBackButton()) {
            PhoneText.draw(graphics, font, BACK_LABEL, baseX + 4, textY, c.topBarText(), PhoneText.scale(1.0f));
        }
        String t = title();
        if (!t.isEmpty()) {
            PhoneText.draw(graphics, font, t, baseX + (w - PhoneText.width(font, t, PhoneText.scale(1.0f))) / 2f,
                    textY, c.topBarText(), PhoneText.scale(1.0f));
        }
        renderTopBarExtras(graphics, baseX, baseY, w, barH, textY);
    }

    @Override
    public final boolean mouseClicked(double relX, double relY, int button, int action) {
        // 手机坐标 → 先给顶栏自留地
        if (showTopBar() && relY < contentTop()) {
            if (onTopBarClick(relX, relY, button, action)) {
                return true;
            }
            if (showBackButton() && action == GLFW.GLFW_PRESS
                    && relX >= 0 && relX <= BACK_HIT_WIDTH) {
                onBack();
                return true;
            }
        }
        // 内容坐标
        return onContentClick(relX, relY - contentTop(), button, action);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        if (relY < contentTop() || maxScroll() <= 0) {
            return false;
        }
        // 跟手：手势往下（scrollDelta > 0）→ offset 减小 → 内容跟着往下走
        setScrollOffset(scrollOffset - (int) (scrollDelta * scrollSpeed()));
        return true;
    }

    @Override
    public void mouseMoved(double relX, double relY) {
        onContentMoved(relX, relY - contentTop());
    }

    /** 内容区内鼠标移动（内容坐标）。默认什么都不做。 */
    protected void onContentMoved(double localX, double localY) {
    }

    // ==================== 拖动换位（可选） ====================
    //
    // IPhonePage 那边的四个钩子收的是**手机内容区**坐标（顶栏在里面，与 mouseClicked 一致），
    // 而本骨架的所有渲染/命中都在**内容坐标**（原点在顶栏下方）里做。
    // 与 onContentClick 一样，这里再切一刀，子类只需要实现下面四个 protected 方法，
    // 不必自己记着扣 contentTop()（那是"两个坐标系混用"的经典失手点）。

    @Override
    public final boolean beginDragReorder(double relX, double relY) {
        return onDragReorderStart(relX, relY - contentTop());
    }

    @Override
    public final void dragReorderTo(double relX, double relY) {
        onDragReorderMove(relX, relY - contentTop());
    }

    @Override
    public final void endDragReorder(double relX, double relY) {
        onDragReorderEnd(relX, relY - contentTop());
    }

    @Override
    public final void cancelDragReorder() {
        onDragReorderCancel();
    }

    /**
     * 这次按住拖动要不要本页接管（拖动换位的入口）。坐标是<b>内容坐标</b>。
     * <p>默认 false = 不接管，手势照旧是滚动。见 {@link IPhonePage#beginDragReorder}。
     */
    protected boolean onDragReorderStart(double localX, double localY) {
        return false;
    }

    /** 接管中的拖动移动（内容坐标）。 */
    protected void onDragReorderMove(double localX, double localY) {
    }

    /** 接管中的拖动松手 + 落点（内容坐标）。在这里落位。 */
    protected void onDragReorderEnd(double localX, double localY) {
    }

    /** 接管中的拖动被打断：只清临时状态，别落位。 */
    protected void onDragReorderCancel() {
    }
}
