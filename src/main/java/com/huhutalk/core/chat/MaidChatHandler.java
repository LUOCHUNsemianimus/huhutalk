package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MaidChatHandler {
    public static void handleChat(ServerPlayer player, UUID maidUuid, String message) {
        EntityMaid maid = findMaidGlobally(player.getServer(), maidUuid);
        if (maid == null) {
            PacketDistributor.sendToPlayer(player, new S2CMaidChatErrorPacket(maidUuid, "找不到该女仆"));
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player, new S2CMaidChatErrorPacket(maidUuid, "这不是你的女仆"));
            return;
        }

        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            PacketDistributor.sendToPlayer(player, new S2CMaidChatErrorPacket(maidUuid, "女仆未配置 AI 或 AI 不可用"));
            return;
        }

        // 将用户消息写入历史
        manager.addUserHistory(message);

        // 聊天行动
        Runnable chatAction = () -> {
            List<LLMMessage> messages = buildMessages(manager, message);
            HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, maidUuid);
            site.client().chat(callback);
        };

        // 尝试摘要压缩（若触发则延迟执行 chatAction）
        if (manager.getHistorySummaryManager().tryCompressBeforeChat(chatAction)) {
            return;
        }
        chatAction.run();
    }

    private static List<LLMMessage> buildMessages(MaidAIChatManager manager, String userMessage) {
        EntityMaid maid = manager.getMaid();
        List<LLMMessage> messages = new ArrayList<>();
        // 系统设定
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            String settingText = setting.getSetting(maid, language);
            messages.add(LLMMessage.systemChat(maid, settingText));
        });
        // 摘要
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        // 历史记录（从旧到新）
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        // 当前消息
        messages.add(LLMMessage.userChat(maid, userMessage));
        return messages;
    }

    private static EntityMaid findMaidGlobally(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof EntityMaid maid && maid.isAlive()) {
                return maid;
            }
        }
        return null;
    }
}