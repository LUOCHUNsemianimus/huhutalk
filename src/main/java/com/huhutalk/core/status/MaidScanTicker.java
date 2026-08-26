package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.chat.OfflineMessageDeliverer;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;

public class MaidScanTicker {

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        if (server.getTickCount() % 100 != 0) return;

        MaidContactBook book = MaidContactBook.get(server);
        Set<String> seenStableIds = new HashSet<>();
        long gameTime = server.overworld().getGameTime();

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid) {
                    String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
                    seenStableIds.add(stableId);

                    // 在 markOnline 之前获取旧状态，判断她是否刚从死亡/离线中恢复
                    MaidContactEntry oldEntry = book.getEntry(stableId);
                    MaidStatus previousStatus = oldEntry == null ? null : oldEntry.getStatus();
                    boolean wasDead = previousStatus == MaidStatus.DEAD;

                    String name = maid.getName().getString();
                    String dimension = level.dimension().location().toString();
                    // 无主女仆（/summon、刷怪蛋、无契约）getOwnerUUID() 为 null，null 安全处理
                    String ownerUuid = maid.getOwnerUUID() == null ? "" : maid.getOwnerUUID().toString();

                    // 有手机 → ONLINE（可达）；无手机 → SHUTDOWN（已关机）
                    if (PhoneHelper.hasPhone(maid)) {
                        book.markOnline(stableId, name, dimension, gameTime, ownerUuid);
                    } else {
                        book.markShutdown(stableId, name, dimension, gameTime, ownerUuid);
                    }

                    // 每次扫描都尝试交付离线消息，内部会检查队列
                    OfflineMessageDeliverer.tryDeliver(maid, wasDead);
                }
            }
        }

        // 将 ONLINE 但未扫描到的标记为 OFFLINE
        for (MaidContactEntry entry : book.getAllEntries().values()) {
            if (entry.getStatus() == MaidStatus.ONLINE && !seenStableIds.contains(entry.getStableId())) {
                book.markOffline(entry.getStableId(), gameTime);
            }
        }
    }
}