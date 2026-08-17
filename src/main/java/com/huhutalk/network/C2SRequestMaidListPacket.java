package com.huhutalk.network;

import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public record C2SRequestMaidListPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SRequestMaidListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_maid_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestMaidListPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {}, buf -> new C2SRequestMaidListPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                MaidContactBook book = MaidContactBook.get(serverPlayer.getServer());
                List<S2CMaidListPacket.MaidDisplayInfo> displayInfos = new ArrayList<>();

                // 从全局联系人簿中取出属于该玩家的所有条目（包括离线/死亡）
                for (MaidContactEntry entry : book.getAllEntries().values()) {
                    if (entry.getOwnerUuid().equals(serverPlayer.getUUID().toString())) {
                        displayInfos.add(new S2CMaidListPacket.MaidDisplayInfo(
                                entry.getStableId(),
                                entry.getName(),
                                entry.getStatus(),
                                entry.getDeathCount(),
                                entry.getDimension()
                        ));
                    }
                }

                context.reply(new S2CMaidListPacket(displayInfos));
            }
        });
    }
}