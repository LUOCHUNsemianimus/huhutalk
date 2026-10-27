package com.huhutalk.network;

import com.huhutalk.core.contact.SosBindData;
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
 * 客户端 → 服务端：设置某绑定女仆的 SOS 工作模式（如近战/弓兵等）。
 * <p>设置成功后回推该玩家最新绑定列表（含工作模式），供客户端刷新 UI。
 */
public record C2SSetSosModePacket(String stableId, String taskUid) implements CustomPacketPayload {
    public static final Type<C2SSetSosModePacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "set_sos_mode"));

    public static final StreamCodec<FriendlyByteBuf, C2SSetSosModePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SSetSosModePacket::stableId,
                    ByteBufCodecs.STRING_UTF8,
                    C2SSetSosModePacket::taskUid,
                    C2SSetSosModePacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SSetSosModePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // 校验 taskUid 为合法资源路径且不超长，防止任意垃圾串写入存档
            String uid = packet.taskUid();
            if (uid == null || uid.length() > 128 || net.minecraft.resources.ResourceLocation.tryParse(uid) == null) {
                return;
            }
            String playerUuid = serverPlayer.getUUID().toString();
            SosBindData data = SosBindData.get(serverPlayer.getServer());
            boolean ok = data.setTaskUid(playerUuid, packet.stableId(), uid);
            // 回推最新绑定列表（含工作模式），客户端据此刷新 UI
            context.reply(new S2CSosBindPacket(data.toEntries(playerUuid)));
            if (ok) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "message.huhutalk.sos.mode_set", uid));
            }
        });
    }
}
