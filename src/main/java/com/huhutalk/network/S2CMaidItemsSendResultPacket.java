package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：一条"带物品消息"(P→M)的发送结果。
 * ok=true：文字+物品整条已发给女仆（客户端补文字气泡、清空物品队列）。
 * ok=false：整条未发送（女仆放不下/不在线等）；客户端保留物品队列、还原文字，并按 message 提示。
 */
public record S2CMaidItemsSendResultPacket(String stableId, boolean ok, String text, String message)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidItemsSendResultPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_items_send_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidItemsSendResultPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemsSendResultPacket::stableId,
                    ByteBufCodecs.BOOL, S2CMaidItemsSendResultPacket::ok,
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemsSendResultPacket::text,
                    ByteBufCodecs.STRING_UTF8, S2CMaidItemsSendResultPacket::message,
                    S2CMaidItemsSendResultPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
