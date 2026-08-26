package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidDeathEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.contact.MaidContactBook;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;

public class MaidDeathHandler {

    @SubscribeEvent
    public void onMaidDeath(MaidDeathEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(maid.level() instanceof ServerLevel serverLevel)) return;

        String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
        MaidContactBook book = MaidContactBook.get(serverLevel.getServer());
        book.markDead(
                stableId,
                maid.getName().getString(),
                serverLevel.dimension().location().toString(),
                serverLevel.getGameTime(),
                maid.getOwnerUUID() == null ? "" : maid.getOwnerUUID().toString()
        );
    }
}