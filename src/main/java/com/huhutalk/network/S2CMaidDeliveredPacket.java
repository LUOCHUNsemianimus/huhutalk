package com.huhutalk.network;

import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端通知客户端：某女仆的离线消息已送达（已从离线队列发出给女仆）。
 * 客户端收到后应清除该女仆的"未送达"红"!"标记（不等女仆回复）。
 */
public record S2CMaidDeliveredPacket(String stableId) implements CustomPacketPayload {
    public static final Type<S2CMaidDeliveredPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_delivered"));

    public static final StreamCodec<FriendlyByteBuf, S2CMaidDeliveredPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    S2CMaidDeliveredPacket::stableId,
                    S2CMaidDeliveredPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
