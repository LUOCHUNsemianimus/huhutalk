package com.huhutalk.core.supply;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「远程补给」工作期登记表（服务端内存态，全局单例）。
 *
 * <h2>两张索引</h2>
 * <ul>
 *   <li>{@link #BY_MAID}：stableId → 工作期。用于"这只女仆还在补给模式吗"的判定与结算。</li>
 *   <li>{@link #BY_PLAYER}：玩家 UUID → 该玩家名下所有在补给模式的女仆 stableId。
 *       用于需求 2.2 的"多只女仆同时开这个模式时，随机挑一只出东西"——
 *       挑的动作必须在玩家维度做，不能各 tick 各的。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 所有入口都在服务端主线程（TLM 的 {@code MaidTickEvent} 与 NeoForge 的
 * {@code ServerTickEvent.Post}），因此内部直接用 {@link HashMap}。
 */
public final class RemoteSupplySessions {

    private static final Map<String, RemoteSupplySession> BY_MAID = new HashMap<>();
    private static final Map<UUID, Set<String>> BY_PLAYER = new HashMap<>();

    private RemoteSupplySessions() {
    }

    // ==================== 开 / 关 ====================

    /**
     * 开启某女仆的补给工作期（已存在则什么都不做，<b>不会</b>重置计数）。
     */
    public static void open(String stableId, UUID playerId, long nowTick) {
        if (stableId == null || stableId.isBlank() || playerId == null) {
            return;
        }
        if (BY_MAID.containsKey(stableId)) {
            return;
        }
        BY_MAID.put(stableId, new RemoteSupplySession(stableId, playerId, nowTick));
        BY_PLAYER.computeIfAbsent(playerId, k -> new LinkedHashSet<>()).add(stableId);
    }

    /** 结束并移除某女仆的工作期，返回被移除的那个（没有则返回 null）。 */
    @Nullable
    public static RemoteSupplySession close(String stableId) {
        if (stableId == null) {
            return null;
        }
        RemoteSupplySession removed = BY_MAID.remove(stableId);
        if (removed != null) {
            Set<String> set = BY_PLAYER.get(removed.playerId());
            if (set != null) {
                set.remove(stableId);
                if (set.isEmpty()) {
                    BY_PLAYER.remove(removed.playerId());
                }
            }
        }
        return removed;
    }

    // ==================== 查询 ====================

    @Nullable
    public static RemoteSupplySession get(String stableId) {
        return stableId == null ? null : BY_MAID.get(stableId);
    }

    /** 该玩家名下所有在补给模式的女仆（顺序稳定）。 */
    public static List<RemoteSupplySession> ofPlayer(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        Set<String> ids = BY_PLAYER.get(playerId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<RemoteSupplySession> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            RemoteSupplySession s = BY_MAID.get(id);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** 该玩家此刻是否至少有一只女仆在补给模式（死亡叙事的门槛之一）。 */
    public static boolean hasAny(UUID playerId) {
        Set<String> ids = BY_PLAYER.get(playerId);
        return ids != null && !ids.isEmpty();
    }

    public static boolean isEmpty() {
        return BY_MAID.isEmpty();
    }

    // ==================== 清理 ====================

    /** 玩家登出：关掉他名下所有工作期（内存态，不补发总结）。 */
    public static void onPlayerGone(UUID playerId) {
        Set<String> ids = BY_PLAYER.remove(playerId);
        if (ids != null) {
            for (String id : ids) {
                BY_MAID.remove(id);
            }
        }
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        BY_MAID.clear();
        BY_PLAYER.clear();
    }
}
