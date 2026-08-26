package com.huhutalk.core.chat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线消息队列（SavedData，全局单例，持久化）。
 * <p>
 * 玩家给 OFFLINE/DEAD 女仆发消息时写入该队列；
 * 女仆复活（MaidScanTicker 检测到 ONLINE 恢复）时整批 drain，
 * 生成系统消息注入 PendingSystemMessageBus。
 * <p>
 * 存储 key = 女仆 stableId，value = 按时间排序的消息列表。
 */
public class OfflineMessageQueue extends SavedData {
    private static final String DATA_NAME = "huhutalk_offline_messages";

    /** 单只女仆积压的离线消息上限，防止无限增长把存档（level.dat）撑爆 */
    private static final int MAX_PER_MAID = 100;

    private final Map<String, List<QueuedMessage>> messages = new HashMap<>();

    @NotNull
    public static OfflineMessageQueue get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OfflineMessageQueue::new, OfflineMessageQueue::load),
                DATA_NAME
        );
    }

    /** 入队一条离线消息，按时间顺序追加。超过单只女仆上限时丢弃最旧消息。 */
    public void enqueue(String stableId, String playerName, String playerUuid,
                        String message, long gameTime) {
        List<QueuedMessage> list = messages.computeIfAbsent(stableId, k -> new ArrayList<>());
        list.add(new QueuedMessage(playerName, playerUuid, message, gameTime));
        while (list.size() > MAX_PER_MAID) {
            list.remove(0); // 丢弃最旧，防止无界增长
        }
        setDirty();
    }

    /**
     * 取走某女仆的全部离线消息，并清空队列。
     * 返回空列表（非 null）。
     */
    public List<QueuedMessage> drain(String stableId) {
        List<QueuedMessage> removed = messages.remove(stableId);
        if (removed == null) {
            return new ArrayList<>();
        }
        setDirty();
        return removed;
    }

    /** 查询某女仆当前积压的离线消息数量（不清空） */
    public int count(String stableId) {
        List<QueuedMessage> list = messages.get(stableId);
        return list == null ? 0 : list.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, List<QueuedMessage>> entry : messages.entrySet()) {
            CompoundTag listTag = new CompoundTag();
            int idx = 0;
            for (QueuedMessage msg : entry.getValue()) {
                CompoundTag msgTag = new CompoundTag();
                msgTag.putString("playerName", msg.playerName());
                msgTag.putString("playerUuid", msg.playerUuid());
                msgTag.putString("message", msg.message());
                msgTag.putLong("timestamp", msg.timestamp());
                listTag.put(String.valueOf(idx++), msgTag);
            }
            root.put(entry.getKey(), listTag);
        }
        tag.put("queues", root);
        return tag;
    }

    public static OfflineMessageQueue load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        OfflineMessageQueue queue = new OfflineMessageQueue();
        if (tag.contains("queues", Tag.TAG_COMPOUND)) {
            CompoundTag root = tag.getCompound("queues");
            for (String stableId : root.getAllKeys()) {
                CompoundTag listTag = root.getCompound(stableId);
                List<QueuedMessage> list = new ArrayList<>();
                for (int i = 0; listTag.contains(String.valueOf(i), Tag.TAG_COMPOUND); i++) {
                    CompoundTag msgTag = listTag.getCompound(String.valueOf(i));
                    list.add(new QueuedMessage(
                            msgTag.getString("playerName"),
                            msgTag.getString("playerUuid"),
                            msgTag.getString("message"),
                            msgTag.getLong("timestamp")
                    ));
                }
                if (!list.isEmpty()) {
                    queue.messages.put(stableId, list);
                }
            }
        }
        return queue;
    }

    /** 队列中单条消息 */
    public record QueuedMessage(String playerName, String playerUuid, String message, long timestamp) {
    }
}