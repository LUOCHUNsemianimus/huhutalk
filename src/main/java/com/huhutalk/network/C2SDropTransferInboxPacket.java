package com.huhutalk.network;

import com.huhutalk.core.transfer.MaidTransferService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：玩家删除某会话聊天记录时告知服务端，把该会话收件箱里尚未领取的
 * 全部"文件"一股脑丢到玩家脚下（不做禁止删除的拦截）。
 */
public record C2SDropTransferInboxPacket(String stableId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SDropTransferInboxPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "drop_transfer_inbox"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SDropTransferInboxPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, C2SDropTransferInboxPacket::stableId,
                    C2SDropTransferInboxPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SDropTransferInboxPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // 仅当该会话确属此玩家所有时才清空其收件箱并丢地
            if (MaidTransferService.findOwnedAliveMaid(serverPlayer.server, packet.stableId(), serverPlayer) == null) {
                return; // 找不到或非本人：不动作（防御，避免乱丢到别人脚下）
            }
            MaidTransferService.dropConversationAtFeet(serverPlayer, packet.stableId());
        });
    }
}
