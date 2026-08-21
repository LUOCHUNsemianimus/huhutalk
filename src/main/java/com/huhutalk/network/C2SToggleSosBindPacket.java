package com.huhutalk.network;

import com.huhutalk.core.contact.SosBindData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：切换某女仆是否绑定到 SOS 紧急呼叫槽位。
 * <p>绑定后点 SOS 会把它召来并围绕玩家保护；最多 {@link SosBindData#MAX_BOUND} 只。
 * 切换成功后回推最新绑定列表（{@link S2CSosBindPacket}）供客户端刷新 UI。
 */
public record C2SToggleSosBindPacket(String stableId) implements CustomPacketPayload {
    public static final Type<C2SToggleSosBindPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "toggle_sos_bind"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SToggleSosBindPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SToggleSosBindPacket::stableId,
                    C2SToggleSosBindPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SToggleSosBindPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            String playerUuid = serverPlayer.getUUID().toString();
            SosBindData data = SosBindData.get(serverPlayer.getServer());
            boolean bound = data.toggle(playerUuid, packet.stableId());
            // 回推最新绑定列表（含工作模式），客户端据此刷新 UI
            context.reply(new S2CSosBindPacket(data.toEntries(playerUuid)));
            if (bound) {
                serverPlayer.sendSystemMessage(Component.literal("[HuhuTalk] ✅ 已把该女仆加入 SOS 呼叫"));
            } else {
                serverPlayer.sendSystemMessage(Component.literal("[HuhuTalk] ➖ 已将该女仆移出 SOS 呼叫"));
            }
        });
    }
}
