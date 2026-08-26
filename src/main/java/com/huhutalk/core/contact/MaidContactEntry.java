package com.huhutalk.core.contact;

import com.huhutalk.common.MaidStatus;
import com.huhutalk.init.MaidPhoneTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 单条女仆联系人数据。
 * <p>
 * 主键是 stableId（HuhuTalkStableId），实体 UUID 仅作为运行时暂存，不参与持久化。
 * ownerUuid 记录女仆主人，用于列表过滤。旧存档中无 ownerUuid 字段时读取为空字符串。
 */
public class MaidContactEntry {
    private final String stableId;
    private String name;
    private MaidStatus status;
    private int deathCount;
    private int lastSeenDeathCount;
    private String dimension;
    private long lastSeenTime;
    private String ownerUuid;
    /** 女仆最后已知区块坐标（用于离线再水化/覆盖判断） */
    private ChunkPos lastChunkPos;
    /** 女仆最后已知手机档位（用于离线再水化） */
    private MaidPhoneTier lastPhoneTier;

    public MaidContactEntry(String stableId, String name, MaidStatus status,
                            int deathCount, int lastSeenDeathCount,
                            String dimension, long lastSeenTime, String ownerUuid) {
        this.stableId = stableId;
        this.name = name;
        this.status = status;
        this.deathCount = deathCount;
        this.lastSeenDeathCount = lastSeenDeathCount;
        this.dimension = dimension;
        this.lastSeenTime = lastSeenTime;
        this.ownerUuid = ownerUuid;
    }

    public String getStableId() {
        return stableId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MaidStatus getStatus() {
        return status;
    }

    public void setStatus(MaidStatus status) {
        this.status = status;
    }

    public int getDeathCount() {
        return deathCount;
    }

    public void setDeathCount(int deathCount) {
        this.deathCount = deathCount;
    }

    public int getLastSeenDeathCount() {
        return lastSeenDeathCount;
    }

    public void setLastSeenDeathCount(int lastSeenDeathCount) {
        this.lastSeenDeathCount = lastSeenDeathCount;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public long getLastSeenTime() {
        return lastSeenTime;
    }

    public void setLastSeenTime(long lastSeenTime) {
        this.lastSeenTime = lastSeenTime;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(String ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public ChunkPos getLastChunkPos() {
        return lastChunkPos;
    }

    public void setLastChunkPos(ChunkPos lastChunkPos) {
        this.lastChunkPos = lastChunkPos;
    }

    public MaidPhoneTier getLastPhoneTier() {
        return lastPhoneTier;
    }

    public void setLastPhoneTier(MaidPhoneTier lastPhoneTier) {
        this.lastPhoneTier = lastPhoneTier;
    }

    /** 把 dimension 字符串解析为维度 ResourceKey（解析失败兜底主世界） */
    public ResourceKey<Level> getDimensionKey() {
        if (dimension == null || dimension.isEmpty()) {
            return Level.OVERWORLD;
        }
        try {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
        } catch (Exception e) {
            return Level.OVERWORLD;
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        // 字符串字段统一 null 安全，防止第三方以 null 入簿后在存档保存时 putString(null) 崩服
        tag.putString("stableId", str(stableId));
        tag.putString("name", str(name));
        tag.putString("status", status == null ? MaidStatus.OFFLINE.name() : status.name());   // 用枚举名称，避免序号变动导致错位
        tag.putInt("deathCount", deathCount);
        tag.putInt("lastSeenDeathCount", lastSeenDeathCount);
        tag.putString("dimension", str(dimension));
        tag.putLong("lastSeenTime", lastSeenTime);
        tag.putString("ownerUuid", str(ownerUuid));
        if (lastChunkPos != null) {
            tag.putInt("lastChunkX", lastChunkPos.x);
            tag.putInt("lastChunkZ", lastChunkPos.z);
        }
        if (lastPhoneTier != null) {
            tag.putString("lastPhoneTier", lastPhoneTier.name());
        }
        return tag;
    }

    /** null → 空串，防御 NBT 写入 */
    private static String str(String s) {
        return s == null ? "" : s;
    }

    public static MaidContactEntry fromNbt(CompoundTag tag) {
        String stableId = tag.getString("stableId");
        String name = tag.getString("name");
        MaidStatus status;
        try {
            status = MaidStatus.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            status = MaidStatus.OFFLINE;   // 旧数据或无状态兜底
        }
        int deathCount = tag.getInt("deathCount");
        int lastSeenDeathCount = tag.getInt("lastSeenDeathCount");
        String dimension = tag.getString("dimension");
        long lastSeenTime = tag.getLong("lastSeenTime");
        // 旧存档没有该字段时返回空字符串
        String ownerUuid = tag.getString("ownerUuid");
        MaidContactEntry entry = new MaidContactEntry(stableId, name, status, deathCount,
                lastSeenDeathCount, dimension, lastSeenTime, ownerUuid);
        if (tag.contains("lastChunkX") && tag.contains("lastChunkZ")) {
            entry.lastChunkPos = new ChunkPos(tag.getInt("lastChunkX"), tag.getInt("lastChunkZ"));
        }
        if (tag.contains("lastPhoneTier")) {
            try {
                entry.lastPhoneTier = MaidPhoneTier.valueOf(tag.getString("lastPhoneTier"));
            } catch (IllegalArgumentException ignored) {
                entry.lastPhoneTier = null;
            }
        }
        return entry;
    }
}