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
        // 「女仆悄悄话」：在主线程先把该女仆气泡临时关闭（entityData），再触发 super.onSuccess。
        // super.onSuccess 内部通过 server.submit 异步执行 addLLMChatText（发聊天栏文字 + 加气泡），
        // 该队列条目排在"关闭"之后；随后 finally 再 submit 恢复原值。因此在 addLLMChatText 执行时
        // isChatBubbleShow()==false → mixin 跳过聊天栏文字、渲染器不画气泡，之后恢复。
        // 不用 try/finally 包住整个 super，因为 super 内部本身就异步入队，只需保证关闭/恢复的入队顺序。
        if (immersive && getMaid().level() instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            boolean original = getMaid().getConfigManager().isChatBubbleShow();
            server.submit(() -> getMaid().getConfigManager().setChatBubbleShow(false));
            try {
                super.onSuccess(responseChat);
            } finally {
                server.submit(() -> getMaid().getConfigManager().setChatBubbleShow(original));
            }
        } else {
            super.onSuccess(responseChat);
        }

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
