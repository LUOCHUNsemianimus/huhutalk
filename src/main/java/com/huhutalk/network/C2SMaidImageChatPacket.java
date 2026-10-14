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
 * 这样在专用服务器（客户端与服务端不同机器）下也能正常工作。
 *
 * <h2>⚠ 字段数已经卡在编解码器的上限（6）</h2>
 * {@code StreamCodec.composite} 在 1.21.1 只有 1~6 个分量的重载，
 * 本 record 现在<b>正好 6 个字段</b>。以后还想加字段就必须手写
 * {@code StreamCodec.of(...)}（例见 {@code C2SVisionSiteEditPacket}）。
 * 这也是"主人自己的样貌"走 {@code C2SVisionSelectionPacket}、而不是每张图带一遍的原因。
 *
 * @param stableId       目标女仆
 * @param userText       随图发送的文字（可为空）
 * @param imageData      PNG 字节
 * @param bubbleMute     女仆悄悄话（抑制头顶气泡）
 * @param chatBannerMute 沉浸式聊天（回复不进公屏）
 * @param maidAppearance <b>「女仆的样貌」</b>（第 6 个字段）：发图窗口所属女仆的外貌描述，
 *                       空串 = 未填写。按窗口取用 ⇒ 不同女仆的窗口发同一张图，带的各是各自的样貌。
 */
public record C2SMaidImageChatPacket(String stableId, String userText, byte[] imageData,
                                     boolean bubbleMute, boolean chatBannerMute,
                                     String maidAppearance) implements CustomPacketPayload {
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
                    ByteBufCodecs.STRING_UTF8,
                    C2SMaidImageChatPacket::maidAppearance,
                    C2SMaidImageChatPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(C2SMaidImageChatPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                MaidChatHandler.handleImageChat(serverPlayer, packet.stableId(), packet.userText(), packet.imageData(),
                        packet.bubbleMute(), packet.chatBannerMute(), packet.maidAppearance());
            }
        });
    }
}
