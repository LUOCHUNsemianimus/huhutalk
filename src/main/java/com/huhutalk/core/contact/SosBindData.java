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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOS 紧急呼叫绑定（SavedData，存主世界维度）。
 * <p>每个玩家最多绑定 {@link #MAX_BOUND} 只女仆（stableId）到 SOS 槽位。
 * 点 SOS 时<b>只</b>把已绑定的这些女仆召来并围绕玩家四周保护，
 * 避免误召大量收菜/养殖等非战斗女仆（灾难性）。
 * <p>
 * 每个绑定可额外记录一个<b>工作模式</b>（taskUid，如近战/弓兵等），
 * 点 SOS 时按该模式切换，兼容新增攻击模式的 mod。默认近战 {@link #DEFAULT_TASK_UID}。
 */
public class SosBindData extends SavedData {
    private static final String DATA_NAME = "huhutalk_sos_bindings";

    /** 单个玩家可绑定的 SOS 女仆上限 */
    public static final int MAX_BOUND = 4;

    /** stableId / taskUid 的最大合法长度（真实 UUID 为 36，防止客户端写超长垃圾键进存档） */
    private static final int MAX_KEY_LEN = 128;

    /** 默认工作模式：近战（TLM attack 任务） */
    public static final String DEFAULT_TASK_UID = "touhou_little_maid:attack";

    /** playerUuid -> 有序的 stableId -> taskUid 映射（LinkedHashMap 保持绑定顺序） */
    private final Map<String, Map<String, String>> bindings = new HashMap<>();

    /** 从服务器获取全局 SOS 绑定（不存在则新建并注册到主世界数据存储） */
    @NotNull
    public static SosBindData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SosBindData::new, SosBindData::load),
                DATA_NAME
        );
    }

    /** 某玩家已绑定的 stableId 列表（按绑定顺序，不可变副本） */
    public List<String> getBound(String playerUuid) {
        Map<String, String> inner = bindings.get(playerUuid);
        return inner == null ? List.of() : List.copyOf(inner.keySet());
    }

    /** 该女仆是否已被此玩家绑定到 SOS */
    public boolean isBound(String playerUuid, String stableId) {
        Map<String, String> inner = bindings.get(playerUuid);
        return inner != null && inner.containsKey(stableId);
    }

    /** 读取某绑定女仆的工作模式 taskUid；未绑定或未设置时返回 {@link #DEFAULT_TASK_UID} */
    public String getTaskUid(String playerUuid, String stableId) {
        Map<String, String> inner = bindings.get(playerUuid);
        if (inner == null) {
            return DEFAULT_TASK_UID;
        }
        return inner.getOrDefault(stableId, DEFAULT_TASK_UID);
    }

    /** 把某玩家的绑定列表转为网络传输用的 {@code (stableId, taskUid)} 条目列表 */
    public List<com.huhutalk.network.S2CSosBindPacket.SosModeEntry> toEntries(String playerUuid) {
        List<com.huhutalk.network.S2CSosBindPacket.SosModeEntry> entries = new ArrayList<>();
        for (String stableId : getBound(playerUuid)) {
            entries.add(new com.huhutalk.network.S2CSosBindPacket.SosModeEntry(
                    stableId, getTaskUid(playerUuid, stableId)));
        }
        return entries;
    }

    /** 设置某绑定女仆的工作模式；未绑定时忽略。返回是否成功。 */
    public boolean setTaskUid(String playerUuid, String stableId, String taskUid) {
        Map<String, String> inner = bindings.get(playerUuid);
        if (inner == null || !inner.containsKey(stableId)) {
            return false;
        }
        if (taskUid == null || taskUid.isBlank() || taskUid.length() > MAX_KEY_LEN) {
            return false;
        }
        inner.put(stableId, taskUid);
        setDirty();
        return true;
    }

    /**
     * 切换绑定状态。满员时绑定失败返回 false（不解绑）。
     * 绑定成功时给新绑定一个默认工作模式。
     *
     * @return 切换后的状态（true=已绑定）
     */
    public boolean toggle(String playerUuid, String stableId) {
        if (stableId == null || stableId.isBlank() || stableId.length() > MAX_KEY_LEN) {
            return false; // 拒绝非法/超长键，防止垃圾字符串写入存档
        }
        Map<String, String> inner = bindings.computeIfAbsent(playerUuid, k -> new LinkedHashMap<>());
        if (inner.containsKey(stableId)) {
            inner.remove(stableId);
            if (inner.isEmpty()) {
                bindings.remove(playerUuid);
            }
            setDirty();
            return false;
        }
        if (inner.size() >= MAX_BOUND) {
            return false; // 已满，拒绝新增
        }
        inner.put(stableId, DEFAULT_TASK_UID);
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, Map<String, String>> e : bindings.entrySet()) {
            CompoundTag inner = new CompoundTag();
            for (Map.Entry<String, String> bind : e.getValue().entrySet()) {
                inner.putString(bind.getKey(), bind.getValue());
            }
            root.put(e.getKey(), inner);
        }
        tag.put("bindings", root);
        return tag;
    }

    public static SosBindData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        SosBindData data = new SosBindData();
        if (tag.contains("bindings", Tag.TAG_COMPOUND)) {
            CompoundTag root = tag.getCompound("bindings");
            for (String playerUuid : root.getAllKeys()) {
                // 兼容旧格式：值为 ListTag（纯 stableId 列表）→ 按默认工作模式转换
                Tag value = root.get(playerUuid);
                Map<String, String> inner = new LinkedHashMap<>();
                if (value instanceof ListTag oldList) {
                    for (int i = 0; i < oldList.size(); i++) {
                        inner.put(oldList.getString(i), DEFAULT_TASK_UID);
                    }
                } else if (value instanceof CompoundTag innerTag) {
                    for (String stableId : innerTag.getAllKeys()) {
                        inner.put(stableId, innerTag.getString(stableId));
                    }
                }
                if (!inner.isEmpty()) {
                    data.bindings.put(playerUuid, inner);
                }
            }
        }
        return data;
    }
}
