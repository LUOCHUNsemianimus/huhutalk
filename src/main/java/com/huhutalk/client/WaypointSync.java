package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.core.waypoint.WaypointEntry;
import com.huhutalk.network.C2SSyncWaypointsPacket;
import journeymap.client.waypoint.ClientWaypointImpl;
import journeymap.common.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 客户端：读取 JourneyMap 路径点并同步给服务端。
 * <p>
 * JourneyMap 是<b>可选软依赖</b>：仅当其已加载时才读取（用 ModList 守卫，
 * 避免未装 JourneyMap 时 ClassNotFoundError）。路径点是客户端本地数据，必须同步到服务端
 * 才能被服务端的 move_to 工具解析。
 * <p>
 * 路径点可能在进入世界后稍晚才从磁盘加载，因此除进世界时立即同步外，
 * 还会由 {@link #tickSync()} 周期性重同步（带签名去重，仅变化时发包）。
 */
@OnlyIn(Dist.CLIENT)
public final class WaypointSync {

    private static final int SYNC_INTERVAL = 200; // 每 10 秒检查一次
    private static int tickCounter = 0;
    private static String lastSignature = "";

    private WaypointSync() {
    }

    /** 客户端 tick：周期性重同步（仅当路径点集合变化时发包） */
    public static void tickSync() {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (++tickCounter % SYNC_INTERVAL != 0) {
            return;
        }
        sendWaypoints();
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
            String signature = buildSignature(entries);
            if (signature.equals(lastSignature)) {
                return;
            }
            lastSignature = signature;
            PacketDistributor.sendToServer(new C2SSyncWaypointsPacket(entries));
            HuhuTalk.LOGGER.info("[WaypointSync] synced {} waypoints to server", entries.size());
        } catch (Throwable t) {
            HuhuTalk.LOGGER.error("[WaypointSync] failed to sync waypoints", t);
        }
    }

    private static String buildSignature(List<WaypointEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(WaypointEntry::name))
                .map(wp -> wp.name() + "@" + wp.dimension() + ":" + wp.x() + "," + wp.y() + "," + wp.z())
                .collect(Collectors.joining("|"));
    }
}
