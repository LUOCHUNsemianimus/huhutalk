package com.huhutalk.common;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * 稳定 ID 工具。
 * <p>
 * 女仆实体 UUID 死亡/胶片复活后会刷新，因此引入与女仆 NBT 一起持久化的
 * 独立稳定 ID。所有与女仆绑定的数据（聊天历史、离线队列、联系人簿）都应以
 * 此 ID 为主键，而不是实体 UUID。
 */
public final class MaidStableIdHelper {
    /** 写入女仆实体根 NBT 的标签名 */
    public static final String STABLE_ID_TAG = "HuhuTalkStableId";

    private MaidStableIdHelper() {
    }

    /** 生成一个新的稳定 ID（UUID 字符串） */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * 获取女仆的稳定 ID；若尚未生成则生成并写入实体。
     * 该逻辑在扫描器和死亡监听器中都会用到。
     */
    public static String getOrCreateStableId(EntityMaid maid) {
        if (maid instanceof StableIdProvider provider) {
            String id = provider.getStableId();
            if (id == null) {
                id = generate();
                provider.setStableId(id);
            }
            return id;
        }
        // 理论上不会走到这里，因为所有 EntityMaid 都被 mixin 注入了
        return generate();
    }

    /**
     * 在所有已加载的维度中，按稳定 ID 查找女仆实体。
     * <p>仅读取既有稳定 ID，不新建；找不到返回 {@code null}（女仆未加载/离线/已卸载）。
     * 用于"按需请求状态"等场景。
     */
    public static EntityMaid findMaid(MinecraftServer server, String stableId) {
        if (server == null || stableId == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid && entity instanceof StableIdProvider provider) {
                    if (stableId.equals(provider.getStableId())) {
                        return maid;
                    }
                }
            }
        }
        return null;
    }
}