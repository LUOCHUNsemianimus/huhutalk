package com.huhutalk.client;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端女仆状态缓存（stableId → 状态文案，如"种地中…"）。
 * 由服务端低频推送更新，供聊天页/女仆列表显示"状态小字"。
 */
public final class MaidStatusCache {
    private static final Map<String, String> STATUS = new HashMap<>();

    private MaidStatusCache() {
    }

    public static void set(String stableId, String status) {
        STATUS.put(stableId, status == null ? "" : status);
    }

    public static String get(String stableId) {
        return STATUS.getOrDefault(stableId, "");
    }

    /** 进入新世界时清空 */
    public static void clear() {
        STATUS.clear();
    }
}
