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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

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
    /** 扫描间隔（tick）。约 1000 tick = 每 ~50 秒一次，低频；仅状态变化才推送 */
    private static final int INTERVAL = 1000;

    private final Map<String, String> lastSent = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
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
