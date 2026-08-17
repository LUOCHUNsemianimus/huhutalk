package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record S2CMaidChatErrorPacket(String stableId, String error) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidChatErrorPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat_error"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidChatErrorPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatErrorPacket::stableId,
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatErrorPacket::error,
            S2CMaidChatErrorPacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}