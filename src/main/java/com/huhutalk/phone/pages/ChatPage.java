package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiShapes;
import com.huhutalk.util.MessageSplitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    private static final String[] BOTTOM_ICONS = {"🎤", "🦊", "🖼️", "📷", "+"};

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

    // ==================== 输入区 / 拓展栏布局（动态高度） ====================
    /** 输入框左边距 = 发送按钮右边距 = 输入框与发送按钮的间距（三者相等） */
    private static final int FIELD_MARGIN = 8;
    /** 输入框内文字水平留白 */
    private static final int PAD_X = 10;
    /** 输入框内文字垂直留白（每侧） */
    private static final int PAD_Y = 4;

    /** 单行输入框高度（= 拓展栏高度 = 发送按钮高度） */
    private int singleLineH() {
        return Minecraft.getInstance().font.lineHeight + PAD_Y * 2;
    }

    /** 发送按钮宽度：约占屏幕 1/4 */
    private int sendW() { return Math.max(40, phoneW() / 4); }

    /** 输入框内部文字可用宽度（用于换行/光标计算） */
    private int inputTextWidth() {
        return phoneW() - FIELD_MARGIN * 3 - sendW() - PAD_X * 2;
    }

    /** 输入框当前高度：按内容自动增高，最多 MAX_VISIBLE_INPUT_LINES 行，超出后内部滚动 */
    private int inputHeight() {
        Font font = Minecraft.getInstance().font;
        int lines = wrapToLines(font, currentInput, inputTextWidth()).size();
        lines = Math.max(1, Math.min(lines, MAX_VISIBLE_INPUT_LINES));
        return lines * (font.lineHeight + 1) + PAD_Y * 2;
    }

    /** 拓展功能栏高度（与单行输入框一致），贴屏幕底部 */
    private int extBarTop(int baseY) { return baseY + phoneH() - singleLineH(); }
    private int inputBottom(int baseY) { return extBarTop(baseY); }
    private int inputTop(int baseY) { return inputBottom(baseY) - inputHeight(); }
    private int inputLeft(int baseX) { return baseX + FIELD_MARGIN; }
    private int inputRight(int baseX) { return sendX(baseX) - FIELD_MARGIN; }
    private int sendX(int baseX) { return baseX + phoneW() - FIELD_MARGIN - sendW(); }
    private int sendH() { return singleLineH(); }
    /** 发送按钮垂直中心始终与输入框垂直中心对齐，高度固定 */
    private int sendY(int baseY) { return (inputTop(baseY) + inputBottom(baseY)) / 2 - sendH() / 2; }

    /** 上拉面板高度（打开时占据输入框上方的区域） */
    private int panelH() { return bottomPanelOpen ? (int)(phoneH() * 0.18) : 0; }
    private int panelTop(int baseY) { return inputTop(baseY) - panelH(); }

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
        int inputTextW = inputTextWidth();
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
        int inputTextW = inputTextWidth();
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
                && relX >= inputLeft(0) && relX <= inputRight(0);
    }

    public int getCursorIndexAtScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY() - PhoneOverlay.getStatusBarHeight();
        Font font = Minecraft.getInstance().font;
        int textX = inputLeft(0) + PAD_X;
        int lineH = font.lineHeight + 1;
        int iTop = inputTop(0);
        int inputTextW = inputTextWidth();

        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        if (lines.isEmpty()) return 0;
        int startIdx = getVisibleStartIdx(lines.size());

        int rowInVisible = (int)((relY - (iTop + PAD_Y)) / lineH);
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

        // 页面底色 #f0e4ea（比消息列表略深），覆盖黑色手机底层
        g.fill(baseX, baseY + navH(), baseX + w, baseY + phoneH(), 0xFFF0E4EA);

        // ==================== 顶部导航栏 ====================
        g.fill(baseX, baseY, baseX + w, baseY + navH(), 0xFFDE7E7E);
        int navCenterY = baseY + navH() / 2;
        // 返回按钮：仅保留白色箭头图标，删除"返回"文字
        g.drawString(font, "←", baseX + 6, navCenterY - font.lineHeight / 2, 0xFFFFFFFF);
        // 返回按钮右侧靠左放置女仆名称 + 状态（保留字号比例，垂直居中）
        String titleText = waitingResponse ? "对方正在输入…" : maidName;
        int nameX = baseX + 22;
        int nameY = navCenterY - font.lineHeight / 2;
        g.drawString(font, titleText, nameX, nameY, 0xFFFFFFFF);
        // 状态小字（半字号，紧跟名称之后，保留原比例；等待回复时不显示）
        String status = MaidStatusCache.get(stableId);
        if (!waitingResponse && !status.isEmpty()) {
            drawScaledText(g, font, status, nameX + font.width(titleText) + 8,
                    navCenterY - 3, 0xFF8A5B5B, STATUS_SCALE);
        }
        // 右上角设置按钮（保留原有功能）
        g.drawString(font, "⚙", baseX + w - 16, navCenterY - font.lineHeight / 2, 0xFFFFFFFF);

        // ==================== 消息区（面板打开时被上拉面板压缩） ====================
        int msgTop = baseY + navH();
        int msgBottom = (bottomPanelOpen ? panelTop(baseY) : inputTop(baseY)) - 2;
        int viewH = Math.max(0, msgBottom - msgTop);

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

        // ==================== 输入区衬底 + 顶部细阴影 ====================
        // 输入区整体与页面同色，顶部细阴影与消息列表分隔
        int iTop = inputTop(baseY);
        drawTopShadow(g, baseX, iTop - 2, w);

        // ==================== 上拉面板（保留原有弹出功能，仅换色 + 细线分隔） ====================
        if (bottomPanelOpen) {
            int pTop = panelTop(baseY);
            int pBottom = inputTop(baseY);
            g.fill(baseX, pTop, baseX + w, pBottom, 0xFFF5E9EF);
            // 与输入框之间用淡灰色细线分隔
            g.fill(baseX, pBottom - 1, baseX + w, pBottom, 0xFFD9D2D6);
            int panelMidY = (pTop + pBottom) / 2;
            g.drawCenteredString(font, "🚧 面板开发中", baseX + w / 2,
                    panelMidY - font.lineHeight / 2, 0xFF8A6A6A);
        }

        // ==================== 输入框（胶囊形，随内容增高，最多 3 行） ====================
        int iBottom = inputBottom(baseY);
        int iW = inputRight(baseX) - inputLeft(baseX);
        int iH = iBottom - iTop;
        UiShapes.fillRoundedRect(g, inputLeft(baseX), iTop, iW, iH, iH / 2, 0xFFFFF5F8);

        int textX = inputLeft(baseX) + PAD_X;
        int lineH = font.lineHeight + 1;

        if (currentInput.isEmpty()) {
            // 占位符（垂直居中）：灰色，75% 不透明度，无阴影
            int phY = iTop + (iH - font.lineHeight) / 2;
            g.drawString(font, "输入消息...", textX, phY, 0xBF9E9E9E, false);
            if (inputActive && isCursorVisible()) {
                int cx = textX + font.width("输入消息...");
                g.fill(cx, phY - 1, cx + 1, phY + font.lineHeight, 0xFF8A6A6A);
            }
        } else {
            List<String> lines = wrapToLines(font, currentInput, inputTextWidth());
            int startIdx = getVisibleStartIdx(lines.size());
            int drawY = iTop + PAD_Y;

            for (int i = startIdx; i < lines.size() && i < startIdx + MAX_VISIBLE_INPUT_LINES; i++) {
                g.drawString(font, lines.get(i), textX, drawY, 0xFF4A3F44, false);
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
                    int cursorDrawY = iTop + PAD_Y + (globalLine - startIdx) * lineH;
                    g.fill(cursorDrawX, cursorDrawY - 1, cursorDrawX + 1,
                            cursorDrawY + font.lineHeight, 0xFF8A6A6A);
                }
            }
        }

        // ==================== 发送按钮（胶囊形，高度固定，垂直中心对齐输入框） ====================
        int sW = sendW();
        int sH = sendH();
        int sX = sendX(baseX);
        int sY = sendY(baseY);
        UiShapes.fillRoundedRect(g, sX, sY, sW, sH, sH / 2, 0xFFB1CEDE);
        String sendLabel = "发送";
        g.drawString(font, sendLabel, sX + (sW - font.width(sendLabel)) / 2,
                sY + (sH - font.lineHeight) / 2, 0xFFFFFFFF, false);

        // ==================== 拓展功能栏（高度=单行输入框，贴屏幕底部） ====================
        int extTop = extBarTop(baseY);
        int extCenterY = extTop + singleLineH() / 2;
        int iconCount = BOTTOM_ICONS.length;
        int iconSlotW = w / iconCount;
        int btnSize = singleLineH() - 6;
        int btnR = btnSize / 2;
        for (int i = 0; i < iconCount; i++) {
            int cx = baseX + i * iconSlotW + iconSlotW / 2;
            UiShapes.fillRoundedRect(g, cx - btnSize / 2, extCenterY - btnSize / 2, btnSize, btnSize, btnR, 0xFFFFF5F8);
            String icon = BOTTOM_ICONS[i];
            // 去掉默认字体阴影，避免 emoji 图标发糊
            g.drawString(font, icon, cx - font.width(icon) / 2,
                    extCenterY - font.lineHeight / 2 + 1, 0xFF6A5A5A, false);
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
        // 女仆气泡：深灰蓝底白字；玩家气泡：亮蓝底白字；错误气泡：沿用主题浅红（仍圆角）
        int textColor = (isError && !isPlayer) ? scheme.errorText() : 0xFFFFFFFF;
        int bgColor = isError ? scheme.errorBubble()
                : (isPlayer ? 0xFF5294BF : 0xFF525A6E);

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
        // 圆角方框：圆角半径 = 1/4 方框高
        int radius = Math.max(2, bubbleH / 4);

        if (isPlayer) {
            int bubbleRight = baseX + phoneW - AVATAR - MARGIN;
            int bubbleLeft = bubbleRight - bubbleW;
            UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, bgColor);
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
            UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, bgColor);
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
        Minecraft mc = Minecraft.getInstance();
        if (isPlayer) {
            // 玩家头像：皮肤纹理"头颅正面"8×8 区块（与消息列表页一致）
            if (mc.player instanceof AbstractClientPlayer acp) {
                ResourceLocation skin = acp.getSkin().texture();
                g.blit(skin, x, y, AVATAR, AVATAR, 8.0f, 8.0f, 8, 8, 64, 64);
            } else {
                g.fill(x, y, x + AVATAR, y + AVATAR, 0xFF8AAEE8);
            }
        } else {
            // 女仆头像：缓存的照片纹理（与消息列表页一致）；未加载时灰色占位
            ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(stableId);
            if (tex != null) {
                int[] d = MaidAvatarManager.getTextureSize(stableId);
                int w = d != null ? d[0] : AVATAR;
                int h = d != null ? d[1] : AVATAR;
                g.blit(tex, x, y, AVATAR, AVATAR, 0.0f, 0.0f, w, h, w, h);
            } else {
                g.fill(x, y, x + AVATAR, y + AVATAR, 0xFFB0BEC5);
            }
        }
    }

    /** 以任意倍率绘制文本（无阴影）。坐标按 1/scale 换算，保证缩放后落在原位 */
    private static void drawScaledText(GuiGraphics g, Font font, String text, float x, float y, int color, float scale) {
        float inv = 1.0f / scale;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x * inv, y * inv, color, false);
        g.pose().popPose();
    }

    /**
     * 绘制一条位于 y 位置的细水平阴影（用于输入区与消息列表的视觉分隔）。
     * 通过叠加几层半透明深色线模拟轻微投影。
     */
    private static void drawTopShadow(GuiGraphics g, int baseX, int y, int width) {
        g.fill(baseX, y, baseX + width, y + 1, 0x16000000);
        g.fill(baseX, y + 1, baseX + width, y + 2, 0x0C000000);
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
        int inputTextW = inputTextWidth();
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
        int inputTextW = inputTextWidth();
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

        // 拓展功能栏（贴屏幕底部）：点击任意按钮切换上拉面板
        int extTop = extBarTop(0);
        if (relY >= extTop && relY <= phoneH()) {
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
                && relX >= inputLeft(baseX) && relX <= inputRight(baseX)) {
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
        int msgBottom = (bottomPanelOpen ? panelTop(0) : inputTop(0)) - 2;
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