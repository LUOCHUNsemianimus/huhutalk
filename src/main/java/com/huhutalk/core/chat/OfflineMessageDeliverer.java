package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.StableIdProvider;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        List<String> pending = PendingSystemMessageBus.drain(stableId);
        if (pending.isEmpty()) {
            return; // 没有待播报的消息
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