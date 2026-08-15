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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天页面（完整版）。
 * - 玩家消息靠右蓝色气泡，女仆消息靠左白色气泡
 * - 输入框支持换行、滚动显示最后3行、闪烁光标跟随真实编辑位置
 * - 顶部导航：左返回 / 中名称 / 右设置齿轮（占位）
 * - 底部导航栏：单字占位标签（语音/表情/图片/拍照/加号），点击展开面板（面板位于输入框下方）
 * - 输入框支持鼠标滚轮滚动，上下键移动光标时自动滚动跟随
 */
public class ChatPage implements IPhonePage {
    private static final int AVATAR = 12;
    private static final int MARGIN = 6;
    private static final int MAX_VISIBLE_INPUT_LINES = 3;

    /** 底部导航图标（占位单字，后续替换为 UI 图标） */
    private static final String[] BOTTOM_ICONS = {"语", "表", "图", "拍", "+"};
    private static final int[] BOTTOM_ICON_COLORS = {
            0xFF4CAF50, 0xFFFF9800, 0xFF2196F3, 0xFF9C27B0, 0xFF607D8B
    };

    private final UUID maidUuid;
    private final String maidName;
    private final List<ChatMessage> messages = new ArrayList<>();
    private String currentInput = "";
    private boolean inputActive = false;

    /** 光标字符索引（由 PhoneInputScreen 实时同步） */
    private int cursorIndex = 0;

    /** 底部面板是否展开 */
    private boolean bottomPanelOpen = false;

    /** 输入框文本滚动偏移：当前可视区域的第一行在全部行列表中的索引 */
    private int inputScrollOffset = 0;

    public ChatPage(UUID maidUuid, String maidName) {
        this.maidUuid = maidUuid;
        this.maidName = maidName;
        // 加载历史
        for (ChatHistoryStorage.ChatMessageEntry e : ChatHistoryStorage.getMessages(maidUuid)) {
            messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError()));
        }
        // 恢复草稿
        this.currentInput = ChatHistoryStorage.getDraft(maidUuid);
        this.cursorIndex = currentInput.length();
        // 清除未读
        NotificationManager.clear(maidUuid);
    }

    public UUID getMaidUuid() { return maidUuid; }
    public String getCurrentInputText() { return currentInput; }

    /** PhoneInputScreen 读取光标索引（供上下键移动后回写隐藏 EditBox） */
    public int getCursorIndex() { return cursorIndex; }

    /** PhoneInputScreen 同步光标索引 */
    public void setCursorIndex(int idx) {
        this.cursorIndex = Math.max(0, Math.min(currentInput.length(), idx));
    }

    /** 输入框实时更新（由 PhoneInputScreen 调用） */
    public void onInputChanged(String text) {
        this.currentInput = text;
        this.cursorIndex = Math.min(this.cursorIndex, text.length());
        // 输入内容变化时，保持滚动偏移在有效范围（渲染时会钳制，但这里确保后续光标计算正确）
        ensureCursorVisible();
        ChatHistoryStorage.setDraft(maidUuid, text);
    }

    /** 输入结束：send=true 则发送 */
    public void onInputFinished(String text, boolean send) {
        this.inputActive = false;
        this.currentInput = text;
        if (send && !text.isBlank()) {
            sendCurrentMessage();
        } else {
            ChatHistoryStorage.setDraft(maidUuid, text);
        }
    }

    /** 输入屏幕关闭（ESC / 点击其他区域） */
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
        messages.add(new ChatMessage(text, true, false));
        ChatHistoryStorage.appendMessage(maidUuid, text, true, false);
        ChatHistoryStorage.setDraft(maidUuid, "");
        PacketDistributor.sendToServer(new C2SMaidChatPacket(maidUuid, text));
        currentInput = "";
        cursorIndex = 0;
        inputScrollOffset = 0; // 发送后重置滚动位置
    }

    // ==================== 布局常量 ====================

    private int phoneW() { return PhoneOverlay.getPhoneWidth(); }
    private int phoneH() { return PhoneOverlay.getPhoneHeight(); }
    private int navH() { return (int)(phoneH() * 0.08); }
    private int bottomNavH() { return (int)(phoneH() * 0.09); }
    private int bottomPanelH() { return bottomPanelOpen ? (int)(phoneH() * 0.18) : 0; }
    private int inputH() {
        int base = (int)(phoneH() * 0.10);
        int min = Minecraft.getInstance().font.lineHeight * MAX_VISIBLE_INPUT_LINES + 8;
        return Math.max(base, min);
    }

    /** 输入框底部 = 底部导航栏上方（展开面板时再上移） */
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

    /** 光标闪烁：500ms 亮 / 500ms 灭 */
    private boolean isCursorVisible() {
        return (System.currentTimeMillis() % 1000) < 500;
    }

    // ==================== 滚动相关 ====================

    /**
     * 计算当前可视区域应该从哪一行开始显示。
     * @param totalLines 输入文本分词后的总行数
     * @return 起始行索引（0开始），保证合法且优先显示最后 MAX_VISIBLE_INPUT_LINES 行
     */
    private int getVisibleStartIdx(int totalLines) {
        int maxStart = Math.max(0, totalLines - MAX_VISIBLE_INPUT_LINES);
        // 如果滚动偏移越界，修正到合法范围
        return Math.max(0, Math.min(inputScrollOffset, maxStart));
    }

    /**
     * 确保光标所在行在可视区域内。
     * 如果光标行 < 当前显示起始行，则滚动使该行成为第一行；
     * 如果光标行 >= 当前显示起始行 + MAX_VISIBLE_INPUT_LINES，则滚动使该行成为最后一行。
     */
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
        int visibleEnd = visibleStart + MAX_VISIBLE_INPUT_LINES; // 超出末尾也没关系

        if (cursorLine < visibleStart) {
            // 光标在可视区域上方，将光标行设为起始行
            inputScrollOffset = cursorLine;
        } else if (cursorLine >= visibleEnd) {
            // 光标在可视区域下方（或太靠后），将光标行设为最后一行
            inputScrollOffset = Math.max(0, cursorLine - MAX_VISIBLE_INPUT_LINES + 1);
        }
        // 再次钳制
        inputScrollOffset = Math.max(0, Math.min(inputScrollOffset, Math.max(0, lines.size() - MAX_VISIBLE_INPUT_LINES)));
    }

    /** 滚动输入框内容（供鼠标滚轮调用） */
    public void scrollInput(int delta) {
        Font font = Minecraft.getInstance().font;
        int inputTextW = sendX(0) - inputLeft(0) - 10;
        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        int maxStart = Math.max(0, lines.size() - MAX_VISIBLE_INPUT_LINES);
        // delta > 0 表示向上滚动（显示更早内容），delta < 0 向下滚动
        inputScrollOffset = Math.max(0, Math.min(maxStart, inputScrollOffset - delta));
    }

    // ==================== 屏幕坐标判定（供 PhoneInputScreen 调用） ====================

    /** 判断屏幕坐标是否落在发送按钮上 */
    public boolean isSendButtonHitScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY();
        return relX >= sendX(0) && relX <= sendX(0) + sendW()
                && relY >= sendY(0) && relY <= sendY(0) + sendH();
    }

    /** 判断屏幕坐标是否落在输入框区域 */
    public boolean isInputAreaHitScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY();
        return relY >= inputTop(0) && relY <= inputBottom(0)
                && relX >= 5 && relX <= phoneW() - 5;
    }

    /** 根据屏幕坐标计算光标字符索引（供 PhoneInputScreen 点击定位） */
    public int getCursorIndexAtScreen(double screenX, double screenY) {
        double relX = screenX - PhoneOverlay.getPhoneX();
        double relY = screenY - PhoneOverlay.getPhoneY();
        Font font = Minecraft.getInstance().font;
        int textX = 10;
        int lineH = font.lineHeight + 1;
        int iTop = inputTop(0);
        int inputTextW = sendX(0) - inputLeft(0) - 10;

        List<String> lines = wrapToLines(font, currentInput, inputTextW);
        if (lines.isEmpty()) return 0;
        int startIdx = getVisibleStartIdx(lines.size());  // ✅ 使用滚动偏移

        // 计算点击所在视觉行
        int rowInVisible = (int)((relY - (iTop + 6)) / lineH);
        rowInVisible = Math.max(0, Math.min(lines.size() - startIdx - 1, rowInVisible));
        int globalLine = startIdx + rowInVisible;
        String line = lines.get(globalLine);

        // 计算行内列
        int col = 0;
        int accumulatedX = 0;
        for (int i = 0; i < line.length(); i++) {
            int charW = font.width(String.valueOf(line.charAt(i)));
            if (relX - textX < accumulatedX + charW / 2.0) {
                break;
            }
            accumulatedX += charW;
            col = i + 1;
        }

        // 光标索引 = 前面所有行字符总数 + 行内偏移
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
        Font font = Minecraft.getInstance().font;
        int w = phoneW();

        // ---- 顶部导航 ----
        g.fill(baseX, baseY, baseX + w, baseY + navH(), 0xFF333333);
        g.drawString(font, "< 返回", baseX + 4, baseY + 3, 0xFFFFFFFF);
        int nameW = font.width(maidName);
        g.drawString(font, maidName, baseX + (w - nameW) / 2, baseY + 3, 0xFFFFFFFF);
        // 右上角设置齿轮（占位）
        g.drawString(font, "⚙", baseX + w - 16, baseY + 3, 0xFFFFFFFF);

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

        // ---- 展开面板（占位）：位于输入框下方、底部导航栏上方 ----
        if (bottomPanelOpen) {
            int panelTop = inputBottom(baseY);                 // 输入框底部
            int panelBottom = baseY + phoneH() - bottomNavH(); // 底部导航栏顶部
            g.fill(baseX + 2, panelTop, baseX + w - 2, panelBottom - 2, 0xFF1E1E1E);
            int panelMidY = (panelTop + panelBottom) / 2;
            g.drawCenteredString(font, "🚧 面板开发中", baseX + w / 2,
                    panelMidY - font.lineHeight / 2, 0xFFAAAAAA);
        }

        // ================= 输入框（换行 + 滚动 + 闪烁光标） =================
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
            int startIdx = getVisibleStartIdx(lines.size()); // ✅ 使用滚动偏移
            int drawY = iTop + 6;

            for (int i = startIdx; i < lines.size() && i < startIdx + MAX_VISIBLE_INPUT_LINES; i++) {
                g.drawString(font, lines.get(i), textX, drawY, 0xFFFFFFFF);
                drawY += lineH;
            }

            // 闪烁光标：跟随真实光标索引
            if (inputActive && isCursorVisible()) {
                int[] lineCol = cursorIndexToLineCol(lines, cursorIndex);
                int globalLine = lineCol[0];
                int col = lineCol[1];
                if (globalLine >= startIdx && globalLine < startIdx + MAX_VISIBLE_INPUT_LINES) {
                    String targetLine = lines.get(globalLine);
                    int safeCol = Math.min(col, targetLine.length());
                    int cursorDrawX = textX + font.width(targetLine.substring(0, safeCol));
                    int cursorDrawY = iTop + 6 + (globalLine - startIdx) * lineH;
                    g.fill(cursorDrawX, cursorDrawY - 1, cursorDrawX + 1, cursorDrawY + font.lineHeight, 0xFFFFFFFF);
                }
            }
        }

        // ---- 发送按钮 ----
        g.fill(sendX(baseX), sendY(baseY), sendX(baseX) + sendW(), sendY(baseY) + sendH(), 0xFF00AA00);
        g.drawString(font, "发送", sendX(baseX) + 10, sendY(baseY) + 4, 0xFFFFFFFF);

        // ================= 底部导航栏 =================
        int bNavTop = baseY + phoneH() - bottomNavH();
        g.fill(baseX, bNavTop, baseX + w, baseY + phoneH(), 0xFF2A2A2A);
        g.fill(baseX, bNavTop, baseX + w, bNavTop + 1, 0xFF555555);

        int iconCount = BOTTOM_ICONS.length;
        int iconSlotW = w / iconCount;
        for (int i = 0; i < iconCount; i++) {
            int iconCenterX = baseX + i * iconSlotW + iconSlotW / 2;
            int iconCenterY = bNavTop + bottomNavH() / 2;
            // 图标背景块
            int blockSize = (int)(iconSlotW * 0.5);
            g.fill(iconCenterX - blockSize / 2, iconCenterY - blockSize / 2,
                    iconCenterX + blockSize / 2, iconCenterY + blockSize / 2,
                    BOTTOM_ICON_COLORS[i] | 0xFF000000);
            // 单字标签居中画在色块内，避免溢出屏幕
            int labelW = font.width(BOTTOM_ICONS[i]);
            g.drawString(font, BOTTOM_ICONS[i],
                    iconCenterX - labelW / 2,
                    iconCenterY - font.lineHeight / 2 + 1,
                    0xFFFFFFFF);
        }
    }

    /** 绘制单条消息气泡，返回气泡总高度 */
    private int drawBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m) {
        boolean isError = m.isError();
        boolean isPlayer = m.isPlayer();
        int textColor = isPlayer ? 0xFFFFFFFF : 0xFF333333;
        int bgColor = isError ? 0xFFFFCCCC : (isPlayer ? 0xFF0066CC : 0xFFEFEFEF);

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
            drawAvatarPlaceholder(g, baseX + phoneW - AVATAR - 2, yTop, isPlayer);
        } else {
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

    /** 头像占位 */
    private void drawAvatarPlaceholder(GuiGraphics g, int x, int y, boolean isPlayer) {
        g.fill(x, y, x + AVATAR, y + AVATAR, isPlayer ? 0xFF8888FF : 0xFFFF8888);
    }

    // ==================== 文本换行工具 ====================

    /** 纯按宽度换行（输入文本无 \\n，每行是原始子串） */
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

    /** 光标字符索引 -> [行号, 行内偏移] */
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

    /** 行号 + 列号 -> 字符索引（与 cursorIndexToLineCol 互为逆运算） */
    private int lineColToCursorIndex(List<String> lines, int targetLine, int targetCol) {
        int base = 0;
        for (int i = 0; i < targetLine; i++) {
            base += lines.get(i).length();
        }
        int maxCol = lines.get(targetLine).length();
        return base + Math.min(targetCol, maxCol);
    }

    /** ✅ 上方向键：移动到上一视觉行的相同列（第一行则跳到行首） */
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
        ensureCursorVisible(); // ✅ 自动滚动跟随
    }

    /** ✅ 下方向键：移动到下一视觉行的相同列（最后一行则跳到行尾） */
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
        ensureCursorVisible(); // ✅ 自动滚动跟随
    }

    /** 消息气泡文本换行（渲染用） */
    private List<FormattedCharSequence> wrapText(Font font, String text, int maxWidth) {
        return font.split(Component.literal(text), maxWidth);
    }

    // ==================== 点击处理 ====================

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        // 页面只处理左键（右键已被 PhoneOverlay 拦截为返回）
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int baseX = 0, baseY = 0;
        int w = phoneW();

        // 返回按钮
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH()) {
            PhoneOverlay.popPage();
            return true;
        }

        // 右上角设置齿轮
        if (relX >= w - 24 && relX <= w && relY >= 0 && relY <= navH()) {
            PhoneOverlay.pushPage(new ComingSoonPage());
            return true;
        }

        // 底部导航栏图标
        int bNavTop = phoneH() - bottomNavH();
        if (relY >= bNavTop && relY <= phoneH()) {
            int iconCount = BOTTOM_ICONS.length;
            int iconSlotW = w / iconCount;
            int clickedIcon = (int)(relX / iconSlotW);
            if (clickedIcon >= 0 && clickedIcon < iconCount) {
                // 点击底部图标：切换展开面板（占位）
                bottomPanelOpen = !bottomPanelOpen;
                return true;
            }
        }

        // 发送按钮
        if (relX >= sendX(baseX) && relX <= sendX(baseX) + sendW()
                && relY >= sendY(baseY) && relY <= sendY(baseY) + sendH()) {
            sendCurrentMessage();
            return true;
        }

        // 输入框区域
        if (relY >= inputTop(baseY) && relY <= inputBottom(baseY)
                && relX >= 5 && relX <= w - 5) {
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