package com.huhutalk.network;

import com.huhutalk.core.chat.MaidChatHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：带一张图片的聊天。
 * <p>
 * 图片以<b>字节内联</b>进包直接传输（发送前已在客户端降采样到帧长上限内），
 * 服务端收到后直接识图，<b>不再依赖客户端/服务端共享的临时目录</b>——
 * 这样在专用服务器（客户端与服务端不同进程/机器）下也能正常工作。
 */
public record C2SMaidImageChatPacket(String stableId, String userText, byte[] imageData,
                                     boolean bubbleMute, boolean chatBannerMute) implements CustomPacketPayload {
    /** 图片最大字节数：受网络包帧长约 2MB 限制，留出其余字段（字符串/布尔）的开销 */
    public static final int MAX_IMAGE_BYTES = 1_000_000;

    public static final CustomPacketPayload.Type<C2SMaidImageChatPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_image_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SMaidImageChatPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidImageChatPacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidImageChatPacket::userText,
                    ByteBufCodecs.byteArray(MAX_IMAGE_BYTES),
                    C2SMaidImageChatPacket::imageData,
                    ByteBufCodecs.BOOL,
                    C2SMaidImageChatPacket::bubbleMute,
                    ByteBufCodecs.BOOL,
                    C2SMaidImageChatPacket::chatBannerMute,
                    C2SMaidImageChatPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(C2SMaidImageChatPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                MaidChatHandler.handleImageChat(serverPlayer, packet.stableId(), packet.userText(), packet.imageData(),
                        packet.bubbleMute(), packet.chatBannerMute());
            }
        });
    }
}
