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
 * 不渲染任何内容，只负责：
 * - 捕获键盘输入并转发给隐藏 EditBox
 * - 同步 EditBox 的真实光标位置给 ChatPage
 * - 鼠标点击定位光标（输入框内）
 * - 鼠标点击发送按钮直接发送（输入状态下也生效）
 * - 上下键按视觉行移动光标（真多行编辑体验）
 * <p>
 * ⚠️ 手动移动光标必须用 moveCursorTo(int, boolean)：
 * 第二参数是"是否扩展选区"，传 false 表示同时收拢选区端点，
 * 即"移动光标并清除选区"。1.21.1 的 EditBox 没有 (int) 或 (int, int) 重载。
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
        // ✅ 光标移到末尾；false = 不扩展选区（同时清除选区）
        this.hiddenBox.moveCursorTo(chatPage.getCurrentInputText().length(), false);
        this.hiddenBox.setResponder(chatPage::onInputChanged);
        this.addRenderableWidget(this.hiddenBox);
        syncCursorToChatPage();
    }

    /** 把隐藏 EditBox 的真实光标索引同步给 ChatPage */
    private void syncCursorToChatPage() {
        if (hiddenBox != null) {
            chatPage.setCursorIndex(hiddenBox.getCursorPosition());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 完全透明，无渲染
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车 = 发送
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            chatPage.onInputFinished(this.hiddenBox.getValue(), true);
            if (this.minecraft != null) this.minecraft.setScreen(null);
            return true;
        }
        // ESC = 保留草稿关闭
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            chatPage.onInputFinished(this.hiddenBox.getValue(), false);
            if (this.minecraft != null) this.minecraft.setScreen(null);
            return true;
        }

        // 上方向键：移动到上一视觉行的相同列
        if (keyCode == GLFW.GLFW_KEY_UP) {
            chatPage.moveCursorUp();
            // ✅ false = 不扩展选区，光标和选区端点一起移动
            this.hiddenBox.moveCursorTo(chatPage.getCursorIndex(), false);
            syncCursorToChatPage();
            return true;
        }
        // 下方向键：移动到下一视觉行的相同列
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            chatPage.moveCursorDown();
            this.hiddenBox.moveCursorTo(chatPage.getCursorIndex(), false);
            syncCursorToChatPage();
            return true;
        }

        // 其余按键（含左右键、Backspace、Delete）转发给 EditBox
        if (this.hiddenBox != null) {
            this.hiddenBox.setFocused(true);
            boolean handled = this.hiddenBox.keyPressed(keyCode, scanCode, modifiers);
            syncCursorToChatPage();
            return handled;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.hiddenBox != null) {
            boolean handled = this.hiddenBox.charTyped(codePoint, modifiers);
            syncCursorToChatPage();
            return handled;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 点击发送按钮（输入状态下也能发送）
            if (chatPage.isSendButtonHitScreen(mouseX, mouseY)) {
                chatPage.onInputFinished(this.hiddenBox.getValue(), true);
                if (this.minecraft != null) this.minecraft.setScreen(null);
                return true;
            }

            // 点击输入框区域：定位光标到对应字符
            if (chatPage.isInputAreaHitScreen(mouseX, mouseY)) {
                int cursorIndex = chatPage.getCursorIndexAtScreen(mouseX, mouseY);
                // ✅ false = 清除潜在假选区
                this.hiddenBox.moveCursorTo(cursorIndex, false);
                syncCursorToChatPage();
                return true;
            }

            // 点击其他区域：关闭输入屏幕，保留草稿
            chatPage.onInputFinished(this.hiddenBox.getValue(), false);
            if (this.minecraft != null) this.minecraft.setScreen(null);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 滚轮滚动输入框：向上滚（scrollY > 0）显示更早内容
        if (scrollY != 0) {
            // 每格滚动一行（1 表示向上滚动一行，-1 表示向下滚动一行）
            chatPage.scrollInput((int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        super.onClose();
        chatPage.onInputClosed();
        // 键盘交还给游戏：恢复鼠标抓取。
        // （期 1 已拆除「按住 Alt 释放鼠标」那条旧通路，不存在需要保留正常光标的情形。）
        if (Minecraft.getInstance().screen == null) {
            long window = Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }
}