package com.huhutalk.core.rescue;

/**
 * 一次「女仆 → 玩家」投递记录（服务端内存态，可复用的中枢结构）。
 *
 * <h2>为什么要单独抽出来</h2>
 * 「远程救援」与后续的「远程补给」工作模式在服务端是同一件事：
 * <b>女仆把某个补给品交给主人，并让这件事在聊天页留下可见记录</b>。
 * 两者的差别只在"送到哪里"：
 * <ul>
 *   <li>{@code used = true}：直接使用（药水喂/砸在主人身上），没有可领取的实体，
 *       气泡只作展示，不可点击。</li>
 *   <li>{@code used = false}：进收件箱，等主人点击领取（即现有的传文件流程）。</li>
 * </ul>
 * 因此把记录抽成结构体，工作模式 2 接入时只需换一个构造点，不必再动渲染与网络层。
 *
 * @param stableId   该次投递归属的会话（女仆 stableId）——决定气泡落在哪个聊天页
 * @param maidName   女仆显示名（叙事与文案用）
 * @param itemId     物品 registry id（客户端据此还原图标）
 * @param itemName   物品显示名
 * @param restored   本次为玩家恢复的状态量（血量点数；非治疗类为 0）
 * @param used       是否已被直接使用（true = 不可领取）
 * @param effectText 气泡上的效果文案
 * @param gameTime   投递发生时的世界时间（气泡时间标签用）
 */
public record MaidDeliveryRecord(
        String stableId,
        String maidName,
        String itemId,
        String itemName,
        int restored,
        boolean used,
        String effectText,
        long gameTime
) {
}
