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
 * 图片不直接塞进包（32KB 上限装不下），而是由客户端截图后写入与集成服务端共享的
 * {@code gameDir/huhutalk/vision_tmp/<imageToken>.png}，这里只传一个短 token；
 * 服务端据此读图 → 识图 → 把描述注入聊天，再连同文字让女仆回复。
 */
public record C2SMaidImageChatPacket(String stableId, String userText, String imageToken,
                                     boolean bubbleMute, boolean chatBannerMute) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SMaidImageChatPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_image_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SMaidImageChatPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidImageChatPacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidImageChatPacket::userText,
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidImageChatPacket::imageToken,
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
                MaidChatHandler.handleImageChat(serverPlayer, packet.stableId(), packet.userText(), packet.imageToken(),
                        packet.bubbleMute(), packet.chatBannerMute());
            }
        });
    }
}
