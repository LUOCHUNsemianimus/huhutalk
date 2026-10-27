package com.huhutalk.network;

import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record S2CMaidChatErrorPacket(String stableId, String error) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidChatErrorPacket> TYPE =
            new CustomPacketPayload.Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_chat_error"));
    public static final StreamCodec<FriendlyByteBuf, S2CMaidChatErrorPacket> STREAM_CODEC = StreamCodec.composite(
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