package com.huhutalk.phone;

import com.huhutalk.api.phone.ui.PhoneTextInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * <b>完全透明</b>的键盘捕获屏——只为了让手机上的内联输入控件（{@link PhoneTextInput}）能收到键盘。
 *
 * <h2>为什么必须有它</h2>
 * 手机页面走的是 {@code IPhonePage}，那套接口<b>没有任何键盘回调</b>：
 * 手机 UI 是画在一个 GUI 层上的，键盘事件在原版里只会送给当前 {@code Screen}。
 * 所以"在手机页面里直接打字"在原版机制下不可能——既有页面全都是开一个 Screen 抢键盘
 * （聊天页 {@code PhoneInputScreen}、联系人页 {@code PhoneGroupNameInputScreen}）。
 *
 * <h2>和它们的不同：这里什么都不画</h2>
 * 那两个屏都画了自己的弹窗。本屏<b>一个像素都不画</b>：手机界面继续在底下渲染，
 * 玩家看到的就是"设置页上那一格在闪光标、我直接往里打字"，体验上是内联编辑，
 * 实现上还是 Screen 捕获键盘。
 *
 * <h2>结束方式</h2>
 * <ul>
 *   <li>{@code Enter}：提交</li>
 *   <li>{@code Esc}：取消（回滚到编辑前的值）</li>
 *   <li>鼠标左键点任意处：提交（Screen 打开期间手机本身不被驱动，所以不会有别的点击目标）</li>
 * </ul>
 */
public class PhoneTextCaptureScreen extends Screen {

    private final PhoneTextInput target;
    private EditBox box;

    public PhoneTextCaptureScreen(PhoneTextInput target) {
        super(Component.literal("输入"));
        this.target = target;
    }

    @Override
    protected void init() {
        // 用 addWidget（而不是 addRenderableWidget）：只进 children 收事件，不进 renderables，
        // 于是它一个像素都不会被画出来。
        this.box = new EditBox(this.font, 0, 0, 1, 1, Component.empty());
        this.box.setMaxLength(target.maxLengthValue());
        this.box.setBordered(false);
        this.box.setValue(target.value() == null ? "" : target.value());
        this.box.setFocused(true);
        this.addWidget(this.box);
        target.beginEdit();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 刻意不画任何东西：手机界面在底下的 GUI 层里继续渲染。
        // 也刻意不调 super.render —— 那会去画 renderables（虽然本屏为空，但语义上不需要）。
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            finish(true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            finish(false);
            return true;
        }
        this.box.setFocused(true);
        boolean handled = this.box.keyPressed(keyCode, scanCode, modifiers);
        target.updateEditingText(this.box.getValue());
        return handled;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        this.box.setFocused(true);
        boolean handled = this.box.charTyped(codePoint, modifiers);
        target.updateEditingText(this.box.getValue());
        return handled;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            finish(true);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 提交或取消，然后关掉本屏（走 {@link #onClose()} 的统一收尾）。 */
    private void finish(boolean commit) {
        target.endEdit(commit);
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        // 若玩家用别的方式关了屏（例如切换界面），当作提交，避免输入被静默丢弃。
        // endEdit 内部幂等：focused 已经为 false 时不会重复回调。
        if (target.focused()) {
            target.endEdit(true);
        }
        super.onClose();
        // 键盘交还给游戏：恢复鼠标抓取（与 PhoneGroupNameInputScreen 同一处理）
        if (Minecraft.getInstance().screen == null) {
            long window = Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }
}
