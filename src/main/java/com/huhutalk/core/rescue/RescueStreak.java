package com.huhutalk.core.rescue;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次「连续复活事件」的内存状态（服务端，一名玩家一份）。
 *
 * <h2>什么叫连续复活事件</h2>
 * 玩家死亡 → 被女仆救回，就是一次救援。只要他还在不停"死—被救"，就视为<b>同一个事件</b>；
 * 一旦连续 {@link RescueStreakManager#IDLE_TICKS}（3 分钟）没再死，说明他已经脱离战斗/危险，
 * 事件结束。事件内：
 * <ul>
 *   <li>第 {@link RescueStreakManager#NARRATE_LIMIT}（3）次<b>以内</b>的每次救援各唤起一次 LLM 叙事；</li>
 *   <li>第 {@link RescueStreakManager#NARRATE_LIMIT} 次<b>之后</b>（即第 4 次起）不再调 LLM，只在这里记账；</li>
 *   <li>事件结束时若已经进入记账区间，则把这段统计<b>总结成最后一条消息</b>发给其中一只女仆。</li>
 * </ul>
 *
 * <h2>为什么是可变对象</h2>
 * 与 {@link PendingRescue}（一次死亡的不可变快照）不同，本类记录的是<b>一段过程</b>，
 * 必然要被反复写入。它只活在服务端主线程（{@link RescueStreakManager} 里），
 * 因此不做并发保护；也<b>不持有 {@code ServerPlayer} 引用</b>（重生会换实例，只按 UUID 归属）。
 *
 * <h2>计数口径（需求方拍板）</h2>
 * <ul>
 *   <li>{@link #deathCount()} / {@link #rescueCount()} 只统计<b>记账区间</b>（第 4 次起）内的死亡与成功救援 ——
 *       前三次已经在各自的叙事里说过了，不重复计入。</li>
 *   <li>没救成的死亡也计入 {@link #deathCount()}（人确实死了），但不会计入 {@link #rescueCount()}。</li>
 *   <li>消耗明细按<b>物品显示名</b>分组累计（如「治疗药水」×3、「抗火药水」×1）。</li>
 * </ul>
 */
public final class RescueStreak {

    /** 玩家名（死亡瞬间抓下来，重生换实例后名字仍在） */
    private final String playerName;

    /** 最近一次"有事发生"的 tick（任何死亡或成功救援都会刷新它）—— 3 分钟静默判定的依据 */
    private long lastActivityTick;

    /** 事件内成功救援次数（1 起）：决定"还调不调 LLM" */
    private int rescueSeq;
    /** 记账区间内的死亡次数 */
    private int deathCount;
    /** 记账区间内的成功救援次数 */
    private int rescueCount;
    /** 记账区间内"两只女仆一起动手"的次数 */
    private int coopRescueCount;

    /** 记账区间内消耗掉的物品：物品显示名 -> 个数（LinkedHashMap 保证文案顺序稳定） */
    private final Map<String, Integer> consumption = new LinkedHashMap<>();
    /** 记账区间内参与过救援的女仆：stableId -> 显示名 */
    private final Map<String, String> participants = new LinkedHashMap<>();
    /** 记账区间内<b>真的投递过药水</b>的女仆 stableId（只有这些能当总结收件人） */
    private final Set<String> deliverers = new LinkedHashSet<>();
    /** 最近一次投递的女仆 stableId —— 结算时优先由她开口（她是最自然的"记账人"） */
    private String lastDelivererStableId;

    /** 「治疗药水不多了」这条特殊对话在当前事件内是否已经说过（说一次就够） */
    private boolean lowPotionWarned;

    RescueStreak(String playerName, long startTick) {
        this.playerName = playerName;
        this.lastActivityTick = startTick;
    }

    // ==================== 写入 ====================

    /** 刷新"最近有事发生"的时刻（死亡或救援成功时调用）。 */
    void touch(long nowTick) {
        if (nowTick > lastActivityTick) {
            lastActivityTick = nowTick;
        }
    }

    /** 记一次成功救援，返回事件内第几次（1 起）。 */
    int advanceRescue() {
        return ++rescueSeq;
    }

    /** 记一次记账区间内的死亡。 */
    void countDeath() {
        deathCount++;
    }

    /** 记一次记账区间内的成功救援。 */
    void countRescue(boolean cooperative) {
        rescueCount++;
        if (cooperative) {
            coopRescueCount++;
        }
    }

    /** 记一次投递：登记参与者、投递者与消耗的物品。 */
    void countDelivery(String stableId, String maidName, String itemName) {
        if (stableId == null || stableId.isBlank()) {
            return;
        }
        participants.put(stableId, maidName == null ? "" : maidName);
        deliverers.add(stableId);
        lastDelivererStableId = stableId;
        String key = itemName == null || itemName.isBlank() ? "（未知道具）" : itemName;
        consumption.merge(key, 1, Integer::sum);
    }

    // ==================== 读取 ====================

    public String playerName() {
        return playerName;
    }

    public long lastActivityTick() {
        return lastActivityTick;
    }

    /** 事件内成功救援次数（1 起）。 */
    public int rescueSeq() {
        return rescueSeq;
    }

    /** 记账区间内的死亡次数。 */
    public int deathCount() {
        return deathCount;
    }

    /** 记账区间内的成功救援次数。 */
    public int rescueCount() {
        return rescueCount;
    }

    /** 记账区间内"两只女仆一起动手"的次数。 */
    public int coopRescueCount() {
        return coopRescueCount;
    }

    /** 是否已经进入"不再调 LLM、只记账"的区间。 */
    public boolean counted() {
        return rescueSeq > RescueStreakManager.NARRATE_LIMIT;
    }

    /** 消耗明细（只读，顺序稳定）。 */
    public Map<String, Integer> consumption() {
        return Collections.unmodifiableMap(consumption);
    }

    /** 参与过记账区间救援的女仆：stableId -> 显示名（只读）。 */
    public Map<String, String> participants() {
        return Collections.unmodifiableMap(participants);
    }

    /** 真的投递过药水的女仆 stableId（只有这些能当总结收件人）。 */
    public Set<String> deliverers() {
        return Collections.unmodifiableSet(deliverers);
    }

    /** 最近一次投递药水的女仆 stableId；还没人投递过则为 {@code null}。 */
    @Nullable
    public String lastDelivererStableId() {
        return lastDelivererStableId;
    }

    public boolean lowPotionWarned() {
        return lowPotionWarned;
    }

    /** 标记"濒危提醒已经说过"，之后本事件内不再重复。 */
    void markLowPotionWarned() {
        this.lowPotionWarned = true;
    }
}
