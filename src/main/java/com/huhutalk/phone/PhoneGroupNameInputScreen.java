package com.huhutalk.phone;

import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.phone.pages.MaidInfoPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 新建分组名的模态输入屏（透明层，键盘由此捕获；手机页在底层继续渲染）。
 * 中心弹窗：标题 + 输入框 + 取消/确认。确认后把新分组写入自定义分组列表，
 * 并把当前女仆分到该组（联系人页据此同步展示）。
 */
public class PhoneGroupNameInputScreen extends Screen {
    private final MaidInfoPage page;

    private EditBox box;
    private int dlgX, dlgY, dlgW, dlgH;
    private int boxX, boxY, boxW, boxH;
    private int btnW, btnH, btnY;
    private int okX, cancelX;
    /** 校验失败时的提示（如重名/空名） */
    private String error = "";

    public PhoneGroupNameInputScreen(MaidInfoPage page) {
        super(Component.literal("新建分组"));
        this.page = page;
    }

    @Override
    protected void init() {
        int w = this.width;
        int h = this.height;
        dlgW = Math.min(260, w - 60);
        dlgH = 110;
        dlgX = (w - dlgW) / 2;
        dlgY = (h - dlgH) / 2 - 20;

        int pad = 14;
        boxX = dlgX + pad;
        boxW = dlgW - pad * 2;
        boxH = 18;
        boxY = dlgY + 30;

        btnW = (dlgW - pad * 3) / 2;
        btnH = 22;
        btnY = dlgY + dlgH - btnH - 12;
        cancelX = dlgX + pad;
        okX = dlgX + dlgW - pad - btnW;

        this.box = new EditBox(this.font, boxX, boxY, boxW, boxH, Component.literal("分组名"));
        this.box.setMaxLength(16);
        this.box.setBordered(false);
        this.box.setTextColor(0xFF333333);
        this.box.setFocused(true);
        this.addRenderableWidget(this.box);
    }

    private void confirm() {
        String name = this.box.getValue() == null ? "" : this.box.getValue().trim();
        if (name.isEmpty()) {
            error = "分组名不能为空";
            return;
        }
        if (!HuhuTalkClientSettings.addCustomGroup(name)) {
            error = "分组已存在或与默认分组重名";
            return;
        }
        // 把当前女仆分到新分组 → 联系人页据此同步展示
        HuhuTalkClientSettings.setMaidGroup(page.getStableId(), name);
        page.onGroupCreated();
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 全屏半透明遮罩 + 居中对话框
        g.fill(0, 0, this.width, this.height, 0x66000000);
        PhoneShapes.fillRoundedRect(g, dlgX, dlgY, dlgW, dlgH, 8, 0xFFFAFAFA);

        g.drawString(this.font, "新建分组", dlgX + 14, dlgY + 10, 0xFF333333);
        // 输入框底（圆角）
        PhoneShapes.fillRoundedRect(g, boxX, boxY, boxW, boxH, boxH / 2, 0xFFFFF5F8);
        // 占位/内容
        String text = this.box.getValue();
        if (text.isEmpty()) {
            g.drawString(this.font, "输入分组名...", boxX + 6, boxY + (boxH - this.font.lineHeight) / 2 + 1, 0xFFC2B0B6, false);
        } else {
            g.drawString(this.font, text, boxX + 6, boxY + (boxH - this.font.lineHeight) / 2 + 1, 0xFF333333, false);
        }
        // 错误提示
        if (!error.isEmpty()) {
            g.drawString(this.font, error, dlgX + 14, btnY - this.font.lineHeight - 4, 0xFFC62828, false);
        }

        // 按钮
        PhoneShapes.fillRoundedRect(g, cancelX, btnY, btnW, btnH, btnH / 2, 0xFFE0E0E0);
        g.drawString(this.font, "取消", cancelX + (btnW - this.font.width("取消")) / 2,
                btnY + (btnH - this.font.lineHeight) / 2 + 1, 0xFF333333, false);
        PhoneShapes.fillRoundedRect(g, okX, btnY, btnW, btnH, btnH / 2, 0xFFDE7E7E);
        g.drawString(this.font, "确定", okX + (btnW - this.font.width("确定")) / 2,
                btnY + (btnH - this.font.lineHeight) / 2 + 1, 0xFFFFFFFF, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.minecraft != null) this.minecraft.setScreen(null);
            return true;
        }
        this.box.setFocused(true);
        return this.box.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        this.box.setFocused(true);
        return this.box.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (mouseX >= okX && mouseX <= okX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                confirm();
                return true;
            }
            if (mouseX >= cancelX && mouseX <= cancelX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                if (this.minecraft != null) this.minecraft.setScreen(null);
                return true;
            }
            // 点击外部也关闭
            if (!(mouseX >= dlgX && mouseX <= dlgX + dlgW && mouseY >= dlgY && mouseY <= dlgY + dlgH)) {
                if (this.minecraft != null) this.minecraft.setScreen(null);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        super.onClose();
        // 键盘交还给游戏：恢复鼠标抓取
        // （期 1 已拆除「按住 Alt 释放鼠标」那条旧通路，不存在需要保留正常光标的情形。）
        if (Minecraft.getInstance().screen == null) {
            long window = Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }
}
