package com.huhutalk.client;

import java.util.*;

/**
 * 聊天历史 + 草稿存储（按世界隔离，内存态）。
 * <p>
 * 切换世界时调用 onWorldChanged(newKey)，
 * 不同世界的聊天记录互不干扰，也不会丢失。
 * <p>
 * 注意：游戏完全退出后内存清空，历史不跨启动保留。
 * 如需长期保存，后续可加 JSON 文件持久化。
 */
public class ChatHistoryStorage {
    private static String currentWorldKey = "default";
    private static final Map<String, Map<UUID, List<ChatMessageEntry>>> WORLD_HISTORY = new HashMap<>();
    private static final Map<String, Map<UUID, String>> WORLD_DRAFTS = new HashMap<>();

    /** 切换世界：保留各世界数据，只切换当前指向 */
    public static void onWorldChanged(String newKey) {
        currentWorldKey = newKey;
        WORLD_HISTORY.putIfAbsent(newKey, new HashMap<>());
        WORLD_DRAFTS.putIfAbsent(newKey, new HashMap<>());
    }

    private static Map<UUID, List<ChatMessageEntry>> currentHistory() {
        return WORLD_HISTORY.computeIfAbsent(currentWorldKey, k -> new HashMap<>());
    }

    private static Map<UUID, String> currentDrafts() {
        return WORLD_DRAFTS.computeIfAbsent(currentWorldKey, k -> new HashMap<>());
    }

    public static void appendMessage(UUID maidUuid, String text, boolean isPlayer, boolean isError) {
        currentHistory()
                .computeIfAbsent(maidUuid, k -> new ArrayList<>())
                .add(new ChatMessageEntry(text, isPlayer, isError));
    }

    public static List<ChatMessageEntry> getMessages(UUID maidUuid) {
        return currentHistory().getOrDefault(maidUuid, Collections.emptyList());
    }

    public static void setDraft(UUID maidUuid, String draft) {
        currentDrafts().put(maidUuid, draft == null ? "" : draft);
    }

    public static String getDraft(UUID maidUuid) {
        return currentDrafts().getOrDefault(maidUuid, "");
    }

    public record ChatMessageEntry(String text, boolean isPlayer, boolean isError) {
    }
}