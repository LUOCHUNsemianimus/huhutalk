package com.huhutalk.network.transport;

import net.minecraft.server.level.ServerPlayer;

/**
 * 本项目自己的受限分发入口，底层仍是 Forge SimpleChannel / PacketDistributor。
 * 只覆盖现有业务实际使用的三种目标。
 */
public final class PacketDistributor {
    private PacketDistributor() {
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketTransport.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketTransport.sendToPlayer(player, payload);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        PacketTransport.sendToAllPlayers(payload);
    }
}
