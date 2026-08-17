package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.StableIdProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class OfflineMessageDeliverer {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.offline_deliverer");

    public static void tryDeliver(EntityMaid maid) {
        if (!(maid instanceof StableIdProvider provider)) {
            return;
        }
        String stableId = provider.getStableId();
        if (stableId == null) {
            return;
        }

        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            return;
        }

        // 第一步：检查 PendingSystemMessageBus（主人不在线时放回的消息）
        List<String> pending = PendingSystemMessageBus.drain(stableId);

        // 第二步：如果 Pending 为空，从 OfflineMessageQueue 转移离线消息
        if (pending.isEmpty()) {
            OfflineMessageQueue offlineQueue = OfflineMessageQueue.get(server);
            List<OfflineMessageQueue.QueuedMessage> queued = offlineQueue.drain(stableId);
            if (!queued.isEmpty()) {
                pending = new ArrayList<>();
                for (OfflineMessageQueue.QueuedMessage msg : queued) {
                    pending.add(String.format("[离线消息] %s: %s", msg.playerName(), msg.message()));
                }
                LOGGER.info("[OfflineDeliverer] 从离线队列取出女仆 {} 的消息 {} 条",
                        stableId, pending.size());
            }
        }

        if (pending.isEmpty()) {
            return; // 确实没有要播报的消息
        }

        LOGGER.info("[OfflineDeliverer] 女仆 {} 有待播报消息 {} 条", stableId, pending.size());

        if (maid.getOwner() instanceof ServerPlayer master && master.isAlive()) {
            String systemText = String.join("\n", pending);
            LOGGER.info("[OfflineDeliverer] 主人 {} 在线，发起主动播报", master.getName().getString());
            MaidChatHandler.handleSystemInjection(master, stableId, systemText);
        } else {
            LOGGER.info("[OfflineDeliverer] 主人不在线，消息放回等待下次扫描");
            for (String msg : pending) {
                PendingSystemMessageBus.queue(stableId, msg);
            }
        }
    }
}