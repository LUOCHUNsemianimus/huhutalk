package com.huhutalk.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 未读消息管理器（按世界隔离）。
 * 切换世界时调用 onWorldChanged，数据互不干扰。
 */
public class NotificationManager {
    private static String currentWorldKey = "default";
    private static final Map<String, Map<UUID, Integer>> UNREAD_BY_WORLD = new HashMap<>();

    /** 切换世界：保留各世界数据，只切换当前指向 */
    public static void onWorldChanged(String newKey) {
        currentWorldKey = newKey;
        UNREAD_BY_WORLD.putIfAbsent(newKey, new HashMap<>());
    }

    /** 当前世界未读表 */
    private static Map<UUID, Integer> current() {
        return UNREAD_BY_WORLD.computeIfAbsent(currentWorldKey, k -> new HashMap<>());
    }

    public static void addUnread(UUID maidUuid) {
        current().merge(maidUuid, 1, Integer::sum);
    }

    public static int getUnread(UUID maidUuid) {
        return current().getOrDefault(maidUuid, 0);
    }

    public static void clear(UUID maidUuid) {
        current().put(maidUuid, 0);
    }

    /** 当前世界所有未读总和 */
    public static int getTotalUnread() {
        return current().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 清空当前世界所有未读（可保留备用） */
    public static void clearAll() {
        current().clear();
    }
}