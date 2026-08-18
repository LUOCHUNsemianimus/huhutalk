package com.huhutalk.core.load;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * WiFi 方块注册表（SavedData，全局单例）。
 * <p>
 * 记录所有已放置 WiFi 方块的（维度 → 区块坐标）。用于判断"青春版手机"女仆是否
 * 处于某个 WiFi 的覆盖范围内，从而决定是否给女仆所在区块下发票据。
 * <p>
 * 与联系人簿/离线队列同存于主世界维度数据存储。
 */
public class WifiBlockRegistry extends SavedData {
    private static final String DATA_NAME = "huhutalk_wifi_blocks";

    private final Map<ResourceKey<Level>, Set<ChunkPos>> positions = new HashMap<>();

    @NotNull
    public static WifiBlockRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WifiBlockRegistry::new, WifiBlockRegistry::load),
                DATA_NAME
        );
    }

    /** 放置 WiFi 方块时登记 */
    public void add(ResourceKey<Level> dimension, ChunkPos pos) {
        positions.computeIfAbsent(dimension, k -> new HashSet<>()).add(pos);
        setDirty();
    }

    /** 移除 WiFi 方块时注销 */
    public void remove(ResourceKey<Level> dimension, ChunkPos pos) {
        Set<ChunkPos> set = positions.get(dimension);
        if (set != null) {
            set.remove(pos);
            if (set.isEmpty()) {
                positions.remove(dimension);
            }
            setDirty();
        }
    }

    /**
     * 判断某女仆区块是否被任一 WiFi 覆盖（切比雪夫距离 ≤ radius）。
     *
     * @param dimension 女仆所在维度
     * @param maidChunk 女仆所在区块
     * @param radius    覆盖半径（区块数）
     */
    public boolean isCovered(ResourceKey<Level> dimension, ChunkPos maidChunk, int radius) {
        Set<ChunkPos> set = positions.get(dimension);
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (ChunkPos wifi : set) {
            if (Math.max(Math.abs(wifi.x - maidChunk.x), Math.abs(wifi.z - maidChunk.z)) <= radius) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> e : positions.entrySet()) {
            ListTag list = new ListTag();
            for (ChunkPos p : e.getValue()) {
                CompoundTag c = new CompoundTag();
                c.putInt("x", p.x);
                c.putInt("z", p.z);
                list.add(c);
            }
            root.put(e.getKey().location().toString(), list);
        }
        tag.put("wifi", root);
        return tag;
    }

    public static WifiBlockRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        WifiBlockRegistry registry = new WifiBlockRegistry();
        if (tag.contains("wifi", Tag.TAG_COMPOUND)) {
            CompoundTag root = tag.getCompound("wifi");
            for (String dim : root.getAllKeys()) {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
                ListTag list = root.getList(dim, Tag.TAG_COMPOUND);
                Set<ChunkPos> set = new HashSet<>();
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag c = list.getCompound(i);
                    set.add(new ChunkPos(c.getInt("x"), c.getInt("z")));
                }
                registry.positions.put(key, set);
            }
        }
        return registry;
    }
}
