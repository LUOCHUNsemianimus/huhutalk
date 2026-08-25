package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 「女仆悄悄话」气泡抑制控制器（服务端）。
 * <p>
 * 当玩家开启「女仆悄悄话」并通过手机发起聊天时，把对应女仆的头顶气泡开关
 * {@code isChatBubbleShow()} 临时关闭一段较长时间（覆盖思考气泡 + 回复气泡的完整生命周期），
 * 之后再恢复。因为 {@code isChatBubbleShow} 是 entityData，服务端关闭后经同步
 * <b>对所有客户端全局生效</b>（原版「单女仆气泡开关」即走此机制）——装本 mod 的玩家视角里
 * 该女仆都不会再冒气泡。
 * <p>
 * <b>退出保护</b>：若抑制期间玩家退出游戏 / 世界停止，会调用 {@link #restoreAll()} 立即恢复所有被抑制女仆，
 * 避免气泡被永久关闭。
 * <p>
 * 通过服务端 tick 到点恢复，与 {@link SignalDelayManager} 同一套"队列 + 每 tick 对账"模式。
 */
public final class ImmersiveBubbleController {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.immersive_bubble");

    /** 气泡抑制持续时间（tick）：默认 15 秒。覆盖思考中 + 回复气泡存在周期，足够手机逐条回复播完 */
    private static final int SUPPRESS_DURATION_TICKS = 15 * 20;

    /** 女仆 UUID -> 应恢复的 tick 与气泡原值 */
    private static final Map<UUID, RestoreEntry> PENDING = new HashMap<>();

    private ImmersiveBubbleController() {
    }

    /**
     * 抑制某女仆的气泡显示（悄悄话）。重复调用会刷新恢复时间并保持关闭。
     * 必须在服务端主线程调用。
     */
    public static void suppress(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        UUID id = maid.getUUID();
        RestoreEntry existing = PENDING.get(id);
        // 记录原值：首次抑制时保存，后续抑制沿用第一次记录的原值（避免中途误改）
        boolean original = (existing != null) ? existing.original : maid.getConfigManager().isChatBubbleShow();
        long recoverTick = maid.level().getServer().getTickCount() + SUPPRESS_DURATION_TICKS;
        PENDING.put(id, new RestoreEntry(recoverTick, original));
        maid.getConfigManager().setChatBubbleShow(false);
    }

    /** 服务端每 tick 检查：到点的女仆恢复气泡原值。 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, RestoreEntry>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RestoreEntry> e = it.next();
            if (e.getValue().recoverTick > now) {
                continue;
            }
            it.remove();
            restore(e.getKey(), e.getValue().original);
        }
    }

    /** 立即恢复所有被抑制的女仆（世界停止 / 玩家登出防御），防止气泡被永久关闭。 */
    public static void restoreAll() {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, RestoreEntry> e : PENDING.entrySet()) {
            restore(e.getKey(), e.getValue().original);
        }
        PENDING.clear();
    }

    /** 按女仆 UUID 找回实体并恢复气泡原值（找不到实体则只移除登记）。 */
    private static void restore(UUID maidUuid, boolean original) {
        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid =
                findMaidByUuid(maidUuid);
        if (maid != null) {
            maid.getConfigManager().setChatBubbleShow(original);
        } else {
            LOGGER.debug("[ImmersiveBubble] 未找到女仆实体 {}，仅移除抑制登记", maidUuid);
        }
    }

    /** 在服务端所有已加载维度里按 UUID 查找女仆实体。 */
    private static EntityMaid findMaidByUuid(UUID uuid) {
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            net.minecraft.world.entity.Entity e = level.getEntity(uuid);
            if (e instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }

    /** 一条待恢复记录 */
    private record RestoreEntry(long recoverTick, boolean original) {
    }
}
