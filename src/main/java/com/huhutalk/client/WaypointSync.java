package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.core.waypoint.WaypointEntry;
import com.huhutalk.network.C2SSyncWaypointsPacket;
import journeymap.client.waypoint.Waypoint;
import journeymap.client.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import com.huhutalk.network.transport.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 客户端：读取 JourneyMap 路径点并同步给服务端。
 * <p>
 * JourneyMap 是<b>可选软依赖</b>：外层 {@link #sendWaypoints()} 先做 ModList 守卫，
 * 未安装时不会解析/加载下面嵌套的 {@link JourneyMapReader}，避免 ClassNotFoundError。
 * 路径点是客户端本地数据，必须同步到服务端才能被服务端的 move_to 工具解析。
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

    /** 进入新世界时重置同步签名，确保切档后必然重新同步（避免复用上一世界的去重签名导致漏发） */
    public static void reset() {
        lastSignature = "";
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
        if (!ModList.get().isLoaded("journeymap")) {
            return;
        }
        try {
            List<WaypointEntry> entries = JourneyMapReader.collect();
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

    /**
     * 只有 ModList 守卫通过后才会解析到本类；所有 JourneyMap 类型集中在这里，
     * 保证未安装模组时外部类不会被 JVM 加载。
     */
    private static final class JourneyMapReader {
        private JourneyMapReader() {
        }

        private static List<WaypointEntry> collect() {
            Collection<Waypoint> all = WaypointStore.INSTANCE.getAll();
            List<WaypointEntry> entries = new ArrayList<>();
            for (Waypoint waypoint : all) {
                if (waypoint.isDeathPoint()) {
                    continue;
                }
                if (!waypoint.isEnable()) {
                    continue;
                }
                // 必须用 raw 坐标：getX/getZ 会按玩家当前维度做下界换算，跨维度会差 8 倍。
                WaypointProjection.fromRawCenters(
                        waypoint.getName(),
                        waypoint.getDimensions(),
                        waypoint.getRawCenterX(),
                        waypoint.getY(),
                        waypoint.getRawCenterZ()
                ).ifPresent(entries::add);
            }
            return entries;
        }
    }
}
