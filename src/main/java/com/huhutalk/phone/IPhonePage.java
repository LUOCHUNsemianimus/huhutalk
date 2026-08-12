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
    /** 鼠标滚轮：scrollDelta 正值为向下滚动 */
    default boolean mouseScrolled(double relX, double relY, double scrollDelta) { return false; }
    /** 鼠标移动：可用于高亮判定 */
    default void mouseMoved(double relX, double relY) {}
}