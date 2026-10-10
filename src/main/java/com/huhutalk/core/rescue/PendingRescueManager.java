package com.huhutalk.core.rescue;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「待救援窗口」管理器（服务端内存态，全局单例）。
 *
 * <h2>多人安全性</h2>
 * 所有状态都以 <b>玩家 UUID</b> 为键，不持有 {@code ServerPlayer} 实例 —— 玩家重生时服务端会
 * 重建实例，按实例缓存必然失效。同时提供 {@link #onPlayerGone(UUID)} 与 {@link #clearAll()}，
 * 在玩家登出 / 服务器停止时清理，避免长开服上的内存泄漏与跨会话串味。
 *
 * <h2>线程约定</h2>
 * 本类的所有入口（{@code LivingDeathEvent} / {@code PlayerRespawnPositionEvent} /
 * {@code ServerTickEvent.Post} / 玩家登出）都发生在<b>服务端主线程</b>，
 * 因此内部直接用 {@link HashMap}，无需并发容器。
 */
public final class PendingRescueManager {

    /** 死亡到救援之间的等待（tick）：5 秒 */
    public static final int DELAY_TICKS = 5 * 20;

    /** 「短时间反复死亡」的统计窗口（tick）：10 分钟。用于让女仆吐槽主人怎么老在死。 */
    private static final long REPEAT_WINDOW_TICKS = 10L * 60L * 20L;

    /** 玩家 UUID -> 待救援登记 */
    private static final Map<UUID, PendingRescue> PENDING = new HashMap<>();

    /**
     * 玩家 UUID -> 本次重生要重定向到的死亡点。
     * <p>
     * 只在「我们主动调用 {@code PlayerList.respawn}」到 {@code PlayerRespawnPositionEvent}
     * 处理完之间的极短窗口内存活，用完即清 —— 这样玩家<b>正常</b>点重生按钮时不会被误重定向。
     */
    private static final Map<UUID, PendingRescue> RESPAWN_REDIRECT = new HashMap<>();

    /** 玩家 UUID -> 统计窗口起点 tick */
    private static final Map<UUID, Long> REPEAT_WINDOW_START = new HashMap<>();
    /** 玩家 UUID -> 窗口内已被救援次数 */
    private static final Map<UUID, Integer> REPEAT_COUNT = new HashMap<>();

    private PendingRescueManager() {
    }

    // ==================== 登记 / 查询 ====================

    /** 登记一次待救援（同一次死亡重复调用只保留最后一次） */
    public static void register(PendingRescue rescue) {
        if (rescue == null) {
            return;
        }
        PENDING.put(rescue.playerId(), rescue);
    }

    @Nullable
    public static PendingRescue get(UUID playerId) {
        return playerId == null ? null : PENDING.get(playerId);
    }

    /** 作废某玩家的待救援（静默）。 */
    public static void cancel(UUID playerId) {
        if (playerId != null) {
            PENDING.remove(playerId);
            RESPAWN_REDIRECT.remove(playerId);
        }
    }

    /** 玩家登出 / 退服：清掉所有与他相关的状态。 */
    public static void onPlayerGone(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PENDING.remove(playerId);
        RESPAWN_REDIRECT.remove(playerId);
        REPEAT_WINDOW_START.remove(playerId);
        REPEAT_COUNT.remove(playerId);
    }

    /** 服务器停止：全清（内存态，随进程结束即可）。 */
    public static void clearAll() {
        PENDING.clear();
        RESPAWN_REDIRECT.clear();
        REPEAT_WINDOW_START.clear();
        REPEAT_COUNT.clear();
    }

    // ==================== tick 对账 ====================

    /**
     * 服务端每 tick 对账：到点的登记出队并执行；玩家已自己点重生 / 已掉线则静默作废。
     * <p>
     * 先"收集 + 出队"再执行，避免执行过程中（重生会牵动玩家列表）回调修改 {@link #PENDING}。
     */
    public static void tick(MinecraftServer server) {
        if (server == null || PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<PendingRescue> due = new ArrayList<>();
        Iterator<Map.Entry<UUID, PendingRescue>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingRescue> entry = it.next();
            if (now >= entry.getValue().executeAtTick()) {
                due.add(entry.getValue());
                it.remove();
            }
        }
        for (PendingRescue rescue : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(rescue.playerId());
            // 掉线：静默作废
            if (player == null) {
                continue;
            }
            // 已经自己点了重生（或复活了）：救援窗口作废，不消耗女仆的药水
            if (!player.isDeadOrDying()) {
                continue;
            }
            MaidRescueService.tryExecute(server, player, rescue);
        }
    }

    // ==================== 重生位置重定向 ====================

    /**
     * 声明"下一个对该玩家的重生要被重定向到死亡点"。
     * <p>调用方必须<b>紧接着</b>调用 {@code PlayerList.respawn(...)}，两者之间不能插入其他玩家操作。
     */
    public static void beginRespawnRedirect(PendingRescue rescue) {
        if (rescue != null) {
            RESPAWN_REDIRECT.put(rescue.playerId(), rescue);
        }
    }

    /** 取出并清除该玩家的重生重定向（没有则返回 null）。 */
    @Nullable
    public static PendingRescue consumeRespawnRedirect(UUID playerId) {
        return playerId == null ? null : RESPAWN_REDIRECT.remove(playerId);
    }

    // ==================== 反复死亡统计 ====================

    /**
     * 记一次成功救援，返回<b>统计窗口内这是第几次</b>（首次返回 1）。
     * <p>用于在注入给女仆的系统消息里追加一句吐槽（"主人你怎么一直在死啊"）。
     * 超过窗口则重新开窗计数。
     */
    public static int countRescue(UUID playerId, long nowTick) {
        if (playerId == null) {
            return 1;
        }
        Long start = REPEAT_WINDOW_START.get(playerId);
        if (start == null || nowTick - start > REPEAT_WINDOW_TICKS) {
            REPEAT_WINDOW_START.put(playerId, nowTick);
            REPEAT_COUNT.put(playerId, 1);
            return 1;
        }
        int next = REPEAT_COUNT.getOrDefault(playerId, 0) + 1;
        REPEAT_COUNT.put(playerId, next);
        return next;
    }
}
