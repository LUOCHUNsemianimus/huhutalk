package com.huhutalk.core.rescue;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.transfer.MaidTransferService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 「连续复活事件」管理器（服务端内存态，全局单例）。
 *
 * <h2>它解决什么</h2>
 * 玩家拿一背包药水当复活甲、反复死去活来时，女仆每次都发一条消息既烦又白烧 token。
 * 规则（需求方拍板）：
 * <ol>
 *   <li>事件内第 1/2/3 次成功救援，<b>各唤一次</b> LLM 叙事；</li>
 *   <li>第 4 次起<b>完全静默</b>——不调 LLM、不写记忆、不进任何消息通道，只在本类里记账；</li>
 *   <li>任意死亡（含没救成的）都会刷新 3 分钟计时；<b>连续 3 分钟没再死</b> = 已脱离危险
 *       → 事件结束，把这段统计<b>总结成最后一条消息</b>发给一只参与女仆（全程只 1 次 LLM 调用）；</li>
 *   <li>事件结束即清状态，下次死亡重新从"第 1 次"算起 —— 否则半小时后的一次单独死亡会被误算成"第 2 次"。</li>
 * </ol>
 *
 * <h2>多人 / 生命周期安全</h2>
 * 全部状态以<b>玩家 UUID</b> 为键，不持有 {@code ServerPlayer}（重生会换实例）；
 * 玩家登出 / 停服时清理，避免长开服内存累积。所有入口都在服务端主线程，
 * 因此内部直接用 {@link HashMap}，无需并发容器。
 */
public final class RescueStreakManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuhuTalk.MOD_ID + ".rescue");

    /** 前几次救援还值得唤起 LLM；第 NARRATE_LIMIT + 1 次起转入静默记账。 */
    public static final int NARRATE_LIMIT = 3;

    /** 事件内连续多久没再死亡就视为"脱离危险"并结算：3 分钟 */
    public static final long IDLE_TICKS = 3L * 60L * 20L;

    /** 「治疗药水不多了」的阈值：参与女仆<b>合计</b>剩余瓶数 ≤ 此值即提醒一次 */
    public static final int LOW_POTION_THRESHOLD = 3;

    /** 玩家 UUID -> 当前连续复活事件 */
    private static final Map<UUID, RescueStreak> EPISODES = new HashMap<>();

    /** 服务端主线程专用的随机源（同 {@link RescueMaidPicker} 的约定）。 */
    private static final Random RANDOM = new Random();

    private RescueStreakManager() {
    }

    /**
     * 一次成功救援落定后调用（<b>必须在复活已完成之后</b>）。
     *
     * @param cooperative 本次是否为"两只女仆一起动手"（用于总结里"我和 xx 一块救了你 x 次"）
     * @return 事件内这是第几次成功救援（1 起）。调用方据此决定"还调不调 LLM"。
     */
    public static int onRescue(ServerPlayer player, long nowTick, boolean cooperative) {
        if (player == null) {
            return 1;
        }
        RescueStreak streak = EPISODES.computeIfAbsent(player.getUUID(),
                id -> new RescueStreak(player.getName().getString(), nowTick));
        streak.touch(nowTick);
        int seq = streak.advanceRescue();
        if (streak.counted()) {
            streak.countRescue(cooperative);
            if (seq == NARRATE_LIMIT + 1) {
                LOGGER.info("[HuhuTalk][救援] 进入静默期：player={} 第 {} 次起只记账、不再调用 AI",
                        streak.playerName(), seq);
            }
        }
        return seq;
    }

    /**
     * 任意一次玩家死亡（含最终没能救成的）都调这里：刷新 3 分钟计时；进入记账区间后计一次死亡。
     * <p>没有事件在跑时什么都不做 —— 事件由"第一次成功救援"开启，而不是由死亡开启。
     */
    public static void onDeath(ServerPlayer player, long nowTick) {
        if (player == null) {
            return;
        }
        RescueStreak streak = EPISODES.get(player.getUUID());
        if (streak == null) {
            return;
        }
        streak.touch(nowTick);
        // 第 4 次死亡起（此时事件内已有 3 次成功救援）才计入统计，与"第四次（含）开始计算"一致
        if (streak.rescueSeq() >= NARRATE_LIMIT) {
            streak.countDeath();
        }
    }

    /** 记账区间内，某只女仆真的投递了一瓶药水：登记参与者、投递者与消耗明细。 */
    public static void recordDelivery(ServerPlayer player, EntityMaid maid, ItemStack potion) {
        if (player == null || maid == null) {
            return;
        }
        RescueStreak streak = EPISODES.get(player.getUUID());
        if (streak == null || !streak.counted()) {
            return;
        }
        String stableId = stableIdOf(maid);
        streak.countDelivery(stableId, maid.getName().getString(), RescuePotion.displayName(potion));
    }

    /**
     * 「治疗药水快没了」的特殊提醒（需求 1.3）。
     * <p>统计口径：本事件里<b>真的投递过药水</b>的女仆们，各自背包（含隙间绑箱与主副手）
     * 当前剩余的<b>治疗系</b>药水<b>合计</b>瓶数。≤ {@link #LOW_POTION_THRESHOLD} 时提醒一次，
     * 整个事件内只提醒一次。
     *
     * @param speaker 刚完成投递的治疗方女仆（由她开口）
     * @return 需要提醒时返回通知，否则 {@code null}（包括：没进记账区间 / 已经提醒过 / 数不出来）
     */
    @Nullable
    public static LowPotionNotice lowPotionNotice(MinecraftServer server, ServerPlayer player,
                                                  EntityMaid speaker) {
        if (server == null || player == null || speaker == null) {
            return null;
        }
        RescueStreak streak = EPISODES.get(player.getUUID());
        if (streak == null || !streak.counted() || streak.lowPotionWarned()) {
            return null;
        }
        int remaining = 0;
        boolean anyResolved = false;
        for (String stableId : streak.deliverers()) {
            EntityMaid maid = MaidStableIdHelper.findMaid(server, stableId);
            if (maid == null) {
                // 未加载/离线：她此刻本来也参与不了救援，不把未知量算进来
                continue;
            }
            anyResolved = true;
            remaining += MaidTransferService.countMatching(maid, RescuePotion::isHealing);
        }
        if (!anyResolved || remaining > LOW_POTION_THRESHOLD) {
            return null;
        }
        streak.markLowPotionWarned();
        String speakerId = stableIdOf(speaker);
        List<String> others = new ArrayList<>();
        for (Map.Entry<String, String> entry : streak.participants().entrySet()) {
            if (!entry.getKey().equals(speakerId)) {
                others.add(entry.getValue());
            }
        }
        LOGGER.info("[HuhuTalk][救援] 濒危提醒：player={} 剩余治疗药水合计={} 开口者={} 同伙={}",
                streak.playerName(), remaining, speaker.getName().getString(), others);
        return new LowPotionNotice(speaker, others, remaining);
    }

    /**
     * 「治疗药水不多了」的通知内容（由 {@link RescueNarrative} 负责措辞）。
     *
     * @param speaker     开口的女仆
     * @param otherNames  同一段记账区间里也投递过药水的其他女仆名（可能为空）
     * @param remaining   她们合计还剩多少瓶治疗系药水
     */
    public record LowPotionNotice(EntityMaid speaker, List<String> otherNames, int remaining) {
    }

    // ==================== 事件结算 ====================

    /**
     * 每 tick 对账：连续 3 分钟没再死亡的事件到此结束，需要总结的就把总结作为最后一条消息发出去。
     * <p>先"收集 + 出队"再执行：结算会调 LLM 并牵动聊天历史，不应在遍历过程中改 {@link #EPISODES}。
     */
    public static void tick(MinecraftServer server) {
        if (server == null || EPISODES.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<UUID> due = new ArrayList<>();
        for (Map.Entry<UUID, RescueStreak> entry : EPISODES.entrySet()) {
            if (now - entry.getValue().lastActivityTick() >= IDLE_TICKS) {
                due.add(entry.getKey());
            }
        }
        for (UUID playerId : due) {
            RescueStreak streak = EPISODES.remove(playerId);
            if (streak == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                LOGGER.info("[HuhuTalk][救援] 计数区间结算作废（玩家不在线）：player={}", streak.playerName());
                continue;
            }
            // 只走到第 3 次就脱离了危险：没有需要统计的内容，静默关窗
            if (!streak.counted()) {
                LOGGER.info("[HuhuTalk][救援] 连续复活事件结束（未进入记账区间）：player={} 救援次数={}",
                        streak.playerName(), streak.rescueSeq());
                continue;
            }
            EntityMaid speaker = resolveSpeaker(server, player, streak);
            if (speaker == null) {
                LOGGER.info("[HuhuTalk][救援] 计数区间结算作废（没有可联系的女仆）：player={} 死亡={} 救援={}",
                        streak.playerName(), streak.deathCount(), streak.rescueCount());
                continue;
            }
            LOGGER.info("[HuhuTalk][救援] 计数区间结算：player={} 死亡={} 救援={} 同手={} 消耗={} 收件人={}",
                    streak.playerName(), streak.deathCount(), streak.rescueCount(),
                    streak.coopRescueCount(), streak.consumption(), speaker.getName().getString());
            RescueNarrative.injectStreakSummary(player, streak, speaker);
        }
    }

    // ==================== 清理 ====================

    /** 玩家登出：丢掉未结算的事件（内存态，不补发）。 */
    public static void onPlayerGone(UUID playerId) {
        if (playerId != null) {
            EPISODES.remove(playerId);
        }
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        EPISODES.clear();
    }

    // ==================== 内部 ====================

    /**
     * 挑出总结的收件人：优先"最近一次投递的那只"（她是最自然的记账人），
     * 她不在线就退而在其余投递者里随机挑一只；要求女仆已加载、存活、仍属于该玩家且 AI 可用。
     */
    @Nullable
    private static EntityMaid resolveSpeaker(MinecraftServer server, ServerPlayer player, RescueStreak streak) {
        List<EntityMaid> ready = new ArrayList<>();
        EntityMaid preferred = null;
        String preferredId = streak.lastDelivererStableId();
        for (String stableId : streak.deliverers()) {
            EntityMaid maid = MaidStableIdHelper.findMaid(server, stableId);
            if (maid == null || !maid.isAlive() || !maid.isOwnedBy(player)) {
                continue;
            }
            MaidAIChatManager manager = maid.getAiChatManager();
            LLMSite site = manager.getLLMSite();
            if (site == null || !site.enabled()) {
                continue;
            }
            ready.add(maid);
            if (stableId.equals(preferredId)) {
                preferred = maid;
            }
        }
        if (ready.isEmpty()) {
            return null;
        }
        return preferred != null ? preferred : ready.get(RANDOM.nextInt(ready.size()));
    }

    /** 取女仆的稳定 ID（与全模组主键约定一致）。 */
    private static String stableIdOf(EntityMaid maid) {
        return MaidStableIdHelper.getOrCreateStableId(maid);
    }
}
