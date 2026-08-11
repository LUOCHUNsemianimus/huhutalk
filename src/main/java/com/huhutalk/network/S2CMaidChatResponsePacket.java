package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record S2CMaidChatResponsePacket(UUID maidUuid, String responseText) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidChatResponsePacket> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidChatResponsePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
            S2CMaidChatResponsePacket::maidUuid,
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::responseText,
            S2CMaidChatResponsePacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}