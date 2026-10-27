package com.huhutalk.network.transport;

import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/**
 * 本项目自己的负载上下文。服务端玩家只从 Forge {@code NetworkEvent.Context#getSender()}
 * 取得；客户端玩家由仅客户端注册的 Supplier 提供，公共代码不 import 客户端类。
 */
public interface IPayloadContext {
    Player player();

    CompletableFuture<Void> enqueueWork(Runnable runnable);

    void reply(CustomPacketPayload payload);
}
