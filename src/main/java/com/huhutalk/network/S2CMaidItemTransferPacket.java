package com.huhutalk.network;

import com.huhutalk.phone.ItemTransfer;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：女仆向玩家推送一批"文件"（M→P），客户端据此生成一个物品气泡。
 * <p>
 * 真正的物品存在服务端收件箱，这里只传渲染所需：transferId + 预览物品(最多 3 个，超出只计总数) + 待领取数。
 * <p>
 * {@code used=true} 表示这是<b>特殊发送</b>：物品已被女仆直接用在主人身上（远程救援），
 * 没有可领取的实体，气泡只作为「发送历史」展示 {@code effectText}，点击无反应。
 */
public record S2CMaidItemTransferPacket(String stableId, String transferId,
                                        List<ItemTransfer.Preview> previews,
                                        int total, int pending,
                                        boolean used, String effectText) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidItemTransferPacket> TYPE =
            new CustomPacketPayload.Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_item_transfer"));

    private static final StreamCodec<FriendlyByteBuf, ItemTransfer.Preview> PREVIEW_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, p -> p.itemId,
                    ByteBufCodecs.INT, p -> p.count,
                    // 完整组件（SNBT）：没有它，客户端只能按 ID 重建物品，药水会全部退化成"水瓶"
                    ByteBufCodecs.STRING_UTF8, p -> p.stackNbt,
                    ItemTransfer.Preview::new
            );

    private static final StreamCodec<FriendlyByteBuf, List<ItemTransfer.Preview>> PREVIEW_LIST_CODEC =
            ByteBufCodecs.collection(count -> new ArrayList<ItemTransfer.Preview>(count), PREVIEW_CODEC);

    /**
     * 手写编解码而不是 {@code StreamCodec.composite}：composite 的泛型重载最多支持 6 对字段，
     * 本包有 7 个（stableId / transferId / previews / total / pending / used / effectText）。
     */
    public static final StreamCodec<FriendlyByteBuf, S2CMaidItemTransferPacket> STREAM_CODEC =
            StreamCodec.<FriendlyByteBuf, S2CMaidItemTransferPacket>of(
                    (FriendlyByteBuf buf, S2CMaidItemTransferPacket packet) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.stableId());
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.transferId());
                        PREVIEW_LIST_CODEC.encode(buf, packet.previews());
                        ByteBufCodecs.INT.encode(buf, packet.total());
                        ByteBufCodecs.INT.encode(buf, packet.pending());
                        ByteBufCodecs.BOOL.encode(buf, packet.used());
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.effectText());
                    },
                    (FriendlyByteBuf buf) -> new S2CMaidItemTransferPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            PREVIEW_LIST_CODEC.decode(buf),
                            ByteBufCodecs.INT.decode(buf),
                            ByteBufCodecs.INT.decode(buf),
                            ByteBufCodecs.BOOL.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf))
            );

    /** 还原成客户端展示负载。 */
    public ItemTransfer.Payload toPayload() {
        List<ItemTransfer.Preview> copy = previews == null ? new ArrayList<>() : new ArrayList<>(previews);
        return new ItemTransfer.Payload(transferId, copy, total, pending, used, effectText);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
