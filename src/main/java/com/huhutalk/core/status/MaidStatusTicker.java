package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.move.MaidMoveData;
import com.huhutalk.network.S2CMaidStatusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.huhutalk.network.transport.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 女仆状态低频推送器。
 * <p>每隔一段时间扫描一次有手机且未被调度守家的女仆，计算状态文案；
 * 仅在状态变化时发给其主人。这样工作/休息/睡觉的关键节点都会被捕捉，但网络/开销很小。
 */
public class MaidStatusTicker {
    /** 扫描间隔（tick）。约 4000 tick = 一次游戏日内 6 次，低频，遵循减少性能开销原则。
     *  实时刷新主要靠"打开聊天/列表时按需请求"，这里只做低频兜底。 */
    private static final int INTERVAL = 4000;

    private final Map<String, String> lastSent = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (server.getTickCount() % INTERVAL != 0) {
            return;
        }
        scan(server);
    }

    private void scan(MinecraftServer server) {
        Set<String> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid) {
                    String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
                    seen.add(stableId);

                    // 没手机 / 正在被调度移动守家 → 不检索状态
                    if (!PhoneHelper.hasPhone(maid) || MaidMoveData.hasTarget(maid)) {
                        continue;
                    }

                    String status = MaidStatusText.compute(maid);
                    String prev = lastSent.get(stableId);
                    if (status.equals(prev)) {
                        continue;
                    }
                    lastSent.put(stableId, status);

                    LivingEntity owner = maid.getOwner();
                    if (owner instanceof ServerPlayer sp && sp.isAlive()) {
                        PacketDistributor.sendToPlayer(sp, new S2CMaidStatusPacket(stableId, status));
                    }
                }
            }
        }
        // 清理已不在场（卸载/死亡）的女仆，避免缓存膨胀
        lastSent.keySet().removeIf(id -> !seen.contains(id));
    }
}
