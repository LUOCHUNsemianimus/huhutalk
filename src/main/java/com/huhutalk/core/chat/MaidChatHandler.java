package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
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
            handleOfflineMessage(player, stableId, message);
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "这不是你的女仆"));
            return;
        }
        // 女仆已加载但未佩戴手机 → 已关机，无法远程联系
        if (!PhoneHelper.hasPhone(maid)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatInfoPacket(stableId, "该女仆没有佩戴手机，暂时无法联系。"));
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
            // 正常聊天允许消费待注入系统消息（如果有积压，一并带上）
            String stableIdIn = stableId;
            EntityMaid maidIn = maid;
            if (maidIn instanceof StableIdProvider provider) {
                List<String> pending = PendingSystemMessageBus.drain(stableIdIn);
                if (!pending.isEmpty()) {
                    messages.add(0, LLMMessage.systemChat(maidIn, String.join("\n", pending)));
                }
            }
            HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableIdIn);
            site.client().chat(callback);
        };

        if (manager.getHistorySummaryManager().tryCompressBeforeChat(chatAction)) {
            return;
        }
        chatAction.run();
    }

    /**
     * 主动播报离线消息。不写 TLM 用户历史。
     * <p>
     * 消息结构：
     * [SYSTEM 离线汇总] [SYSTEM 设定] [SYSTEM 摘要(可选)] [历史] [USER 引导回应]
     */
    public static void handleSystemInjection(ServerPlayer player, String stableId, String systemText) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) return;
        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) return;

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.systemChat(maid, systemText));
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            String settingText = setting.getSetting(maid, language);
            messages.add(LLMMessage.systemChat(maid, settingText));
        });
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        messages.add(LLMMessage.userChat(maid, "主人就站在你面前，正期待着你的反应。请综合以上所有信息，给他一个自然的、发自内心的回应。"));

        HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableId);
        site.client().chat(callback);
    }

    private static void handleOfflineMessage(ServerPlayer player, String stableId, String message) {
        MaidContactBook book = MaidContactBook.get(player.getServer());
        MaidContactEntry entry = book.getEntry(stableId);

        if (entry == null || !entry.getOwnerUuid().equals(player.getUUID().toString())) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "找不到该女仆或不是你的女仆"));
            return;
        }

        OfflineMessageQueue queue = OfflineMessageQueue.get(player.getServer());
        queue.enqueue(stableId, player.getName().getString(), player.getUUID().toString(),
                message, player.serverLevel().getGameTime());

        String infoText = entry.getStatus() == MaidStatus.DEAD
                ? "女仆已死亡，消息会等她复活后代为转达。"
                : "女仆暂不在线，消息已离线保存。";
        PacketDistributor.sendToPlayer(player, new S2CMaidChatInfoPacket(stableId, infoText));
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