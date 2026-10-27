package com.huhutalk.network.transport;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 保留原 36 个包的注册块写法：
 * {@code playToServer(TYPE, CODEC, Packet::handle)} / {@code playToClient(...)}。
 * 它只登记描述和 handler，不生成 Minecraft/Forge 的每个包独立类型。
 */
public final class PayloadRegistrar {
    PayloadRegistrar() {
    }

    public <T extends CustomPacketPayload> void playToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<FriendlyByteBuf, T> codec,
            PacketHandler<T> handler) {
        PacketTransport.registerDescriptor(type, PacketTransport.Direction.TO_SERVER, codec, handler);
    }

    /**
     * 共同端登记 S2C 描述（不绑定客户端 handler）。双方按同一顺序调用。
     */
    public <T extends CustomPacketPayload> void playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<FriendlyByteBuf, T> codec) {
        PacketTransport.registerDescriptor(type, PacketTransport.Direction.TO_CLIENT, codec, null);
    }

    /**
     * 仅客户端调用：在同一描述上绑定原 handler。
     */
    public <T extends CustomPacketPayload> void playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<FriendlyByteBuf, T> codec,
            PacketHandler<T> handler) {
        PacketTransport.registerDescriptor(type, PacketTransport.Direction.TO_CLIENT, codec, handler);
    }
}
