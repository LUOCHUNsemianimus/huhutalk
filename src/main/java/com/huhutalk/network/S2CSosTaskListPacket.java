package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 服务端 → 客户端：当前可用的女仆工作模式（taskUid）列表。
 * <p>客户端据此在 SOS 绑定页渲染工作模式选择器。兼容新增攻击模式的 mod：
 * 所有非隐藏任务都会被枚举，而不只是攻击类。
 */
public record S2CSosTaskListPacket(List<String> taskUids) implements CustomPacketPayload {
    public static final Type<S2CSosTaskListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "sos_task_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSosTaskListPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    S2CSosTaskListPacket::taskUids,
                    S2CSosTaskListPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
