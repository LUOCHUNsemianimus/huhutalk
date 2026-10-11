package com.huhutalk.network;

import com.huhutalk.core.supply.RemoteSupplySettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：同步「远程补给触发女仆回复」开关（需求 2.4）。
 * <p>
 * 为什么需要这个包：开关决定的是<b>服务端</b>要不要多花一次 LLM 调用
 * （补给品用尽 / 主人没被救回 / 玩家手动切走模式），而玩家拨动的是手机设置页上的本地开关。
 * 客户端在<b>登录时</b>、<b>打开设置页时</b>、以及<b>拨动开关时</b>各推一次，
 * 服务端因此不需要把这份偏好落盘（见 {@link RemoteSupplySettings}）。
 * <p>
 * 与「药水复活」那个开关的区别：那个开关关掉 = <b>功能本身不生效</b>（不被救）；
 * 这个开关关掉 = <b>功能照常，只是不主动叫她开口</b>（事实照样进记忆）。
 */
public record C2SRemoteSupplyReplySettingPacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<C2SRemoteSupplyReplySettingPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "remote_supply_reply_setting"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRemoteSupplyReplySettingPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    C2SRemoteSupplyReplySettingPacket::enabled,
                    C2SRemoteSupplyReplySettingPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRemoteSupplyReplySettingPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            RemoteSupplySettings.setReplyEnabled(serverPlayer.getUUID(), packet.enabled());
        });
    }
}
