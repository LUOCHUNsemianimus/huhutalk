package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
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

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 气泡抑制已由 ImmersiveBubbleController 在聊天发起时处理（覆盖整个回复周期 + 全局生效），
        // 这里无需再临时关气泡。
        super.onSuccess(responseChat);

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
        if (sender != null && sender.isAlive()) {
            if (getMaid().level() instanceof ServerLevel serverLevel) {
                serverLevel.getServer().submit(() -> {
                    PacketDistributor.sendToPlayer(sender,
                            new S2CMaidChatErrorPacket(stableId, throwable.getMessage()));
                });
            }
        }
    }
}
