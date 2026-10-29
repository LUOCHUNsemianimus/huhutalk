package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.SystemText;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveService;
import com.huhutalk.core.archive.ArchiveTurnContext;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class HuhuTalkChatCallback extends LLMCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.archive");

    private final ServerPlayer sender;
    private final String stableId;
    /** 是否「女仆悄悄话」（沉浸手机聊天）：开启时临时关闭女仆气泡，女仆回复不出现在聊天栏/头顶气泡 */
    private final boolean immersive;
    /** 在线手机归档 turn（文本或图片）；null = 旧构造入口/系统注入，不写归档。 */
    private final ArchiveTurnContext archiveTurn;
    /** 女仆回复归档 messageId 在构造时生成并复用，避免重复 onSuccess 写多份。 */
    private final String archiveReplyMessageId;
    private final AtomicBoolean archiveReplySubmitted = new AtomicBoolean(false);
    /** 本次回复待附带的表情资源路径（空串=无）。由 {@link SendEmoteTool} 调用 {@link #attachEmote} 设置 */
    private String pendingEmote = "";
    /**
     * 本次回复可选附带的只读归档引用。仅本回调持有，不跨回调共享；发送/归档接线在后续步骤。
     */
    private volatile PhoneMessageQuote pendingQuote;

    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                ServerPlayer sender, String stableId) {
        this(chatManager, messages, sender, stableId, false);
    }

    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                ServerPlayer sender, String stableId, boolean immersive) {
        this(chatManager, messages, sender, stableId, immersive, null);
    }

    /**
     * 兼容重载：archiveTurn 非空时才把本回调最终文本回复写入会话归档。
     * 原构造器保持委托 archiveTurn=null，系统注入仍走原调用。
     */
    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                ServerPlayer sender, String stableId, boolean immersive,
                                ArchiveTurnContext archiveTurn) {
        super(chatManager, messages);
        this.sender = sender;
        this.stableId = stableId;
        this.immersive = immersive;
        this.archiveTurn = archiveTurn;
        this.archiveReplyMessageId = archiveTurn == null ? "" : UUID.randomUUID().toString();
        this.needAddTools = true;
    }

    /** 把一张表情挂到本次回复上（表情作为文本回复的附件，客户端同组显示） */
    public void attachEmote(String emotePath) {
        this.pendingEmote = emotePath == null ? "" : emotePath;
    }

    /** 挂上一条已经验证来源的只读引用；同一回调后挂的引用覆盖先前的。 */
    public void attachQuote(PhoneMessageQuote quote) {
        this.pendingQuote = quote;
    }

    /** 当前回调待发送的引用；没有时为 null。 */
    public PhoneMessageQuote getQuote() {
        return pendingQuote;
    }

    /** 清掉本回调的引用，避免失败的 quote 尝试沿用旧结果。 */
    public void clearQuote() {
        this.pendingQuote = null;
    }

    /** 触发本次聊天的主人（ServerPlayer）。 */
    public ServerPlayer getSender() {
        return sender;
    }

    /** 本会话女仆的 stableId。 */
    public String getStableId() {
        return stableId;
    }

    /** 当前聊天轮次的归档上下文；系统注入/旧构造入口为 null。 */
    public ArchiveTurnContext getArchiveTurn() {
        return archiveTurn;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 气泡抑制已由 ImmersiveBubbleController 在聊天发起时处理（覆盖整个回复周期 + 全局生效），
        // 这里无需再临时关气泡。
        super.onSuccess(responseChat);

        // 注意：这里【不】关闭手机来源窗口。
        // 窗口需要覆盖"思考 + 逐条打字显示"整段，而逐条显示是纯客户端行为（节拍由玩家的
        // 「每字耗时 / 单句上限」决定），服务端无从得知何时播完。因此改由客户端在最后一条
        // 短句落屏后回执 C2SPhoneTypingDonePacket，服务端再关闭窗口 → 动画放下手机。
        // 服务端 tick 仍有超时兜底，防止客户端异常导致窗口悬挂。

        String replyText = responseChat.getChatText();
        // 一次性捕获本次回复选中的表情与引用，归档与后续发送使用同一份事实。
        String emote = pendingEmote;
        PhoneMessageQuote quote = pendingQuote;

        // 归档最终回复不依赖 sender 是否在线/存活；归属变化在 service 内校验并只记归档日志。
        if (archiveTurn != null && replyText != null && !replyText.isBlank()
                && getMaid().level() instanceof ServerLevel archiveLevel) {
            if (archiveReplySubmitted.compareAndSet(false, true)) {
                MinecraftServer server = archiveLevel.getServer();
                String messageId = archiveReplyMessageId;
                server.submit(() -> archiveReply(server, messageId, replyText, emote, quote));
            }
        }

        if (sender != null && sender.isAlive() && !replyText.isBlank()) {
            if (getMaid().level() instanceof ServerLevel serverLevel) {
                MinecraftServer server = serverLevel.getServer();
                server.submit(() -> {
                    // 按玩家所在维度应用信号延迟：主世界满格 / 其余维度中等 / 地狱差
                    SignalDelayManager.sendDelayedReply(sender, stableId, replyText, emote, quote);
                });
            }
        }
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        super.onFailure(request, throwable, errorCode);
        // 失败没有回复可显示，不会再有客户端回执 → 立即关闭窗口，避免动画卡在持机状态
        closePhoneSource();
        if (sender != null && sender.isAlive()) {
            if (getMaid().level() instanceof ServerLevel serverLevel) {
                serverLevel.getServer().submit(() -> {
                    // 通用「聊天失败」模板 + 解包后的原异常详情；不吞详情、不改异常处理
                    PacketDistributor.sendToPlayer(sender,
                            new S2CMaidChatErrorPacket(stableId,
                                    SystemText.of("message.huhutalk.chat.chat_failed",
                                            SystemText.errorDetail(throwable))));
                });
            }
        }
    }

    private void archiveReply(MinecraftServer server, String messageId, String replyText, String emote,
                              PhoneMessageQuote quote) {
        try {
            ArchiveService.recordMaidReply(server, getMaid(), archiveTurn, messageId, replyText,
                    emote, quote == null ? null : quote.messageId());
        } catch (ArchiveException exception) {
            // 只限捕获归档错误：不改变原聊天/LLM 错误处理，也不阻断回复发送。
            LOGGER.error("[HuhuTalk] 在线手机女仆回复归档失败 turnId={}",
                    archiveTurn.turnId(), exception);
        }
    }

    /** 关闭该女仆的手机来源窗口（幂等）。用 {@code asEntity()} + instanceof 取实体，避免依赖具体实现。 */
    private void closePhoneSource() {
        EntityMaid maid = (getMaid().asEntity() instanceof EntityMaid m) ? m : null;
        if (maid != null) {
            PhoneChatSourceController.close(maid);
        }
    }
}
