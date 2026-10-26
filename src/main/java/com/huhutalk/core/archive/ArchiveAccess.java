package com.huhutalk.core.archive;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 档案读取的共享归属守卫。
 *
 * <p>历史分页与按需图片两个入口必须使用同一判断：已加载女仆必须当前归属该玩家；
 * 只有女仆未加载时才允许联系人簿归属兜底。已加载但归属不符绝不回退旧联系人。</p>
 */
public final class ArchiveAccess {
    private ArchiveAccess() {
    }

    public static boolean mayRead(MinecraftServer server, ServerPlayer player, String maidStableId) {
        if (server == null || player == null) {
            return false;
        }
        String stableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        EntityMaid maid = MaidStableIdHelper.findMaid(server, stableId);
        if (maid != null) {
            return player.getUUID().equals(maid.getOwnerUUID());
        }
        MaidContactEntry entry = MaidContactBook.get(server).getEntry(stableId);
        return entry != null && player.getUUID().toString().equals(entry.getOwnerUuid());
    }
}
