package com.huhutalk.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 聊天历史 + 草稿存储（按世界隔离 + 磁盘持久化）。
 * 键为女仆 stableId（死亡/复活不变）。
 * <p>
 * 内存中保留当前所有已加载世界的数据；每次写入后同步落盘到
 * .minecraft/huhutalk/chat/<世界key>.json。
 * 切换世界时：保存旧世界 -> 切换 key -> 从磁盘加载新世界。
 * 游戏完全退出后重新进入存档，历史依然存在。
 */
public class ChatHistoryStorage {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static String currentWorldKey = "default";
    private static final Map<String, Map<String, List<ChatMessageEntry>>> WORLD_HISTORY = new HashMap<>();
    private static final Map<String, Map<String, String>> WORLD_DRAFTS = new HashMap<>();

    /** 切换世界：先保存旧世界，再加载新世界 */
    public static void onWorldChanged(String newKey) {
        if (!newKey.equals(currentWorldKey)) {
            saveToDisk(currentWorldKey); // 旧世界落盘
        }
        currentWorldKey = newKey;
        WORLD_HISTORY.putIfAbsent(newKey, new HashMap<>());
        WORLD_DRAFTS.putIfAbsent(newKey, new HashMap<>());
        loadFromDisk(newKey); // 新世界从磁盘恢复
    }

    /** 显式保存当前世界（退出世界/退出游戏时调用） */
    public static void saveCurrentWorld() {
        saveToDisk(currentWorldKey);
    }

    private static Map<String, List<ChatMessageEntry>> currentHistory() {
        return WORLD_HISTORY.computeIfAbsent(currentWorldKey, k -> new HashMap<>());
    }

    private static Map<String, String> currentDrafts() {
        return WORLD_DRAFTS.computeIfAbsent(currentWorldKey, k -> new HashMap<>());
    }

    public static void appendMessage(String stableId, String text, boolean isPlayer, boolean isError) {
        currentHistory()
                .computeIfAbsent(stableId, k -> new ArrayList<>())
                .add(new ChatMessageEntry(text, isPlayer, isError, false));
        saveToDisk(currentWorldKey); // 写入即落盘
    }

    /** 把最后一条"玩家发送"的消息标为未送达（入离线队列），供红"!"显示。返回是否命中 */
    public static boolean markLastSentPending(String stableId) {
        List<ChatMessageEntry> list = currentHistory().get(stableId);
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            ChatMessageEntry e = list.get(i);
            if (e.isPlayer()) {
                list.set(i, new ChatMessageEntry(e.text(), true, e.isError(), true));
                saveToDisk(currentWorldKey);
                return true;
            }
        }
        return false;
    }

    /** 清除某女仆所有未送达标记（女仆回复到达即视为送达） */
    public static void clearPending(String stableId) {
        List<ChatMessageEntry> list = currentHistory().get(stableId);
        if (list == null || list.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            ChatMessageEntry e = list.get(i);
            if (e.pending()) {
                list.set(i, new ChatMessageEntry(e.text(), e.isPlayer(), e.isError(), false));
                changed = true;
            }
        }
        if (changed) {
            saveToDisk(currentWorldKey);
        }
    }

    public static List<ChatMessageEntry> getMessages(String stableId) {
        return currentHistory().getOrDefault(stableId, Collections.emptyList());
    }

    /** 该女仆是否有未送达（入离线队列）的消息，用于列表红"!"标记 */
    public static boolean hasPendingMessages(String stableId) {
        List<ChatMessageEntry> list = currentHistory().get(stableId);
        if (list == null) {
            return false;
        }
        for (ChatMessageEntry e : list) {
            if (e.pending()) {
                return true;
            }
        }
        return false;
    }

    public static void setDraft(String stableId, String draft) {
        currentDrafts().put(stableId, draft == null ? "" : draft);
        saveToDisk(currentWorldKey); // 草稿同样落盘
    }

    public static String getDraft(String stableId) {
        return currentDrafts().getOrDefault(stableId, "");
    }

    // ==================== 磁盘读写 ====================

    /** 存档目录：.minecraft/huhutalk/chat/ */
    private static Path storageDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("huhutalk").resolve("chat");
    }

    /** 世界 key 转安全文件名（去掉 / : 等非法字符） */
    private static String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
    }

    private static Path worldFile(String key) {
        return storageDir().resolve(sanitizeKey(key) + ".json");
    }

    /** JSON 文件结构：history 与 drafts 均用 stableId 字符串作 key */
    private static class WorldSaveData {
        Map<String, List<ChatMessageEntry>> history = new HashMap<>();
        Map<String, String> drafts = new HashMap<>();
    }

    private static void saveToDisk(String key) {
        if ("default".equals(key)) return; // 初始占位 key 不落盘
        try {
            WorldSaveData data = new WorldSaveData();
            Map<String, List<ChatMessageEntry>> hist = WORLD_HISTORY.get(key);
            if (hist != null) {
                for (Map.Entry<String, List<ChatMessageEntry>> e : hist.entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        data.history.put(e.getKey(), e.getValue());
                    }
                }
            }
            Map<String, String> drafts = WORLD_DRAFTS.get(key);
            if (drafts != null) {
                for (Map.Entry<String, String> e : drafts.entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        data.drafts.put(e.getKey(), e.getValue());
                    }
                }
            }
            Path file = worldFile(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            LOGGER.error("[HuhuTalk] 保存聊天历史失败: {}", key, e);
        }
    }

    private static void loadFromDisk(String key) {
        Path file = worldFile(key);
        if (!Files.exists(file)) return;
        try {
            WorldSaveData data = GSON.fromJson(Files.readString(file), WorldSaveData.class);
            Map<String, List<ChatMessageEntry>> hist = new HashMap<>();
            Map<String, String> drafts = new HashMap<>();

            if (data != null && data.history != null) {
                for (Map.Entry<String, List<ChatMessageEntry>> e : data.history.entrySet()) {
                    hist.put(e.getKey(), e.getValue() == null ? new ArrayList<>() : e.getValue());
                }
            }
            if (data != null && data.drafts != null) {
                for (Map.Entry<String, String> e : data.drafts.entrySet()) {
                    drafts.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }

            WORLD_HISTORY.put(key, hist);
            WORLD_DRAFTS.put(key, drafts);
        } catch (IOException e) {
            LOGGER.error("[HuhuTalk] 读取聊天历史失败: {}", key, e);
        }
    }

    public record ChatMessageEntry(String text, boolean isPlayer, boolean isError, boolean pending) {
    }
}