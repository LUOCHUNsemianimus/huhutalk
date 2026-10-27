package com.huhutalk.network;

import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record S2CMaidChatResponsePacket(String stableId, String responseText, String emote) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidChatResponsePacket> TYPE =
            new CustomPacketPayload.Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_chat_response"));
    public static final StreamCodec<FriendlyByteBuf, S2CMaidChatResponsePacket> STREAM_CODEC = StreamCodec.composite(
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