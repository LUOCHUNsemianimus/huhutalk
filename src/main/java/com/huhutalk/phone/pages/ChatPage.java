package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天页面：
 * - 玩家消息：蓝底白字，气泡靠右，右侧头像占位
 * - 女仆消息：白底深灰字，气泡靠左，左侧头像占位
 * - 输入框支持换行、最多显示3行自动滚动、闪烁光标
 * - 草稿自动保存/恢复（按世界隔离）
 */
public class ChatPage implements IPhonePage {
    private static final int AVATAR = 12;      // 头像占位大小
    private static final int MARGIN = 6;       // 页面内边距
    private static final int MAX_VISIBLE_INPUT_LINES = 3; // 输入框最多显示行数

    private final UUID maidUuid;
    private final String maidName;
    private final List<ChatMessage> messages = new ArrayList<>();
    private String currentInput = "";
    private boolean inputActive = false;

    public ChatPage(UUID maidUuid, String maidName) {
        this.maidUuid = maidUuid;
        this.maidName = maidName;
        // 加载历史（包含玩家自己发的话）
        for (ChatHistoryStorage.ChatMessageEntry e : ChatHistoryStorage.getMessages(maidUuid)) {
            messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError()));
        }
        // 恢复草稿
        this.currentInput = ChatHistoryStorage.getDraft(maidUuid);
        // 进入页面即清除未读
        NotificationManager.clear(maidUuid);
    }

    public UUID getMaidUuid() { return maidUuid; }

    public String getCurrentInputText() { return currentInput; }

    /** 输入框实时更新（由 PhoneInputScreen 调用） */
    public void onInputChanged(String text) {
        this.currentInput = text;
        ChatHistoryStorage.setDraft(maidUuid, text); // 实时保存草稿
    }

    /** 输入结束：send=true 则发送 */
    public void onInputFinished(String text, boolean send) {
        this.inputActive = false;
        this.currentInput = text;
        if (send && !text.isBlank()) {
            sendCurrentMessage();
        } else {
            ChatHistoryStorage.setDraft(maidUuid, text); // 不发送也保留草稿
        }
    }

    /** 输入屏幕关闭（ESC 等） */
    public void onInputClosed() {
        this.inputActive = false;
    }

    /** 页面被隐藏（关手机）时保存草稿 */
    public void onPageHide() {
        ChatHistoryStorage.setDraft(maidUuid, currentInput);
    }

    /** 服务器回复到达（由 ClientPayloadSetup 调用） */
    public void onReceiveMessage(String text, boolean isError) {
        messages.add(new ChatMessage(text, false, isError));
    }

    private void sendCurrentMessage() {
        if (currentInput.isBlank()) return;
        String text = currentInput;
        // 1. 本地显示
        messages.add(new ChatMessage(text, true, false));
        // 2. 保存玩家消息
        ChatHistoryStorage.appendMessage(maidUuid, text, true, false);
        // 3. 清除草稿
        ChatHistoryStorage.setDraft(maidUuid, "");
        // 4. 发送网络包
        PacketDistributor.sendToServer(new C2SMaidChatPacket(maidUuid, text));
        currentInput = "";
    }

    // ============ 布局常量（渲染与点击共用） ============
    private int phoneW() { return PhoneOverlay.getPhoneWidth(); }
    private int phoneH() { return PhoneOverlay.getPhoneHeight(); }
    private int navH() { return (int)(phoneH() * 0.08); }

    /** 输入框高度：至少容纳 3 行文字 */
    private int inputH() {
        int base = (int)(phoneH() * 0.10);
        int min = Minecraft.getInstance().font.lineHeight * MAX_VISIBLE_INPUT_LINES + 8;
        return Math.max(base, min);
    }

    private int inputTop(int baseY) { return baseY + phoneH() - inputH(); }
    private int inputLeft(int baseX) { return baseX + 5; }
    private int inputRight(int baseX) { return baseX + phoneW() - 5; }
    private int sendX(int baseX) { return baseX + phoneW() - 48; }       // 发送按钮左缘
    private int sendY(int baseY) { return inputTop(baseY) + 4; }         // 发送按钮上缘
    private int sendW() { return 40; }
    private int sendH() { return inputH() - 8; }

    /** 光标闪烁：500ms 亮 / 500ms 灭 */
    private boolean isCursorVisible() {
        return (System.currentTimeMillis() % 1000) < 500;
    }

    // ============ 渲染 ============
    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int w = phoneW();

        // ---- 顶部导航 ----
        g.fill(baseX, baseY, baseX + w, baseY + navH(), 0xFF333333);
        g.drawString(font, "< 返回", baseX + 4, baseY + 3, 0xFFFFFFFF);
        int nameW = font.width(maidName);
        g.drawString(font, maidName, baseX + (w - nameW) / 2, baseY + 3, 0xFFFFFFFF);

        // ---- 消息区 ----
        int msgTop = baseY + navH();
        int msgBottom = inputTop(baseY) - 2;
        g.enableScissor(baseX, msgTop, baseX + w, msgBottom);
        int y = msgBottom - 4;
        for (int i = messages.size() - 1; i >= 0 && y > msgTop; i--) {
            ChatMessage m = messages.get(i);
            int bubbleH = drawBubble(g, font, baseX, y, w, m);
            y -= bubbleH + 4;
        }
        g.disableScissor();

        // ================= 输入框（换行 + 滚动 + 闪烁光标） =================
        int iTop = inputTop(baseY);
        int iBottom = iTop + inputH();

        // 输入框背景
        g.fill(inputLeft(baseX), iTop + 3, inputRight(baseX), iBottom - 3,
                inputActive ? 0xFF555555 : 0xFF222222);

        int textX = baseX + 10;
        int lineH = font.lineHeight + 1;

        if (currentInput.isEmpty()) {
            // 占位提示
            g.drawString(font, "输入消息...", textX, iTop + 6, 0xFF888888);
            // 正在输入时空输入也有闪烁光标
            if (inputActive && isCursorVisible()) {
                int cursorX = textX + font.width("输入消息...");
                g.fill(cursorX, iTop + 5, cursorX + 1, iTop + 5 + font.lineHeight, 0xFFFFFFFF);
            }
        } else {
            // 输入框可用文本宽度 = 输入框左缘到发送按钮左缘之间
            int inputTextW = sendX(baseX) - inputLeft(baseX) - 10;
            List<FormattedCharSequence> lines = wrapText(font, currentInput, inputTextW);

            // ✅ 只显示最后 3 行（自动滚动到底部）
            int startIdx = Math.max(0, lines.size() - MAX_VISIBLE_INPUT_LINES);
            int drawY = iTop + 6;
            for (int i = startIdx; i < lines.size(); i++) {
                g.drawString(font, lines.get(i), textX, drawY, 0xFFFFFFFF);
                drawY += lineH;
            }
            // ✅ 闪烁光标：位于最后一行末尾
            if (inputActive && isCursorVisible()) {
                FormattedCharSequence lastLine = lines.get(lines.size() - 1);
                int cursorX = textX + font.width(lastLine);
                g.fill(cursorX, drawY - lineH + 2, cursorX + 1, drawY + 2, 0xFFFFFFFF);
            }
        }

        // ---- 发送按钮 ----
        g.fill(sendX(baseX), sendY(baseY), sendX(baseX) + sendW(), sendY(baseY) + sendH(), 0xFF00AA00);
        g.drawString(font, "发送", sendX(baseX) + 10, sendY(baseY) + 4, 0xFFFFFFFF);
    }

    /** 绘制单条消息气泡，返回气泡总高度。yBottom 为气泡底部坐标。 */
    private int drawBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m) {
        boolean isError = m.isError();
        boolean isPlayer = m.isPlayer();
        int textColor = isPlayer ? 0xFFFFFFFF : 0xFF333333;
        int bgColor = isError ? 0xFFFFCCCC : (isPlayer ? 0xFF0066CC : 0xFFEFEFEF);

        // 按手机宽度换行
        int maxTextW = phoneW - AVATAR * 2 - MARGIN * 4;
        List<FormattedCharSequence> lines = wrapText(font, m.text(), maxTextW);

        // 计算最长行宽度
        int longest = 0;
        for (FormattedCharSequence line : lines) {
            longest = Math.max(longest, font.width(line));
        }
        int bubbleW = Math.min(longest + 8, phoneW - AVATAR * 2 - MARGIN * 2);
        int lineH = font.lineHeight + 1;
        int bubbleH = lines.size() * lineH + 4;
        int yTop = yBottom - bubbleH;

        if (isPlayer) {
            // 玩家消息：靠右，头像在右
            int bubbleRight = baseX + phoneW - AVATAR - MARGIN;
            int bubbleLeft = bubbleRight - bubbleW;
            g.fill(bubbleLeft, yTop, bubbleRight, yBottom, bgColor);
            int ty = yTop + 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, bubbleLeft + 4, ty, textColor);
                ty += lineH;
            }
            drawAvatarPlaceholder(g, baseX + phoneW - AVATAR - 2, yTop, isPlayer);
        } else {
            // 女仆消息：靠左，头像在左
            int bubbleLeft = baseX + AVATAR + MARGIN;
            int bubbleRight = bubbleLeft + bubbleW;
            g.fill(bubbleLeft, yTop, bubbleRight, yBottom, bgColor);
            int ty = yTop + 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, bubbleLeft + 4, ty, textColor);
                ty += lineH;
            }
            drawAvatarPlaceholder(g, baseX + 2, yTop, isPlayer);
        }
        return bubbleH;
    }

    /**
     * 头像占位：12x12 色块。
     * TODO: 后续替换为车万女仆官方表情包（预留自定义入口）：
     *       女仆侧读取 maid 的表情贴图，玩家侧渲染 PlayerSkin/头颅。
     */
    private void drawAvatarPlaceholder(GuiGraphics g, int x, int y, boolean isPlayer) {
        g.fill(x, y, x + AVATAR, y + AVATAR, isPlayer ? 0xFF8888FF : 0xFFFF8888);
    }

    /** 文本按宽度换行（1.21.1 返回 FormattedCharSequence） */
    private List<FormattedCharSequence> wrapText(Font font, String text, int maxWidth) {
        return font.split(Component.literal(text), maxWidth);
    }

    // ============ 输入 ============
    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;
        int baseX = 0, baseY = 0; // 相对手机坐标

        // 返回按钮
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH()) {
            PhoneOverlay.popPage();
            return true;
        }

        // 发送按钮（与渲染坐标一致）
        if (relX >= sendX(baseX) && relX <= sendX(baseX) + sendW()
                && relY >= sendY(baseY) && relY <= sendY(baseY) + sendH()) {
            sendCurrentMessage();
            return true;
        }

        // 输入框区域
        if (relY >= inputTop(baseY) && relY <= inputTop(baseY) + inputH()
                && relX >= 5 && relX <= phoneW() - 5) {
            activateInput();
            return true;
        }
        return false;
    }

    private void activateInput() {
        inputActive = true;
        Minecraft.getInstance().setScreen(new PhoneInputScreen(this));
    }

    private record ChatMessage(String text, boolean isPlayer, boolean isError) {
    }
}