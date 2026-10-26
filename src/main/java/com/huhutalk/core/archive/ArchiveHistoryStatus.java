package com.huhutalk.core.archive;

/**
 * 手机档案读取的结果状态。
 *
 * <p>只使用枚举经网络传输，服务端不把磁盘路径、异常消息或堆栈发给客户端；
 * 客户端按枚举分别显示缺档、无记录、无权限、超限、繁忙与一般失败。记录范围包含
 * 玩家/女仆原话以及带结构化事件的观察/系统/工具记录。</p>
 */
public enum ArchiveHistoryStatus {
    /** 本次成功返回了一段（可能为空页，表示已经翻到头）。 */
    OK,
    /** 归档存在，但当前没有任何可展示的会话或结构化事件记录。 */
    EMPTY,
    /** 该玩家 + 女仆还没有服务端归档文件。 */
    NO_ARCHIVE,
    /** 请求者不是该女仆当前主人，也未在联系人簿中归属该玩家。 */
    NO_PERMISSION,
    /** 单条记录本身超过传输预算，无法在不截断原文的前提下返回。 */
    TOO_LARGE,
    /** 每玩家 5 tick 至多一次读取的速率限制命中。 */
    RATE_LIMITED,
    /** 请求字段非法（ID、cursor、limit 等）。 */
    INVALID_REQUEST,
    /** 客户端携带的 serverWorldId 与当前服务器不一致。 */
    STALE_WORLD,
    /** 服务端读取/解析归档失败，明细只写服务端日志。 */
    ERROR;

    public boolean isSuccess() {
        return this == OK || this == EMPTY;
    }

    public static ArchiveHistoryStatus fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive history status must not be null");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "unknown archive history status: " + value, exception);
        }
    }
}
