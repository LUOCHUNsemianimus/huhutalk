package com.huhutalk.network;

import com.huhutalk.core.transfer.MaidTransferService;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.huhutalk.network.transport.PacketDistributor;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：玩家点击某个物品气泡，申请领取该批文件（M→P 领取）。
 * 服务端领取后回推 {@link S2CMaidItemClaimResultPacket} 告知剩余待领取数。
 */
public record C2SClaimTransferPacket(String stableId, String transferId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SClaimTransferPacket> TYPE =
            new CustomPacketPayload.Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "claim_transfer"));

    public static final StreamCodec<FriendlyByteBuf, C2SClaimTransferPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, C2SClaimTransferPacket::stableId,
                    ByteBufCodecs.STRING_UTF8, C2SClaimTransferPacket::transferId,
                    C2SClaimTransferPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SClaimTransferPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
                return;
            }
            MaidTransferService.ClaimResult result =
                    MaidTransferService.claim(serverPlayer, packet.stableId(), packet.transferId());
            // 把最新待领取数回推给该玩家，供气泡刷新（0 = 已接收/已清空）
            PacketDistributor.sendToPlayer(serverPlayer,
                    new S2CMaidItemClaimResultPacket(packet.stableId(), packet.transferId(), result.remaining));
        });
    }
}
