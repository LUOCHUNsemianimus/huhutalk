package com.huhutalk.phone.pages;

import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidAvatarManager;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.init.HuhuTalkSounds;
import com.huhutalk.network.C2SMaidChatPacket;
import com.huhutalk.network.C2SMaidChatWithItemsPacket;
import com.huhutalk.network.C2SMaidImageChatPacket;
import com.huhutalk.network.C2SClaimTransferPacket;
import com.huhutalk.network.C2SPhoneTypingDonePacket;
import com.huhutalk.network.C2SRequestMaidStatusPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneInputScreen;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.UiFont;
import com.huhutalk.phone.UiShapes;
import com.huhutalk.phone.voice.VoiceInput;
import com.huhutalk.util.MessageSplitter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("huhutalk.chat_page");

    private final String stableId;
    private final String maidName;
    private final List<ChatMessage> messages = new ArrayList<>();

    // ==================== P→M 传文件：随消息发送的物品队列 ====================
    /** 最多可随一条消息发送的物品格数（2行×5） */
    public static final int TRANSFER_CELLS = 10;
    /** 待发送物品队列：每个格子引用"我的背包"一个主槽位（-1=空）。随下次聊天发送一并发给女仆 */
    private final int[] transferQueue = new int[TRANSFER_CELLS];
    /** 是否正在等待一条"带物品消息"的服务端确认（确认前不再次发送） */
    private boolean itemSendAwait = false;
    /** 待确认那条消息的文字（便于确认成功后补气泡 / 失败后还原到输入框） */
    private String pendingItemText = "";
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

    // ==================== 拍照 & 图片（📷 / 🖼️） ====================
    /** 相机模式：正在拍照界面（替代整个聊天界面渲染） */
    private boolean cameraActive = false;
    /** 相机已拍下的 PNG 字节（null=还没拍） */
    private byte[] cameraShot = null;
    /** 当前选中待发送的图片 key（图库文件名或 captured id；null=未选） */
    private String selectedImageKey = null;
    /** 选中图片的 PNG 字节（发送用） */
    private byte[] selectedImageBytes = null;
    /** 已发送、等待识图结果的图片 key（用于灰滤镜与错误红!标记）；null=无待识图 */
    private String pendingImageKey = null;
    /** 图片栏横向滚动偏移（像素） */
    private int galleryScrollOffset = 0;
    /** 图片栏是否正在长按拖动翻页 */
    private boolean galleryDragging = false;
    /** 拖动手势起始 GUI X */
    private double galleryDragStartX = 0;
    /** 拖动起始时的滚动偏移 */
    private int galleryDragStartOffset = 0;
    /** 图片缩略图画廊的绘制边长 */
    private static final int GALLERY_ITEM = 56;
    /** 玩家图片消息气泡里图片的绘制边长 */
    private static final int IMAGE_DRAW_SIZE = 48;

    // ==================== 断句逐条显示（任务2） ====================
    // 打字耗时由设置里的「每字耗时 / 单句上限」控制（见 typingMillis），不再用硬编码常量。
    /** 待逐条显示的短句队列（整段回复按断句符拆分后填充） */
    private final List<String> splitQueue = new ArrayList<>();
    /** 每条短句应落屏的绝对时间戳（毫秒，System.currentTimeMillis）；与 splitQueue 一一对应 */
    private final List<Long> splitSchedule = new ArrayList<>();
    /** 下一条要显示的短句在 splitQueue 中的下标 */
    private int splitIndex = 0;
    /** 当前断句回复的发送时间（游戏时间 tick）：同一回复被拆成的短句共享一致时间，便于分组显示 */
    private long splitGameTime = 0;
    /** 是否正在断句逐条显示（期间"对方正在输入…"保持） */
    private boolean splitting = false;
    /** 本次回复待显示的表情附件（ResourceLocation 字符串，null=无）。文本短句显示完后，作为同组附件尾部出现 */
    private String pendingReplyEmote = null;
    /** 本次回复的逐条显示是否已回执服务端（用于关闭手机来源窗口 → 动画放下段）。每次回复复位 */
    private boolean splitDoneNotified = false;

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
            if (e.item() != null) {
                // 物品气泡（M→P 传文件）：整体恢复
                messages.add(new ChatMessage("", false, false, false, e.gameTime(), null, null, e.item()));
            } else if (e.image() != null) {
                // 图片消息：直接整条恢复（图片已持久化在 chat_images，重进后仍可渲染）
                messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError(), e.pending(), e.gameTime(), null, e.image()));
            } else if (e.emote() != null) {
                // 表情消息：直接整条恢复（不按逗号拆句），保留 emote 路径
                messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError(), e.pending(), e.gameTime(), e.emote(), null));
            } else if (!e.isPlayer() && !e.isError()) {
                // 历史中的女仆正常回复：按断句规则拆成多条气泡显示（与实时收到时一致），
                // 避免重新进入世界后整段回复被合成一个大块。错误/玩家消息保持原样。
                List<String> segments = MessageSplitter.split(e.text());
                for (String seg : segments) {
                    messages.add(new ChatMessage(seg, false, false, false, e.gameTime(), null, null));
                }
            } else {
                messages.add(new ChatMessage(e.text(), e.isPlayer(), e.isError(), e.pending(), e.gameTime(), null, null));
            }
        }
        this.currentInput = ChatHistoryStorage.getDraft(stableId);
        this.cursorIndex = currentInput.length();
        for (int i = 0; i < TRANSFER_CELLS; i++) {
            transferQueue[i] = -1;
        }
        NotificationManager.clear(stableId);
        // 按需请求一次该女仆的当前状态（打开聊天页立刻刷新状态小字）
        PacketDistributor.sendToServer(new C2SRequestMaidStatusPacket(List.of(stableId)));
    }

    public boolean isWaitingResponse() { return waitingResponse; }
    public void setWaitingResponse(boolean waiting) { this.waitingResponse = waiting; }

    /** 本地新增一条女仆发来的物品气泡（M→P）。历史持久化由网络接收端负责，这里只加到内存列表。 */
    public void onReceiveItemTransfer(com.huhutalk.phone.ItemTransfer.Payload payload) {
        if (payload == null) {
            return;
        }
        clearPending();
        scrollOffset = 0;
        messages.add(new ChatMessage("", false, false, false, currentGameTime(), null, null, payload));
    }

    /** 收到领取结果后，更新对应物品气泡的待领取数（0 = 已接收）。 */
    public void onItemClaimResult(String transferId, int newPending) {
        if (transferId == null) {
            return;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            com.huhutalk.phone.ItemTransfer.Payload p = m.item();
            if (p != null && transferId.equals(p.transferId)) {
                messages.set(i, new ChatMessage("", false, false, false, m.gameTime(), null, null,
                        p.withPending(newPending)));
                break;
            }
        }
    }

    /** 是否处于相机模式（用于让手机体在相机模式下透明化，透出游戏画面） */
    public boolean isCameraMode() { return cameraActive; }

    /** 把最后一条"玩家发送"的消息标红"!"（消息入离线队列、尚未送达）。本地+落盘 */
    public void markLastSentPending() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.isPlayer()) {
                messages.set(i, new ChatMessage(m.text(), true, m.isError(), true, m.gameTime(), m.emote(), m.image()));
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
                messages.set(i, new ChatMessage(m.text(), m.isPlayer(), m.isError(), false, m.gameTime(), m.emote(), m.image()));
            }
        }
        ChatHistoryStorage.clearPending(stableId);
        // 图片识图等待态：回复/送达到达即视为图片已被女仆看到
        this.pendingImageKey = null;
    }

    /** 消息入离线队列：停止"输入中"并标红"!"（不显示提示文字） */
    public void onQueued() {
        this.waitingResponse = false;
        // 入离线队列 → 女仆未响应，中断任何断句显示
        this.splitting = false;
        this.splitQueue.clear();
        this.splitIndex = 0;
        this.pendingReplyEmote = null;
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
        if (send) {
            routeChatSend();
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
        // 按键音：点击说话键 / 按住快捷语音输入键的即时反馈（不受 DND 影响）
        HuhuTalkSounds.playVoiceHold();
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
        // 保险：若上一条回复还在逐条读条，先一口气列完，再发本条
        flushPendingSplitting();
        messages.add(new ChatMessage(text, true, false, false, currentGameTime(), null, null));
        ChatHistoryStorage.appendMessage(stableId, text, true, false);
        ChatHistoryStorage.setDraft(stableId, "");
        PacketDistributor.sendToServer(new C2SMaidChatPacket(stableId, text,
                HuhuTalkClientSettings.isMaidWhisperEnabled(), HuhuTalkClientSettings.isImmersiveChatEnabled()));
        this.waitingResponse = true;
        scrollOffset = 0;
    }

    public void onReceiveMessage(String text, boolean isError) {
        onReceiveMessage(text, isError, null);
    }

    /**
     * 收到女仆回复。
     *
     * @param emote 可选的表情附件（ResourceLocation 字符串，null/空=无）。有附件时，表情作为本次回复的
     *              附件在同一时刻分组尾部显示。
     */
    public void onReceiveMessage(String text, boolean isError, String emote) {
        // 收到女仆回复/提示 → 视为送达，清除未送达标记
        clearPending();
        // 回到最新消息（让新回复可见）
        scrollOffset = 0;
        String pendingEmoteLocal = (emote == null || emote.isEmpty()) ? null : emote;
        // 错误/提示消息：整段显示，不拆句
        if (isError) {
            // 发送失败音（受 DND 影响）：任何错误消息（发送失败、AI 错误、识图失败等）落屏时播放
            HuhuTalkSounds.playSendFail();
            messages.add(new ChatMessage(text, false, true, false, currentGameTime(), null, null));
            this.pendingReplyEmote = null;
            this.waitingResponse = false;
            this.splitting = false;
            this.splitQueue.clear();
            this.splitIndex = 0;
            return;
        }
        long groupTime = currentGameTime();
        // 正常女仆回复：按逗号断句，逐条显示；表情作为附件在同一时刻分组尾部出现
        List<String> segments = MessageSplitter.split(text);
        if (segments.size() <= 1 && pendingEmoteLocal == null) {
            // 无逗号可拆且无附件 → 整段显示
            // 界面内接收音：未断句整段显示时响 1 次（受 DND 影响）
            HuhuTalkSounds.playMsgReceiveUi();
            messages.add(new ChatMessage(text, false, false, false, groupTime, null, null));
            this.pendingReplyEmote = null;
            this.waitingResponse = false;
            this.splitting = false;
            this.splitQueue.clear();
            this.splitIndex = 0;
            return;
        }
        // 进入断句模式：重置队列，每条短句按"自己字数 × 0.5s（封顶 2.4s）"逐条显示。
        // 长句打字慢、短句快；第一条也要等它自己的耗时，因此不提前立即显示。
        // 用「绝对落屏时间戳」排程而非"上次显示时间 + 耗时"：这样即使玩家收起了手机
        // （渲染循环不再驱动），时序依然按真实时间推进，重开手机时不会把积压的短句一次性倾倒出来。
        splitSchedule.clear();
        splitQueue.clear();
        splitQueue.addAll(segments);
        long scheduleAt = System.currentTimeMillis();
        for (String seg : segments) {
            scheduleAt += typingMillis(seg);
            splitSchedule.add(scheduleAt);
        }
        splitIndex = 0;
        splitDoneNotified = false;
        splitGameTime = groupTime; // 该回复的所有短句 + 附件表情共享同一发送时间
        pendingReplyEmote = pendingEmoteLocal;
        splitting = true;
        waitingResponse = true; // 断句期间保持"对方正在输入…"
    }

    /**
     * 按绝对时间推进断句显示：把所有"应落屏时间已到"的短句补上。
     * <p>
     * 幂等且与调用频率无关——既由渲染每帧调用（手机打开时最跟手），
     * 也由客户端每 tick 调用（手机收起时仍按真实时间推进，修掉"收起手机后重开才逐条播"的遗留问题）。
     * 全部短句与尾随表情落屏后，回执服务端关闭手机来源窗口，动画进入放下段。
     */
    public void advanceSplitting() {
        if (!splitting) {
            return;
        }
        long now = System.currentTimeMillis();
        // 逐条追加：到点即落屏
        while (splitIndex < splitQueue.size() && now >= splitSchedule.get(splitIndex)) {
            messages.add(new ChatMessage(splitQueue.get(splitIndex), false, false, false, splitGameTime, null, null));
            // 界面内接收音：随断句逐条出现响一次（受 DND 影响）
            HuhuTalkSounds.playMsgReceiveUi();
            splitIndex++;
        }
        // 文本全部显示完 → 附带的表情作为同一分组尾部出现
        if (splitIndex >= splitQueue.size() && pendingReplyEmote != null) {
            messages.add(new ChatMessage("", false, false, false, splitGameTime, pendingReplyEmote, null));
            pendingReplyEmote = null;
        }
        // 全部显示完（含附件表情）→ 结束"正在输入…"并回执服务端
        if (splitIndex >= splitQueue.size() && pendingReplyEmote == null) {
            splitting = false;
            waitingResponse = false;
            if (!splitDoneNotified) {
                splitDoneNotified = true;
                // 通知服务端：本条回复已播完 → 关闭手机来源窗口 → 动画放下手机
                PacketDistributor.sendToServer(new C2SPhoneTypingDonePacket(stableId));
            }
        }
    }

    /** 按字数计费的单句打字耗时：字数 × 设置里的「每字耗时」，封顶设置里的「单句上限」。 */
    private static long typingMillis(String segment) {
        int len = segment == null ? 0 : segment.length();
        return Math.min(len * HuhuTalkClientSettings.getCharTypingMs(), HuhuTalkClientSettings.getMaxTypingMs());
    }

    /**
     * 立即结束当前"断句逐条显示"状态，把尚未排完的剩余短句与待显示表情一口气全部列出。
     * 用于玩家在回复仍逐条播放时抢先发送下一条：先落定本条，再进入下一条的推理。
     */
    private void flushPendingSplitting() {
        if (!splitting && pendingReplyEmote == null) {
            return;
        }
        // 剩余未显示短句全部追加（共享同一分组时间）
        while (splitIndex < splitQueue.size()) {
            messages.add(new ChatMessage(splitQueue.get(splitIndex), false, false, false, splitGameTime, null, null));
            splitIndex++;
        }
        // 待显示表情作为该组尾部一次性列出
        if (pendingReplyEmote != null) {
            messages.add(new ChatMessage("", false, false, false, splitGameTime, pendingReplyEmote, null));
            pendingReplyEmote = null;
        }
        // 复位断句状态，解除"对方正在输入…"
        splitting = false;
        waitingResponse = false;
        splitQueue.clear();
        splitSchedule.clear();
        splitIndex = 0;
        // 内容已全部落屏（玩家抢先发了下一条）：同样回执服务端关闭窗口，动画才能放下手机
        if (!splitDoneNotified) {
            splitDoneNotified = true;
            PacketDistributor.sendToServer(new C2SPhoneTypingDonePacket(stableId));
        }
    }

    // ==================== P→M 传文件：队列访问（供 FileTransferPage 使用） ====================

    /** 是否还有待随消息发送的物品（有则底栏"+"角标显示粉点）。 */
    public boolean hasPendingTransfers() {
        for (int s : transferQueue) {
            if (s >= 0) return true;
        }
        return false;
    }

    /** 待发送物品文件数。 */
    public int transferCount() {
        int c = 0;
        for (int s : transferQueue) {
            if (s >= 0) c++;
        }
        return c;
    }

    /** 某发送格引用的"我的背包"源槽位（-1=空）。 */
    public int transferSlot(int cell) {
        if (cell < 0 || cell >= TRANSFER_CELLS) return -1;
        return transferQueue[cell];
    }

    /** 某个背包源槽位是否已在队列中被占用（避免同一格重复发两次）。 */
    public boolean transferSourceUsed(int srcSlot) {
        for (int s : transferQueue) {
            if (s == srcSlot) return true;
        }
        return false;
    }

    /** 把某背包源槽位放入指定发送格。若该源已在队列或格子越界则不放入。 */
    public boolean transferSet(int cell, int srcSlot) {
        if (cell < 0 || cell >= TRANSFER_CELLS) return false;
        if (transferSourceUsed(srcSlot)) return false;
        transferQueue[cell] = srcSlot;
        return true;
    }

    /** 把某背包源槽位放进队列的第一个空格。该源已在队列或队列满则失败。 */
    public boolean transferAdd(int srcSlot) {
        if (transferSourceUsed(srcSlot)) return false;
        for (int i = 0; i < TRANSFER_CELLS; i++) {
            if (transferQueue[i] < 0) {
                transferQueue[i] = srcSlot;
                return true;
            }
        }
        return false;
    }

    /** 移除某发送格。 */
    public void transferRemove(int cell) {
        if (cell >= 0 && cell < TRANSFER_CELLS) {
            transferQueue[cell] = -1;
        }
    }

    /** 发送成功后清空整条队列。 */
    public void transferClearAll() {
        for (int i = 0; i < TRANSFER_CELLS; i++) {
            transferQueue[i] = -1;
        }
    }

    // ==================== 发送：普通文字 / 图片 / 带物品 ====================

    /** 中央分发：文字 / 图片 / 带物品三条发送路径择一。 */
    private void routeChatSend() {
        boolean hasItems = hasPendingTransfers();
        boolean hasImg = selectedImageKey != null;
        boolean hasText = !currentInput.isBlank();
        if (!hasText && !hasImg && !hasItems) {
            return;
        }
        if (hasItems) {
            // 有物品随消息：走 ack 流（整条成功后女仆才收到），物品优先于图片附件
            trySendWithItems();
            return;
        }
        if (hasImg) {
            sendImageChat();
            return;
        }
        sendCurrentMessage();
    }

    /**
     * 发送"文字 + 队列里的物品"（P→M，随消息发给女仆）。
     * 文字可以为空（仅发物品）。发送后本地不立即提交，等服务端 ack 决定成/败。
     */
    private void trySendWithItems() {
        if (itemSendAwait) {
            return; // 上一条还在等确认，先忽略
        }
        String text = currentInput;
        // 记录待确认文字（空则只发物品）
        pendingItemText = text == null ? "" : text;
        List<Integer> slots = new ArrayList<>();
        for (int s : transferQueue) {
            if (s >= 0) slots.add(s);
        }
        if (slots.isEmpty()) {
            return; // 无物品也不该走这里
        }
        if (!pendingItemText.isBlank()) {
            flushPendingSplitting();
        }
        // 本地不提交文字，先清输入框等待确认
        currentInput = "";
        cursorIndex = 0;
        inputScrollOffset = 0;
        ChatHistoryStorage.setDraft(stableId, "");
        itemSendAwait = true;
        PacketDistributor.sendToServer(new C2SMaidChatWithItemsPacket(stableId, pendingItemText, slots,
                HuhuTalkClientSettings.isMaidWhisperEnabled(), HuhuTalkClientSettings.isImmersiveChatEnabled()));
    }

    /**
     * 服务端确认成功：整条已发给女仆。补上玩家的文字气泡、清空物品队列、进入"对方正在输入…"。
     * 历史落盘由网络处理端负责（含不在当前页的情况），这里只更新内存视图。
     */
    public void onItemSendAccepted() {
        String text = pendingItemText;
        itemSendAwait = false;
        pendingItemText = "";
        transferClearAll();
        scrollOffset = 0;
        if (text != null && !text.isBlank()) {
            messages.add(new ChatMessage(text, true, false, false, currentGameTime(), null, null));
            waitingResponse = true;
        }
    }

    /**
     * 服务端确认失败（放不下/不在线等）：整条未发送。还原文字到输入框、保留物品队列、提示原因。
     */
    public void onItemSendRejected(String message) {
        String text = pendingItemText;
        itemSendAwait = false;
        pendingItemText = "";
        // 还原文字
        if (text != null && !text.isBlank()) {
            currentInput = text;
            cursorIndex = currentInput.length();
            ChatHistoryStorage.setDraft(stableId, text);
        }
        // 队列保留（粉点还在，可稍后再发）
        String notice = message == null || message.isEmpty() ? "发送失败，物品已保留" : message;
        messages.add(new ChatMessage("[提示] " + notice, false, true, false, currentGameTime(), null, null));
    }

    private void sendCurrentMessage() {
        if (currentInput.isBlank()) return;
        String text = currentInput;
        // 保险：若上一条回复还在逐条读条，先一口气列完，再发本条
        flushPendingSplitting();
        messages.add(new ChatMessage(text, true, false, false, currentGameTime(), null, null));
        ChatHistoryStorage.appendMessage(stableId, text, true, false);
        ChatHistoryStorage.setDraft(stableId, "");
        PacketDistributor.sendToServer(new C2SMaidChatPacket(stableId, text,
                HuhuTalkClientSettings.isMaidWhisperEnabled(), HuhuTalkClientSettings.isImmersiveChatEnabled()));
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
        // 图片面板（🖼️）：高度 = 缩放后的图片边长 + 上下留白，保证图片完整可见
        if (extPanelIcon == 2) return galleryItemSize() + UiFont.sh(12);
        // 附加栏工具网格（第5个"+"）：略高于默认占位，好放 app 图标 + 名称
        if (extPanelIcon == 4) return (int)(phoneH() * 0.22);
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

        // 相机模式：替代整个聊天界面渲染
        if (cameraActive) {
            renderCameraMode(g, font, baseX, baseY, w);
            return;
        }

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
            } else if (extPanelIcon == 2) {
                renderGalleryPanel(g, font, baseX, pTop, pBottom, w);
            } else if (extPanelIcon == 4) {
                renderToolsPanel(g, font, baseX, pTop, pBottom, w);
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
            // 图片栏（🖼️，index 2）有选中图时，图标右上角加一个小方形粉点（#de6f8d）示意有选中
            if (i == 2 && selectedImageKey != null) {
                int dot = UiFont.sh(5);
                UiShapes.fillRoundedRect(g, cx + btnSize / 2 - dot - UiFont.sh(1),
                        extCenterY - btnSize / 2 + UiFont.sh(1), dot, dot, 1, 0xFFDE6F8D);
            }
            // 附加栏（第5个"+"，index 4）有待随消息发送的物品时同样标粉点
            if (i == 4 && hasPendingTransfers()) {
                int dot = UiFont.sh(5);
                UiShapes.fillRoundedRect(g, cx + btnSize / 2 - dot - UiFont.sh(1),
                        extCenterY - btnSize / 2 + UiFont.sh(1), dot, dot, 1, 0xFFDE6F8D);
            }
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
        // 物品气泡（M→P 传文件）：固定高度
        if (m.item() != null) {
            return itemBubbleSize() + (groupTop ? timeH() : 0);
        }
        // 表情消息：固定高度
        if (m.emote() != null) {
            return emoteBubbleSize() + (groupTop ? timeH() : 0);
        }
        // 玩家图片消息：固定高度
        if (m.image() != null) {
            return imageBubbleSize() + (groupTop ? timeH() : 0);
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
        // 物品气泡（M→P 传文件）：走专用物品气泡
        if (m.item() != null) {
            return drawItemBubble(g, font, baseX, yBottom, phoneW, m, showAvatar, groupTop);
        }
        // 表情消息：走纯图片气泡（始终带头像）
        if (m.emote() != null) {
            return drawEmoteBubble(g, font, baseX, yBottom, phoneW, m, showAvatar, groupTop);
        }
        // 玩家图片消息：走本地图片气泡（始终带头像）
        if (m.image() != null) {
            return drawImageBubble(g, font, baseX, yBottom, phoneW, m, showAvatar, groupTop);
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

    // ==================== 物品气泡（M→P 传文件） ====================

    /** 物品预览图标格子边长：两倍默认字体行高（随字体/缩放变化，不写死像素）。 */
    private static int itemBox() {
        return Math.max(8, 2 * lh());
    }

    /** 物品气泡总高度：上距 + 图标行 + 间距 + 两行状态/说明文字 + 下距 */
    private int itemBubbleSize() {
        int textH = lh() + 1;
        return 6 + itemBox() + 5 + textH * 2 + 6;
    }

    /** 由预览数据还原一个仅用于渲染图标的 ItemStack（未知物品返回 EMPTY）。 */
    private static ItemStack previewToStack(com.huhutalk.phone.ItemTransfer.Preview p) {
        if (p == null || p.itemId == null || p.itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            ResourceLocation rl = ResourceLocation.parse(p.itemId);
            Item it = BuiltInRegistries.ITEM.get(rl);
            ItemStack s = new ItemStack(it);
            s.setCount(Math.max(1, p.count));
            return s.isEmpty() ? ItemStack.EMPTY : s;
        } catch (Exception ex) {
            return ItemStack.EMPTY;
        }
    }

    /** 画一个放大的物品图标（原生16px缩放后放进 box 大小的方框）。 */
    private static void drawItemIcon(GuiGraphics g, ItemStack stack, int centerX, int centerY) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        PoseStack pose = g.pose();
        pose.pushPose();
        int ib = itemBox();
        pose.translate(centerX - ib / 2f, centerY - ib / 2f, 0);
        float s = ib / 16f;
        pose.scale(s, s, 1f);
        g.renderItem(stack, 0, 0);
        pose.popPose();
    }

    /** 女仆发送的物品气泡：画预览图标(最多前3个)、额外物品数说明与领取状态，整块可点击领取。 */
    private int drawItemBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m,
                               boolean showAvatar, boolean groupTop) {
        int textH = lh() + 1;
        int ib = itemBox();
        int bubbleH = itemBubbleSize();
        int yTop = yBottom - bubbleH;

        int bubbleLeft = baseX + AVATAR + MARGIN;          // 女仆侧
        int maxW = phoneW - AVATAR * 2 - MARGIN * 2;
        com.huhutalk.phone.ItemTransfer.Payload payload = m.item();
        java.util.List<com.huhutalk.phone.ItemTransfer.Preview> previews =
                payload.previews == null ? java.util.Collections.emptyList() : payload.previews;

        // 每格图标步进与内边距（跟随字体尺寸）
        int step = ib + 6;
        int padX = 6;
        // 宽度能放下几个图标：最多 3；极端大字体(ib 很大)时自动减少，防止图标溢出屏幕
        int iconFit = Math.min(3, (maxW - 2 * padX + step) / step);
        iconFit = Math.max(1, iconFit);
        int shown = Math.min(previews.size(), iconFit);

        // 说明行（第二行 / 第三行），长度决定气泡在图标行之外还需多宽
        int hidden = Math.max(0, payload.total - shown);
        String lineA = hidden > 0 ? "和剩余 " + hidden + " 个文件" : "";
        String lineB = payload.claimed() ? "✓ 已接收"
                : (payload.pending > 0 ? "待领取 " + payload.pending + " 个文件" : "");
        float capTs = 0.7f;
        // 气泡宽度贴合内容：图标行宽 与 说明最长一行 取大，封顶到 maxW（不再堆满整屏）
        int iconRowW = 2 * padX + shown * ib + Math.max(0, shown - 1) * 6;
        int capW = (int) (Math.max(font.width(lineA), font.width(lineB)) * capTs) + 2 * padX;
        int bubbleW = Math.min(maxW, Math.max(iconRowW, Math.max(capW, ib + 2 * padX)));
        if (bubbleW > maxW) bubbleW = maxW;
        int radius = Math.max(2, bubbleH / 5);
        UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, 0xFF525A6E);

        // 第一行：预览图标（最多 shown 个）
        int iconCenterY = yTop + 6 + ib / 2;
        int cx = bubbleLeft + padX + ib / 2;
        for (int i = 0; i < shown; i++) {
            ItemStack st = previewToStack(previews.get(i));
            // 图标底色框
            UiShapes.fillRoundedRect(g, cx - ib / 2 - 1, iconCenterY - ib / 2 - 1,
                    ib + 2, ib + 2, 4, 0x33000000);
            drawItemIcon(g, st, cx, iconCenterY);
            // 每个物品的数量小字（右上角）
            if (!st.isEmpty() && st.getCount() > 1) {
                String cnt = String.valueOf(st.getCount());
                drawScaledText(g, font, cnt,
                        cx + ib / 2f - font.width(cnt) * 0.6f, iconCenterY - ib / 2f - 2,
                        0xFFFFFFFF, 0.6f);
            }
            cx += step;
        }

        // 第二行：超出图标数量的说明（仅当有多出的文件）
        int textX = bubbleLeft + padX;
        int textY = yTop + 6 + ib + 4;
        if (!lineA.isEmpty()) {
            drawScaledText(g, font, lineA, textX, textY, 0xFFD8DCE6, capTs);
        }
        // 第三行：领取状态
        int stateY = textY + textH;
        int stateColor = payload.claimed() ? 0xFF7FE08A : 0xFFFFE08A;
        drawScaledText(g, font, lineB, textX, stateY, stateColor, capTs);

        if (showAvatar) {
            drawAvatarPlaceholder(g, baseX + 2, yTop, false);
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

    // ==================== 附加栏工具（第5个"+"，传文件等） ====================

    /** 附加栏内的工具（图标 emoji + 名称）。目前第一个工具是"传文件"，后续可继续追加。 */
    private static final String[][] EXTRA_TOOLS = {{"📃", "传文件"}};

    /** 附加栏工具的圆角方框边长：跟随默认字体行高变化（约两倍行高），不写死像素。 */
    private static int toolTile() {
        return Math.max(10, Math.round(2 * lh()));
    }

    /** 绘制附加栏工具网格：app 风格圆角方块图标 + 名称。 */
    private void renderToolsPanel(GuiGraphics g, Font font, int baseX, int pTop, int pBottom, int w) {
        int padX = UiFont.sh(8);
        int padY = UiFont.sh(6);
        int labelH = lh();
        int availH = pBottom - pTop;
        int tileS = toolTile();
        int gap = UiFont.sh(6);
        int cols = Math.max(1, (w - padX * 2 + gap) / (tileS + gap));
        int startX = baseX + padX;
        int startY = pTop + padY;
        for (int i = 0; i < EXTRA_TOOLS.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int x = startX + col * (tileS + gap);
            int y = startY + row * (tileS + labelH + UiFont.sh(2));
            UiShapes.fillRoundedRect(g, x, y, tileS, tileS, Math.max(3, tileS / 5), 0xFFFFFFFF);
            String icon = EXTRA_TOOLS[i][0];
            float ts = UiFont.sc(1.5f);
            drawScaledText(g, font, icon,
                    x + (tileS - font.width(icon) * ts) / 2f,
                    y + (tileS - lh() * ts) / 2f, 0xFFDE6F8D, ts);
            drawScaledText(g, font, EXTRA_TOOLS[i][1],
                    x + (tileS - font.width(EXTRA_TOOLS[i][1]) * UiFont.sc(0.8f)) / 2f,
                    y + tileS + 1, 0xFF6A5A5A, UiFont.sc(0.8f));
        }
    }

    /** 命中附加栏里的某个工具。当前只有"传文件"，命中即返回 true。 */
    private boolean hitExtraTool(double relX, double relY) {
        if (!bottomPanelOpen || extPanelIcon != 4) {
            return false;
        }
        int pTop = panelTop(0);
        int pBottom = extBarTop(0);
        if (relY < pTop || relY >= pBottom) {
            return false;
        }
        int padX = UiFont.sh(8);
        int padY = UiFont.sh(6);
        int labelH = lh();
        int availH = pBottom - pTop;
        int tileS = toolTile();
        int gap = UiFont.sh(6);
        int cols = Math.max(1, (phoneW() - padX * 2 + gap) / (tileS + gap));
        int startX = padX;
        int startY = pTop + padY;
        for (int i = 0; i < EXTRA_TOOLS.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int x = startX + col * (tileS + gap);
            int y = startY + row * (tileS + labelH + UiFont.sh(2));
            if (relX >= x && relX <= x + tileS && relY >= y && relY <= y + tileS) {
                return true;
            }
        }
        return false;
    }

    /** 打开选中的附加工具页面。当前为"传文件"发送页（与当前聊天共用发送队列）。 */
    private void openExtraTool(int index) {
        if (index == 0) {
            PhoneOverlay.pushPage(new FileTransferPage(this));
        }
        bottomPanelOpen = false;
        extPanelIcon = -1;
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

    // ==================== 拍照（📷）& 图片（🖼️） ====================

    /** 进入相机模式（替代聊天界面） */
    private void enterCameraMode() {
        cameraActive = true;
        cameraShot = null;
        bottomPanelOpen = false;
        extPanelIcon = -1;
    }

    /** 退出相机模式，回到聊天界面 */
    private void exitCameraMode() {
        cameraActive = false;
        cameraShot = null;
    }

    /**
     * 右键返回（全局右键回调）：相机模式下先退出拍照界面回到聊天页，不弹页。
     * 返回 true 表示已消费，上层（PhoneOverlay）不应再 popPage。
     */
    public boolean onBackPressed() {
        if (cameraActive) {
            exitCameraMode();
            return true;
        }
        return false;
    }

    /** 相机取景框底（内容相对坐标，上方为取景区域，下方 44px 为控件条） */
    private int cameraViewBottom() {
        return phoneH() - UiFont.sh(44);
    }

    /** 相机控件条按钮的顶 Y（内容相对坐标，垂直居中于 44px 控件条） */
    private int cameraButtonY() {
        int btnH = UiFont.sh(28);
        return cameraViewBottom() + (UiFont.sh(44) - btnH) / 2;
    }

    /**
     * 相机模式侧键的点击目标（<b>内容相对坐标</b>下的按钮中心），供
     * {@code PhoneOverlay.cameraSideButton} 合成点击用。
     * <p>
     * 之所以只给出"点哪儿"而不是直接暴露 {@code takePhoto/confirmPhoto/savePhoto}：
     * 让侧键与屏幕上那几颗按钮走<b>同一条命中通路</b>，按钮的排版改了、命中框改了，
     * 侧键自动跟着改，不会出现"按钮挪了但侧键还点老位置"这种暗病。
     *
     * <h2>为什么"已拍成"时侧键 4 指向右下角</h2>
     * 中间那颗胶囊在已拍成时会变成「重拍」，而「确认」在右下角。
     * 玩家直觉是"再按一次 4 = 确认发送"，所以侧键 4 必须指向<b>确认</b>而不是中间那颗；
     * 想丢掉重拍则交给侧键 5（它指向中间胶囊）。
     *
     * @param clickButton true = 侧键 4（左键/点击）；false = 侧键 5（右键/返回）
     * @return {x, y} 内容相对坐标；无可用目标时返回 null
     */
    public int[] cameraSideButtonTarget(boolean clickButton) {
        int w = phoneW();
        if (w <= 0) {
            return null;
        }
        int navH = navH();
        int btnH = UiFont.sh(28);
        int btnY = cameraButtonY();
        boolean shot = cameraShot != null;
        if (clickButton) {
            if (shot) {
                // 已拍成：右下角「确认」→ 选中这张并回聊天（随后随文字一起发送）
                return new int[]{w - UiFont.sh(54) + UiFont.sh(44) / 2, btnY + btnH / 2};
            }
            // 取景中：中间「拍照」胶囊
            return new int[]{w / 2, btnY + btnH / 2};
        }
        if (shot) {
            // 已拍成：中间「重拍」胶囊（丢弃这张，回到取景中）
            return new int[]{w / 2, btnY + btnH / 2};
        }
        // 取景中：左上角返回
        return new int[]{UiFont.sh(20), navH / 2};
    }

    /**
     * 相机「保存到相册」按钮的中心（内容相对坐标），供侧键<b>长按</b>合成点击用。
     * <p>
     * 与 {@link #cameraSideButtonTarget} 分开，是因为"保存"是长按触发的独立动作，
     * 且按钮只在已拍成时存在；没有照片时返回 null，长按自然什么都不做。
     */
    public int[] cameraSaveButtonTarget() {
        int w = phoneW();
        if (w <= 0 || cameraShot == null) {
            return null;
        }
        // 右上角「保存」胶囊
        return new int[]{w - UiFont.sh(54) + UiFont.sh(44) / 2, navH() + UiFont.sh(8) + UiFont.sh(22) / 2};
    }

    /** 渲染相机界面：透明取景框（游戏透出）+ 四角边框 + 十字准星 + 底部控件 */
    private void renderCameraMode(GuiGraphics g, Font font, int baseX, int baseY, int w) {
        int h = phoneH();
        // 顶部导航（不透明，保证返回/标题可读）
        g.fill(baseX, baseY, baseX + w, baseY + navH(), 0xFFDE7E7E);
        drawScaledText(g, font, "←", baseX + UiFont.sh(6), baseY + navH() / 2 - lh() / 2,
                0xFFFFFFFF, UiFont.sc(1.0f));
        drawScaledText(g, font, "拍照", baseX + UiFont.sh(22), baseY + navH() / 2 - lh() / 2,
                0xFFFFFFFF, UiFont.sc(1.0f));

        int viewTop = baseY + navH() + UiFont.sh(8);
        int viewBottom = baseY + cameraViewBottom();
        int cx = baseX + w / 2;
        int cy = (viewTop + viewBottom) / 2;

        // 取景框：透明背景（游戏透出），仅画四角边框 + 十字准星
        int fr = UiFont.sh(2);      // 框线宽
        int corner = UiFont.sh(16); // 四角边长
        int l = baseX + UiFont.sh(8), r = baseX + w - UiFont.sh(8);
        g.fill(l, viewTop, l + corner, viewTop + fr, 0xCCFFFFFF);
        g.fill(r - corner, viewTop, r, viewTop + fr, 0xCCFFFFFF);
        g.fill(l, viewBottom - fr, l + corner, viewBottom, 0xCCFFFFFF);
        g.fill(r - corner, viewBottom - fr, r, viewBottom, 0xCCFFFFFF);
        g.fill(l, viewTop, l + fr, viewTop + corner, 0xCCFFFFFF);
        g.fill(r - fr, viewTop, r, viewTop + corner, 0xCCFFFFFF);
        g.fill(l, viewBottom - corner, l + fr, viewBottom, 0xCCFFFFFF);
        g.fill(r - fr, viewBottom - corner, r, viewBottom, 0xCCFFFFFF);
        g.fill(cx - 10, cy - 1, cx + 10, cy + 1, 0xAAFFFFFF);
        g.fill(cx - 1, cy - 10, cx + 1, cy + 10, 0xAAFFFFFF);

        // 底部控件条：半透明黑，衬托按钮可读
        g.fill(baseX, viewBottom, baseX + w, baseY + h, 0x88000000);

        int btnW = UiFont.sh(88);
        int btnH = UiFont.sh(28);
        int btnY = baseY + cameraButtonY();
        // 拍照 / 重拍 胶囊（居中）
        UiShapes.fillRoundedRect(g, cx - btnW / 2, btnY, btnW, btnH, btnH / 2, 0xFFFFFFFF);
        String shotLabel = cameraShot != null ? "重拍" : "拍照";
        drawScaledText(g, font, shotLabel, cx - font.width(shotLabel) * UiFont.sc(1.0f) / 2,
                btnY + (btnH - lh()) / 2, 0xFFDE7E7E, UiFont.sc(1.0f));

        if (cameraShot != null) {
            // 右上角：保存
            drawCameraButton(g, font, baseX + w - UiFont.sh(54), baseY + navH() + UiFont.sh(8),
                    UiFont.sh(44), UiFont.sh(22), "保存");
            // 右下角：确认
            drawCameraButton(g, font, baseX + w - UiFont.sh(54), btnY, UiFont.sh(44), btnH, "确认");
            // 左下角：已拍缩略图
            ResourceLocation tex = PhotoLibrary.getOrLoadTexture("__camshot__");
            int t = UiFont.sh(36);
            int ty = btnY + (btnH - t) / 2;
            UiShapes.fillRoundedRect(g, baseX + UiFont.sh(12), ty, t, t, UiFont.sh(4), 0xFFFFFFFF);
            if (tex != null) {
                int[] d = PhotoLibrary.getTextureSize("__camshot__");
                g.blit(tex, baseX + UiFont.sh(12), ty, t, t, 0.0f, 0.0f, d[0], d[1], d[0], d[1]);
            }
        }
    }

    /** 绘制相机界面里的小胶囊按钮 */
    private void drawCameraButton(GuiGraphics g, Font font, int x, int y, int w, int h, String text) {
        UiShapes.fillRoundedRect(g, x, y, w, h, h / 2, 0xFFDE7E7E);
        drawScaledText(g, font, text, x + (w - font.width(text) * UiFont.sc(1.0f)) / 2f,
                y + (h - lh()) / 2, 0xFFFFFFFF, UiFont.sc(1.0f));
    }

    /** 相机模式的鼠标点击处理 */
    private boolean handleCameraClick(double relX, double relY, int w) {
        // 左上角返回
        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH()) {
            exitCameraMode();
            return true;
        }
        int cx = w / 2;
        int btnW = UiFont.sh(88);
        int btnH = UiFont.sh(28);
        int btnY = cameraButtonY();

        if (cameraShot != null) {
            // 右上角保存
            if (hit(relX, relY, w - UiFont.sh(54), navH() + UiFont.sh(8), UiFont.sh(44), UiFont.sh(22))) {
                savePhoto();
                return true;
            }
            // 右下角确认
            if (hit(relX, relY, w - UiFont.sh(54), btnY, UiFont.sh(44), btnH)) {
                confirmPhoto();
                return true;
            }
        }
        // 中下部拍照/重拍胶囊
        if (hit(relX, relY, cx - btnW / 2, btnY, btnW, btnH)) {
            takePhoto();
            return true;
        }
        return true;
    }

    /** 拍照：截取主渲染目标存为 cameraShot，并注册为可渲染纹理 */
    private void takePhoto() {
        // 临时隐藏手机一帧，抓取干净的纯游戏画面（增加沉浸感）
        PhoneOverlay.hideForCapture(png -> {
            if (png != null) {
                cameraShot = png;
                PhotoLibrary.registerCapturedBytes("__camshot__", png);
            }
        });
    }

    /** 确认：进入选中（待发送），不持久化 → 返回聊天 */
    private void confirmPhoto() {
        if (cameraShot != null) {
            selectedImageKey = "__camshot__";
            selectedImageBytes = cameraShot;
        }
        exitCameraMode();
    }

    /**
     * 保存到相册：<b>留在已拍成页</b>，不退出相机。
     * <p>
     * 与 {@link #confirmPhoto()} 刻意区分：保存只是"把这张存下来"，玩家接下来仍可选择
     * 轻点侧键 4 确认发送、或点侧键 5 重拍。若保存也直接退回聊天页，玩家就失去了选择，
     * 也让"确认"与"保存"两个意图在结果上不可区分。
     */
    private void savePhoto() {
        if (cameraShot != null) {
            PhotoLibrary.saveToGallery(cameraShot);
        }
    }

    // ==================== 图片面板（🖼️） ====================

    /** 图片栏显示的条目：当前选中（可能是未保存的临时照）排最前，其后是图库已保存照片 */
    private List<String> galleryDisplayItems() {
        List<String> gallery = PhotoLibrary.listGallery();
        List<String> out = new ArrayList<>();
        if (selectedImageKey != null && !gallery.contains(selectedImageKey)) {
            out.add(selectedImageKey); // 相机"确认但不保存"的临时照
        }
        out.addAll(gallery);
        return out;
    }

    /** 图片边长：随全局字体倍率缩放（GALLERY_ITEM 为基准；与面板高度联动，保证图片完整可见） */
    private int galleryItemSize() {
        return UiFont.sh(GALLERY_ITEM);
    }

    /** 图片栏内容总宽（用于滚动钳制） */
    private int galleryContentWidth() {
        return Math.max(0, galleryDisplayItems().size() * (galleryItemSize() + UiFont.sh(8)) - UiFont.sh(8) + UiFont.sh(24));
    }

    /** 图片栏可滚动范围 */
    private int galleryMaxScroll() {
        int viewW = phoneW() - UiFont.sh(20);
        return Math.max(0, galleryContentWidth() - viewW);
    }

    private int clampGalleryScroll(int offset) {
        return Math.max(0, Math.min(offset, galleryMaxScroll()));
    }

    /** 渲染图片面板：横向画廊，每张右上角选中圆标（灰/粉） */
    private void renderGalleryPanel(GuiGraphics g, Font font, int baseX, int pTop, int pBottom, int w) {
        // 拖动更新（长按拖动翻页）
        if (galleryDragging) {
            updateGalleryDrag();
        }
        List<String> items = galleryDisplayItems();
        int viewH = Math.max(0, pBottom - pTop);
        galleryScrollOffset = clampGalleryScroll(galleryScrollOffset);
        int itemY = pTop + UiFont.sh(6);
        int itemSize = galleryItemSize();
        int gap = UiFont.sh(8);
        int startX = baseX + UiFont.sh(12) - galleryScrollOffset;

        g.enableScissor(baseX, pTop, baseX + w, pBottom);
        if (items.isEmpty()) {
            drawScaledText(g, font, "暂无照片，先用📷拍一张吧",
                    baseX + (w - font.width("暂无照片，先用📷拍一张吧") * UiFont.sc(1.0f)) / 2,
                    itemY, 0xFF8A6A6A, UiFont.sc(1.0f));
            g.disableScissor();
            return;
        }
        int indSize = Math.max(1, itemSize / 4); // 选中标边长 = 图片高度的 1/4
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i);
            int x = startX + i * (itemSize + gap);
            if (x + itemSize < baseX || x > baseX + w) {
                continue;
            }
            // 选中描边
            boolean selected = selectedImageKey != null && selectedImageKey.equals(key);
            int border = selected ? 0xFFDE7E7E : 0xFFFFFFFF;
            UiShapes.fillRoundedRect(g, x, itemY, itemSize, itemSize, UiFont.sh(4), border);
            ResourceLocation tex = PhotoLibrary.getOrLoadTexture(key);
            if (tex != null) {
                int[] d = PhotoLibrary.getTextureSize(key);
                int inset = UiFont.sh(2);
                g.blit(tex, x + inset, itemY + inset, itemSize - inset * 2, itemSize - inset * 2,
                        0.0f, 0.0f, d[0], d[1], d[0], d[1]);
            } else {
                g.fill(x + UiFont.sh(2), itemY + UiFont.sh(2), x + itemSize - UiFont.sh(2),
                        itemY + itemSize - UiFont.sh(2), 0xFFB0BEC5);
            }
            // 右上角选中小方块：边长 = 图片高度的 1/4，未选灰(25%) / 选中粉 #de7e7e
            int indX = x + itemSize - indSize - UiFont.sh(2);
            int indY = itemY + UiFont.sh(2);
            g.fill(indX, indY, indX + indSize, indY + indSize, selected ? 0xFFDE7E7E : 0x40000000);
            // 左下角删除小红叉（仅已持久化的图库照片；临时 __camshot__ 不显示）：红底 + 白 X
            if (!key.startsWith("__camshot__")) {
                int delX = x + UiFont.sh(2);
                int delY = itemY + itemSize - indSize - UiFont.sh(2);
                g.fill(delX, delY, delX + indSize, delY + indSize, 0xFFE53935);
                drawX(g, delX + Math.max(1, indSize / 5), delY + Math.max(1, indSize / 5),
                        indSize - Math.max(2, indSize / 5) * 2, 0xFFFFFFFF);
            }
        }
        g.disableScissor();
    }

    /** 在一小方块区域（x,y,size）内画白色 X 线（用于删除叉） */
    private static void drawX(GuiGraphics g, int x, int y, int size, int color) {
        if (size <= 0) return;
        for (int i = 0; i < size; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + size - i - 1, y + i, x + size - i, y + i + 1, color);
        }
    }

    /** 是否点击到图片栏某张图的右上角选中小方块 */
    private boolean hitGalleryDot(double relX, double relY) {
        List<String> items = galleryDisplayItems();
        if (items.isEmpty()) {
            return false;
        }
        int pTop = panelTop(0), pBottom = extBarTop(0);
        if (relY < pTop || relY >= pBottom) {
            return false;
        }
        int itemSize = galleryItemSize();
        int gap = UiFont.sh(8);
        int startX = UiFont.sh(12) - galleryScrollOffset;
        int indSize = Math.max(1, itemSize / 4);
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i);
            int x = startX + i * (itemSize + gap);
            int indX = x + itemSize - indSize - UiFont.sh(2);
            int indY = pTop + UiFont.sh(6) + UiFont.sh(2);
            if (relX >= indX && relX <= indX + indSize && relY >= indY && relY <= indY + indSize) {
                toggleGallerySelect(key);
                return true;
            }
        }
        return false;
    }

    /** 是否点击到某张图库照片的左下角删除叉；命中则删除持久化图片 */
    private boolean hitGalleryDelete(double relX, double relY) {
        List<String> items = galleryDisplayItems();
        if (items.isEmpty()) {
            return false;
        }
        int pTop = panelTop(0), pBottom = extBarTop(0);
        if (relY < pTop || relY >= pBottom) {
            return false;
        }
        int itemSize = galleryItemSize();
        int gap = UiFont.sh(8);
        int startX = UiFont.sh(12) - galleryScrollOffset;
        int delSize = Math.max(1, itemSize / 4);
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i);
            if (key.startsWith("__camshot__")) {
                continue; // 临时照无删除
            }
            int x = startX + i * (itemSize + gap);
            int delX = x + UiFont.sh(2);
            int delY = pTop + UiFont.sh(6) + itemSize - delSize - UiFont.sh(2);
            if (relX >= delX && relX <= delX + delSize && relY >= delY && relY <= delY + delSize) {
                deleteGalleryImage(key);
                return true;
            }
        }
        return false;
    }

    /** 删除一张已持久化的图库照片；若删的是当前选中图则一并清空选中 */
    private void deleteGalleryImage(String key) {
        PhotoLibrary.deleteGallery(key);
        if (selectedImageKey != null && selectedImageKey.equals(key)) {
            selectedImageKey = null;
            selectedImageBytes = null;
        }
    }

    /** 命中图片主体的 key（点图片本身也算选中，便于新手）；未命中返回 null */
    private String hitGalleryItem(double relX, double relY) {
        List<String> items = galleryDisplayItems();
        if (items.isEmpty()) {
            return null;
        }
        int pTop = panelTop(0);
        int itemSize = galleryItemSize();
        int gap = UiFont.sh(8);
        int startX = UiFont.sh(12) - galleryScrollOffset;
        int itemY = pTop + UiFont.sh(6);
        for (int i = 0; i < items.size(); i++) {
            int x = startX + i * (itemSize + gap);
            if (relX >= x && relX <= x + itemSize && relY >= itemY && relY <= itemY + itemSize) {
                return items.get(i);
            }
        }
        return null;
    }

    /** 切换某张图的选中状态 */
    private void toggleGallerySelect(String key) {
        if (selectedImageKey != null && selectedImageKey.equals(key)) {
            selectedImageKey = null;
            selectedImageBytes = null;
        } else {
            selectedImageKey = key;
            selectedImageBytes = PhotoLibrary.readBytes(key);
        }
    }

    /** 长按拖动翻页：用当前鼠标 X 更新横向偏移 */
    private void updateGalleryDrag() {
        Minecraft mc = Minecraft.getInstance();
        double guiX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double relX = guiX - PhoneOverlay.getPhoneX();
        double delta = relX - galleryDragStartX;
        if (!isLeftMouseDown()) {
            galleryDragging = false;
            return;
        }
        galleryScrollOffset = clampGalleryScroll(galleryDragStartOffset - (int) delta);
    }

    // ==================== 发送联动 ====================

    /** 发送"文字 + 一张图"：本地立即显示玩家消息+图气泡，压缩后写入 vision_tmp，通知服务端识图后让女仆回复 */
    private void sendImageChat() {
        String text = currentInput;
        String key = selectedImageKey;
        // 发送时按 key 现读字节（不依赖选中时预存的字节，读图失败可即时发现）
        byte[] img = key == null ? null : PhotoLibrary.readBytes(key);
        if (img == null) {
            LOGGER.error("[HuhuTalk] 发送图片失败：key={} 读图字节为空，退化为纯文字", key);
            sendCurrentMessage();
            return;
        }

        long gameTime = currentGameTime();
        if (text != null && !text.isBlank()) {
            messages.add(new ChatMessage(text, true, false, false, gameTime, null, null));
            ChatHistoryStorage.appendMessage(stableId, text, true, false);
        }
        // 持久化图片到 chat_images，得到稳定 key（重进页面仍可渲染）；失败则退回原 key
        String chatKey = PhotoLibrary.persistChatImage(img);
        if (chatKey == null) {
            chatKey = key;
        }
        messages.add(new ChatMessage("", true, false, true, gameTime, null, chatKey));
        pendingImageKey = chatKey;
        // 图片写历史（含 image 引用），重进页面可恢复图片气泡
        ChatHistoryStorage.appendMessage(stableId, "", null, chatKey, true, false);

        // 降采样后把图片字节内联进包传输（不再依赖共享 vision_tmp 目录，专用服务器也能用）；
        // 超过包帧长上限则退化为纯文字
        byte[] toSend = PhotoLibrary.downscalePng(img, 512);
        if (toSend == null || toSend.length == 0 || toSend.length > C2SMaidImageChatPacket.MAX_IMAGE_BYTES) {
            LOGGER.error("[HuhuTalk] 发送图片失败：图片过大或处理失败，退化为纯文字");
            PacketDistributor.sendToServer(new C2SMaidChatPacket(stableId, text == null ? "" : text,
                    HuhuTalkClientSettings.isMaidWhisperEnabled(), HuhuTalkClientSettings.isImmersiveChatEnabled()));
            this.waitingResponse = true;
            resetAfterSend();
            ChatHistoryStorage.setDraft(stableId, "");
            return;
        }
        LOGGER.info("[HuhuTalk] 发送图片聊天: stableId={}, text='{}', imgBytes={}",
                stableId, text, toSend.length);
        PacketDistributor.sendToServer(new C2SMaidImageChatPacket(stableId,
                text == null ? "" : text, toSend,
                HuhuTalkClientSettings.isMaidWhisperEnabled(), HuhuTalkClientSettings.isImmersiveChatEnabled()));
        this.waitingResponse = true;
        // 清空输入框与草稿（否则重进页面草稿里还存着已发出去的这份文字）
        resetAfterSend();
        ChatHistoryStorage.setDraft(stableId, "");
    }

    /** 识图报错：把等待中的图片气泡标为错误（渲染红色感叹号），并清除等待态 */
    public void onVisionError() {
        if (pendingImageKey == null) {
            return;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.image() != null && pendingImageKey.equals(m.image())) {
                messages.set(i, new ChatMessage(m.text(), m.isPlayer(), true, false, m.gameTime(), m.emote(), m.image()));
                break;
            }
        }
        pendingImageKey = null;
    }

    /** 发送后复位输入框与选中图 */
    private void resetAfterSend() {
        currentInput = "";
        cursorIndex = 0;
        inputScrollOffset = 0;
        scrollOffset = 0;
        selectedImageKey = null;
        selectedImageBytes = null;
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
        messages.add(new ChatMessage("", true, false, false, currentGameTime(), path, null));
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
            messages.add(new ChatMessage("", false, false, false, currentGameTime(), reply.loc().toString(), null));
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

    /** 玩家图片气泡尺寸（宽/高），含内边距 */
    private int imageBubbleSize() {
        return IMAGE_DRAW_SIZE + UiFont.sh(8);
    }

    /** 绘制一条玩家发送的本地图片气泡（始终带头像）。 */
    private int drawImageBubble(GuiGraphics g, Font font, int baseX, int yBottom, int phoneW, ChatMessage m,
                                boolean showAvatar, boolean groupTop) {
        int pad = UiFont.sh(4);
        int bubbleW = imageBubbleSize();
        int bubbleH = imageBubbleSize();
        int yTop = yBottom - bubbleH;
        int radius = Math.max(2, bubbleH / 4);
        ResourceLocation rl = PhotoLibrary.getOrLoadTexture(m.image());
        int[] d = PhotoLibrary.getTextureSize(m.image());

        int bubbleLeft = baseX + phoneW - AVATAR - MARGIN - bubbleW; // 玩家图片在右侧
        UiShapes.fillRoundedRect(g, bubbleLeft, yTop, bubbleW, bubbleH, radius, 0xFF5294BF);
        if (rl != null) {
            g.blit(rl, bubbleLeft + pad, yTop + pad, IMAGE_DRAW_SIZE, IMAGE_DRAW_SIZE,
                    0.0f, 0.0f, d[0], d[1], d[0], d[1]);
            // 等待识图：图片上盖一层 30% 灰色滤镜 + 白字"压缩中…"，示意"还没发过去、女仆暂时没看到"
            if (m.pending()) {
                g.fill(bubbleLeft + pad, yTop + pad, bubbleLeft + pad + IMAGE_DRAW_SIZE,
                        yTop + pad + IMAGE_DRAW_SIZE, 0x4D808080);
                float ts = UiFont.sc(0.5f);
                drawScaledText(g, font, "图片压缩中…",
                        bubbleLeft + pad + (IMAGE_DRAW_SIZE - font.width("图片压缩中…") * ts) / 2f,
                        yTop + pad + (IMAGE_DRAW_SIZE - font.lineHeight * ts) / 2f,
                        0xFFFFFFFF, ts);
            }
        } else {
            g.fill(bubbleLeft + pad, yTop + pad, bubbleLeft + pad + IMAGE_DRAW_SIZE,
                    yTop + pad + IMAGE_DRAW_SIZE, 0xFFB0BEC5);
        }
        // 识图出错：图片左侧标小红感叹号
        if (m.isError()) {
            drawScaledText(g, font, "!", bubbleLeft - UiFont.sh(8), yTop + UiFont.sh(2),
                    0xFFFF3333, UiFont.sc(1.0f));
        }
        drawAvatarPlaceholder(g, baseX + phoneW - AVATAR - 2, yTop, true);
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

    // ==================== 物品气泡点击领取（M→P） ====================

    /** 检测本次左键点是否落在某个"尚未接收"的物品气泡上，是则发领取包并返回 true。 */
    private boolean tryClaimItemAt(double relX, double relY, int w) {
        double msgTop = navH();
        double msgBottom = inputTop(0) - 2;
        if (relY < msgTop || relY > msgBottom || messages.isEmpty()) {
            return false;
        }
        Font font = Minecraft.getInstance().font;
        int[] gs = computeGroupStart();
        int n = messages.size();
        // 与渲染一致：自底部向上逐条回溯，把屏幕 Y 映射回某条消息
        double y = msgBottom - 4 + scrollOffset;
        for (int i = n - 1; i >= 0; i--) {
            boolean groupTop = gs[i] == i;
            int totalBubble = bubbleHeight(font, messages.get(i), w, groupTop) + 4;
            if (relY >= y - totalBubble && relY <= y) {
                ChatMessage m = messages.get(i);
                var p = m.item();
                if (p != null && !p.claimed()) {
                    claimItemTransfer(p.transferId);
                    return true;
                }
                return false; // 点在非待领物品消息上：不吞事件
            }
            y -= totalBubble;
        }
        return false;
    }

    /** 发送领取请求：服务端尽力放入玩家背包，剩余可再点，满则整批丢地，随后回包刷新。 */
    private void claimItemTransfer(String transferId) {
        if (transferId == null || transferId.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new C2SClaimTransferPacket(stableId, transferId));
    }

    /** 矩形命中判定（relX/relY 相对手机左上角） */
    private static boolean hit(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_PRESS) return false;

        int baseX = 0, baseY = 0;
        int w = phoneW();

        // 相机模式：独立处理拍照 / 确认 / 保存 / 返回
        if (cameraActive) {
            return handleCameraClick(relX, relY, w);
        }

        if (relX >= 0 && relX <= 60 && relY >= 0 && relY <= navH()) {
            PhoneOverlay.popPage();
            return true;
        }

        if (relX >= w - 24 && relX <= w && relY >= 0 && relY <= navH()) {
            PhoneOverlay.pushPage(new MaidInfoPage(stableId, maidName));
            return true;
        }

        // 物品气泡（M→P 传文件）：点击"尚未接收"的物品消息即向服务端申请领取
        if (tryClaimItemAt(relX, relY, w)) {
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

        // 图片面板（🖼️）：仅在面板区域内处理删除/选中/拖动；面板外的点击（如底栏）放行，避免吞掉其它按钮
        if (bottomPanelOpen && extPanelIcon == 2) {
            int gTop = panelTop(0), gBottom = extBarTop(0);
            if (relY >= gTop && relY < gBottom) {
                if (hitGalleryDelete(relX, relY)) {
                    return true;
                }
                if (hitGalleryDot(relX, relY)) {
                    return true;
                }
                String body = hitGalleryItem(relX, relY);
                if (body != null) {
                    toggleGallerySelect(body);
                    return true;
                }
                galleryDragging = true;
                galleryDragStartX = relX;
                galleryDragStartOffset = galleryScrollOffset;
                return true;
            }
        }

        // 附加栏(第5个"+"，index4)的工具面板：点工具图标则打开对应工具页
        if (bottomPanelOpen && extPanelIcon == 4) {
            int pTop = panelTop(0);
            int pBottom = extBarTop(0);
            if (relY >= pTop && relY < pBottom) {
                int padX = UiFont.sh(8);
                int padY = UiFont.sh(6);
                int labelH = lh();
                int availH = pBottom - pTop;
                int tileS = toolTile();
                int gap = UiFont.sh(6);
                int cols = Math.max(1, (w - padX * 2 + gap) / (tileS + gap));
                int startX = padX;
                int startY = pTop + padY;
                for (int i = 0; i < EXTRA_TOOLS.length; i++) {
                    int col = i % cols;
                    int row = i / cols;
                    int x = startX + col * (tileS + gap);
                    int y = startY + row * (tileS + labelH + UiFont.sh(2));
                    if (relX >= x && relX <= x + tileS && relY >= y && relY <= y + tileS) {
                        openExtraTool(i);
                        return true;
                    }
                }
            }
        }

        // 拓展功能栏（贴屏幕底部）：点击任意按钮切换上拉面板 / 进入相机
        int extTop = extBarTop(0);
        if (relY >= extTop && relY <= phoneH()) {
            int iconCount = BOTTOM_ICONS.length;
            int iconSlotW = w / iconCount;
            int clickedIcon = (int)(relX / iconSlotW);
            if (clickedIcon >= 0 && clickedIcon < iconCount) {
                if (clickedIcon == 3) {
                    // 📷 相机：进入拍照界面（替代聊天）
                    enterCameraMode();
                    return true;
                }
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
            routeChatSend();
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

        // 图片面板打开且鼠标在面板内：滚轮左右翻页（scrollDeltaY 正=下滚 → 右翻）
        if (bottomPanelOpen && extPanelIcon == 2) {
            int pTop = panelTop(0), pBottom = extBarTop(0);
            if (relY >= pTop && relY <= pBottom) {
                galleryScrollOffset = clampGalleryScroll(galleryScrollOffset + (int)(scrollDelta * 20));
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

    /**
     * 一条聊天消息。emote/image 为资源/图片 key；item 为物品气泡负载（M→P 传文件），null=无。
     * 为了少改动已有调用点，保留一个不带 item 的便捷构造（item=null）。
     */
    private record ChatMessage(String text, boolean isPlayer, boolean isError, boolean pending, long gameTime,
                               String emote, String image, com.huhutalk.phone.ItemTransfer.Payload item) {
        private ChatMessage(String text, boolean isPlayer, boolean isError, boolean pending, long gameTime,
                            String emote, String image) {
            this(text, isPlayer, isError, pending, gameTime, emote, image, null);
        }
    }
}