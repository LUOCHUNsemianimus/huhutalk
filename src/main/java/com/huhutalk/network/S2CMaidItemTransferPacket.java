package com.huhutalk.network;

import com.huhutalk.phone.ItemTransfer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：女仆向玩家推送一批"文件"（M→P），客户端据此生成一个可领取的物品气泡。
 * <p>
 * 真正的物品存在服务端收件箱，这里只传渲染所需：transferId + 预览物品(最多 3 个，超出只计总数) + 待领取数。
 */
public record S2CMaidItemTransferPacket(String stableId, String transferId,
                                        List<ItemTransfer.Preview> previews,
                                        int total, int pending) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidItemTransferPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_item_transfer"));

    private static final StreamCodec<RegistryFriendlyByteBuf, ItemTransfer.Preview> PREVIEW_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, p -> p.itemId,
                    ByteBufCodecs.INT, p -> p.count,
                    (id, count) -> new ItemTransfer.Preview(id, count)
            );

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ItemTransfer.Preview>> PREVIEW_LIST_CODEC =
            ByteBufCodecs.collection(count -> new ArrayList<ItemTransfer.Preview>(count), PREVIEW_CODEC);

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidItemTransferPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemTransferPacket::stableId,
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemTransferPacket::transferId,
                    PREVIEW_LIST_CODEC, S2CMaidItemTransferPacket::previews,
                    ByteBufCodecs.INT, S2CMaidItemTransferPacket::total,
                    ByteBufCodecs.INT, S2CMaidItemTransferPacket::pending,
                    S2CMaidItemTransferPacket::new
            );

    /** 还原成客户端展示负载。 */
    public ItemTransfer.Payload toPayload() {
        List<ItemTransfer.Preview> copy = previews == null ? new ArrayList<>() : new ArrayList<>(previews);
        return new ItemTransfer.Payload(transferId, copy, total, pending);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
