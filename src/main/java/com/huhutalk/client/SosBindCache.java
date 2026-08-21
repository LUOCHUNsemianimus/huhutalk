package com.huhutalk.client;

import com.huhutalk.core.contact.SosBindData;
import com.huhutalk.network.S2CSosBindPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端 SOS 绑定缓存（stableId → 工作模式 taskUid）。
 * <p>由服务端 {@code S2CSosBindPacket} 推送更新，供女仆列表页显示"已绑定 SOS"徽标，
 * 以及绑定页显示/选择工作模式。
 */
public final class SosBindCache {
    private static final Map<String, String> BINDINGS = new HashMap<>();

    private SosBindCache() {
    }

    /** 全量覆盖（来自服务端最新绑定列表，含工作模式） */
    public static void set(List<S2CSosBindPacket.SosModeEntry> entries) {
        BINDINGS.clear();
        if (entries != null) {
            for (S2CSosBindPacket.SosModeEntry e : entries) {
                BINDINGS.put(e.stableId(), e.taskUid());
            }
        }
    }

    public static boolean isBound(String stableId) {
        return BINDINGS.containsKey(stableId);
    }

    /** 读取某绑定女仆的工作模式 taskUid；未绑定时返回默认近战 */
    public static String getTaskUid(String stableId) {
        return BINDINGS.getOrDefault(stableId, SosBindData.DEFAULT_TASK_UID);
    }

    public static int count() {
        return BINDINGS.size();
    }

    /** 进入新世界时清空 */
    public static void clear() {
        BINDINGS.clear();
    }
}
