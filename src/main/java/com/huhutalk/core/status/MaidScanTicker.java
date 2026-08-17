package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.MaidStatus;
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

                    book.markOnline(
                            stableId,
                            maid.getName().getString(),
                            level.dimension().location().toString(),
                            gameTime,
                            maid.getOwnerUUID().toString()
                    );

                    // 不再依赖 resurrected，只要女仆在线就尝试交付积压消息
                    OfflineMessageDeliverer.tryDeliver(maid);
                }
            }
        }

        for (MaidContactEntry entry : book.getAllEntries().values()) {
            if (entry.getStatus() == MaidStatus.ONLINE && !seenStableIds.contains(entry.getStableId())) {
                book.markOffline(entry.getStableId(), gameTime);
            }
        }
    }
}