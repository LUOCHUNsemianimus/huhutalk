package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端发给客户端的聊天提示信息（非错误）。
 * 用于离线消息入队确认、系统通知等。
 * 客户端收到后写入聊天历史，并在聊天页展示（黄色标记）。
 */
public record S2CMaidChatInfoPacket(String stableId, String info) implements CustomPacketPayload {
    public static final Type<S2CMaidChatInfoPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidChatInfoPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidChatInfoPacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidChatInfoPacket::info,
                    S2CMaidChatInfoPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}