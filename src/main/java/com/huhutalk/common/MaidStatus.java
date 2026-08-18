package com.huhutalk.common;

/**
 * 女仆联系人状态。
 * <p>
 * - ONLINE：实体已加载且持有手机，可直达聊天
 * - OFFLINE：实体未加载（区块卸载/无强制加载票据），消息入离线队列
 * - DEAD：已确认死亡（MaidDeathEvent 触发），墓碑/胶片待复活
 * - SHUTDOWN：在簿但当前未佩戴手机（"已关机"），不可被远程联系
 */
public enum MaidStatus {
    ONLINE,
    OFFLINE,
    DEAD,
    SHUTDOWN;

    /** 将枚举数字转为 MaidStatus，未知值按 OFFLINE 兜底 */
    public static MaidStatus fromId(int id) {
        MaidStatus[] values = values();
        if (id >= 0 && id < values.length) {
            return values[id];
        }
        return OFFLINE;
    }
}