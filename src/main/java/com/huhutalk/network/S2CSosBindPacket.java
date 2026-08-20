package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 服务端 → 客户端：某玩家当前已绑定到 SOS 的女仆 stableId 列表。
 * <p>在绑定切换后、以及请求女仆列表时下发，客户端写入 {@link com.huhutalk.client.SosBindCache}，
 * 供女仆列表页显示"已绑定 SOS"徽标。
 */
public record S2CSosBindPacket(List<String> stableIds) implements CustomPacketPayload {
    public static final Type<S2CSosBindPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "sos_bind"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSosBindPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    S2CSosBindPacket::stableIds,
                    S2CSosBindPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
