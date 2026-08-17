package com.huhutalk.core.contact;

import com.huhutalk.common.MaidStatus;
import net.minecraft.nbt.CompoundTag;

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

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("stableId", stableId);
        tag.putString("name", name);
        tag.putString("status", status.name());   // 用枚举名称，避免序号变动导致错位
        tag.putInt("deathCount", deathCount);
        tag.putInt("lastSeenDeathCount", lastSeenDeathCount);
        tag.putString("dimension", dimension);
        tag.putLong("lastSeenTime", lastSeenTime);
        tag.putString("ownerUuid", ownerUuid);
        return tag;
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
        return new MaidContactEntry(stableId, name, status, deathCount,
                lastSeenDeathCount, dimension, lastSeenTime, ownerUuid);
    }
}