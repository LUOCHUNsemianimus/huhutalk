package com.huhutalk.network;

import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：某批文件领取结果。pending = 领取后仍待领取的文件数（0 = 已全部领取/已整批丢地 → 气泡显示"已接收"）。
 */
public record S2CMaidItemClaimResultPacket(String stableId, String transferId, int pending)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidItemClaimResultPacket> TYPE =
            new CustomPacketPayload.Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_item_claim_result"));

    public static final StreamCodec<FriendlyByteBuf, S2CMaidItemClaimResultPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemClaimResultPacket::stableId,
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemClaimResultPacket::transferId,
                    ByteBufCodecs.INT, S2CMaidItemClaimResultPacket::pending,
                    S2CMaidItemClaimResultPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
