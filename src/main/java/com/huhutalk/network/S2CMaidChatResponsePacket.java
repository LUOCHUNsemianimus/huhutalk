package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record S2CMaidChatResponsePacket(String stableId, String responseText, String emote) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidChatResponsePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidChatResponsePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::stableId,
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::responseText,
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::emote,
            S2CMaidChatResponsePacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}