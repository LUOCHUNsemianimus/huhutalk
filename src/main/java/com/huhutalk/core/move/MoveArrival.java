package com.huhutalk.core.move;

/**
 * 移动目标到达后的动作。
 * <ul>
 *   <li>{@link #BACK}：回到主人身边 → 跟随主人 + 切回移动前任务（"过来"）。</li>
 *   <li>{@link #MEET}：去往目标点并<b>等待</b>，玩家进入目标点附近后自动跟随（"去某地等我"，默认）。</li>
 *   <li>{@link #WAIT_IDLE}：到目标点切空闲（不守家）。</li>
 *   <li>{@link #PROTECT}：到目标点切入近战攻击模式（"去某地保护我"）。</li>
 *   <li>{@link #NONE}：到达后停在原地，保持当前状态。</li>
 * </ul>
 */
public enum MoveArrival {
    NONE,
    BACK,
    MEET,
    WAIT_IDLE,
    PROTECT
}
