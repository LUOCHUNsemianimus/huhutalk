package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.network.S2CMaidDeliveredPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class OfflineMessageDeliverer {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.offline_deliverer");

    /**
     * 尝试交付女仆的离线消息。
     *
     * @param maid    已加载的女仆实体
     * @param wasDead 她之前是否处于 DEAD 状态（用于构建更贴切的系统提示）
     */
    public static void tryDeliver(EntityMaid maid, boolean wasDead) {
        if (!(maid instanceof StableIdProvider provider)) return;
        String stableId = provider.getStableId();
        if (stableId == null) return;

        MinecraftServer server = maid.level().getServer();
        if (server == null) return;

        // 第一步：检查 PendingSystemMessageBus（主人不在线时被放回的消息）
        List<String> pending = PendingSystemMessageBus.drain(stableId);

        // 第二步：如果 Pending 为空，从 OfflineMessageQueue 转移离线消息
        if (pending.isEmpty()) {
            OfflineMessageQueue offlineQueue = OfflineMessageQueue.get(server);
            List<OfflineMessageQueue.QueuedMessage> queued = offlineQueue.drain(stableId);
            if (!queued.isEmpty()) {
                pending = new ArrayList<>();
                for (OfflineMessageQueue.QueuedMessage msg : queued) {
                    pending.add(String.format("%s: %s", msg.playerName(), msg.message()));
                }
                LOGGER.info("[OfflineDeliverer] 从离线队列取出女仆 {} 的消息 {} 条",
                        stableId, pending.size());
            }
        }

        if (pending.isEmpty()) return;

        // 女仆当前未佩戴手机（已关机）→ 不能主动播报，放回等待
        if (!PhoneHelper.hasPhone(maid)) {
            LOGGER.info("[OfflineDeliverer] 女仆 {} 未佩戴手机，消息放回等待", stableId);
            for (String msg : pending) {
                PendingSystemMessageBus.queue(stableId, msg);
            }
            return;
        }

        LOGGER.info("[OfflineDeliverer] 女仆 {} 有待播报消息 {} 条", stableId, pending.size());

        if (maid.getOwner() instanceof ServerPlayer master && master.isAlive()) {
            String systemText = buildSystemText(pending, wasDead);
            LOGGER.info("[OfflineDeliverer] 主人 {} 在线，发起主动播报", master.getName().getString());
            // 消息已从离线队列取出交给女仆 → 通知主人客户端清除未送达"!"（不等女仆回复）
            PacketDistributor.sendToPlayer(master, new S2CMaidDeliveredPacket(stableId));
            MaidChatHandler.handleSystemInjection(master, stableId, systemText);
        } else {
            LOGGER.info("[OfflineDeliverer] 主人不在线，消息放回等待下次扫描");
            for (String msg : pending) {
                PendingSystemMessageBus.queue(stableId, msg);
            }
        }
    }

    /**
     * 构建系统消息文本：明确告知女仆发生了什么、主人发了多少条、以及每条内容。
     */
    private static String buildSystemText(List<String> messages, boolean wasDead) {
        StringBuilder sb = new StringBuilder();
        if (wasDead) {
            sb.append("你刚刚经历了一次死亡，并成功复活。主人在你离开期间非常担心，")
                    .append("给你发来了 ").append(messages.size()).append(" 条消息。\n\n")
                    .append("以下是你离开期间收到的所有消息：\n");
        } else {
            sb.append("你刚刚苏醒，重新回到主人身边。在你不在这段时间，主人给你发来了 ")
                    .append(messages.size()).append(" 条消息。\n\n")
                    .append("以下是你离开期间收到的所有消息：\n");
        }

        for (int i = 0; i < messages.size(); i++) {
            sb.append(i + 1).append(". ").append(messages.get(i)).append("\n");
        }

        sb.append("\n请逐条阅读这些消息，体会主人当时的心情，然后给主人一个真诚、温暖的回应。");
        return sb.toString();
    }
}