package com.huhutalk.network;

import com.huhutalk.core.rescue.RescueSettings;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：同步「药水复活」开关。
 * <p>
 * 为什么需要这个包：开关最终生效的地点是<b>服务端</b>（玩家死亡那一瞬间由
 * {@code MaidRescueService#trySchedule} 判定），而玩家拨动的是手机设置页上的本地开关。
 * 客户端在<b>登录时</b>、<b>打开设置页时</b>、以及<b>拨动开关时</b>各推一次，
 * 服务端因此不需要把这份偏好落盘（见 {@link RescueSettings}）。
 * <p>
 * 这是玩家自己的玩法偏好，不存在可被利用的信任问题：改它顶多影响自己会不会被救。
 */
public record C2SPotionRescueSettingPacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<C2SPotionRescueSettingPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "potion_rescue_setting"));

    public static final StreamCodec<FriendlyByteBuf, C2SPotionRescueSettingPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    C2SPotionRescueSettingPacket::enabled,
                    C2SPotionRescueSettingPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SPotionRescueSettingPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            RescueSettings.set(serverPlayer.getUUID(), packet.enabled());
        });
    }
}
