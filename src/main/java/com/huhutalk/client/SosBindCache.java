package com.huhutalk.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端 SOS 绑定缓存（stableId 集合）。
 * <p>由服务端 {@code S2CSosBindPacket} 推送更新，供女仆列表页显示"已绑定 SOS"徽标。
 */
public final class SosBindCache {
    private static final Set<String> BOUND = new HashSet<>();

    private SosBindCache() {
    }

    /** 全量覆盖（来自服务端最新绑定列表） */
    public static void set(List<String> stableIds) {
        BOUND.clear();
        if (stableIds != null) {
            BOUND.addAll(stableIds);
        }
    }

    public static boolean isBound(String stableId) {
        return BOUND.contains(stableId);
    }

    public static int count() {
        return BOUND.size();
    }

    /** 进入新世界时清空 */
    public static void clear() {
        BOUND.clear();
    }
}
