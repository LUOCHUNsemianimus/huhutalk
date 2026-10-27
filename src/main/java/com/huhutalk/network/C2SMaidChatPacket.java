package com.huhutalk.network;

import com.huhutalk.core.chat.MaidChatHandler;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record C2SMaidChatPacket(String stableId, String message,
                                boolean bubbleMute, boolean chatBannerMute) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SMaidChatPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_chat"));

    public static final StreamCodec<FriendlyByteBuf, C2SMaidChatPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidChatPacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidChatPacket::message,
                    ByteBufCodecs.BOOL,
                    C2SMaidChatPacket::bubbleMute,
                    ByteBufCodecs.BOOL,
                    C2SMaidChatPacket::chatBannerMute,
                    C2SMaidChatPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(C2SMaidChatPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                MaidChatHandler.handleChat(serverPlayer, packet.stableId(), packet.message(),
                        packet.bubbleMute(), packet.chatBannerMute());
            }
        });
    }
}