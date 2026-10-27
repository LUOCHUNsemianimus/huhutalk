package com.huhutalk.network;

import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端发给客户端的聊天提示信息（非错误）。
 * 用于离线消息入队确认、系统通知等。
 * 客户端收到后写入聊天历史，并在聊天页展示（黄色标记）。
 * <p>{@code queued=true} 表示这条提示是"消息已入离线队列、尚未送达"，
 * 客户端应把最后一条发送的消息标红"!"（送达后由回复清除）。
 */
public record S2CMaidChatInfoPacket(String stableId, String info, boolean queued) implements CustomPacketPayload {
    public static final Type<S2CMaidChatInfoPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_chat_info"));

    public static final StreamCodec<FriendlyByteBuf, S2CMaidChatInfoPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidChatInfoPacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidChatInfoPacket::info,
                    ByteBufCodecs.BOOL,
                    S2CMaidChatInfoPacket::queued,
                    S2CMaidChatInfoPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}