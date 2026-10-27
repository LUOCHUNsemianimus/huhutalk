package com.huhutalk.core.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「传文件」- 女仆发件收件箱（SavedData，全局单例，持久化）。
 * <p>
 * 女仆发给玩家的物品先进入这里（M→P），由女仆身上的物品 / 隙间(wireless_io)绑箱取出，
 * 在玩家真正"领取"之前一直妥善存放，避免因女仆下线/传送/改背包而丢失。
 * 存储结构：外层 key = 女仆会话 stableId；
 * 内层 key = transferId（一次发给玩家的整批文件，UUID 字符串）；
 * value = 该批内仍待领取的 {@link ItemStack} 列表（每个栈视为一个"文件"）。
 * 已领取的栈会从列表移除；领取完 / 被整批丢地后移除该批。
 */
public class MaidTransferData extends SavedData {
    private static final String DATA_NAME = "huhutalk_maid_transfers";

    /** 单批文件数量上限：女仆侧无硬限制，但为防包/存档膨胀设一个较大安全上限 */
    public static final int MAX_FILES_PER_BATCH = 256;
    /** 单次会话(女仆)积压批数上限，防止异常增长撑爆存档 */
    private static final int MAX_BATCH_PER_MAID = 32;

    /** 会话 stableId -> (transferId -> 该批待领取物品列表) */
    private final Map<String, Map<String, List<ItemStack>>> inbox = new LinkedHashMap<>();

    @NotNull
    public static MaidTransferData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MaidTransferData::load, MaidTransferData::new, DATA_NAME
        );
    }

    // ==================== 查询 ====================

    /** 某会话的全部待领取文件数量（跨所有批）。无返回 0 */
    public int pendingCount(String stableId) {
        Map<String, List<ItemStack>> batches = inbox.get(stableId);
        if (batches == null) {
            return 0;
        }
        int total = 0;
        for (List<ItemStack> files : batches.values()) {
            total += files.size();
        }
        return total;
    }

    /** 某会话某批当前待领取文件列表（不存在返回空 list，不修改存储） */
    public List<ItemStack> getBatch(String stableId, String transferId) {
        Map<String, List<ItemStack>> batches = inbox.get(stableId);
        if (batches == null) {
            return new ArrayList<>();
        }
        List<ItemStack> files = batches.get(transferId);
        return files == null ? new ArrayList<>() : files;
    }

    // ==================== 写入 ====================

    /**
     * 把一批物品放入收件箱（M→P）。返回该批的 transferId。
     * 会做数量上限保护；stableId/空批次做防御。
     */
    public String addBatch(String stableId, List<ItemStack> files) {
        if (stableId == null || stableId.isBlank() || files == null || files.isEmpty()) {
            return null;
        }
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack s : files) {
            if (s != null && !s.isEmpty()) {
                copy.add(s.copy());
            }
        }
        if (copy.isEmpty()) {
            return null;
        }
        if (copy.size() > MAX_FILES_PER_BATCH) {
            copy = new ArrayList<>(copy.subList(0, MAX_FILES_PER_BATCH));
        }
        Map<String, List<ItemStack>> batches = inbox.computeIfAbsent(stableId, k -> new LinkedHashMap<>());
        // 若该会话已积压过多批，丢弃最早的一批（避免无限增长）
        while (batches.size() >= MAX_BATCH_PER_MAID) {
            String oldest = batches.keySet().iterator().next();
            batches.remove(oldest);
        }
        String transferId = UUID.randomUUID().toString();
        batches.put(transferId, copy);
        setDirty();
        return transferId;
    }

    /** 完全移除某批（领取完或整批丢地后调用）。返回是否真的存在并移除 */
    public boolean removeBatch(String stableId, String transferId) {
        Map<String, List<ItemStack>> batches = inbox.get(stableId);
        if (batches == null) {
            return false;
        }
        boolean removed = batches.remove(transferId) != null;
        if (removed) {
            if (batches.isEmpty()) {
                inbox.remove(stableId);
            }
            setDirty();
        }
        return removed;
    }

    /** 把某批中仍待领取的列表替换为给定的剩余列表（领取部分后调用）。 */
    public void setBatchRemaining(String stableId, String transferId, List<ItemStack> remaining) {
        Map<String, List<ItemStack>> batches = inbox.get(stableId);
        if (batches == null) {
            return;
        }
        if (remaining == null || remaining.isEmpty()) {
            batches.remove(transferId);
            if (batches.isEmpty()) {
                inbox.remove(stableId);
            }
        } else {
            batches.put(transferId, remaining);
        }
        setDirty();
    }

    /** 删除某女仆会话的整份收件箱（玩家删除该会话聊天记录时调用）。返回被移除的全部文件栈列表。 */
    public List<ItemStack> drainConversation(String stableId) {
        Map<String, List<ItemStack>> batches = inbox.remove(stableId);
        if (batches == null) {
            return new ArrayList<>();
        }
        List<ItemStack> all = new ArrayList<>();
        for (List<ItemStack> files : batches.values()) {
            all.addAll(files);
        }
        setDirty();
        return all;
    }

    // ==================== 持久化 ====================

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, Map<String, List<ItemStack>>> chat : inbox.entrySet()) {
            CompoundTag chatTag = new CompoundTag();
            for (Map.Entry<String, List<ItemStack>> batch : chat.getValue().entrySet()) {
                ListTag fileList = new ListTag();
                for (ItemStack stack : batch.getValue()) {
                    fileList.add(stack.save(new CompoundTag()));
                }
                chatTag.put(batch.getKey(), fileList);
            }
            root.put(chat.getKey(), chatTag);
        }
        tag.put("inbox", root);
        return tag;
    }

    public static MaidTransferData load(CompoundTag tag) {
        MaidTransferData data = new MaidTransferData();
        if (tag.contains("inbox", Tag.TAG_COMPOUND)) {
            CompoundTag root = tag.getCompound("inbox");
            for (String stableId : root.getAllKeys()) {
                CompoundTag chatTag = root.getCompound(stableId);
                Map<String, List<ItemStack>> batches = new LinkedHashMap<>();
                for (String transferId : chatTag.getAllKeys()) {
                    ListTag fileList = chatTag.getList(transferId, Tag.TAG_COMPOUND);
                    List<ItemStack> files = new ArrayList<>();
                    for (int i = 0; i < fileList.size(); i++) {
                        CompoundTag stackTag = fileList.getCompound(i);
                        ItemStack stack = ItemStack.of(stackTag);
                        if (!stack.isEmpty()) {
                            files.add(stack);
                        }
                    }
                    if (!files.isEmpty()) {
                        batches.put(transferId, files);
                    }
                }
                if (!batches.isEmpty()) {
                    data.inbox.put(stableId, batches);
                }
            }
        }
        return data;
    }
}
