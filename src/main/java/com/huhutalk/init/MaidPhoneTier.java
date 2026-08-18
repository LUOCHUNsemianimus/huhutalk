package com.huhutalk.init;

/**
 * 手机等级（档位）。
 * <p>
 * 决定女仆的可达性（是否被强制加载）：
 * <ul>
 *   <li>{@link #WATCH}：电话手表（青春版）。本身<b>没有</b>强制加载票据，
 *       仅当女仆位于 WiFi 方块的覆盖范围内时，才由票据管理器对其所在区块下发 1×1 票据。</li>
 *   <li>{@link #SMART}：智能手机（Pro 版）。<b>永远</b>持有强制加载票据，
 *       女仆可以放置在任意位置而不会因区块卸载而掉线。</li>
 * </ul>
 */
public enum MaidPhoneTier {
    WATCH,
    SMART
}
