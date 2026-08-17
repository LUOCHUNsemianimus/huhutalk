package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class MaidChatHandler {
    public static void handleChat(ServerPlayer player, String stableId, String message) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) {
            // TODO 步骤6：改为离线消息入队
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "女仆不在线或未加载"));
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "这不是你的女仆"));
            return;
        }

        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "女仆未配置 AI 或 AI 不可用"));
            return;
        }

        manager.addUserHistory(message);

        Runnable chatAction = () -> {
            List<LLMMessage> messages = buildMessages(manager, message);
            HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableId);
            site.client().chat(callback);
        };

        if (manager.getHistorySummaryManager().tryCompressBeforeChat(chatAction)) {
            return;
        }
        chatAction.run();
    }

    private static List<LLMMessage> buildMessages(MaidAIChatManager manager, String userMessage) {
        EntityMaid maid = manager.getMaid();
        List<LLMMessage> messages = new ArrayList<>();
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            String settingText = setting.getSetting(maid, language);
            messages.add(LLMMessage.systemChat(maid, settingText));
        });
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        messages.add(LLMMessage.userChat(maid, userMessage));
        return messages;
    }

    private static EntityMaid findMaidByStableId(MinecraftServer server, String stableId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid
                        && maid instanceof StableIdProvider provider
                        && stableId.equals(provider.getStableId())
                        && maid.isAlive()) {
                    return maid;
                }
            }
        }
        return null;
    }
}