package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.util.MessageSplitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天页面（完整版）。
 * 所有键（历史/草稿/未读/发消息）统一使用 stableId，死亡/复活不丢失数据。
 */
public class ChatPage implements IPhonePage {
    private static final int AVATAR = 12;
    private static final int MARGIN = 6;
    private static final int MAX_VISIBLE_INPUT_LINES = 3;
    /** 状态小字缩放（半字号，避免从标题栏凸出） */
    private static final float STATUS_SCALE = 0.5f;

    private static final String[] BOTTOM_ICONS = {"语", "表", "图", "拍", "+"};
    private static final int[] BOTTOM_ICON_COLORS = {
            0xFF4CAF50, 0xFFFF9800, 0xFF2196F3, 0xFF9C27B0, 0xFF607D8B
    };

    private final String stableId;
    private final String maidName;
    private final List<ChatMessage> messages = new ArrayList<>();
    private String currentInput = "";
    private boolean inputActive = false;
    private int cursorIndex = 0;
    private boolean bottomPanelOpen = false;
    private int inputScrollOffset = 0;
    /** 是否正在等待女仆回复（用于"对方正在输入…"） */
    private boolean waitingResponse = false;
    /** 消息区纵向滚动偏移（像素），滚轮上滚看更早消息 */
    private int scrollOffset = 0;
    /** 滚轮滚动速度（像素/格） */
    private static final int SCROLL_SPEED = 12;

    // ==================== 断句逐条显示（任务2） ====================
    /** 每个字消耗的"打字"时间（毫秒），按字数线性计费 */
    private static final long CHAR_TYPING_MS = 500;
    /** 单个短句"打字"时间上限（毫秒），防止超长句拖太久 */
    private static final long MAX_TYPING_MS = 2400;
    /** 待逐条显示的短句队列（整段回复按断句符拆分后填充） */
    private final List<String> splitQueue = new ArrayList<>();
    /** 下一条要显示的短句在 splitQueue 中的下标 */
    private int splitIndex = 0;
    /** 上一条短句显示的时间戳（毫秒） */
    private long lastSplitTime = 0;
    /** 是否正在断句逐条显示（期间"对方正在输入…"保持） */
    private boolean splitting = false;

    public ChatPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
        for (ChatHistoryStorage.ChatMessageEntry e : ChatHistoryStorage.getMessages(stableId)) {
            // 历史中的女仆正常回复：按断句规则拆成多条气泡显示（与实时收到时一致），
            // 避免重新进入世界后整段回复被合成一个大块。错误/玩家消息保持原样。
            if (!e.isPlayer() && !e.isError()) {
                List<String> segments = MessageSplitter.split(e.text());
                for (String seg : segments) {
                    messages.add(new ChatMessage(seg, false, false, false));
                }
            } else {
                messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError(), e.pending()));
            }
        }
        this.currentInput = ChatHistoryStorage.getDraft(stableId);
        this.cursorIndex = currentInput.length();
        NotificationManager.clear(stableId);
        // 按需请求一次该女仆的当前状态（打开聊天页立刻刷新状态小字）
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(List.of(stableId)));
    }

    public boolean isWaitingResponse() { return waitingResponse; }
    public void setWaitingResponse(boolean waiting) { this.waitingResponse = waiting; }

    /** 把最后一条"玩家发送"的消息标红"!"（消息入离线队列、尚未送达）。本地+落盘 */
    public void markLastSentPending() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.isPlayer()) {
                messages.set(i, new ChatMessage(m.text(), true, m.isError(), true));
                break;
            }
        }
        ChatHistoryStorage.markLastSentPending(stableId);
    }

    /** 清除所有未送达标记（女仆回复到达即视为送达）。本地+落盘 */
    public void clearPending() {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.pending()) {
                messages.set(i, new ChatMessage(m.text(), m.isPlayer(), m.isError(), false));
            }
        }
        ChatHistoryStorage.clearPending(stableId);
    }

    /** 消息入离线队列：停止"输入中"并标红"!"（不显示提示文字） */
    public void onQueued() {
        this.waitingResponse = false;
        // 入离线队列 → 女仆未响应，中断任何断句显示
        this.splitting = false;
        this.splitQueue.clear();
        this.splitIndex = 0;
        markLastSentPending();
    }

    public String getStableId() { return stableId; }
    public String getCurrentInputText() { return currentInput; }
    public int getCursorIndex() { return cursorIndex; }

    public void setCursorIndex(int idx) {
        this.cursorIndex = Math.max(0, Math.min(currentInput.length(), idx));
    }

    public void onInputChanged(String text) {
        this.currentInput = text;
        this.cursorIndex = Math.min(this.cursorIndex, text.length());
        ensureCursorVisible();
        ChatHistoryStorage.setDraft(stableId, text);
    }

    public void onInputFinished(String text, boolean send) {
        this.inputActive = false;
        this.currentInput = text;
        if (send && !text.isBlank()) {
            sendCurrentMessage();
        } else {
            ChatHistoryStorage.setDraft(stableId, text);
        }
    }

    public void onInputClosed() {
        this.inputActive = false;
    }

    public void onPageHide() {
        ChatHistoryStorage.setDraft(stableId, currentInput);
    }

    public void onReceiveMessage(String text, boolean isError) {
        // 收到女仆回复/提示 → 视为送达，清除未送达标记
        clearPending();
        // 回到最新消息（让新回复可见）
        scrollOffset = 0;
        // 错误/提示消息：整段显示，不拆句
        if (isError) {
            messages.add(new ChatMessage(text, false, true, false));
            this.waitingResponse = false;
            this.splitting = false;
            this.splitQueue.clear();
            this.splitIndex = 0;
            return;
        }
        // 正常女仆回复：按逗号断句，逐条显示（仅在手机页面渲染层处理，历史存储仍存整段）
        List<String> segments = MessageSplitter.split(text);
        if (segments.size() <= 1) {
            // 无逗号可拆 → 整段显示
            messages.add(new ChatMessage(text, false, false, false));
            this.waitingResponse = false;
            this.splitting = false;
            this.splitQueue.clear();
            this.splitIndex = 0;
            return;
        }
        // 进入断句模式：重置队列，每条短句按"自己字数 × 0.5s（封顶 2.4s）"逐条显示。
        // 长句打字慢、短句快；第一条也要等它自己的耗时，因此不提前立即显示。
        splitQueue.clear();
        splitQueue.addAll(segments);
        splitIndex = 0;
        splitting = true;
        waitingResponse = true; // 断句期间保持"对方正在输入…"
        lastSplitTime = System.currentTimeMillis();
    }

    /**
     * 每帧推进断句显示：每条短句出现的延迟 = <b>它自己</b>的字数 × 每字耗时（每字 0.5s、封顶 2.4s）。
     * 长句打字慢、短句快；全部显示完才结束"正在输入…"。在 render 每帧调用。
     */
    private void advanceSplitting() {
        if (!splitting) {
            return;
        }
        long now = System.currentTimeMillis();
        // 逐条追加：要显示的当前句（splitIndex）决定它何时出现（打字耗时 = 该句自己的字数）
        while (splitIndex < splitQueue.size()) {
            long typingMs = typingMillis(splitQueue.get(splitIndex));
            if (now - lastSplitTime < typingMs) {
                break;
            }
            messages.add(new ChatMessage(splitQueue.get(splitIndex), false, false, false));
            splitIndex++;
            lastSplitTime = now;
        }
        // 全部显示完 → 结束"正在输入…"
        if (splitIndex >= splitQueue.size()) {
            splitting = false;
            waitingResponse = false;
        }
    }

    /** 按字数计费的单句打字耗时：字数 × {@link #CHAR_TYPING_MS}，封顶 {@link #MAX_TYPING_MS} */
    private static long typingMillis(String segment) {
        int len = segment == null ? 0 : segment.length();
        return Math.min(len * CHAR_TYPING_MS, MAX_TYPING_MS);
    }

    private void sendCurrentMessage() {
        if (currentInput.isBlank()) return;
        String text = currentInput;
        messages.add(new ChatMessage(text, true, false, false));
        ChatHistoryStorage.appendMessage(stableId, text, true, false);
        ChatHistoryStorage.setDraft(stableId, "");
        PacketDistributor.sendToServer(new C2SMaidChatPacket(stableId, text));
        this.waitingResponse = true; // 进入"对方正在输入…"状态
        currentInput = "";
        cursorIndex = 0;
        inputScrollOffset = 0;
        scrollOffset = 0; // 发送后回到最新消息，确保新消息可见
    }

    // ==================== 布局常量 ====================

    private int phoneW() { return PhoneOverlay.getPhoneWidth(); }
    private int phoneH() { return PhoneOverlay.getPhoneHeight(); }
    private int navH() { return Minecraft.getInstance().font.lineHeight + 8; }
    private int bottomNavH() { return (int)(phoneH() * 0.09); }
    private int bottomPanelH() { return bottomPanelOpen ? (int)(phoneH() * 0.18) : 0; }
    private int inputH() {
        int base = (int)(phoneH() * 0.10);
        int min = Minecraft.getInstance().font.lineHeight * MAX_VISIBLE_INPUT_LINES + 8;
        return Math.max(base, min);
    }

    private int inputBottom(int baseY) {
        return baseY + phoneH() - bottomNavH() - bottomPanelH();
    }
    private int inputTop(int baseY) { return inputBottom(baseY) - inputH(); }
    private int inputLeft(int baseX) { return baseX + 5; }
    private int inputRight(int baseX) { return baseX + phoneW() - 5; }
    private int sendX(int baseX) { return baseX + phoneW() - 48; }
    private int sendY(int baseY) { return inputTop(baseY) + 4; }
    private int sendW() { return 40; }
    private int sendH() { return inputH() - 8; }

    private boolean isCursorVisible() {
        return (System.currentTimeMillis() % 1000) < 500;
    }

    // ==================== 滚动相关 ====================

    private int getVisibleStartIdx(int totalLines) {
        int maxStart = Math.max(0, totalLines - MAX_VISIBLE_INPUT_LINES);
        return Math.max(0, Math.min(inputScrollOffset, maxStart));
    }

    private void ensureCursorVisible() {
        Font font = Minecraft.getInstance().font;
        int inputTextW = sendX(0) - inputLeft(0) - 10;
        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        if (lines.isEmpty()) {
            inputScrollOffset = 0;
            return;
        }
        int cursorLine = cursorIndexToLineCol(lines, cursorIndex)[0];
        int visibleStart = getVisibleStartIdx(lines.size());
        int visibleEnd = visibleStart + MAX_VISIBLE_INPUT_LINES;

        if (cursorLine < visibleStart) {
            inputScrollOffset = cursorLine;
        } else if (cursorLine >= visibleEnd) {
            inputScrollOffset = Math.max(0, cursorLine - MAX_VISIBLE_INPUT_LINES + 1);
        }
        inputScrollOffset = Math.max(0, Math.min(inputScrollOffset,
                Math.max(0, lines.size() - MAX_VISIBLE_INPUT_LINES)));
    }

    public void scrollInput(int delta) {
        Font font = Minecraft.getInstance().font;
        int inputTextW = sendX(0) - inputLeft(0) - 10;
        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        int maxStart = Math.max(0, lines.size() - MAX_VISIBLE_INPUT_LINES);
        inputScrollOffset = Math.max(0, Math.min(maxStart, inputScrollOffset - delta));
    }

    // ==================== 屏幕坐标判定 ====================

    public boolean isSendButtonHitScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY() - PhoneOverlay.getStatusBarHeight();
        return relX >= sendX(0) && relX <= sendX(0) + sendW()
                && relY >= sendY(0) && relY <= sendY(0) + sendH();
    }

    public boolean isInputAreaHitScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY() - PhoneOverlay.getStatusBarHeight();
        return relY >= inputTop(0) && relY <= inputBottom(0)
                && relX >= 5 && relX <= phoneW() - 5;
    }

    public int getCursorIndexAtScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY() - PhoneOverlay.getStatusBarHeight();
        Font font = Minecraft.getInstance().font;
        int textX = 10;
        int lineH = font.lineHeight + 1;
        int iTop = inputTop(0);
        int inputTextW = sendX(0) - inputLeft(0) - 10;

        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        if (lines.isEmpty()) return 0;
        int startIdx = getVisibleStartIdx(lines.size());

        int rowInVisible = (int)((relY - (iTop + 6)) / lineH);
        rowInVisible = Math.max(0, Math.min(lines.size() - startIdx - 1, rowInVisible));
        int globalLine = startIdx + rowInVisible;
        String line = lines.get(globalLine);

        int col = 0;
        int accumulatedX = 0;
        for (int i = 0; i < line.length(); i++) {
            int charW = font.width(String.valueOf(line.charAt(i)));
            if (relX - textX < accumulatedX + charW / 2.0) break;
            accumulatedX += charW;
            col = i + 1;
        }

        int cursor = 0;
        for (int i = 0; i < globalLine; i++) {
            cursor += lines.get(i).length();
        }
        cursor += col;
        return Math.max(0, Math.min(currentInput.length(), cursor));
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        // 每帧推进断句显示（任务2）
        advanceSplitting();
        Font font = Minecraft.getInstance().font;
        int w = phoneW();

        com.huhutalk.client.theme.ColorScheme scheme = com.huhutalk.client.theme.ThemeManager.getActive();
        // 页面底色（浅珍珠粉），覆盖黑色手机底层
        g.fill(baseX, baseY + navH(), baseX + w, baseY + phoneH(), scheme.pageBg());
        g.fill(baseX, baseY, baseX + w, baseY + navH(), scheme.topBarBg());
        g.drawString(font, "< 返回", baseX + 4, baseY + 3, scheme.topBarText());
        // 等待女仆回复时显示"对方正在输入…"，否则显示女仆名字
        String titleText = waitingResponse ? "对方正在输入…" : maidName;
        int nameW = font.width(titleText);
        g.drawString(font, titleText, baseX + (w - nameW) / 2, baseY + 3, waitingResponse ? 0xFFAAAAAA : scheme.topBarText());
        // 状态小字（女仆当前状态，低频推送；半字号，避免从标题栏凸出）
        String status = MaidStatusCache.get(stableId);
        if (!waitingResponse && !status.isEmpty()) {
            drawScaledText(g, font, status, baseX + (w - font.width(status) * STATUS_SCALE) / 2f,
                    baseY + 11, 0xFFAAAAAA, STATUS_SCALE);
        }
        g.drawString(font, "⚙", baseX + w - 16, baseY + 3, 0xFFFFFFFF);

        int msgTop = baseY + navH();
        int msgBottom = inputTop(baseY) - 2;
        int viewH = Math.max(0, msgBottom - msgTop);

        // 计算消息区总高度，用于滚动钳制（底部锚定，滚上滚看更早消息）
        int totalH = 0;
        for (ChatMessage m : messages) {
            totalH += bubbleHeight(font, m, w) + 4;
        }
        int maxScroll = Math.max(0, totalH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        g.enableScissor(baseX, msgTop, baseX + w, msgBottom);
        int y = msgBottom - 4 + scrollOffset;
        for (int i = messages.size() - 1; i >= 0 && y > msgTop; i--) {
            ChatMessage m = messages.get(i);
            int bubbleH = drawBubble(g, font, baseX, y, w, m);
            y -= bubbleH + 4;
        }
        g.disableScissor();

        if (bottomPanelOpen) {
            int panelTop = inputBottom(baseY);
            int panelBottom = baseY + phoneH() - bottomNavH();
            g.fill(baseX + 2, panelTop, baseX + w - 2, panelBottom - 2, 0xFF1E1E1E);
            int panelMidY = (panelTop + panelBottom) / 2;
            g.drawCenteredString(font, "🚧 面板开发中", baseX + w / 2,
                    panelMidY - font.lineHeight / 2, 0xFFAAAAAA);
        }

        int iTop = inputTop(baseY);
        int iBottom = inputBottom(baseY);
        g.fill(inputLeft(baseX), iTop + 3, inputRight(baseX), iBottom - 3,
                inputActive ? 0xFF555555 : 0xFF222222);

        int textX = baseX + 10;
        int lineH = font.lineHeight + 1;

        if (currentInput.isEmpty()) {
            g.drawString(font, "输入消息...", textX, iTop + 6, 0xFF888888);
            if (inputActive && isCursorVisible()) {
                int cx = textX + font.width("输入消息...");
                g.fill(cx, iTop + 5, cx + 1, iTop + 5 + font.lineHeight, 0xFFFFFFFF);
            }
        } else {
            int inputTextW = sendX(baseX) - inputLeft(baseX) - 10;
            List<String> lines = wrapToLines(font, currentInput, inputTextW);
            int startIdx = getVisibleStartIdx(lines.size());
            int drawY = iTop + 6;

            for (int i = startIdx; i < lines.size() && i < startIdx + MAX_VISIBLE_INPUT_LINES; i++) {
                g.drawString(font, lines.get(i), textX, drawY, 0xFFFFFFFF);
                drawY += lineH;
            }

            if (inputActive && isCursorVisible()) {
                int[] lineCol = cursorIndexToLineCol(lines, cursorIndex);
                int globalLine = lineCol[0];
                int col = lineCol[1];
                if (globalLine >= startIdx && globalLine < startIdx + MAX_VISIBLE_INPUT_LINES) {
                    String targetLine = lines.get(globalLine);
                    int safeCol = Math.min(col, targetLine.length());
                    int cursorDrawX = textX + font.width(targetLine.substring(0, safeCol));
                    int cursorDrawY = iTop + 6 + (globalLine - startIdx) * lineH;
                    g.fill(cursorDrawX, cursorDrawY - 1, cursorDrawX + 1,
                            cursorDrawY + font.lineHeight, 0xFFFFFFFF);
                }
            }
        }

        g.fill(sendX(baseX), sendY(baseY), sendX(baseX) + sendW(), sendY(baseY) + sendH(), 0xFF00AA00);
        g.drawString(font, "发送", sendX(baseX) + 10, sendY(baseY) + 4, 0xFFFFFFFF);

        int bNavTop = baseY + phoneH() - bottomNavH();
        g.fill(baseX, bNavTop, baseX + w, baseY + phoneH(), 0xFF2A2A2A);
        g.fill(baseX, bNavTop, baseX + w, bNavTop + 1, 0xFF555555);

        int iconCount = BOTTOM_ICONS.length;
        int iconSlotW = w / iconCount;
        for (int i = 0; i < iconCount; i++) {
            int iconCenterX = baseX + i * iconSlotW + iconSlotW / 2;
            int iconCenterY = bNavTop + bottomNavH() / 2;
            int blockSize = (int)(iconSlotW * 0.5);
            g.fill(iconCenterX - blockSize / 2, iconCenterY - blockSize / 2,
                    iconCenterX + blockSize / 2, iconCenterY + blockSize / 2,
                    BOTTOM_ICON_COLORS[i] | 0xFF000000);
            int labelW = font.width(BOTTOM_ICONS[i]);
            g.drawString(font, BOTTOM_ICONS[i],
                    iconCenterX - labelW / 2,
                    iconCenterY - font.lineHeight / 2 + 1,
                    0xFFFFFFFF);
        }
    }

    /** 计算某条消息气泡的高度（与 drawBubble 的换行/行高逻辑一致），用于滚动钳制 */
    private int bubbleHeight(Font font, ChatMessage m, int phoneW) {
        int maxTextW = phoneW - AVATAR * 2 - MARGIN * 4;
        List<FormattedCharSequence> lines = font.split(Component.literal(m.text()), maxTextW);
        int lineH = font.lineHeight + 1;
        return lines.size() * lineH + 4;
    }

    private int drawBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m) {
        boolean isError = m.isError();
        boolean isPlayer = m.isPlayer();
        com.huhutalk.client.theme.ColorScheme scheme = com.huhutalk.client.theme.ThemeManager.getActive();
        // 玩家气泡：蓝底白字；女仆气泡：深青底白字；错误气泡：中性浅红
        int textColor = isPlayer ? scheme.playerText() : (isError ? scheme.errorText() : scheme.maidText());
        int bgColor = isError ? scheme.errorBubble()
                : (isPlayer ? scheme.playerBubble() : scheme.maidBubble());

        int maxTextW = phoneW - AVATAR * 2 - MARGIN * 4;
        List<FormattedCharSequence> lines = font.split(Component.literal(m.text()), maxTextW);

        int longest = 0;
        for (FormattedCharSequence line : lines) {
            longest = Math.max(longest, font.width(line));
        }
        int bubbleW = Math.min(longest + 8, phoneW - AVATAR * 2 - MARGIN * 2);
        int lineH = font.lineHeight + 1;
        int bubbleH = lines.size() * lineH + 4;
        int yTop = yBottom - bubbleH;

        if (isPlayer) {
            int bubbleRight = baseX + phoneW - AVATAR - MARGIN;
            int bubbleLeft = bubbleRight - bubbleW;
            g.fill(bubbleLeft, yTop, bubbleRight, yBottom, bgColor);
            int ty = yTop + 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, bubbleLeft + 4, ty, textColor);
                ty += lineH;
            }
            drawAvatarPlaceholder(g, baseX + phoneW - AVATAR - 2, yTop, true);
            // 未送达（入离线队列）的玩家消息标红"!"
            if (m.pending()) {
                g.drawString(font, "!", bubbleLeft - 8, yTop + 2, 0xFFFF3333);
            }
        } else {
            int bubbleLeft = baseX + AVATAR + MARGIN;
            int bubbleRight = bubbleLeft + bubbleW;
            g.fill(bubbleLeft, yTop, bubbleRight, yBottom, bgColor);
            int ty = yTop + 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, bubbleLeft + 4, ty, textColor);
                ty += lineH;
            }
            drawAvatarPlaceholder(g, baseX + 2, yTop, false);
        }
        return bubbleH;
    }

    private void drawAvatarPlaceholder(GuiGraphics g, int x, int y, boolean isPlayer) {
        g.fill(x, y, x + AVATAR, y + AVATAR, isPlayer ? 0xFF8888FF : 0xFFFF8888);
    }

    /** 以任意倍率绘制文本（无阴影）。坐标按 1/scale 换算，保证缩放后落在原位 */
    private static void drawScaledText(GuiGraphics g, Font font, String text, float x, float y, int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    private List<String> wrapToLines(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String test = current.toString() + c;
            if (font.width(test) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
        }
        lines.add(current.toString());
        return lines;
    }

    private int[] cursorIndexToLineCol(List<String> lines, int cursorIndex) {
        int accumulated = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineLen = lines.get(i).length();
            if (cursorIndex <= accumulated + lineLen) {
                return new int[]{i, cursorIndex - accumulated};
            }
            accumulated += lineLen;
        }
        int lastIdx = lines.size() - 1;
        return new int[]{lastIdx, lines.get(lastIdx).length()};
    }

    private int lineColToCursorIndex(List<String> lines, int targetLine, int targetCol) {
        int base = 0;
        for (int i = 0; i < targetLine; i++) {
            base += lines.get(i).length();
        }
        int maxCol = lines.get(targetLine).length();
        return base + Math.min(targetCol, maxCol);
    }

    public void moveCursorUp() {
        Font font = Minecraft.getInstance().font;
        int inputTextW = sendX(0) - inputLeft(0) - 10;
        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        int[] lc = cursorIndexToLineCol(lines, cursorIndex);
        if (lc[0] > 0) {
            cursorIndex = lineColToCursorIndex(lines, lc[0] - 1, lc[1]);
        } else {
            cursorIndex = 0;
        }
        cursorIndex = Math.max(0, Math.min(currentInput.length(), cursorIndex));
        ensureCursorVisible();
    }

    public void moveCursorDown() {
        Font font = Minecraft.getInstance().font;
        int inputTextW = sendX(0) - inputLeft(0) - 10;
        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        int[] lc = cursorIndexToLineCol(lines, cursorIndex);
        if (lc[0] < lines.size() - 1) {
            cursorIndex = lineColToCursorIndex(lines, lc[0] + 1, lc[1]);
        } else {
            cursorIndex = currentInput.length();
        }
        cursorIndex = Math.max(0, Math.min(currentInput.length(), cursorIndex));
        ensureCursorVisible();
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int baseX = 0, baseY = 0;
        int w = phoneW();

        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH()) {
            PhoneOverlay.popPage();
            return true;
        }

        if (relX >= w - 24 && relX <= w && relY >= 0 && relY <= navH()) {
            PhoneOverlay.pushPage(new MaidInfoPage(stableId, maidName));
            return true;
        }

        int bNavTop = phoneH() - bottomNavH();
        if (relY >= bNavTop && relY <= phoneH()) {
            int iconCount = BOTTOM_ICONS.length;
            int iconSlotW = w / iconCount;
            int clickedIcon = (int)(relX / iconSlotW);
            if (clickedIcon >= 0 && clickedIcon < iconCount) {
                bottomPanelOpen = !bottomPanelOpen;
                return true;
            }
        }

        if (relX >= sendX(baseX) && relX <= sendX(baseX) + sendW()
                && relY >= sendY(baseY) && relY <= sendY(baseY) + sendH()) {
            sendCurrentMessage();
            return true;
        }

        if (relY >= inputTop(baseY) && relY <= inputBottom(baseY)
                && relX >= 5 && relX <= w - 5) {
            activateInput();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        Font font = Minecraft.getInstance().font;
        int w = phoneW();
        int msgTop = navH();
        int msgBottom = inputTop(0) - 2;
        int viewH = Math.max(0, msgBottom - msgTop);
        int totalH = 0;
        for (ChatMessage m : messages) {
            totalH += bubbleHeight(font, m, w) + 4;
        }
        int maxScroll = Math.max(0, totalH - viewH);
        // 滚轮上滚（scrollDelta>0）→ 增加偏移看更早消息；下滚回到最新
        scrollOffset = Math.max(0, Math.min(scrollOffset + (int)(scrollDelta * SCROLL_SPEED), maxScroll));
        return true;
    }

    private void activateInput() {
        inputActive = true;
        Minecraft.getInstance().setScreen(new PhoneInputScreen(this));
    }

    private record ChatMessage(String text, boolean isPlayer, boolean isError, boolean pending) {
    }
}