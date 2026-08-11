package com.huhutalk.client.screen;

import com.huhutalk.client.IPhoneScreen;
import com.huhutalk.client.PhoneScreenManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class PhoneHomeScreen extends Screen implements IPhoneScreen {
    public PhoneHomeScreen() {
        super(Component.literal("HUHUTALK 手机桌面"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        int btnWidth = 140;
        int btnHeight = 24;
        int x = (this.width - btnWidth) / 2;
        int y = this.height / 2;

        this.addRenderableWidget(Button.builder(
                Component.literal("打开 HUHUTALK"),
                (btn) -> PhoneScreenManager.pushScreen(new MaidListScreen())
        ).pos(x, y).size(btnWidth, btnHeight).build());

        // 左上角返回按钮 → popScreen
        this.addRenderableWidget(Button.builder(
                Component.literal("←"),
                (btn) -> PhoneScreenManager.popScreen()
        ).pos(5, 5).size(20, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int panelWidth = 240;
        int panelHeight = 360;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xCC121212);
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("手机桌面"),
                this.width / 2,
                panelY + 20,
                0xFFFFFF
        );
    }

    @Override
    public void onClose() {
        // 隐藏手机（保留栈）
        PhoneScreenManager.closePhone();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && this.getChildAt(mouseX, mouseY).isEmpty()) {
            PhoneScreenManager.popScreen();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}