package com.huhutalk.network;

import com.huhutalk.core.waypoint.WaypointCache;
import com.huhutalk.core.waypoint.WaypointEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 客户端 → 服务端：同步玩家在当前世界的 JourneyMap 路径点精简列表。
 * 服务端存入 {@link WaypointCache}，供 move_to 工具解析路径点。
 */
public record C2SSyncWaypointsPacket(List<WaypointEntry> waypoints) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SSyncWaypointsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "sync_waypoints"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSyncWaypointsPacket> STREAM_CODEC =
            StreamCodec.composite(
                    WaypointEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    C2SSyncWaypointsPacket::waypoints,
                    C2SSyncWaypointsPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SSyncWaypointsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                WaypointCache.setWaypoints(serverPlayer.getUUID(), packet.waypoints());
            }
        });
    }
}
