package com.huhutalk.core.move;

/**
 * 移动目标到达后的动作。
 * <ul>
 *   <li>{@link #NONE}：到达后停在原地，保持"移动"模式（不额外切换）。</li>
 *   <li>{@link #WAIT_IDLE}：到达后切回空闲模式（"去那里等我"）。</li>
 *   <li>{@link #PROTECT}：到达后切入近战攻击模式（"去那里保护我"）。</li>
 * </ul>
 */
public enum MoveArrival {
    NONE,
    WAIT_IDLE,
    PROTECT
}
