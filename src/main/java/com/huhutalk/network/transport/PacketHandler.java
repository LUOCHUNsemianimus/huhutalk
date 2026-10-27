package com.huhutalk.network.transport;

/**
 * 36 个包原有静态 handle 方法的函数接口形状。
 */
@FunctionalInterface
public interface PacketHandler<T extends CustomPacketPayload> {
    void handle(T payload, IPayloadContext context);
}
