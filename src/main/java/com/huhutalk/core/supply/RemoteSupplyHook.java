package com.huhutalk.core.supply;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.chat.MaidNarrativeBus;
import com.huhutalk.core.move.MaidMoveData;
import com.huhutalk.core.move.TaskMoveTo;
import com.huhutalk.core.rescue.PendingRescueManager;
import com.huhutalk.core.rescue.RescueEnvironment;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「远程补给」的服务端事件入口。
 *
 * <h2>四个钩子各管什么</h2>
 * <ul>
 *   <li>{@link MaidTickEvent}（每 maid）：<b>工作期的开 / 关</b>。任务被切成补给模式就开启计数，
 *       被切成别的就结账。中间被 {@code move_to} 借走时只<b>暂停</b>（见下）。</li>
 *   <li>{@link MaidTaskEnableEvent}：TLM <b>只在玩家手动切换</b>任务时 post 它
 *       （{@code MaidTaskPackage}；LLM 的 {@code switch_work_task} 不会）。
 *       所以我们用它把"手动切走"标记下来 —— 这既是需求 2.3.2 与 2.3.3 的判别器，
 *       也是避免"模型刚说关了她又自己说一遍"双重调用的关键。</li>
 *   <li>{@link ServerTickEvent.Post}：每玩家一次补给判定 + 死亡叙事的延迟对账。</li>
 *   <li>{@link LivingDeathEvent}：记下"主人在补给期间死了"，等救援结果出来再决定要不要叙事。</li>
 * </ul>
 *
 * <h2>为什么"瞬态任务"只暂停不结账</h2>
 * {@code move_to} 会先把任务切成 {@link TaskMoveTo}，走完再恢复原任务。
 * 若把这次切换当成"离开补给模式"，玩家叫她去搬个东西就会刷出一堆假总结。
 * 判据用 {@link MaidMoveData#hasTarget(EntityMaid)}：只有"确实在移动中"才算瞬态，
 * 避免移动被取消后任务残留在 {@code move_to} 上导致永久暂停。
 */
public class RemoteSupplyHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuhuTalk.MOD_ID + ".supply");

    /** 每 maid 的工作期对账间隔（tick） */
    private static final int MAID_CHECK_INTERVAL = 10;
    /** "手动切换"标记的有效期（tick）：事件在 setTask 之前 post，观察要在其后不久完成 */
    private static final long MANUAL_MARK_TOLERANCE = 40L;
    /** 死亡叙事至少要等救援窗口关闭后再判定（5s 窗口 + 1s 余量） */
    private static final long DEATH_EVAL_DELAY = PendingRescueManager.DELAY_TICKS + 20L;
    /** 死亡记录的保留上限（tick）：5 分钟还没复活就丢弃 */
    private static final long DEATH_RECORD_TTL = 5L * 60L * 20L;
    /** 死亡期间把"补给用尽"按住的上限（tick）：20 秒（死亡叙事约 6 秒就判定完，够用） */
    private static final int USED_UP_HOLD_TICKS = 20 * 20;

    /** 玩家 UUID -> 一次待判定的死亡 */
    private record DeathNote(String deathMessage, long deathTick) {
    }

    private static final Map<UUID, DeathNote> DEATHS = new HashMap<>();

    // ==================== 工作期：开 / 暂停 / 结账 ====================

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        MinecraftServer server = maid.getServer();
        if (server == null || server.getTickCount() % MAID_CHECK_INTERVAL != 0) {
            return;
        }
        String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
        if (stableId == null || stableId.isBlank()) {
            return;
        }
        IMaidTask task = maid.getTask();
        RemoteSupplySession session = RemoteSupplySessions.get(stableId);

        if (task == TaskRemoteSupply.INSTANCE) {
            if (session == null) {
                if (maid.getOwner() instanceof ServerPlayer owner) {
                    RemoteSupplySessions.open(stableId, owner.getUUID(), server.getTickCount());
                    LOGGER.info("[HuhuTalk][补给] 工作期开始：maid={} owner={}",
                            maid.getName().getString(), owner.getName().getString());
                }
            } else {
                session.resume();
            }
            return;
        }

        if (session == null) {
            return;
        }
        // 被 move_to 这类瞬态任务借走：暂停，不结账、不补给
        if (isTransientMove(maid, task)) {
            session.pause();
            return;
        }
        closeSession(server, maid, stableId, session, task);
    }

    /** 只有"确实还带着移动目标"的 {@link TaskMoveTo} 才算瞬态；残留的移动任务按真正离开处理。 */
    private static boolean isTransientMove(EntityMaid maid, IMaidTask task) {
        return task == TaskMoveTo.INSTANCE && MaidMoveData.hasTarget(maid);
    }

    /** 结账：区分"玩家手动切走"（要她开口）与"模型/程序切走"（只写记忆）。 */
    private void closeSession(MinecraftServer server, EntityMaid maid, String stableId,
                              RemoteSupplySession session, IMaidTask newTask) {
        RemoteSupplySessions.close(stableId);
        String newUid = newTask == null ? "" : newTask.getUid().toString();
        String newName = newTask == null ? "（未知）" : newTask.getName().getString();
        boolean manual = session.consumeManualSwitch(newUid, server.getTickCount(), MANUAL_MARK_TOLERANCE) != null;

        if (!(maid.getOwner() instanceof ServerPlayer owner) || !owner.isAlive()) {
            // 主人不在线：内存态直接丢弃（不补发）
            LOGGER.info("[HuhuTalk][补给] 工作期结束（主人不在线，丢弃）：maid={} 新任务={} 消耗={}",
                    maid.getName().getString(), newUid, session.consumption());
            return;
        }
        LOGGER.info("[HuhuTalk][补给] 工作期结束：maid={} 新任务={} 手动={} 消耗={}",
                maid.getName().getString(), newUid, manual, session.consumption());

        if (manual) {
            RemoteSupplyNarrative.injectManualSwitch(owner, maid, newName, session.consumption());
        } else {
            RemoteSupplyNarrative.queueSwitchMemory(maid, newName, session.consumption());
        }
    }

    // ==================== 手动切换的标记 ====================

    /**
     * TLM 只在<b>玩家手动切换</b>时 post 这个事件，所以它天然就是"手动 vs 模型"的判别器。
     * <p>这里只<b>记下</b>预期目标，不立刻结账 —— 事件是可取消的，而它又发生在
     * {@code maid.setTask} 之前；真正的结账等我们观察到任务确实变了再做。
     */
    @SubscribeEvent
    public void onMaidTaskEnable(MaidTaskEnableEvent event) {
        EntityMaid maid = event.getEntityMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        IMaidTask target = event.getTargetTask();
        if (target == TaskRemoteSupply.INSTANCE) {
            return;
        }
        String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
        RemoteSupplySession session = RemoteSupplySessions.get(stableId);
        if (session == null) {
            return;
        }
        MinecraftServer server = maid.getServer();
        session.markManualSwitch(target.getUid().toString(), server == null ? 0L : server.getTickCount());
    }

    // ==================== 每 tick：补给判定 + 死亡叙事对账 ====================

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        RemoteSupplyService.tickServer(server);
        tickDeaths(server);
    }

    /**
     * 主人在补给期间死了：等救援结果落定后再决定要不要让她说一句。
     *
     * <p>判定条件（三者同时满足）：
     * <ol>
     *   <li>救援窗口已经关闭（{@link #DEATH_EVAL_DELAY}）；</li>
     *   <li>玩家<b>已经活着</b>（说明他自己点了重生，而不是还在死亡界面）；</li>
     *   <li>这次死亡<b>没有</b>被救援成功（{@link PendingRescueManager#wasRescuedSince}）——
     *       被救回来的话，救援那条链路自己会讲，不能两边都来说一遍。</li>
     * </ol>
     */
    private void tickDeaths(MinecraftServer server) {
        if (server == null || DEATHS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, DeathNote> entry : DEATHS.entrySet()) {
            UUID playerId = entry.getKey();
            DeathNote note = entry.getValue();
            if (now > note.deathTick() + DEATH_RECORD_TTL) {
                done.add(playerId);
                continue;
            }
            if (now < note.deathTick() + DEATH_EVAL_DELAY) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                done.add(playerId);
                continue;
            }
            // 还在死亡界面：再等等（他可能过一会儿才点重生）
            if (!player.isAlive() || player.isDeadOrDying()) {
                continue;
            }
            done.add(playerId);
            if (PendingRescueManager.wasRescuedSince(playerId, note.deathTick())) {
                // 救回来了 —— 叙事归救援链路。那条挂着的"补给用尽"也一并撤掉：
                // 他刚被救活，此刻再补一句"没药了"只会让对话割裂（兼容层，见 §12）。
                boolean dropped = MaidNarrativeBus.withdrawAny(playerId, MaidNarrativeBus.Kind.SUPPLY_USED_UP);
                LOGGER.info("[HuhuTalk][补给] 已被救援，撤下待播的「补给用尽」：player={} 撤下={}",
                        player.getName().getString(), dropped);
                continue;
            }
            List<RemoteSupplySession> sessions = RemoteSupplySessions.ofPlayer(playerId);
            if (sessions.isEmpty()) {
                MaidNarrativeBus.release(playerId, MaidNarrativeBus.Kind.SUPPLY_USED_UP);
                continue; // 期间她已经被切走 / 掉线了，就别硬编
            }
            EntityMaid speaker = RemoteSupplyService.pickSpeaker(server, player, sessions);
            if (speaker == null) {
                MaidNarrativeBus.release(playerId, MaidNarrativeBus.Kind.SUPPLY_USED_UP);
                continue;
            }
            // 把还没播出去的"补给用尽"吸收进这条死亡叙事里：一件事只花一次 LLM（兼容层，见 §12）
            boolean alsoRanOut =
                    MaidNarrativeBus.withdrawAny(playerId, MaidNarrativeBus.Kind.SUPPLY_USED_UP);
            LOGGER.info("[HuhuTalk][补给] 主人未撑到补给且未被救回：player={} 开口者={} 死因=\"{}\" 并入补给用尽={}",
                    player.getName().getString(), speaker.getName().getString(),
                    note.deathMessage(), alsoRanOut);
            RemoteSupplyNarrative.injectDiedWithoutRescue(player, speaker, note.deathMessage(), alsoRanOut);
        }
        for (UUID id : done) {
            DEATHS.remove(id);
        }
    }

    /** 死亡瞬间记一笔（只关心"他名下确实有补给女仆"的情况）。 */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !RemoteSupplySessions.hasAny(player.getUUID())) {
            return;
        }
        DEATHS.put(player.getUUID(),
                new DeathNote(RescueEnvironment.describeDeath(player, event.getSource()), server.getTickCount()));
        // 他刚死：先把还没播出去的"补给用尽"按住，等救援结果出来再决定是吸收还是放出。
        // 超时保护 20 秒 —— 死亡叙事本身在死亡后约 6 秒就会判定完，够用。
        MaidNarrativeBus.hold(player.getUUID(), MaidNarrativeBus.Kind.SUPPLY_USED_UP, USED_UP_HOLD_TICKS);
    }

    // ==================== 清理 ====================

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        RemoteSupplySessions.onPlayerGone(id);
        RemoteSupplyService.onPlayerGone(id);
        RemoteSupplySettings.clear(id);
        DEATHS.remove(id);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        RemoteSupplySessions.clearAll();
        RemoteSupplyService.clearAll();
        RemoteSupplySettings.clearAll();
        DEATHS.clear();
    }
}
