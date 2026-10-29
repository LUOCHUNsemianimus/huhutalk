package com.huhutalk.network;

import com.huhutalk.core.rescue.RescueSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：同步「药水复活叙事」偏好。
 * <p>
 * 为什么需要这个包：叙事偏好的生效地点是<b>服务端</b>（死亡瞬间登记后由
 * {@code MaidRescueService#tryExecute} 决定是否调用 LLM），而玩家拨动的是手机设置页上的本地开关。
 * 客户端在<b>登录时</b>、<b>打开设置页时</b>、以及<b>拨动开关时</b>各推一次，
 * 服务端因此不需要把这份偏好落盘（见 {@link RescueSettings}）。
 * <p>
 * <b>关闭只停止救援相关的 AI 叙事/提醒，不会停止实际救援</b>：登记、5 秒对账、消耗药水、
 * 死亡点复活与物品气泡都照常执行。网络包的字段与含义保持原样，不重命名、不重置用户偏好。
 * <p>
 * 这是玩家自己的玩法偏好，不存在可被利用的信任问题：改它顶多影响自己会不会收到女仆的救援叙事。
 */
public record C2SPotionRescueSettingPacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<C2SPotionRescueSettingPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "potion_rescue_setting"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SPotionRescueSettingPacket> STREAM_CODEC =
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
