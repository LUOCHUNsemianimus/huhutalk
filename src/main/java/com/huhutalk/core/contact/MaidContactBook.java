package com.huhutalk.core.contact;

import com.huhutalk.common.MaidStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 联系人簿（SavedData），全局单例（存放在主世界维度）。
 * <p>
 * 永久保存所有女仆的 stableId 联系信息与状态。
 * 每 100 tick 由 MaidScanTicker 扫描刷新，死亡时由 MaidDeathHandler 标记 DEAD。
 *
 * <p>获取实例（服务端）：
 * {@code MaidContactBook.get(server)}
 */
public class MaidContactBook extends SavedData {
    private static final String DATA_NAME = "huhutalk_maid_contacts";

    private final Map<String, MaidContactEntry> entries = new HashMap<>();

    /** 从服务器获取全局联系人簿（不存在则新建并注册到主世界数据存储） */
    @NotNull
    public static MaidContactBook get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MaidContactBook::new, MaidContactBook::load),
                DATA_NAME
        );
    }

    public Map<String, MaidContactEntry> getAllEntries() {
        return entries;
    }

    /**
     * 标记女仆在线。若从 OFFLINE/DEAD 恢复到 ONLINE，返回 true（供复活检测使用）。
     */
    public boolean markOnline(String stableId, String name, String dimension,
                              long gameTime, String ownerUuid) {
        MaidContactEntry entry = entries.get(stableId);
        if (entry == null) {
            entries.put(stableId, new MaidContactEntry(
                    stableId, name, MaidStatus.ONLINE, 0, 0, dimension, gameTime, ownerUuid
            ));
            setDirty();
            return true;
        }
        boolean resurrected = (entry.getStatus() != MaidStatus.ONLINE);
        if (resurrected) {
            entry.setLastSeenDeathCount(entry.getDeathCount());
        }
        entry.setName(name);
        entry.setDimension(dimension);
        entry.setLastSeenTime(gameTime);
        entry.setOwnerUuid(ownerUuid);
        entry.setStatus(MaidStatus.ONLINE);
        setDirty();
        return resurrected;
    }

    public void markOffline(String stableId, long gameTime) {
        MaidContactEntry entry = entries.get(stableId);
        if (entry != null && entry.getStatus() == MaidStatus.ONLINE) {
            entry.setStatus(MaidStatus.OFFLINE);
            entry.setLastSeenTime(gameTime);
            setDirty();
        }
    }

    public void markDead(String stableId, String name, String dimension,
                         long gameTime, String ownerUuid) {
        MaidContactEntry entry = entries.get(stableId);
        if (entry == null) {
            entry = new MaidContactEntry(stableId, name, MaidStatus.DEAD,
                    1, 0, dimension, gameTime, ownerUuid);
            entries.put(stableId, entry);
            setDirty();
            return;
        }
        entry.setName(name);
        entry.setDimension(dimension);
        entry.setLastSeenTime(gameTime);
        entry.setOwnerUuid(ownerUuid);
        if (entry.getStatus() != MaidStatus.DEAD) {
            entry.setStatus(MaidStatus.DEAD);
            entry.setDeathCount(entry.getDeathCount() + 1);
        }
        setDirty();
    }

    public MaidContactEntry getEntry(String stableId) {
        return entries.get(stableId);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (MaidContactEntry entry : entries.values()) {
            root.put(entry.getStableId(), entry.toNbt());
        }
        tag.put("contacts", root);
        return tag;
    }

    public static MaidContactBook load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        MaidContactBook book = new MaidContactBook();
        if (tag.contains("contacts", Tag.TAG_COMPOUND)) {
            CompoundTag contactsTag = tag.getCompound("contacts");
            for (String key : contactsTag.getAllKeys()) {
                MaidContactEntry entry = MaidContactEntry.fromNbt(contactsTag.getCompound(key));
                book.entries.put(key, entry);
            }
        }
        return book;
    }
}