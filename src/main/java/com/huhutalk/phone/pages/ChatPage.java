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
import com.huhutalk.phone.UiFont;
import com.huhutalk.phone.UiShapes;
import com.huhutalk.phone.voice.VoiceInput;
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
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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
    /** 当前打开的是底部哪个图标的扩展面板（-1 表示未打开） */
    private int extPanelIcon = -1;
    private int inputScrollOffset = 0;
    /** 是否正在等待女仆回复（用于"对方正在输入…"） */
    private boolean waitingResponse = false;
    /** 消息区纵向滚动偏移（像素），滚轮上滚看更早消息 */
    private int scrollOffset = 0;
    /** 滚轮滚动速度（像素/格） */
    private static final int SCROLL_SPEED = 12;

    // ==================== 表情包（左二 🦊 按钮） ====================
    /** 表情面板每行数量 */
    private static final int EMOTE_COLS = 3;
    /** 表情单元格边距（像素） */
    private static final int EMOTE_ITEM_PAD = 4;
    /** 表情在气泡里的绘制尺寸（像素） */
    private static final int EMOTE_DRAW_SIZE = 36;
    /** 表情面板纵向滚动偏移（像素），滚轮在面板内滚动 */
    private int emojiScrollOffset = 0;
    /** 已枚举的女仆表情缓存（首次打开时填充） */
    private List<EmoteEntry> cachedEmotes = null;
    /** 待回复的表情定时时间戳队列（System.currentTimeMillis）。每次发送表情入队一个，到点随机补一条回复 */
    private final List<Long> pendingEmoteReplies = new ArrayList<>();
    private static final Random EMOTE_RANDOM = new Random();

    /** 车万女仆(TLM)自带表情包路径前缀 */
    private static final String MAID_EMOJI_DIR = "textures/chat_bubble/maid_emoji";
    /** 一张女仆表情（资源位置 + 纹理宽高，尺寸从文件名 `-WxH.png` 解析，默认 24x24） */
    private record EmoteEntry(ResourceLocation loc, int w, int h) {}

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
    /** 当前断句回复的发送时间（游戏时间 tick）：同一回复被拆成的短句共享一致时间，便于分组显示 */
    private long splitGameTime = 0;
    /** 是否正在断句逐条显示（期间"对方正在输入…"保持） */
    private boolean splitting = false;

    // ==================== 语音（按住说话） ====================
    /** 语音输入来源：鼠标按住按钮 / F 快捷键 */
    public enum VoiceSource { MOUSE, KEY }
    /** 是否正在录音（按住说话进行中） */
    private boolean voiceActive = false;
    /** 当前录音来源 */
    private VoiceSource voiceSource = VoiceSource.KEY;

    public ChatPage(String stableId, String maidName) {
        this.stableId = stableId;
        this.maidName = maidName;
        for (ChatHistoryStorage.ChatMessageEntry e : ChatHistoryStorage.getMessages(stableId)) {
            if (e.emote() != null) {
                // 表情消息：直接整条恢复（不按逗号拆句），保留 emote 路径
                messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError(), e.pending(), e.gameTime(), e.emote()));
            } else if (!e.isPlayer() && !e.isError()) {
                // 历史中的女仆正常回复：按断句规则拆成多条气泡显示（与实时收到时一致），
                // 避免重新进入世界后整段回复被合成一个大块。错误/玩家消息保持原样。
                List<String> segments = MessageSplitter.split(e.text());
                for (String seg : segments) {
                    messages.add(new ChatMessage(seg, false, false, false, e.gameTime(), null));
                }
            } else {
                messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError(), e.pending(), e.gameTime(), null));
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
                messages.set(i, new ChatMessage(m.text(), true, m.isError(), true, m.gameTime(), m.emote()));
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
                messages.set(i, new ChatMessage(m.text(), m.isPlayer(), m.isError(), false, m.gameTime(), m.emote()));
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
        cancelVoice();
    }

    // ==================== 语音（按住说话 / F 快捷键） ====================

    /** 开始录音（按住说话）。未配置 STT 或已在录音时不会重复开启。 */
    public void startVoice(VoiceSource source) {
        if (voiceActive) return;
        if (VoiceInput.isRecording()) return;
        boolean ok = VoiceInput.start(this::sendText);
        if (ok) {
            voiceActive = true;
            voiceSource = source;
        } else if (!VoiceInput.isConfigured()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "[HuhuTalk] 未配置语音识别，请先在车万女仆的 AI 设置中配置并启用 STT 站点。"));
            }
        }
    }

    /** 停止录音并发送识别文字。cancel=true 丢弃本次结果（右键取消）。 */
    public void stopVoice(boolean cancel) {
        if (!voiceActive && !VoiceInput.isRecording()) return;
        voiceActive = false;
        VoiceInput.stop(cancel);
    }

    /** 右键取消：停止录音并丢弃结果 */
    public void cancelVoice() {
        stopVoice(true);
    }

    /** 是否正在录音（供 PhoneOverlay 在右键时拦截为取消） */
    public boolean isVoiceRecording() {
        return voiceActive || VoiceInput.isRecording();
    }

    /** 把一段文字作为"玩家发送"的消息加入聊天并发送给当前女仆（语音转文字结果走这里） */
    private void sendText(String text) {
        if (text == null || text.isBlank()) return;
        messages.add(new ChatMessage(text, true, false, false, currentGameTime(), null));
        ChatHistoryStorage.appendMessage(stableId, text, true, false);
        ChatHistoryStorage.setDraft(stableId, "");
        PacketDistributor.sendToServer(new C2SMaidChatPacket(stableId, text));
        this.waitingResponse = true;
        scrollOffset = 0;
    }

    public void onReceiveMessage(String text, boolean isError) {
        // 收到女仆回复/提示 → 视为送达，清除未送达标记
        clearPending();
        // 回到最新消息（让新回复可见）
        scrollOffset = 0;
        // 错误/提示消息：整段显示，不拆句
        if (isError) {
            messages.add(new ChatMessage(text, false, true, false, currentGameTime(), null));
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
            messages.add(new ChatMessage(text, false, false, false, currentGameTime(), null));
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
        splitGameTime = currentGameTime(); // 该回复的所有短句共享同一发送时间
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
            messages.add(new ChatMessage(splitQueue.get(splitIndex), false, false, false, splitGameTime, null));
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
        messages.add(new ChatMessage(text, true, false, false, currentGameTime(), null));
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

    /** 全局字体倍率 */
    private static float uiScale() { return UiFont.uiScale(); }
    /** 缩放后默认字体的行高 */
    private static int lh() { return Math.max(1, (int) (Minecraft.getInstance().font.lineHeight * UiFont.sc(1.0f))); }
    /** 顶部导航栏高度：随字体倍率缩放 */
    private int navH() { return lh() + UiFont.sh(8); }

    /** 当前客户端世界游戏时间（tick）。无世界时返回 0 */
    private static long currentGameTime() {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    /** 多久以前（复用车万女仆 HistoryChatWidget 的换算：24000 tick/天、1000 tick/小时） */
    private static String formatGameTime(long gameTime) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return "";
        long diff = level.getGameTime() - gameTime;
        if (diff < 0) return "";
        long days = diff / 24000L;
        long hours = (diff % 24000L) / 1000L;
        if (days > 0) return days + "天前";
        if (hours > 0) return hours + "小时前";
        return "刚刚";
    }

    /** 气泡上方时间标签的高度（0.5 倍字号 + 间距） */
    private static int timeH() {
        return (int) (Minecraft.getInstance().font.lineHeight * UiFont.sc(0.5f)) + UiFont.sh(2);
    }

    // ==================== 输入区 / 拓展栏布局（动态高度） ====================
    /** 输入框左边距 = 发送按钮右边距 = 输入框与发送按钮的间距（三者相等） */
    private static final int FIELD_MARGIN = 8;
    /** 输入框内文字水平留白 */
    private static final int PAD_X = 10;
    /** 输入框内文字垂直留白（每侧） */
    private static final int PAD_Y = 4;

    /** 单行输入框高度（= 拓展栏高度 = 发送按钮高度） */
    private int singleLineH() {
        return lh() + UiFont.sh(PAD_Y) * 2;
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
        int lines = wrapToLines(font, currentInput, (int) (inputTextWidth() / uiScale())).size();
        lines = Math.max(1, Math.min(lines, MAX_VISIBLE_INPUT_LINES));
        return lines * (lh() + 1) + UiFont.sh(PAD_Y) * 2;
    }

    /** 拓展功能栏高度（与单行输入框一致），贴屏幕底部 */
    private int extBarTop(int baseY) { return baseY + phoneH() - singleLineH(); }
    /** 输入框底：面板打开时被顶到面板上方，否则紧贴拓展栏 */
    private int inputBottom(int baseY) { return bottomPanelOpen ? panelTop(baseY) : extBarTop(baseY); }
    private int inputTop(int baseY) { return inputBottom(baseY) - inputHeight(); }
    private int inputLeft(int baseX) { return baseX + FIELD_MARGIN; }
    private int inputRight(int baseX) { return sendX(baseX) - FIELD_MARGIN; }
    private int sendX(int baseX) { return baseX + phoneW() - FIELD_MARGIN - sendW(); }
    private int sendH() { return singleLineH(); }
    /** 发送按钮垂直中心始终与输入框垂直中心对齐，高度固定 */
    private int sendY(int baseY) { return (inputTop(baseY) + inputBottom(baseY)) / 2 - sendH() / 2; }

    /** 上拉面板高度（打开时在拓展栏上方展开）。语音面板（左一图标）更高，好放"按住说话"大按钮。 */
    private int panelH() {
        if (!bottomPanelOpen) return 0;
        if (extPanelIcon == 0) return (int)(phoneH() * 0.26);
        // 表情面板（左二 🦊）：更高，好放三列可滚动表情网格
        if (extPanelIcon == 1) return (int)(phoneH() * 0.45);
        return (int)(phoneH() * 0.18);
    }
    /** 面板顶：直接位于拓展功能栏上方；打开时把输入框往上顶，而不是从输入框上方伸出 */
    private int panelTop(int baseY) { return extBarTop(baseY) - panelH(); }

    /** 语音大按钮直径 = 扩展栏(面板)高度的 5/6 */
    private int voiceBtnD() { return Math.max(1, (int)(panelH() * 5.0 / 6.0)); }
    /** 语音大按钮圆心（内容区相对坐标） */
    private int voiceBtnCy() { return (panelTop(0) + extBarTop(0)) / 2; }

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
        int inputTextW = (int) (inputTextWidth() / uiScale());
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
        int inputTextW = (int) (inputTextWidth() / uiScale());
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
        int lineH = lh() + 1;
        int iTop = inputTop(0);
        int inputTextW = (int) (inputTextWidth() / uiScale());

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
        // 每帧推进表情回复（1 秒延迟随机回复）
        advanceEmoteReply();
        // 鼠标按住说话：左键松开（无论是否在手机范围内）即停止并发送
        if (voiceActive && voiceSource == VoiceSource.MOUSE && !isLeftMouseDown()) {
            stopVoice(false);
        }
        Font font = Minecraft.getInstance().font;
        int w = phoneW();

        // 页面底色 #f0e4ea（比消息列表略深），覆盖黑色手机底层
        g.fill(baseX, baseY + navH(), baseX + w, baseY + phoneH(), 0xFFF0E4EA);

        // ==================== 顶部导航栏 ====================
        g.fill(baseX, baseY, baseX + w, baseY + navH(), 0xFFDE7E7E);
        int navCenterY = baseY + navH() / 2;
        // 返回按钮：仅保留白色箭头图标，删除"返回"文字
        drawScaledText(g, font, "←", baseX + UiFont.sh(6), navCenterY - lh() / 2, 0xFFFFFFFF, UiFont.sc(1.0f));
        // 返回按钮右侧靠左放置女仆名称 + 状态（保留字号比例，垂直居中；超宽截断避免压住设置钮）
        String titleText = waitingResponse ? "对方正在输入…" : maidName;
        int nameX = baseX + UiFont.sh(22);
        int gearX = baseX + w - UiFont.sh(16);
        int titleMaxW = gearX - nameX - UiFont.sh(8);
        String displayTitle = titleText;
        if (font.width(titleText) * UiFont.uiScale() > titleMaxW) {
            int room = Math.max(1, (int) ((titleMaxW - font.width("…") * UiFont.uiScale()) / UiFont.uiScale()));
            String cut = font.plainSubstrByWidth(titleText, room);
            displayTitle = cut.isEmpty() ? "" : cut + "…";
        }
        drawScaledText(g, font, displayTitle, nameX, navCenterY - lh() / 2, 0xFFFFFFFF, UiFont.sc(1.0f));
        // 状态小字（半字号，紧跟名称之后，保留原比例；等待回复时不显示）
        String status = MaidStatusCache.get(stableId);
        if (!waitingResponse && !status.isEmpty()) {
            drawScaledText(g, font, status,
                    nameX + font.width(displayTitle) * UiFont.sc(1.0f) + UiFont.sh(8),
                    navCenterY - 3, 0xFF8A5B5B, UiFont.sc(STATUS_SCALE));
        }
        // 右上角设置按钮（保留原有功能）
        drawScaledText(g, font, "⚙", gearX, navCenterY - lh() / 2, 0xFFFFFFFF, UiFont.sc(1.0f));

        // ==================== 消息区（面板打开时输入框上移，消息区同步上移） ====================
        int msgTop = baseY + navH();
        int msgBottom = inputTop(baseY) - 2;
        int viewH = Math.max(0, msgBottom - msgTop);

        int[] groupStart = computeGroupStart();
        int n = messages.size();
        int totalH = 0;
        for (int i = 0; i < n; i++) {
            boolean groupTop = groupStart[i] == i;
            totalH += bubbleHeight(font, messages.get(i), w, groupTop) + 4;
        }
        int maxScroll = Math.max(0, totalH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        g.enableScissor(baseX, msgTop, baseX + w, msgBottom);
        int y = msgBottom - 4 + scrollOffset;
        for (int i = n - 1; i >= 0 && y > msgTop; i--) {
            ChatMessage m = messages.get(i);
            boolean groupTop = groupStart[i] == i;
            int idxInGroup = i - groupStart[i];
            // 女仆头像按 1/4/7… 段位显示；玩家消息始终显示头像；表情消息始终显示头像
            boolean showAvatar = m.emote() != null || m.isPlayer() || (idxInGroup % 3 == 0);
            int totalBubble = drawBubble(g, font, baseX, y, w, m, showAvatar, groupTop);
            y -= totalBubble + 4;
        }
        g.disableScissor();

        // ==================== 输入区衬底 + 顶部细阴影 ====================
        // 输入区整体与页面同色，顶部细阴影与消息列表分隔
        int iTop = inputTop(baseY);
        drawTopShadow(g, baseX, iTop - 2, w);

        // ==================== 上拉面板（位于拓展栏上方；打开时把输入框顶上去） ====================
        if (bottomPanelOpen) {
            int pTop = panelTop(baseY);
            int pBottom = extBarTop(baseY);
            g.fill(baseX, pTop, baseX + w, pBottom, 0xFFF5E9EF);
            // 面板底部用淡灰色细线与拓展功能栏分隔
            g.fill(baseX, pBottom - 1, baseX + w, pBottom, 0xFFD9D2D6);
            if (extPanelIcon == 0) {
                renderVoiceButton(g, font, baseX, pTop, pBottom, w);
            } else if (extPanelIcon == 1) {
                renderEmotePanel(g, font, baseX, pTop, pBottom, w);
            } else {
                int panelMidY = (pTop + pBottom) / 2;
                String panelText = "🚧 面板开发中";
                drawScaledText(g, font, panelText,
                        baseX + (w - font.width(panelText) * UiFont.sc(1.0f)) / 2,
                        panelMidY - lh() / 2, 0xFF8A6A6A, UiFont.sc(1.0f));
            }
        }

        // ==================== 输入框（胶囊形，随内容增高，最多 3 行） ====================
        int iBottom = inputBottom(baseY);
        int iW = inputRight(baseX) - inputLeft(baseX);
        int iH = iBottom - iTop;
        UiShapes.fillRoundedRect(g, inputLeft(baseX), iTop, iW, iH, iH / 2, 0xFFFFF5F8);

        int textX = inputLeft(baseX) + PAD_X;
        int lineH = lh() + 1;

        if (currentInput.isEmpty()) {
            // 占位符（垂直居中）：灰色，75% 不透明度，无阴影
            int phY = iTop + (iH - lh()) / 2;
            drawScaledText(g, font, "输入消息...", textX, phY, 0xBF9E9E9E, UiFont.sc(1.0f));
            if (inputActive && isCursorVisible()) {
                int cx = textX + (int) (font.width("输入消息...") * UiFont.sc(1.0f));
                g.fill(cx, phY - 1, cx + 1, phY + lh(), 0xFF8A6A6A);
            }
        } else {
            List<String> lines = wrapToLines(font, currentInput, (int) (inputTextWidth() / uiScale()));
            int startIdx = getVisibleStartIdx(lines.size());
            int drawY = iTop + UiFont.sh(PAD_Y);

            for (int i = startIdx; i < lines.size() && i < startIdx + MAX_VISIBLE_INPUT_LINES; i++) {
                drawScaledText(g, font, lines.get(i), textX, drawY, 0xFF4A3F44, UiFont.sc(1.0f));
                drawY += lineH;
            }

            if (inputActive && isCursorVisible()) {
                int[] lineCol = cursorIndexToLineCol(lines, cursorIndex);
                int globalLine = lineCol[0];
                int col = lineCol[1];
                if (globalLine >= startIdx && globalLine < startIdx + MAX_VISIBLE_INPUT_LINES) {
                    String targetLine = lines.get(globalLine);
                    int safeCol = Math.min(col, targetLine.length());
                    int cursorDrawX = textX + (int) (font.width(targetLine.substring(0, safeCol)) * UiFont.sc(1.0f));
                    int cursorDrawY = iTop + UiFont.sh(PAD_Y) + (globalLine - startIdx) * lineH;
                    g.fill(cursorDrawX, cursorDrawY - 1, cursorDrawX + 1,
                            cursorDrawY + lh(), 0xFF8A6A6A);
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
        drawScaledText(g, font, sendLabel,
                sX + (sW - font.width(sendLabel) * UiFont.sc(1.0f)) / 2,
                sY + (sH - lh()) / 2, 0xFFFFFFFF, UiFont.sc(1.0f));

        // ==================== 拓展功能栏（高度=单行输入框，贴屏幕底部） ====================
        int extTop = extBarTop(baseY);
        int extCenterY = extTop + singleLineH() / 2;
        int iconCount = BOTTOM_ICONS.length;
        int iconSlotW = w / iconCount;
        int btnSize = singleLineH() - UiFont.sh(6);
        int btnR = btnSize / 2;
        for (int i = 0; i < iconCount; i++) {
            int cx = baseX + i * iconSlotW + iconSlotW / 2;
            UiShapes.fillRoundedRect(g, cx - btnSize / 2, extCenterY - btnSize / 2, btnSize, btnSize, btnR, 0xFFFFF5F8);
            String icon = BOTTOM_ICONS[i];
            // 去掉默认字体阴影，避免 emoji 图标发糊
            drawScaledText(g, font, icon, cx - font.width(icon) * UiFont.sc(1.0f) / 2,
                    extCenterY - lh() / 2 + 1, 0xFF6A5A5A, UiFont.sc(1.0f));
        }
    }

    /** 计算某条消息气泡的高度（含上方时间标签，与 drawBubble 的换行/行高逻辑一致），用于滚动钳制 */
    /**
     * 相邻且 gameTime 相同的一批消息视为同一「时刻分组」（同一回复被拆成的短句）。
     * 返回数组：每个下标对应该消息所在分组的起始下标。
     */
    private int[] computeGroupStart() {
        int n = messages.size();
        int[] gs = new int[n];
        for (int i = 0; i < n; i++) {
            if (i > 0 && messages.get(i).gameTime() == messages.get(i - 1).gameTime()) {
                gs[i] = gs[i - 1];
            } else {
                gs[i] = i;
            }
        }
        return gs;
    }

    /**
     * 计算某条消息气泡的高度（与 drawBubble 换行/行高逻辑一致），用于滚动钳制。
     * groupTop = 该条是「同一时刻分组」的顶部时，需多预留 timeH 放居中时间标签。
     */
    private int bubbleHeight(Font font, ChatMessage m, int phoneW, boolean groupTop) {
        // 表情消息：固定高度
        if (m.emote() != null) {
            return emoteBubbleSize() + (groupTop ? timeH() : 0);
        }
        int maxTextW = (int) ((phoneW - AVATAR * 2 - MARGIN * 4) / uiScale());
        List<FormattedCharSequence> lines = font.split(Component.literal(m.text()), maxTextW);
        int lineH = lh() + 1;
        int base = lines.size() * lineH + UiFont.sh(4);
        return base + (groupTop ? timeH() : 0);
    }

    /**
     * 绘制一条消息气泡。
     * groupTop = 本消息是「同一时刻分组」的顶部，在其正上方屏幕中间显示发送时间；
     * showAvatar = 女仆头像是否在本气泡显示（同一被拆分消息按 1/4/7… 段位合并；玩家始终显示）。
     */
    private int drawBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m,
                           boolean showAvatar, boolean groupTop) {
        // 表情消息：走纯图片气泡（始终带头像）
        if (m.emote() != null) {
            return drawEmoteBubble(g, font, baseX, yBottom, phoneW, m, showAvatar, groupTop);
        }
        boolean isError = m.isError();
        boolean isPlayer = m.isPlayer();
        com.huhutalk.client.theme.ColorScheme scheme = com.huhutalk.client.theme.ThemeManager.getActive();
        // 女仆气泡：深灰蓝底白字；玩家气泡：亮蓝底白字；错误气泡：沿用主题浅红（仍圆角）
        int textColor = (isError && !isPlayer) ? scheme.errorText() : 0xFFFFFFFF;
        int bgColor = isError ? scheme.errorBubble()
                : (isPlayer ? 0xFF5294BF : 0xFF525A6E);

        // 换行宽度按缩放后折算，保证放大字体时文字仍不超出气泡
        int maxTextW = (int) ((phoneW - AVATAR * 2 - MARGIN * 4) / uiScale());
        List<FormattedCharSequence> lines = font.split(Component.literal(m.text()), maxTextW);

        int longest = 0;
        for (FormattedCharSequence line : lines) {
            longest = Math.max(longest, font.width(line));
        }
        int bubbleW = Math.min((int) (longest * uiScale()) + UiFont.sh(8),
                phoneW - AVATAR * 2 - MARGIN * 2);
        int lineH = lh() + 1;
        int bubbleH = lines.size() * lineH + UiFont.sh(4);
        int yTop = yBottom - bubbleH;
        // 圆角方框：圆角半径 = 1/4 方框高
        int radius = Math.max(2, bubbleH / 4);

        if (isPlayer) {
            int bubbleRight = baseX + phoneW - AVATAR - MARGIN;
            int bubbleLeft = bubbleRight - bubbleW;
            UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, bgColor);
            int ty = yTop + UiFont.sh(2);
            for (FormattedCharSequence line : lines) {
                drawScaledText(g, font, line, bubbleLeft + UiFont.sh(4), ty, textColor, UiFont.sc(1.0f));
                ty += lineH;
            }
            drawAvatarPlaceholder(g, baseX + phoneW - AVATAR - 2, yTop, true);
            // 未送达（入离线队列）的玩家消息标红"!"
            if (m.pending()) {
                drawScaledText(g, font, "!", bubbleLeft - UiFont.sh(8), yTop + UiFont.sh(2),
                        0xFFFF3333, UiFont.sc(1.0f));
            }
        } else {
            int bubbleLeft = baseX + AVATAR + MARGIN;
            UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, bgColor);
            int ty = yTop + UiFont.sh(2);
            for (FormattedCharSequence line : lines) {
                drawScaledText(g, font, line, bubbleLeft + UiFont.sh(4), ty, textColor, UiFont.sc(1.0f));
                ty += lineH;
            }
            // 女仆头像按 1/4/7… 段位合并显示（同一条被拆消息只显示一次 / 每三条一次）
            if (showAvatar) {
                drawAvatarPlaceholder(g, baseX + 2, yTop, false);
            }
        }
        // 同一时刻分组的顶部：在屏幕中间上方显示"xx天前"（保留 0.5 倍灰字字号与颜色）
        if (groupTop) {
            String timeText = formatGameTime(m.gameTime());
            if (!timeText.isEmpty()) {
                float ts = UiFont.sc(0.5f);
                int centerX = baseX + phoneW / 2;
                drawScaledText(g, font, timeText,
                        centerX - font.width(timeText) * ts / 2f, yTop - timeH(), 0xFF9A8A90, ts);
            }
        }
        return bubbleH + (groupTop ? timeH() : 0);
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

    /** FormattedCharSequence 版（用于气泡内已换行文本） */
    private static void drawScaledText(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y, int color, float scale) {
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
        int inputTextW = (int) (inputTextWidth() / uiScale());
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
        int inputTextW = (int) (inputTextWidth() / uiScale());
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

    /** 左键是否处于按下状态（用于"按住说话"的松手检测） */
    private static boolean isLeftMouseDown() {
        Minecraft mc = Minecraft.getInstance();
        return org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** 绘制语音面板里的圆形"按住说话"大按钮（#de7e7e 底白字，居中，直径 = 扩展栏高度 5/6） */
    private void renderVoiceButton(GuiGraphics g, Font font, int baseX, int pTop, int pBottom, int w) {
        int d = voiceBtnD();
        int cx = baseX + w / 2;
        int cy = (pTop + pBottom) / 2;
        // 录音中高亮为更深的强调色
        int color = voiceActive ? 0xFFC2605A : 0xFFDE7E7E;
        UiShapes.fillRoundedRect(g, cx - d / 2, cy - d / 2, d, d, d / 2, color);
        String label = voiceActive ? "松开发送" : "按住说话";
        float ts = UiFont.sc(0.9f);
        drawScaledText(g, font, label, cx - font.width(label) * ts / 2f,
                cy - Minecraft.getInstance().font.lineHeight * ts / 2f, 0xFFFFFFFF, ts);
    }

    // ==================== 表情包（左二 🦊 按钮） ====================

    /** 枚举车万女仆(TLM)表情包目录下所有 PNG 表情（跨命名空间/资源包），按资源名排序。 */
    private List<EmoteEntry> getAllEmotes() {
        if (cachedEmotes != null) {
            return cachedEmotes;
        }
        List<EmoteEntry> list = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager() != null) {
            var found = mc.getResourceManager().listResources(
                    MAID_EMOJI_DIR, p -> p.getPath().endsWith(".png"));
            for (ResourceLocation rl : found.keySet()) {
                int[] sz = parseEmoteSize(rl);
                list.add(new EmoteEntry(rl, sz[0], sz[1]));
            }
        }
        list.sort(Comparator.comparing(e -> e.loc().toString()));
        cachedEmotes = list;
        return list;
    }

    /** 从文件名解析表情尺寸：`emoji_20-24x24.png` → 24x24；无尺寸后缀默认 24x24。与 TLM 的解析规则一致。 */
    private static int[] parseEmoteSize(ResourceLocation rl) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^.*?-(\\d+)x(\\d+)\\.png$").matcher(rl.getPath());
        if (m.matches()) {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            w = Math.max(8, Math.min(256, w));
            h = Math.max(8, Math.min(256, h));
            return new int[]{w, h};
        }
        return new int[]{24, 24};
    }

    /** 按表情资源路径查尺寸（供气泡绘制）；找不到时默认 24x24 */
    private int[] emoteDims(String path) {
        for (EmoteEntry e : getAllEmotes()) {
            if (e.loc().toString().equals(path)) {
                return new int[]{e.w(), e.h()};
            }
        }
        return new int[]{24, 24};
    }

    /** 表情面板网格布局参数：每格宽 / 格内绘制边长 / 行高 */
    private int[] emoteGridParams() {
        int cellW = phoneW() / EMOTE_COLS;
        int itemSize = (int)(cellW * 0.7);
        int rowH = itemSize + EMOTE_ITEM_PAD * 2;
        return new int[]{cellW, itemSize, rowH};
    }

    /** 表情面板内容总高度（用于滚动钳制） */
    private int emoteContentHeight() {
        int[] p = emoteGridParams();
        int rows = (getAllEmotes().size() + EMOTE_COLS - 1) / EMOTE_COLS;
        return rows * p[2] + EMOTE_ITEM_PAD * 2;
    }

    /** 绘制表情面板：三列向下排列，鼠标在面板内时滚轮可上下滚动。 */
    private void renderEmotePanel(GuiGraphics g, Font font, int baseX, int pTop, int pBottom, int w) {
        List<EmoteEntry> emotes = getAllEmotes();
        if (emotes.isEmpty()) {
            drawScaledText(g, font, "暂无表情包",
                    baseX + (w - font.width("暂无表情包") * UiFont.sc(1.0f)) / 2,
                    pTop + UiFont.sh(8), 0xFF8A6A6A, UiFont.sc(1.0f));
            return;
        }
        int[] p = emoteGridParams();
        int viewH = Math.max(0, pBottom - pTop);
        int maxScroll = Math.max(0, emoteContentHeight() - viewH);
        emojiScrollOffset = Math.max(0, Math.min(emojiScrollOffset, maxScroll));

        g.enableScissor(baseX, pTop, baseX + w, pBottom);
        for (int i = 0; i < emotes.size(); i++) {
            EmoteEntry e = emotes.get(i);
            int row = i / EMOTE_COLS, col = i % EMOTE_COLS;
            int x = baseX + col * p[0] + (p[0] - p[1]) / 2;
            int y = pTop + EMOTE_ITEM_PAD + row * p[2] - emojiScrollOffset;
            if (y + p[1] < pTop || y > pBottom) {
                continue;
            }
            UiShapes.fillRoundedRect(g, x, y, p[1], p[1], p[1] / 4, 0xFFFFFFFF);
            int inset = UiFont.sh(2);
            g.blit(e.loc(), x + inset, y + inset, p[1] - inset * 2, p[1] - inset * 2,
                    0.0f, 0.0f, e.w(), e.h(), e.w(), e.h());
        }
        g.disableScissor();
    }

    /** 判断 (relX,relY) 落在哪个表情格子上；未命中或面板未开返回 null。坐标相对手机左上角。 */
    private EmoteEntry hitEmote(double relX, double relY) {
        if (!bottomPanelOpen || extPanelIcon != 1) {
            return null;
        }
        List<EmoteEntry> emotes = getAllEmotes();
        if (emotes.isEmpty()) {
            return null;
        }
        int[] p = emoteGridParams();
        int pTop = panelTop(0), pBottom = extBarTop(0);
        if (relY < pTop || relY >= pBottom) {
            return null;
        }
        int viewH = Math.max(0, pBottom - pTop);
        int maxScroll = Math.max(0, emoteContentHeight() - viewH);
        int soff = Math.max(0, Math.min(emojiScrollOffset, maxScroll));
        for (int i = 0; i < emotes.size(); i++) {
            int row = i / EMOTE_COLS, col = i % EMOTE_COLS;
            int x = col * p[0] + (p[0] - p[1]) / 2;
            int y = pTop + EMOTE_ITEM_PAD + row * p[2] - soff;
            if (relX >= x && relX <= x + p[1] && relY >= y && relY <= y + p[1]) {
                return emotes.get(i);
            }
        }
        return null;
    }

    /** 发送一张表情给女仆：本地立即显示玩家表情，1 秒后随机回复一张（不经过大模型）。 */
    private void sendEmote(ResourceLocation emote) {
        String path = emote.toString();
        messages.add(new ChatMessage("", true, false, false, currentGameTime(), path));
        ChatHistoryStorage.appendMessage(stableId, "", path, true, false);
        ChatHistoryStorage.setDraft(stableId, "");
        currentInput = "";
        cursorIndex = 0;
        inputScrollOffset = 0;
        scrollOffset = 0;
        waitingResponse = true; // 1 秒回复等待期显示"对方正在输入…"
        pendingEmoteReplies.add(System.currentTimeMillis() + 1000L);
    }

    /** 每帧推进表情回复：到 1 秒后随机补一张女仆表情并结束等待。在 render 每帧调用。 */
    private void advanceEmoteReply() {
        if (pendingEmoteReplies.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        while (!pendingEmoteReplies.isEmpty() && pendingEmoteReplies.get(0) <= now) {
            pendingEmoteReplies.remove(0);
            List<EmoteEntry> emotes = getAllEmotes();
            if (emotes.isEmpty()) {
                continue;
            }
            EmoteEntry reply = emotes.get(EMOTE_RANDOM.nextInt(emotes.size()));
            messages.add(new ChatMessage("", false, false, false, currentGameTime(), reply.loc().toString()));
            ChatHistoryStorage.appendMessage(stableId, "", reply.loc().toString(), false, false);
            scrollOffset = 0;
        }
        if (pendingEmoteReplies.isEmpty()) {
            waitingResponse = false;
        }
    }

    /** 表情消息气泡绘制尺寸（宽/高），含上下内边距 */
    private int emoteBubbleSize() {
        return EMOTE_DRAW_SIZE + UiFont.sh(8);
    }

    /** 绘制一条表情消息气泡：纯图片气泡，始终带头像。 */
    private int drawEmoteBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m,
                                boolean showAvatar, boolean groupTop) {
        boolean isPlayer = m.isPlayer();
        int pad = UiFont.sh(4);
        int bubbleW = emoteBubbleSize();
        int bubbleH = emoteBubbleSize();
        int yTop = yBottom - bubbleH;
        int radius = Math.max(2, bubbleH / 4);
        ResourceLocation rl = ResourceLocation.tryParse(m.emote());
        int[] dims = emoteDims(m.emote());
        int sw = dims[0], sh = dims[1];

        if (isPlayer) {
            int bubbleRight = baseX + phoneW - AVATAR - MARGIN;
            int bubbleLeft = bubbleRight - bubbleW;
            UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, 0xFF5294BF);
            if (rl != null) {
                g.blit(rl, bubbleLeft + pad, yTop + pad, EMOTE_DRAW_SIZE, EMOTE_DRAW_SIZE,
                        0.0f, 0.0f, sw, sh, sw, sh);
            } else {
                g.fill(bubbleLeft + pad, yTop + pad, bubbleLeft + pad + EMOTE_DRAW_SIZE,
                        yTop + pad + EMOTE_DRAW_SIZE, 0xFFB0BEC5);
            }
            drawAvatarPlaceholder(g, baseX + phoneW - AVATAR - 2, yTop, true);
        } else {
            int bubbleLeft = baseX + AVATAR + MARGIN;
            UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, 0xFF525A6E);
            if (rl != null) {
                g.blit(rl, bubbleLeft + pad, yTop + pad, EMOTE_DRAW_SIZE, EMOTE_DRAW_SIZE,
                        0.0f, 0.0f, sw, sh, sw, sh);
            } else {
                g.fill(bubbleLeft + pad, yTop + pad, bubbleLeft + pad + EMOTE_DRAW_SIZE,
                        yTop + pad + EMOTE_DRAW_SIZE, 0xFFB0BEC5);
            }
            if (showAvatar) {
                drawAvatarPlaceholder(g, baseX + 2, yTop, false);
            }
        }
        if (groupTop) {
            String timeText = formatGameTime(m.gameTime());
            if (!timeText.isEmpty()) {
                float ts = UiFont.sc(0.5f);
                int centerX = baseX + phoneW / 2;
                drawScaledText(g, font, timeText,
                        centerX - font.width(timeText) * ts / 2f, yTop - timeH(), 0xFF9A8A90, ts);
            }
        }
        return bubbleH + (groupTop ? timeH() : 0);
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

        // 语音面板（左一图标）里的圆形"按住说话"按钮：左键按住开始录音（松手检测在 render）
        if (bottomPanelOpen && extPanelIcon == 0) {
            int d = voiceBtnD();
            int cx = w / 2;
            int cy = voiceBtnCy();
            double dx = relX - cx, dy = relY - cy;
            double rr = d / 2.0;
            if (dx * dx + dy * dy <= rr * rr) {
                startVoice(VoiceSource.MOUSE);
                return true;
            }
        }

        // 表情面板（左二 🦊）：点击某张表情 → 发给女仆
        if (bottomPanelOpen && extPanelIcon == 1) {
            EmoteEntry hit = hitEmote(relX, relY);
            if (hit != null) {
                sendEmote(hit.loc());
                return true;
            }
        }

        // 拓展功能栏（贴屏幕底部）：点击任意按钮切换上拉面板
        int extTop = extBarTop(0);
        if (relY >= extTop && relY <= phoneH()) {
            int iconCount = BOTTOM_ICONS.length;
            int iconSlotW = w / iconCount;
            int clickedIcon = (int)(relX / iconSlotW);
            if (clickedIcon >= 0 && clickedIcon < iconCount) {
                if (bottomPanelOpen && extPanelIcon == clickedIcon) {
                    bottomPanelOpen = false;
                    extPanelIcon = -1;
                } else {
                    bottomPanelOpen = true;
                    extPanelIcon = clickedIcon;
                }
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
        // 表情面板打开且鼠标在面板内：滚动表情网格，而非消息区
        if (bottomPanelOpen && extPanelIcon == 1) {
            int pTop = panelTop(0), pBottom = extBarTop(0);
            if (relY >= pTop && relY <= pBottom) {
                int viewH = Math.max(0, pBottom - pTop);
                int maxScroll = Math.max(0, emoteContentHeight() - viewH);
                emojiScrollOffset = Math.max(0,
                        Math.min(emojiScrollOffset + (int)(scrollDelta * SCROLL_SPEED), maxScroll));
                return true;
            }
        }

        Font font = Minecraft.getInstance().font;
        int w = phoneW();
        int msgTop = navH();
        int msgBottom = inputTop(0) - 2;
        int viewH = Math.max(0, msgBottom - msgTop);
        int[] gs = computeGroupStart();
        int totalH = 0;
        for (int i = 0; i < messages.size(); i++) {
            totalH += bubbleHeight(font, messages.get(i), w, gs[i] == i) + 4;
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

    private record ChatMessage(String text, boolean isPlayer, boolean isError, boolean pending, long gameTime, String emote) {
    }
}