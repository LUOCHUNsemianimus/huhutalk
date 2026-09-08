package com.huhutalk.network;

import com.huhutalk.core.transfer.MaidTransferService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：玩家在聊天里发送"文字 + 队列里的物品"（P→M，随信附件）。
 * 服务端只在其整批能放进女仆可用背包时把物品转过去并让女仆知晓，否则整条不发送并回失败 ack。
 */
public record C2SMaidChatWithItemsPacket(String stableId, String text, List<Integer> slots,
                                         boolean bubbleMute, boolean immersive) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SMaidChatWithItemsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat_with_items"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Integer>> INT_LIST_CODEC =
            ByteBufCodecs.collection(count -> new ArrayList<Integer>(count), ByteBufCodecs.INT);

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SMaidChatWithItemsPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, C2SMaidChatWithItemsPacket::stableId,
                    ByteBufCodecs.STRING_UTF8, C2SMaidChatWithItemsPacket::text,
                    INT_LIST_CODEC, C2SMaidChatWithItemsPacket::slots,
                    ByteBufCodecs.BOOL, C2SMaidChatWithItemsPacket::bubbleMute,
                    ByteBufCodecs.BOOL, C2SMaidChatWithItemsPacket::immersive,
                    C2SMaidChatWithItemsPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SMaidChatWithItemsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            String problem = MaidTransferService.trySendChatWithItems(serverPlayer, packet.stableId(),
                    packet.text(), packet.slots(), packet.bubbleMute(), packet.immersive());
            boolean ok = problem == null;
            PacketDistributor.sendToPlayer(serverPlayer,
                    new S2CMaidItemsSendResultPacket(packet.stableId(), ok, packet.text(), ok ? "" : problem));
        });
    }
}
