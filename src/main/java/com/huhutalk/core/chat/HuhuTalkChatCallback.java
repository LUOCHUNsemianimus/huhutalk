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

    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                ServerPlayer sender, String stableId) {
        super(chatManager, messages);
        this.sender = sender;
        this.stableId = stableId;
        this.needAddTools = true;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        super.onSuccess(responseChat);
        String replyText = responseChat.getChatText();
        if (sender != null && sender.isAlive() && !replyText.isBlank()) {
            if (getMaid().level() instanceof ServerLevel serverLevel) {
                MinecraftServer server = serverLevel.getServer();
                server.submit(() -> {
                    // 按玩家所在维度应用信号延迟：主世界满格 / 其余维度中等 / 地狱差
                    SignalDelayManager.sendDelayedReply(sender, stableId, replyText);
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