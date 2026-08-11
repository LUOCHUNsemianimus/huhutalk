package com.huhutalk.client.screen;

import com.huhutalk.client.IPhoneScreen;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.PhoneScreenManager;
import com.huhutalk.network.C2SMaidChatPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MaidChatScreen extends Screen implements IPhoneScreen {
    private static final int CHAT_AREA_TOP = 40;
    private static final int CHAT_AREA_BOTTOM = 160;
    private static final int CHAT_AREA_LEFT = 10;
    private static final int CHAT_AREA_RIGHT = 10;

    private final UUID maidUuid;
    private final String maidName;
    private final List<ChatMessage> messages;
    private EditBox inputBox;
    private int scrollOffset = 0;
    private boolean firstRender = true;

    public MaidChatScreen(UUID maidUuid, String maidName) {
        super(Component.literal("与 " + maidName + " 聊天"));
        this.maidUuid = maidUuid;
        this.maidName = maidName;
        this.messages = new ArrayList<>(ChatHistoryStorage.load(maidUuid));
    }

    public UUID getMaidUuid() {
        return maidUuid;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        NotificationManager.clear(maidUuid);

        int inputWidth = 140;
        int buttonWidth = 35;
        int totalWidth = inputWidth + buttonWidth + 5;
        int startX = this.width / 2 - totalWidth / 2;
        int y = this.height - 30;
        inputBox = new EditBox(this.font, startX, y, inputWidth, 20, Component.literal(""));
        inputBox.setMaxLength(256);
        addRenderableWidget(inputBox);
        addRenderableWidget(Button.builder(Component.literal("发送"), btn -> sendMessage())
                .pos(startX + inputWidth + 5, y)
                .size(buttonWidth, 20)
                .build());

        // 左上角返回按钮
        this.addRenderableWidget(Button.builder(
                Component.literal("←"),
                (btn) -> {
                    ChatHistoryStorage.save(maidUuid, messages);
                    PhoneScreenManager.popScreen();
                }
        ).pos(5, 5).size(20, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // ✅ 修复③：首次渲染时滚动到底部
        if (firstRender) {
            firstRender = false;
            scrollToBottom();
        }

        int panelLeft = CHAT_AREA_LEFT;
        int panelRight = this.width - CHAT_AREA_RIGHT;
        guiGraphics.fill(panelLeft, CHAT_AREA_TOP, panelRight, CHAT_AREA_BOTTOM, 0x88000000);
        guiGraphics.drawCenteredString(this.font, "与 " + maidName + " 聊天", this.width / 2, 15, 0xFFFFFF);
        renderChatMessages(guiGraphics);
    }

    private void renderChatMessages(GuiGraphics guiGraphics) {
        int lineHeight = font.lineHeight + 2;
        int maxLines = (CHAT_AREA_BOTTOM - CHAT_AREA_TOP) / lineHeight;

        int totalLines = 0;
        for (ChatMessage msg : messages) {
            totalLines += splitMessage(msg.text).size();
        }
        int maxScroll = Math.max(0, totalLines - maxLines);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int yBase = CHAT_AREA_TOP + 5;
        int currentLine = 0;
        for (ChatMessage msg : messages) {
            List<FormattedCharSequence> lines = splitMessage(msg.text);
            for (FormattedCharSequence line : lines) {
                if (currentLine < scrollOffset) {
                    currentLine++;
                    continue;
                }
                if (currentLine >= scrollOffset + maxLines) {
                    return;
                }
                int drawY = yBase + (currentLine - scrollOffset) * lineHeight;
                int color = msg.isError ? 0xFF5555 : 0xCCCCCC;
                if (msg.isPlayer) {
                    int textWidth = font.width(line);
                    int x = this.width - CHAT_AREA_RIGHT - textWidth - 5;
                    guiGraphics.drawString(font, line, x, drawY, 0xAADDFF, false);
                } else {
                    int x = CHAT_AREA_LEFT + 5;
                    guiGraphics.drawString(font, line, x, drawY, color, false);
                }
                currentLine++;
            }
        }
    }

    private List<FormattedCharSequence> splitMessage(String text) {
        int maxWidth = (this.width - CHAT_AREA_LEFT - CHAT_AREA_RIGHT) / 2 - 10;
        return font.split(Component.literal(text), maxWidth);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendMessage();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // ❌ 删除这行：ChatHistoryStorage.save(maidUuid, messages);
            PhoneScreenManager.closePhone();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendMessage() {
        String text = inputBox.getValue().trim();
        if (text.isEmpty()) return;
        inputBox.setValue("");
        messages.add(new ChatMessage(text, true, false));
        PacketDistributor.sendToServer(new C2SMaidChatPacket(maidUuid, text));
        scrollToBottom();
        ChatHistoryStorage.save(maidUuid, messages);   // ✅ 保留
    }

    public void onReceiveMessage(UUID uuid, String text) {
        if (!this.maidUuid.equals(uuid)) return;
        messages.add(new ChatMessage(text, false, false));
        scrollToBottom();
        ChatHistoryStorage.save(maidUuid, messages);   // ✅ 保留
    }

    public void onReceiveError(UUID uuid, String error) {
        if (!this.maidUuid.equals(uuid)) return;
        messages.add(new ChatMessage("[错误] " + error, false, true));
        scrollToBottom();
        ChatHistoryStorage.save(maidUuid, messages);   // ✅ 保留
    }
    private void scrollToBottom() {
        int lineHeight = font.lineHeight + 2;
        int maxLines = (CHAT_AREA_BOTTOM - CHAT_AREA_TOP) / lineHeight;
        int totalLines = 0;
        for (ChatMessage msg : messages) totalLines += splitMessage(msg.text).size();
        scrollOffset = Math.max(0, totalLines - maxLines);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset -= (int) (scrollY * 3);
        int lineHeight = font.lineHeight + 2;
        int maxLines = (CHAT_AREA_BOTTOM - CHAT_AREA_TOP) / lineHeight;
        int totalLines = 0;
        for (ChatMessage msg : messages) totalLines += splitMessage(msg.text).size();
        int maxScroll = Math.max(0, totalLines - maxLines);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && this.getChildAt(mouseX, mouseY).isEmpty()) {
            // ❌ 删除这行：ChatHistoryStorage.save(maidUuid, messages);
            PhoneScreenManager.popScreen();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------- 内部数据类 ----------
    public static class ChatMessage {
        public final String text;
        public final boolean isPlayer;
        public final boolean isError;

        public ChatMessage(String text, boolean isPlayer, boolean isError) {
            this.text = text;
            this.isPlayer = isPlayer;
            this.isError = isError;
        }
    }
}