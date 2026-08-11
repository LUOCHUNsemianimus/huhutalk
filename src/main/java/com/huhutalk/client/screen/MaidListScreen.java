package com.huhutalk.client.screen;

import com.huhutalk.client.IPhoneScreen;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.PhoneScreenManager;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class MaidListScreen extends Screen implements IPhoneScreen {
    private List<S2CMaidListPacket.MaidDisplayInfo> maidList = new ArrayList<>();
    private boolean requested = false;
    private boolean needRebuild = false;        // 防止每帧重建

    public MaidListScreen() {
        super(Component.literal("女仆会话列表"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        if (!requested) {
            PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
            requested = true;
        }
        // 初始构建按钮
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 仅在外部触发重建时才刷新按钮，避免每帧检查
        if (needRebuild) {
            rebuildWidgets();
            needRebuild = false;
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, Component.literal("女仆会话列表"), width / 2, 30, 0xFFFFFF);
        if (maidList.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.literal("你还没有女仆"), width / 2, height / 2, 0xFFAAAA);
        }

        // 绘制未读红点
        int buttonWidth = 200;
        int x = (this.width - buttonWidth) / 2;
        int startY = 60;
        int spacing = 25;
        for (int i = 0; i < maidList.size(); i++) {
            S2CMaidListPacket.MaidDisplayInfo info = maidList.get(i);
            int unread = NotificationManager.getUnread(info.entityId());
            if (unread > 0) {
                int dotX = x + buttonWidth + 5;
                int dotY = startY + i * spacing + 5;
                guiGraphics.fill(dotX, dotY, dotX + 8, dotY + 8, 0xFFFF0000);
            }
        }

        guiGraphics.drawCenteredString(font, Component.literal("按 鼠标右键 或 ← 返回"), width / 2, height - 20, 0xFFAAAA);
    }

    @Override
    protected void rebuildWidgets() {
        // 不调用 super.rebuildWidgets()，避免递归
        clearWidgets();
        int startY = 60;
        int spacing = 25;
        int buttonWidth = 200;
        int x = (this.width - buttonWidth) / 2;

        for (int i = 0; i < maidList.size(); i++) {
            S2CMaidListPacket.MaidDisplayInfo info = maidList.get(i);
            int y = startY + i * spacing;

            boolean online = info.online();
            Component name = Component.literal(info.name());
            if (!online) {
                name = name.copy().withStyle(ChatFormatting.GRAY);
            }

            Button button = Button.builder(name, btn -> {
                if (online) {
                    NotificationManager.clear(info.entityId());
                    PhoneScreenManager.pushScreen(new MaidChatScreen(info.entityId(), info.name()));
                }
            }).pos(x, y).size(buttonWidth, 20).build();

            button.active = online;   // 离线不可点击
            this.addRenderableWidget(button);
        }

        // 左上角返回按钮
        this.addRenderableWidget(Button.builder(
                Component.literal("←"),
                (btn) -> PhoneScreenManager.popScreen()
        ).pos(5, 5).size(20, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        List<S2CMaidListPacket.MaidDisplayInfo> current = MaidListCache.getList();
        if (!current.equals(maidList)) {
            maidList = current;
            needRebuild = true;             // 标记需要重建，下一帧渲染前执行
        }
    }

    // ==================== 统一关闭逻辑 ====================
    @Override
    public void onClose() {
        if (PhoneScreenManager.isClosingByManager()) {
            // 由管理器主动关闭（隐藏或切换），直接调用原版清理
            super.onClose();
        } else {
            // 意外关闭（如玩家按原版 Esc），通知管理器隐藏
            PhoneScreenManager.closePhone();
            super.onClose();
        }
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