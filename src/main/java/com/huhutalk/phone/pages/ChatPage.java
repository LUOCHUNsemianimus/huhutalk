package com.huhutalk.phone.pages;

import com.huhutalk.chat.ChatMessage;
import com.huhutalk.chat.MessageManager;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.UUID;

/**
 * 聊天页面。
 * 支持消息显示、输入框激活、发送消息（暂时模拟女仆回复）。
 */
public class ChatPage implements IPhonePage {
    private final UUID maidUuid;
    private final String maidName;
    private List<ChatMessage> messages;
    private String currentInput = "";
    private boolean inputActive = false;

    public ChatPage(MaidEntry maid) {
        this.maidUuid = maid.uuid();
        this.maidName = maid.name();
        this.messages = MessageManager.getMessages(maidUuid);
        if (this.messages.isEmpty()) {
            MessageManager.addMessage(maidUuid,
                    new ChatMessage(new UUID(0, 0), "系统", "开始聊天吧！", ChatMessage.SenderType.MAID));
            this.messages = MessageManager.getMessages(maidUuid);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);
        int inputH = (int)(h * 0.1);

        // 顶部导航栏
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, 0xFF333333);
        // 返回按钮
        graphics.drawString(Minecraft.getInstance().font, "< 返回",
                baseX + 4, baseY + 3, 0xFFFFFFFF);
        // 昵称
        int nameW = Minecraft.getInstance().font.width(maidName);
        graphics.drawString(Minecraft.getInstance().font, maidName,
                baseX + (w - nameW) / 2, baseY + 3, 0xFFFFFFFF);
        // 设置图标
        graphics.drawString(Minecraft.getInstance().font, "⚙",
                baseX + w - 20, baseY + 3, 0xFFFFAA00);

        // 消息区域
        int msgTop = baseY + navH;
        int msgBottom = baseY + h - inputH;
        graphics.enableScissor(baseX, msgTop, baseX + w, msgBottom);
        drawMessages(graphics, baseX, msgTop, msgBottom - msgTop);
        graphics.disableScissor();

        // 输入框区域
        int inputY = baseY + h - inputH;
        graphics.fill(baseX + 5, inputY + 3, baseX + w - 5, inputY + inputH - 3,
                inputActive ? 0xFF555555 : 0xFF222222);
        // 表情按钮
        graphics.fill(baseX + 10, inputY + 6, baseX + 30, inputY + inputH - 6, 0xFFFFCC00);
        graphics.drawString(Minecraft.getInstance().font, "😊", baseX + 14, inputY + 8, 0xFF000000);
        // 文本预览
        String display = currentInput.isEmpty() ? "输入消息..." : currentInput;
        int textColor = currentInput.isEmpty() ? 0xFF888888 : 0xFFFFFFFF;
        graphics.drawString(Minecraft.getInstance().font, display,
                baseX + 35, inputY + 8, textColor);
        // 发送按钮
        int sendX = baseX + w - 45;
        graphics.fill(sendX, inputY + 6, sendX + 30, inputY + inputH - 6, 0xFF00AA00);
        graphics.drawString(Minecraft.getInstance().font, "发送", sendX + 3, inputY + 8, 0xFFFFFFFF);
    }

    private void drawMessages(GuiGraphics graphics, int baseX, int topY, int height) {
        Minecraft mc = Minecraft.getInstance();
        int lineHeight = mc.font.lineHeight + 4;
        int y = topY + height - lineHeight;
        for (int i = messages.size() - 1; i >= 0 && y >= topY; i--) {
            ChatMessage msg = messages.get(i);
            boolean isPlayer = msg.getType() == ChatMessage.SenderType.PLAYER;
            String text = msg.getSenderName() + ": " + msg.getContent();
            int w = PhoneOverlay.getPhoneWidth();
            int maxWidth = w - 20;
            if (mc.font.width(text) > maxWidth) {
                // 简单截断，未来可自动换行
                text = mc.font.plainSubstrByWidth(text, maxWidth - 10) + "...";
            }
            int x = isPlayer ? baseX + w - mc.font.width(text) - 6 : baseX + 6;
            int color = isPlayer ? 0xFF88FF88 : 0xFFFFFFFF;
            graphics.drawString(mc.font, text, x, y, color);
            y -= lineHeight;
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = (int)(h * 0.08);
        int inputH = (int)(h * 0.1);

        // 返回按钮
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH) {
            PhoneOverlay.popPage();
            return true;
        }

        int inputTop = h - inputH;
        // 检查输入栏区域
        if (relY >= inputTop + 3 && relY <= inputTop + inputH - 3) {
            // 表情按钮
            if (relX >= 10 && relX <= 30) {
                // TODO: 打开表情面板
                return true;
            }
            // 发送按钮
            int sendX = w - 45;
            if (relX >= sendX && relX <= sendX + 30) {
                sendMessage();
                return true;
            }
            // 其他区域：激活输入
            activateInput();
            return true;
        }
        return false;
    }

    private void activateInput() {
        inputActive = true;
        int screenX = PhoneOverlay.getPhoneX() + 35;  // 相对手机坐标
        int screenY = PhoneOverlay.getPhoneY() + PhoneOverlay.getPhoneHeight() - (int)(PhoneOverlay.getPhoneHeight() * 0.1) + 2;
        Minecraft.getInstance().setScreen(new PhoneInputScreen(this, currentInput, screenX, screenY));
    }

    public void onInputFinished(String text, boolean send) {
        inputActive = false;
        currentInput = text;
        if (send && !currentInput.isBlank()) {
            sendMessage();
            currentInput = "";
        }
    }

    public String getCurrentInputText() {
        return currentInput;
    }

    private void sendMessage() {
        if (currentInput.isBlank()) return;
        ChatMessage playerMsg = new ChatMessage(
                Minecraft.getInstance().player.getUUID(),
                Minecraft.getInstance().player.getName().getString(),
                currentInput,
                ChatMessage.SenderType.PLAYER
        );
        MessageManager.addMessage(maidUuid, playerMsg);
        // 模拟女仆回复
        new Thread(() -> {
            try { Thread.sleep(600 + (int)(Math.random()*1200)); } catch (InterruptedException ignored) {}
            ChatMessage reply = new ChatMessage(maidUuid, maidName,
                    "收到！（自动回复）", ChatMessage.SenderType.MAID);
            MessageManager.addMessage(maidUuid, reply);
        }).start();
        currentInput = "";
        messages = MessageManager.getMessages(maidUuid);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 消息列表滚动，简单处理：调整绘制起始索引，当前实现为从底部绘制不受滚动影响
        return true;
    }
}