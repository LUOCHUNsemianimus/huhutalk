package com.huhutalk.util;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MaidBridgeUtil {

    /**
     * 判断是否为女仆实体
     */
    public static boolean isMaidEntity(Entity entity) {
        return entity instanceof EntityMaid;
    }

    /**
     * ✅ 修复④：获取玩家拥有的所有女仆（全维度、全服务器搜索）
     * 仅在服务端调用有效；客户端调用返回空列表。
     *
     * @param player 目标玩家（必须是 ServerPlayer）
     * @return 该玩家拥有的所有已加载女仆实体列表
     */
    public static List<EntityMaid> getAllOwnedMaid(ServerPlayer player) {
        List<EntityMaid> owned = new ArrayList<>();
        MinecraftServer server = player.getServer();
        if (server == null) return owned;

        UUID playerUuid = player.getUUID();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof EntityMaid maid && maid.isAlive()) {
                    if (playerUuid.equals(maid.getOwnerUUID())) {
                        owned.add(maid);
                    }
                }
            }
        }
        return owned;
    }

    /**
     * 获取女仆所有者UUID
     */
    public static UUID getMaidOwnerId(EntityMaid maid) {
        return maid.getOwnerUUID();
    }
}