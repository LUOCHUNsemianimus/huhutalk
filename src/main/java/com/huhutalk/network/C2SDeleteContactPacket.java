package com.huhutalk.network;

import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import net.minecraft.network.chat.Component;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：删除某女仆联系人（从联系人簿移除）。
 * <p>危险操作，仅建议用于已彻底无法回归的女仆。只允许删除属于该玩家的联系人。
 * 客户端在发出后本地清除该会话历史。若女仆日后再次上线，会被重新录入联系人簿。
 */
public record C2SDeleteContactPacket(String stableId) implements CustomPacketPayload {
    public static final Type<C2SDeleteContactPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "delete_contact"));

    public static final StreamCodec<FriendlyByteBuf, C2SDeleteContactPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SDeleteContactPacket::stableId,
                    C2SDeleteContactPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SDeleteContactPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            MaidContactBook book = MaidContactBook.get(serverPlayer.getServer());
            MaidContactEntry entry = book.getEntry(packet.stableId());
            // 只允许删除自己名下的联系人
            if (entry != null && entry.getOwnerUuid().equals(serverPlayer.getUUID().toString())) {
                book.remove(packet.stableId());
                serverPlayer.sendSystemMessage(
                        Component.translatable("message.huhutalk.contact.deleted"));
            } else {
                serverPlayer.sendSystemMessage(
                        Component.translatable("message.huhutalk.contact.not_found"));
            }
        });
    }
}
