package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.SystemText;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.http.HttpRequest;
import java.util.List;

public class HuhuTalkChatCallback extends LLMCallback {
    private final ServerPlayer sender;
    private final String stableId;
    /** 是否「女仆悄悄话」（沉浸手机聊天）：开启时临时关闭女仆气泡，女仆回复不出现在聊天栏/头顶气泡 */
    private final boolean immersive;
    /** 本次回复待附带的表情资源路径（空串=无）。由 {@link SendEmoteTool} 调用 {@link #attachEmote} 设置 */
    private String pendingEmote = "";

    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                ServerPlayer sender, String stableId) {
        this(chatManager, messages, sender, stableId, false);
    }

    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                ServerPlayer sender, String stableId, boolean immersive) {
        super(chatManager, messages);
        this.sender = sender;
        this.stableId = stableId;
        this.immersive = immersive;
        this.needAddTools = true;
    }

    /** 把一张表情挂到本次回复上（表情作为文本回复的附件，客户端同组显示） */
    public void attachEmote(String emotePath) {
        this.pendingEmote = emotePath == null ? "" : emotePath;
    }

    /** 触发本次聊天的主人（ServerPlayer）。 */
    public ServerPlayer getSender() {
        return sender;
    }

    /** 本会话女仆的 stableId。 */
    public String getStableId() {
        return stableId;
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
        if (sender != null && sender.isAlive() && !replyText.isBlank()) {
            if (getMaid().level() instanceof ServerLevel serverLevel) {
                MinecraftServer server = serverLevel.getServer();
                String emote = pendingEmote;
                server.submit(() -> {
                    // 按玩家所在维度应用信号延迟：主世界满格 / 其余维度中等 / 地狱差
                    SignalDelayManager.sendDelayedReply(sender, stableId, replyText, emote);
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

    /** 关闭该女仆的手机来源窗口（幂等）。用 {@code asEntity()} + instanceof 取实体，避免依赖具体实现。 */
    private void closePhoneSource() {
        EntityMaid maid = (getMaid().asEntity() instanceof EntityMaid m) ? m : null;
        if (maid != null) {
            PhoneChatSourceController.close(maid);
        }
    }
}
