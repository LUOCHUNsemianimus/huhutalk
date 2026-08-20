package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：女仆当前状态文案（低频推送，如"种地中…""我睡着啦…"）。
 * 客户端写入 {@link com.huhutalk.client.MaidStatusCache}，供手机"状态小字"显示。
 */
public record S2CMaidStatusPacket(String stableId, String statusText) implements CustomPacketPayload {

    public static final Type<S2CMaidStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidStatusPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidStatusPacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidStatusPacket::statusText,
                    S2CMaidStatusPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
