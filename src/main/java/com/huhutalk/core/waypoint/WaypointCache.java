package com.huhutalk.core.waypoint;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端路径点缓存：玩家UUID → 该玩家的路径点列表。
 * <p>
 * 路径点本体在客户端（JourneyMap），客户端通过 C2S 包把精简列表同步上来，
 * 供服务端的 move_to 工具解析"去某某命名路径点"。
 */
public final class WaypointCache {
    private static final Map<UUID, List<WaypointEntry>> CACHE = new HashMap<>();

    private WaypointCache() {
    }

    public static void setWaypoints(UUID player, List<WaypointEntry> waypoints) {
        CACHE.put(player, waypoints == null ? List.of() : waypoints);
    }

    public static List<WaypointEntry> getWaypoints(UUID player) {
        return CACHE.getOrDefault(player, List.of());
    }

    /** 按名字（忽略大小写）查找玩家路径点 */
    @Nullable
    public static WaypointEntry find(UUID player, String name) {
        if (name == null) {
            return null;
        }
        for (WaypointEntry wp : getWaypoints(player)) {
            if (wp.name().equalsIgnoreCase(name)) {
                return wp;
            }
        }
        return null;
    }

    public static void clearPlayer(UUID player) {
        CACHE.remove(player);
    }

    /** 服务端关闭时清空 */
    public static void clearAll() {
        CACHE.clear();
    }
}
