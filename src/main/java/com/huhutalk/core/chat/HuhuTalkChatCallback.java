package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatResponsePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;

public class HuhuTalkChatCallback extends LLMCallback {
    private final ServerPlayer sender;
    private final UUID maidUuid;

    public HuhuTalkChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages, ServerPlayer sender, UUID maidUuid) {
        super(chatManager, messages);
        this.sender = sender;
        this.maidUuid = maidUuid;
        // ✅ 修复④：务必开启工具调用，否则 LLM 会把 tool_call 当作文本输出
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
                    PacketDistributor.sendToPlayer(sender, new S2CMaidChatResponsePacket(maidUuid, replyText));
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
                    PacketDistributor.sendToPlayer(sender, new S2CMaidChatErrorPacket(maidUuid, throwable.getMessage()));
                });
            }
        }
    }
}