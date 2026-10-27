package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.chat.OfflineMessageDeliverer;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * 女仆状态扫描器（每 100 tick 一轮）：刷新联系人簿状态，并在<b>她刚刚回来</b>时补发离线消息。
 *
 * <h2>门禁（§5.16 的关键修复）</h2>
 * 旧实现每轮都无条件调用 {@code OfflineMessageDeliverer.tryDeliver(maid, wasDead)}，
 * 于是任何写进总线的"记忆"都会在 ≤5 秒内被当成"她刚苏醒"再播一遍（同一件事两次 LLM 对话）。
 * <b>现在只在状态跃迁时投递</b>：把上一轮的状态交给
 * {@link OfflineMessageDeliverer#onMaidScanned(EntityMaid, MaidStatus, boolean)}，
 * 由它判断这次算不算"她回来了"（死亡复活 / 离开信号范围后回来 / 刚拿到手机）。
 *
 * <h2>两个必须的细节</h2>
 * <ul>
 *   <li><b>跳过非存活女仆</b>：死亡瞬间实体还会在地上躺约 1 秒。若不跳过，
 *       下一轮扫描会把 DEAD 又刷成 ONLINE —— 既把"复活"跃迁提前消费掉，
 *       还会用"你刚刚经历了一次死亡，并成功复活"这句在她是死的状态下播报。</li>
 *   <li><b>无手机不做投递</b>：那时她收不到（{@code SHUTDOWN}），
 *       投递器会把【落盘的】离线队列消息搬进【内存】总线，服务器一重启就永久丢失。</li>
 * </ul>
 */
public class MaidScanTicker {

    /**
     * 本次服务器会话是否还没扫过一轮。
     * <p>用于"重启后补发残留离线消息"：离线队列是落盘的，而女仆在簿状态可能仍是 ONLINE
     * （重启不会产生跃迁），所以首轮给每只在线的女仆一次机会 —— 但只有<b>确实有积压</b>时
     * 投递器才会真的开口。
     */
    private boolean firstScan = true;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 100 != 0) return;

        MaidContactBook book = MaidContactBook.get(server);
        Set<String> seenStableIds = new HashSet<>();
        long gameTime = server.overworld().getGameTime();
        boolean bootScan = firstScan;
        firstScan = false;

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EntityMaid maid) || !maid.isAlive()) {
                    continue; // 尸体不算"她在场"（见类注释）
                }
                String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
                seenStableIds.add(stableId);

                // 在刷新状态之前取旧状态：这是判断"她是否刚回来"的唯一依据
                MaidContactEntry oldEntry = book.getEntry(stableId);
                MaidStatus previousStatus = oldEntry == null ? null : oldEntry.getStatus();

                String name = maid.getName().getString();
                String dimension = level.dimension().location().toString();
                // 无主女仆（/summon、刷怪蛋、无契约）getOwnerUUID() 为 null，null 安全处理
                String ownerUuid = maid.getOwnerUUID() == null ? "" : maid.getOwnerUUID().toString();

                if (PhoneHelper.hasPhone(maid)) {
                    book.markOnline(stableId, name, dimension, gameTime, ownerUuid);
                    // 有手机才算"可达"：只有这时才谈得上补发离线消息
                    OfflineMessageDeliverer.onMaidScanned(maid, previousStatus, bootScan);
                } else {
                    // 无手机（已关机）：只记状态，不投递（见类注释）
                    book.markShutdown(stableId, name, dimension, gameTime, ownerUuid);
                }
            }
        }

        // 将 ONLINE 但未扫描到的标记为 OFFLINE。
        // 死亡 / 魂符内的状态不会被这里覆盖：markOffline 只处理正处于 ONLINE 的条目。
        for (MaidContactEntry entry : book.getAllEntries().values()) {
            if (entry.getStatus() == MaidStatus.ONLINE && !seenStableIds.contains(entry.getStableId())) {
                book.markOffline(entry.getStableId(), gameTime);
            }
        }
    }

    /** 服务器停止：下一局要重新做一次"首轮兜底"，同时清掉投递器的内存欠账。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        firstScan = true;
        OfflineMessageDeliverer.clearAll();
    }
}
