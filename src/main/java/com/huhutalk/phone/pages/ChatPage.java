package com.huhutalk.phone.pages;

import com.huhutalk.chat.ChatMessage;
import com.huhutalk.chat.MessageManager;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 聊天页面，支持键盘/鼠标操作，Alt+点击激活输入框。
 */
public class ChatPage implements IPhonePage {
    private final UUID maidUuid;
    private final String maidName;
    private List<ChatMessage> messages;
    private int scrollPosition = 0; // 消息列表滚动偏移（向上为正）
    private int maxScroll = 0;

    private String currentInput = ""; // 输入框文本缓冲
    private boolean inputActive = false; // 输入框是否被激活（高亮）

    public ChatPage(MaidEntry maid) {
        this.maidUuid = maid.uuid();
        this.maidName = maid.name();
        this.messages = MessageManager.getMessages(maidUuid);
        // 添加测试消息（如果为空）
        if (this.messages.isEmpty()) {
            MessageManager.addMessage(maidUuid, new ChatMessage(new UUID(0, 0), "系统", "开始聊天吧！", ChatMessage.SenderType.MAID));
            this.messages = MessageManager.getMessages(maidUuid);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        // 顶部导航
        graphics.fill(baseX, baseY, baseX + 220, baseY + 25, 0xFF333333);
        graphics.drawString(Minecraft.getInstance().font, "< 返回", baseX + 5, baseY + 5, 0xFFFFFFFF);
        graphics.drawString(Minecraft.getInstance().font, maidName, baseX + 60, baseY + 5, 0xFFFFFFFF);
        // 设置图标（⚙）
        graphics.drawString(Minecraft.getInstance().font, "⚙", baseX + 195, baseY + 5, 0xFFFFAA00);

        // 消息区域 (y: 25 ~ 300)
        int msgAreaTop = baseY + 25;
        int msgAreaBottom = baseY + 300;
        int msgAreaHeight = msgAreaBottom - msgAreaTop;
        graphics.enableScissor(baseX, msgAreaTop, baseX + 220, msgAreaBottom); // 裁剪
        drawMessages(graphics, baseX, msgAreaTop, msgAreaHeight);
        graphics.disableScissor();

        // 输入框区域 (y: 305 ~ 335)
        int inputY = baseY + 305;
        graphics.fill(baseX + 5, inputY, baseX + 215, inputY + 25, inputActive ? 0xFF555555 : 0xFF222222);
        // 表情按钮
        graphics.fill(baseX + 10, inputY + 3, baseX + 30, inputY + 23, 0xFFFFCC00);
        graphics.drawString(Minecraft.getInstance().font, "😊", baseX + 14, inputY + 6, 0xFF000000);
        // 输入文本区
        String displayText = currentInput.isEmpty() ? "输入消息..." : currentInput;
        int textColor = currentInput.isEmpty() ? 0xFF888888 : 0xFFFFFFFF;
        graphics.drawString(Minecraft.getInstance().font, displayText, baseX + 40, inputY + 6, textColor);
        // 发送按钮
        graphics.fill(baseX + 185, inputY + 3, baseX + 210, inputY + 23, 0xFF00AA00);
        graphics.drawString(Minecraft.getInstance().font, "发送", baseX + 188, inputY + 6, 0xFFFFFFFF);
    }

    private void drawMessages(GuiGraphics graphics, int baseX, int topY, int height) {
        int lineHeight = Minecraft.getInstance().font.lineHeight + 4;
        int y = topY + height - 5; // 从底部开始画
        int drawn = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            boolean isPlayer = (msg.getType() == ChatMessage.SenderType.PLAYER);
            String text = msg.getSenderName() + ": " + msg.getContent();
            // 简单处理：每行最多宽度180，但这里不自动换行，仅单行显示（可后续优化）
            int textWidth = Minecraft.getInstance().font.width(text);
            if (textWidth > 190) text = text.substring(0, 17) + "..."; // 简单截断
            int x = isPlayer ? baseX + 220 - textWidth - 10 : baseX + 10;
            int color = isPlayer ? 0xFF88FF88 : 0xFFFFFFFF;
            y -= lineHeight;
            graphics.drawString(Minecraft.getInstance().font, text, x, y, color);
            drawn++;
            if (y < topY) break;
        }
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false; // 只处理按下

        // 返回按钮
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= 25) {
            PhoneOverlay.popPage();
            return true;
        }

        // 输入框激活区域（5~215, 305~330）
        if (relX >= 5 && relX <= 215 && relY >= 305 && relY <= 330) {
            // 表情按钮细分
            if (relX >= 10 && relX <= 30) {
                // TODO: 打开表情面板
                return true;
            }
            // 发送按钮细分
            if (relX >= 185 && relX <= 210) {
                sendMessage();
                return true;
            }
            // 其余区域：激活输入框
            activateInput();
            return true;
        }
        return false;
    }

    private void activateInput() {
        inputActive = true;
        // 计算输入框在屏幕上的全局坐标
        int screenX = PhoneOverlay.getPhoneX() + 40; // 相对于手机
        int screenY = PhoneOverlay.getPhoneY() + 306;
        Minecraft.getInstance().setScreen(new PhoneInputScreen(this, currentInput, screenX, screenY));
    }

    // 由 PhoneInputScreen 回调
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
        // 玩家消息
        ChatMessage playerMsg = new ChatMessage(
                Minecraft.getInstance().player.getUUID(),
                Minecraft.getInstance().player.getName().getString(),
                currentInput,
                ChatMessage.SenderType.PLAYER
        );
        MessageManager.addMessage(maidUuid, playerMsg);
        // 模拟女仆回复（后续替换为网络）
        new Thread(() -> {
            try { Thread.sleep(500 + (int)(Math.random()*1000)); } catch (InterruptedException e) {}
            ChatMessage maidReply = new ChatMessage(maidUuid, maidName, "收到！", ChatMessage.SenderType.MAID);
            MessageManager.addMessage(maidUuid, maidReply);
        }).start();
        currentInput = "";
        messages = MessageManager.getMessages(maidUuid);
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        // 滚动消息区域
        int lineHeight = Minecraft.getInstance().font.lineHeight + 4;
        scrollPosition -= (int) (scrollDelta * lineHeight);
        // 暂不处理，消息绘制从底部开始，滚动通过改变起始索引，先简化
        return true;
    }
}