package com.huhutalk.core.waypoint;

import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;

/**
 * 一条路径点数据（客户端 JourneyMap 同步给服务端的精简版）。
 * 坐标用<b>原始值</b>（raw），避免 JourneyMap 客户端 getX/getZ 的下界换算。
 */
public record WaypointEntry(String name, String dimension, int x, int y, int z) {

    public static final StreamCodec<FriendlyByteBuf, WaypointEntry> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, WaypointEntry::name,
                    ByteBufCodecs.STRING_UTF8, WaypointEntry::dimension,
                    ByteBufCodecs.INT, WaypointEntry::x,
                    ByteBufCodecs.INT, WaypointEntry::y,
                    ByteBufCodecs.INT, WaypointEntry::z,
                    WaypointEntry::new
            );
}
