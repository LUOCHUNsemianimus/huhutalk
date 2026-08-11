package com.huhutalk.network;

import com.huhutalk.core.chat.MaidChatHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record C2SMaidChatPacket(UUID maidUuid, String message) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SMaidChatPacket> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SMaidChatPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
            C2SMaidChatPacket::maidUuid,
            ByteBufCodecs.STRING_UTF8,
            C2SMaidChatPacket::message,
            C2SMaidChatPacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SMaidChatPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            MaidChatHandler.handleChat(player, packet.maidUuid, packet.message);
        });
    }
}