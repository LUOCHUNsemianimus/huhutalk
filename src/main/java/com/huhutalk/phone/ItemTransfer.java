package com.huhutalk.phone;

import com.google.gson.annotations.SerializedName;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天里"物品气泡/文件"的展示负载（纯数据，可被 Gson 直接持久化）。
 * <p>
 * 设计说明：真正的物品实体一直保存在服务端收件箱（{@link com.huhutalk.core.transfer.MaidTransferData}），
 * 客户端这边只保存渲染所需的最小信息——前几个物品的 registryId+数量、本批总数、以及待领取数量。
 * 这样即使重进存档仍能画出气泡与“和其余 x 个物品 / x个文件待领取 / 已接收”，领取时再拿 transferId 找服务端。
 */
public final class ItemTransfer {
    private ItemTransfer() {
    }

    /** 一个用于渲染的预览物品。 */
    public static final class Preview {
        @SerializedName("id")
        public String itemId;
        @SerializedName("count")
        public int count;
        /**
         * 该物品的<b>完整组件</b>序列化（SNBT 字符串）。
         * <p>
         * 为什么必须有它：客户端的 {@code previewToStack} 原本只能按 registry id 重建
         * {@code new ItemStack(item)}，这会把 {@code POTION_CONTENTS} 之类的组件全部丢掉 ——
         * 于是<b>所有药水都退化成一模一样的"水瓶"图标</b>（模型与液色都取自组件）。
         * 带上组件后，药水/染色物品/附魔书等"图标取决于组件"的物品才能在气泡里画对。
         * <p>
         * 空串表示无法序列化（旧存档、或编码失败）——客户端退回"只按 ID + 数量"渲染。
         */
        @SerializedName("nbt")
        public String stackNbt = "";

        public Preview() {
        }

        /** 旧签名（无组件）：保留以兼容既有调用点与旧存档。 */
        public Preview(String itemId, int count) {
            this(itemId, count, "");
        }

        public Preview(String itemId, int count, String stackNbt) {
            this.itemId = itemId == null ? "" : itemId;
            this.count = Math.max(1, count);
            this.stackNbt = stackNbt == null ? "" : stackNbt;
        }

        /**
         * 从真实物品栈构造预览：带上完整组件，保证客户端画得出正确图标。
         * <p>服务端推气泡时一律走这里，不要再手工拼 id + count。
         */
        public static Preview of(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return new Preview("", 1);
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return new Preview(id.toString(), stack.getCount(), encodeStack(stack));
        }

        /**
         * 把物品栈编码成 SNBT 字符串。
         * <p>先 {@code copy()} 再 {@code setCount(1)}，只序列化物品本体与 NBT：
         * 数量由 {@link #count} 单独携带，预览不能被写成 1 个；真实栈也不会被改动。
         *
         * @return SNBT；编码失败返回空串（调用方自动退回只有 ID 的预览）
         */
        private static String encodeStack(ItemStack stack) {
            try {
                ItemStack single = stack.copy();
                single.setCount(1);
                return single.save(new CompoundTag()).toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** 从 SNBT 还原物品栈（数量为 1）。字符串为空或解析失败返回 {@link ItemStack#EMPTY}。 */
        public static ItemStack decodeStack(String stackNbt) {
            if (stackNbt == null || stackNbt.isBlank()) {
                return ItemStack.EMPTY;
            }
            try {
                CompoundTag tag = TagParser.parseTag(stackNbt);
                return ItemStack.of(tag);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        }
    }

    /** 一次 M→P 发送（一个物品气泡）。 */
    public static final class Payload {
        @SerializedName("tid")
        public String transferId;
        @SerializedName("previews")
        public List<Preview> previews = new ArrayList<>();
        /** 本批文件总数（含未渲染的） */
        @SerializedName("total")
        public int total;
        /** 当前仍待领取的文件数（=0 表示已领完/已清空 → 显示“已接收”） */
        @SerializedName("pending")
        public int pending;
        /**
         * 是否为「特殊发送」：这批物品<b>已被直接使用</b>（远程救援把药水喂给主人），
         * 因此没有可领取的实体、不进收件箱、点击也不该发领取包。
         * <p>旧存档没有该字段时 Gson 解析为 {@code false}，即普通可领取文件。
         */
        @SerializedName("used")
        public boolean used;
        /**
         * 使用效果文案（例如「已使用 · 恢复 4 ❤」）。仅 {@link #used} 为真时有意义。
         */
        @SerializedName("effect")
        public String effectText = "";

        public Payload() {
        }

        public Payload(String transferId, List<Preview> previews, int total, int pending) {
            this(transferId, previews, total, pending, false, "");
        }

        public Payload(String transferId, List<Preview> previews, int total, int pending,
                       boolean used, String effectText) {
            this.transferId = transferId == null ? "" : transferId;
            this.previews = previews == null ? new ArrayList<>() : previews;
            this.total = Math.max(0, total);
            this.pending = Math.max(0, pending);
            this.used = used;
            this.effectText = effectText == null ? "" : effectText;
        }

        /** 是否已全部领取（显示“已接收”）。 */
        public boolean claimed() {
            return pending <= 0;
        }

        /** 是否可以点击领取：普通文件且还有剩余时才可领；已使用的特殊发送永远不可领。 */
        public boolean claimable() {
            return !used && !claimed();
        }

        /** 返回一个待领取数更新的副本（用于领取后刷新气泡）。 */
        public Payload withPending(int newPending) {
            return new Payload(transferId, previews, total, newPending, used, effectText);
        }
    }
}
