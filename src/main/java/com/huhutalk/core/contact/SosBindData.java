package com.huhutalk.core.contact;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SOS 紧急呼叫绑定（SavedData，存主世界维度）。
 * <p>每个玩家最多绑定 {@link #MAX_BOUND} 只女仆（stableId）到 SOS 槽位。
 * 点 SOS 时<b>只</b>把已绑定的这些女仆召来并围绕玩家四周保护，
 * 避免误召大量收菜/养殖等非战斗女仆（灾难性）。
 */
public class SosBindData extends SavedData {
    private static final String DATA_NAME = "huhutalk_sos_bindings";

    /** 单个玩家可绑定的 SOS 女仆上限 */
    public static final int MAX_BOUND = 4;

    /** playerUuid -> 有序的 stableId 列表（最多 MAX_BOUND 只） */
    private final Map<String, List<String>> bindings = new HashMap<>();

    /** 从服务器获取全局 SOS 绑定（不存在则新建并注册到主世界数据存储） */
    @NotNull
    public static SosBindData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SosBindData::new, SosBindData::load),
                DATA_NAME
        );
    }

    /** 某玩家已绑定的 stableId 列表（不可变副本） */
    public List<String> getBound(String playerUuid) {
        List<String> list = bindings.get(playerUuid);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 该女仆是否已被此玩家绑定到 SOS */
    public boolean isBound(String playerUuid, String stableId) {
        List<String> list = bindings.get(playerUuid);
        return list != null && list.contains(stableId);
    }

    /**
     * 切换绑定状态。满员时绑定失败返回 false（不解绑）。
     *
     * @return 切换后的状态（true=已绑定）
     */
    public boolean toggle(String playerUuid, String stableId) {
        List<String> list = bindings.computeIfAbsent(playerUuid, k -> new ArrayList<>());
        if (list.contains(stableId)) {
            list.remove(stableId);
            setDirty();
            return false;
        }
        if (list.size() >= MAX_BOUND) {
            return false; // 已满，拒绝新增
        }
        list.add(stableId);
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, List<String>> e : bindings.entrySet()) {
            ListTag list = new ListTag();
            for (String id : e.getValue()) {
                list.add(StringTag.valueOf(id));
            }
            root.put(e.getKey(), list);
        }
        tag.put("bindings", root);
        return tag;
    }

    public static SosBindData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        SosBindData data = new SosBindData();
        if (tag.contains("bindings", Tag.TAG_COMPOUND)) {
            CompoundTag root = tag.getCompound("bindings");
            for (String playerUuid : root.getAllKeys()) {
                ListTag list = root.getList(playerUuid, Tag.TAG_STRING);
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    ids.add(list.getString(i));
                }
                data.bindings.put(playerUuid, ids);
            }
        }
        return data;
    }
}
