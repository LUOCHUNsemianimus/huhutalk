package com.huhutalk.phone;

import com.google.gson.annotations.SerializedName;

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

    /** 一个用于渲染的预览物品（仅 registryId + 数量，不带 NBT/组件，足够画图标与数量）。 */
    public static final class Preview {
        @SerializedName("id")
        public String itemId;
        @SerializedName("count")
        public int count;

        public Preview() {
        }

        public Preview(String itemId, int count) {
            this.itemId = itemId == null ? "" : itemId;
            this.count = Math.max(1, count);
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

        public Payload() {
        }

        public Payload(String transferId, List<Preview> previews, int total, int pending) {
            this.transferId = transferId == null ? "" : transferId;
            this.previews = previews == null ? new ArrayList<>() : previews;
            this.total = Math.max(0, total);
            this.pending = Math.max(0, pending);
        }

        /** 是否已全部领取（显示“已接收”）。 */
        public boolean claimed() {
            return pending <= 0;
        }

        /** 返回一个待领取数更新的副本（用于领取后刷新气泡）。 */
        public Payload withPending(int newPending) {
            return new Payload(transferId, previews, total, newPending);
        }
    }
}
