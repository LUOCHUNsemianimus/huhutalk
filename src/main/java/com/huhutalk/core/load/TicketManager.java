package com.huhutalk.core.load;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import com.huhutalk.init.MaidPhoneTier;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 强制加载票据管理器。
 * <p>
 * 核心职责：让女仆"不掉线"。决定女仆所在区块是否被强制加载（1×1，forceTicks）：
 * <ul>
 *   <li><b>智能手机（Pro）</b>：总是对女仆当前区块下发票据 → 任意位置常驻。</li>
 *   <li><b>电话手表（青春版）</b>：仅当其区块位于某个 WiFi 方块覆盖（3×3 区块）内时下发；
 *       覆盖外回收 → 女仆卸载（离线）。</li>
 *   <li><b>无手机</b>：回收任何已下发票据。</li>
 * </ul>
 * 对未加载（但在联系人簿中、且记录有手机/坐标）的女仆，会按记录"再水化"：
 * Pro 恒载；青春版若记录坐标在覆盖内则重新加载。
 * <p>
 * 注册在服务端游戏总线上，每 {@code INTERVAL} tick 对账一次。
 */
public class TicketManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 永久强制加载票据（timeout=0，仅显式移除才消失） */
    private static final TicketType<Unit> MAID_TICKET =
            TicketType.create("huhutalk_maid", (a, b) -> 0);

    /** 对账周期（tick） */
    private static final int INTERVAL = 20;

    /** WiFi 覆盖半径（区块）。直径 3 区块 → 半径 1 */
    private static final int COVER_RADIUS = 1;

    /** 已下发的票据：stableId → 票据（维度 + 区块） */
    private final Map<String, ActiveTicket> active = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (server.getTickCount() % INTERVAL != 0) {
            return;
        }
        reconcile(server);
    }

    private void reconcile(MinecraftServer server) {
        Map<String, ActiveTicket> desired = new HashMap<>();
        Set<String> seen = new HashSet<>();

        // 1) 遍历已加载女仆：刷新联系人记录 + 计算所需票据
        WifiBlockRegistry wifi = WifiBlockRegistry.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid) {
                    String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
                    seen.add(stableId);
                    record(server, maid, stableId);

                    MaidPhoneTier tier = PhoneHelper.getPhoneTier(maid);
                    // Pro 恒载；青春版仅当位于 WiFi 覆盖内才载
                    boolean covered = tier == MaidPhoneTier.WATCH
                            && wifi.isCovered(level.dimension(), chunkOf(maid), COVER_RADIUS);
                    if (tier == MaidPhoneTier.SMART || covered) {
                        desired.put(stableId,
                                new ActiveTicket(level.dimension(), chunkOf(maid)));
                    }
                }
            }
        }

        // 2) 再水化：未加载但在簿、且记录有手机的女仆
        MaidContactBook book = MaidContactBook.get(server);
        for (MaidContactEntry entry : book.getAllEntries().values()) {
            if (seen.contains(entry.getStableId())) {
                continue;
            }
            if (entry.getStatus() == MaidStatus.DEAD) {
                continue;
            }
            MaidPhoneTier tier = entry.getLastPhoneTier();
            ChunkPos lastChunk = entry.getLastChunkPos();
            if (tier == null || lastChunk == null) {
                continue;
            }
            ServerLevel level = server.getLevel(entry.getDimensionKey());
            if (level == null) {
                continue;
            }
            boolean covered = tier == MaidPhoneTier.SMART
                    || wifi.isCovered(level.dimension(), lastChunk, COVER_RADIUS);
            if (covered) {
                desired.put(entry.getStableId(), new ActiveTicket(level.dimension(), lastChunk));
            }
        }

        // 3) 回收不再需要 / 已变化的票据
        for (Iterator<Map.Entry<String, ActiveTicket>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ActiveTicket> e = it.next();
            ActiveTicket want = desired.get(e.getKey());
            if (want == null || !want.equals(e.getValue())) {
                e.getValue().removeTicket(server);
                it.remove();
            }
        }

        // 4) 下发新票据
        for (Map.Entry<String, ActiveTicket> e : desired.entrySet()) {
            ActiveTicket cur = active.get(e.getKey());
            if (cur == null || !cur.equals(e.getValue())) {
                e.getValue().addTicket(server);
                active.put(e.getKey(), e.getValue());
            }
        }
    }

    /** 把已加载女仆的坐标/手机档位刷新到联系人簿（供离线再水化与覆盖判断） */
    private void record(MinecraftServer server, EntityMaid maid, String stableId) {
        MaidContactBook book = MaidContactBook.get(server);
        ServerLevel level = (ServerLevel) maid.level();
        MaidContactEntry entry = book.getOrCreateEntry(stableId,
                maid.getName().getString(), maid.getOwnerUUID().toString());
        entry.setDimension(level.dimension().location().toString());
        entry.setLastChunkPos(chunkOf(maid));
        entry.setLastPhoneTier(PhoneHelper.getPhoneTier(maid));
    }

    private static ChunkPos chunkOf(EntityMaid maid) {
        return new ChunkPos(maid.blockPosition());
    }

    /** 一张已下发/待下发的 1×1 强制加载票据 */
    private record ActiveTicket(ResourceKey<Level> dimension, ChunkPos chunk) {

        private ServerLevel resolve(MinecraftServer server) {
            return server.getLevel(dimension);
        }

        private void addTicket(MinecraftServer server) {
            ServerLevel level = resolve(server);
            if (level != null) {
                level.getChunkSource().addRegionTicket(MAID_TICKET, chunk, 0, Unit.INSTANCE, true);
            }
        }

        private void removeTicket(MinecraftServer server) {
            ServerLevel level = resolve(server);
            if (level != null) {
                level.getChunkSource().removeRegionTicket(MAID_TICKET, chunk, 0, Unit.INSTANCE);
            }
        }
    }
}
