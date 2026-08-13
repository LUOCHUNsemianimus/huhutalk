package com.huhutalk.phone;

import com.huhutalk.phone.pages.ChatPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 手机输入屏幕（透明层）。
 * 不渲染任何内容，只负责捕获键盘输入并同步给 ChatPage。
 * EditBox 放在屏幕外，视觉上完全无感。
 */
public class PhoneInputScreen extends Screen {
    private final ChatPage chatPage;
    private EditBox hiddenBox;

    public PhoneInputScreen(ChatPage chatPage) {
        super(Component.literal("手机输入"));
        this.chatPage = chatPage;
    }

    @Override
    protected void init() {
        this.hiddenBox = new EditBox(this.font, -1000, -1000, 200, 20, Component.literal(""));
        this.hiddenBox.setMaxLength(512);
        this.hiddenBox.setBordered(false);
        this.hiddenBox.setTextColor(0x00000000);
        this.hiddenBox.setFocused(true);
        this.hiddenBox.setValue(chatPage.getCurrentInputText());
        this.hiddenBox.setResponder(chatPage::onInputChanged);
        this.addRenderableWidget(this.hiddenBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 完全透明
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            chatPage.onInputFinished(this.hiddenBox.getValue(), true);
            if (this.minecraft != null) this.minecraft.setScreen(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            chatPage.onInputFinished(this.hiddenBox.getValue(), false);
            if (this.minecraft != null) this.minecraft.setScreen(null);
            return true;
        }
        // ✅ 关键修复：把按键（包括 Backspace/Delete）转发给隐藏输入框
        if (this.hiddenBox != null) {
            this.hiddenBox.setFocused(true); // 确保始终有焦点
            return this.hiddenBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.hiddenBox != null) {
            return this.hiddenBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        chatPage.onInputClosed();
        if (Minecraft.getInstance().screen == null) {
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (PhoneOverlay.shouldCaptureMouse()) {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            } else {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            }
        }
    }
}