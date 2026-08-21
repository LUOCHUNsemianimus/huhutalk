package com.huhutalk.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端可用女仆工作模式缓存（taskUid 列表）。
 * <p>由服务端 {@code S2CSosTaskListPacket} 推送，供 SOS 绑定页渲染工作模式选择器。
 * 兼容新增攻击模式的 mod：所有非隐藏任务都会被枚举。
 */
public final class SosTaskCache {
    private static final List<String> TASKS = new ArrayList<>();

    private SosTaskCache() {
    }

    /** 全量覆盖（来自服务端最新任务列表） */
    public static void set(List<String> taskUids) {
        TASKS.clear();
        if (taskUids != null) {
            TASKS.addAll(taskUids);
        }
    }

    /** 全部可用任务 taskUid（不可变副本） */
    public static List<String> getAll() {
        return List.copyOf(TASKS);
    }

    /** 是否已加载任务列表 */
    public static boolean isEmpty() {
        return TASKS.isEmpty();
    }

    /** 进入新世界时清空 */
    public static void clear() {
        TASKS.clear();
    }
}
