package com.huhutalk.phone;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 手机内的所有页面必须实现此接口。
 * 鼠标坐标均相对于手机左上角。
 */
public interface IPhonePage {
    /** 页面被推入栈时调用 */
    default void onOpen() {}
    /** 页面被弹出栈时调用 */
    default void onClose() {}

    /** 每帧渲染，baseX/baseY 为手机在屏幕上的左上角坐标 */
    void render(GuiGraphics graphics, int baseX, int baseY, float partialTick);

    /** 鼠标点击：action = GLFW_PRESS(1) 或 GLFW_RELEASE(0) */
    default boolean mouseClicked(double relX, double relY, int button, int action) { return false; }

    /**
     * 鼠标滚轮 / 侧键按住拖动：<b>scrollDelta 正值 = 手势往下（或往右）</b>。
     * <p>
     * 注意这跟"内容往哪边跑"不是一回事：各面板按自己的渲染约定换算（见 ChatPage 里三处 mouseScrolled）。
     * 约定写着"手势方向"是刻意的 —— 唯一喂入这条通路的是<b>拖动</b>（{@code PhoneOverlay.moveCursor}），
     * 而拖动必须跟手：鼠标往下走，内容就得跟着往下走。
     */
    default boolean mouseScrolled(double relX, double relY, double scrollDelta) { return false; }
    /** 鼠标移动：可用于高亮判定 */
    default void mouseMoved(double relX, double relY) {}

    /**
     * 「按住拖动」手势在 (relX,relY) 处的<b>主轴是不是横向</b>（默认纵向）。
     * <p>
     * 只有横向画廊这类面板需要重写（聊天页的图库扩展栏）：默认的纵向判据会把
     * "左右滑"当成"上下滚"，于是玩家横着拖半天，画面纹丝不动。
     * 坐标与 {@link #mouseClicked} 一致：相对<b>手机内容区</b>左上角（不含顶部状态栏）。
     * <p>
     * 为什么放在页面而不是 Overlay：主轴取决于"鼠标此刻落在哪个面板里"，
     * 那是页面的排版知识，Overlay 不该知道聊天页的面板在哪。
     */
    default boolean dragAxisHorizontal(double relX, double relY) { return false; }

    /**
     * 右键返回。页面可在此处理"多级返回"（如手册子栏目逐级回退）。
     * 返回 true 表示已消费该右键（上层不再执行 popPage/关闭手机）；false 则交给上层默认逻辑。
     */
    default boolean onBackPressed() { return false; }
}