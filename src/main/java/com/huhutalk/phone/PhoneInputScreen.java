package com.huhutalk.phone;

import com.huhutalk.phone.pages.ChatPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class PhoneInputScreen extends Screen {
    private final ChatPage chatPage;
    private EditBox editBox;
    private final int targetX, targetY;

    public PhoneInputScreen(ChatPage chatPage, String initialText, int screenX, int screenY) {
        super(Component.literal("手机输入"));
        this.chatPage = chatPage;
        this.targetX = screenX;
        this.targetY = screenY;
    }

    @Override
    protected void init() {
        // 1.21.1 不再需要设置 sendRepeatsToGui，已默认支持
        this.editBox = new EditBox(this.font, targetX, targetY, 160, 16, Component.literal(""));
        this.editBox.setMaxLength(256);
        this.editBox.setValue(chatPage.getCurrentInputText());
        this.editBox.setFocused(true);
        this.addRenderableWidget(this.editBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.editBox.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            chatPage.onInputFinished(this.editBox.getValue(), true);
            this.minecraft.setScreen(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            chatPage.onInputFinished(this.editBox.getValue(), false);
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        // 注意：1.21.1 不需要恢复 sendRepeatsToGui
        // 恢复光标模式
        if (Minecraft.getInstance().screen == null) {
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (PhoneOverlay.shouldCaptureMouse()) {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            } else {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            }
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return super.charTyped(codePoint, modifiers);
    }
}