package com.huhutalk.core.supply;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一只女仆的一次「远程补给」工作期（服务端内存态）。
 *
 * <h2>生命期</h2>
 * 女仆的任务被切成 {@link TaskRemoteSupply} 时开启，被切成别的任务时结束（结算并清掉）。
 * 中间若被 {@code move_to} 之类的<b>瞬态任务</b>借走，则进入 {@link #isPaused() 暂停}：
 * 不结账、不再补给，等她回到补给任务再继续 —— 否则玩家叫她去搬个东西就会刷出一堆假总结。
 *
 * <h2>计数（需求 2.3.1）</h2>
 * 只统计"这段时间里、由补给动作真正消耗掉"的物品，按<b>物品显示名</b>分组累计。
 * 工作期结束时把这份明细交给 LLM（手动切走 → 立即触发一次；程序/LLM 切走 → 只写记忆，
 * 见 {@link RemoteSupplyNarrative}）。
 *
 * <h2>为什么区分"手动切走"</h2>
 * 手动切走（玩家在 GUI 里改模式）要让她<b>说一句</b>；而如果是模型自己用
 * {@code switch_work_task} 切走，再额外唤一次 LLM 就会变成"她刚说关了、过会儿又说一遍"
 * 的双重调用（需求 2.3.3 明确要避免）。判据见 {@link #markManualSwitch(String, long)}。
 */
public final class RemoteSupplySession {

    /** 归属女仆（stableId：全模组主键约定，死亡/胶片复活后不变） */
    private final String maidStableId;
    /** 主人 UUID */
    private final UUID playerId;

    /** 是否被瞬态任务借走（不结账、不补给） */
    private boolean paused;
    /** 最近一次成功补给的时间（供"最近还在补给的优先开口"用） */
    private long lastSupplyTick;

    /** 这一段消耗掉的物品：显示名 -> 个数（LinkedHashMap 保证文案顺序稳定） */
    private final Map<String, Integer> consumption = new LinkedHashMap<>();

    /**
     * {@code MaidTaskEnableEvent} 观察到的"本次切换是玩家手动发起的"，值为目标任务 uid。
     * <p>TLM 只在<b>手动切换</b>那条路径 post 该事件；LLM 的 {@code switch_work_task} 不会。
     * 所以"任务变了但这里没记录"就等价于"是模型/程序切走的"。
     */
    private String pendingManualTaskUid;
    private long pendingManualTick;

    RemoteSupplySession(String maidStableId, UUID playerId, long openTick) {
        this.maidStableId = maidStableId;
        this.playerId = playerId;
        this.lastSupplyTick = openTick;
    }

    // ==================== 状态 ====================

    public String maidStableId() {
        return maidStableId;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean isPaused() {
        return paused;
    }

    public void pause() {
        this.paused = true;
    }

    public void resume() {
        this.paused = false;
    }

    public long lastSupplyTick() {
        return lastSupplyTick;
    }

    /** 记一次成功补给的时刻（结算时用来挑"最近还在干活的那只"开口）。 */
    public void markSupplied(long nowTick) {
        this.lastSupplyTick = nowTick;
    }

    /** 记一次由补给消耗掉的物品（按显示名累计）。 */
    public void countConsume(String itemName) {
        String key = itemName == null || itemName.isBlank() ? "（未知物品）" : itemName;
        consumption.merge(key, 1, Integer::sum);
    }

    /** 这一段消耗掉的物品明细（只读，顺序稳定）。 */
    public Map<String, Integer> consumption() {
        return Collections.unmodifiableMap(consumption);
    }

    // ==================== 手动 / 程序切换的判别 ====================

    /** 记下"这次任务切换是玩家手动发起的"，目标任务 uid（由 {@code MaidTaskEnableEvent} 提供）。 */
    void markManualSwitch(String targetTaskUid, long nowTick) {
        this.pendingManualTaskUid = targetTaskUid;
        this.pendingManualTick = nowTick;
    }

    /**
     * 取出并清除"手动切换"标记；超时（同一 tick 附近才算）或与目标不符时返回 {@code null}。
     * <p>为什么允许超时：事件在 {@code maid.setTask} <b>之前</b> post，而它又是可取消的，
     * 所以真正的判定要等我们下一次观察到任务确实变了才做。
     */
    @Nullable
    public String consumeManualSwitch(String actualTaskUid, long nowTick, long toleranceTicks) {
        if (pendingManualTaskUid == null) {
            return null;
        }
        boolean fresh = nowTick - pendingManualTick <= toleranceTicks;
        boolean matches = pendingManualTaskUid.equals(actualTaskUid);
        String recorded = pendingManualTaskUid;
        pendingManualTaskUid = null;
        return (fresh && matches) ? recorded : null;
    }
}
