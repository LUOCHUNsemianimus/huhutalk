package com.huhutalk.core.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待注入系统消息总线（内存）。
 * <p>
 * 女仆复活时由 MaidScanTicker 将离线消息汇总后放进来，
 * 步骤 7 的 MaidAIChatManagerMixin 在 AI 管理器就绪时取出并注入。
 * 不做磁盘持久化：如果服务器在注入前崩溃，系统消息丢失可接受。
 */
public class PendingSystemMessageBus {
    private static final Map<String, List<String>> PENDING = new HashMap<>();

    /** 给某女仆排队一条系统消息 */
    public static void queue(String stableId, String message) {
        PENDING.computeIfAbsent(stableId, k -> new ArrayList<>()).add(message);
    }

    /** 取走某女仆的全部系统消息，并清空。返回空列表（非 null） */
    public static List<String> drain(String stableId) {
        List<String> removed = PENDING.remove(stableId);
        return removed == null ? new ArrayList<>() : removed;
    }

    /** 清空全部（服务器关闭/世界退出时可调用，防御内存泄漏） */
    public static void clearAll() {
        PENDING.clear();
    }
}