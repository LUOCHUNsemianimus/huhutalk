package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.core.waypoint.WaypointEntry;
import com.huhutalk.network.C2SSyncWaypointsPacket;
import journeymap.client.waypoint.ClientWaypointImpl;
import journeymap.common.waypoint.WaypointStore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 客户端：读取 JourneyMap 路径点并同步给服务端。
 * <p>
 * JourneyMap 是<b>可选软依赖</b>：仅当其已加载时才读取（用 ModList 守卫，
 * 避免未装 JourneyMap 时 ClassNotFoundError）。路径点是客户端本地数据，必须同步到服务端
 * 才能被服务端的 move_to 工具解析。
 */
@OnlyIn(Dist.CLIENT)
public final class WaypointSync {

    private WaypointSync() {
    }

    /** 收集当前玩家当前世界的 JourneyMap 路径点并发送给服务端 */
    public static void sendWaypoints() {
        if (!net.neoforged.fml.ModList.get().isLoaded("journeymap")) {
            return;
        }
        try {
            Collection<ClientWaypointImpl> all = WaypointStore.getInstance().getAll();
            List<WaypointEntry> entries = new ArrayList<>();
            for (ClientWaypointImpl wp : all) {
                if (wp.isDeathPoint()) {
                    continue;
                }
                if (!wp.isEnabled()) {
                    continue;
                }
                // 用原始坐标（raw），避免 JourneyMap getX/getZ 的下界换算
                entries.add(new WaypointEntry(
                        wp.getName(),
                        wp.getPrimaryDimension(),
                        wp.getRawX(),
                        wp.getY(),
                        wp.getRawZ()
                ));
            }
            PacketDistributor.sendToServer(new C2SSyncWaypointsPacket(entries));
            HuhuTalk.LOGGER.info("[WaypointSync] synced {} waypoints to server", entries.size());
        } catch (Throwable t) {
            HuhuTalk.LOGGER.error("[WaypointSync] failed to sync waypoints", t);
        }
    }
}
