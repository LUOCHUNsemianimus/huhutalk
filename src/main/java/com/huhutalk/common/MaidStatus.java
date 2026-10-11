package com.huhutalk.common;

/**
 * 女仆联系人状态。
 * <p>
 * - ONLINE：实体已加载且持有手机，可直达聊天
 * - OFFLINE：实体未加载（区块卸载/无强制加载票据），消息入离线队列
 * - DEAD：已确认死亡（MaidDeathEvent 触发），墓碑/胶片待复活
 * - SHUTDOWN：在簿但当前未佩戴手机（"已关机"），不可被远程联系
 * - IN_CHARM：被玩家收进了魂符/胶片 —— <b>不算失联</b>（玩家自己知道她在哪）
 */
public enum MaidStatus {
    ONLINE,
    OFFLINE,
    DEAD,
    SHUTDOWN,
    /**
     * 魂符内：被收进魂符/胶片，不在世界里。
     * <p><b>为什么要单独一个状态</b>：她从魂符里被放出来时会经历 {@code IN_CHARM → ONLINE}；
     * 若不与"失联后回来"区分，就会触发一句"你刚刚离开了信号范围…"——
     * 可她只是被玩家揣兜里了，玩家当然知道。详见 §5.16。
     * <p>只能追加在枚举末尾：状态在存档里按<b>名称</b>存（{@code MaidContactEntry}），
     * 而 {@link #fromId} 用序号读，新增值只会在末尾。
     */
    IN_CHARM;

    /** 将枚举数字转为 MaidStatus，未知值按 OFFLINE 兜底 */
    public static MaidStatus fromId(int id) {
        MaidStatus[] values = values();
        if (id >= 0 && id < values.length) {
            return values[id];
        }
        return OFFLINE;
    }
}