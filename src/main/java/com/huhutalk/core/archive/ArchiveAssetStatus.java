package com.huhutalk.core.archive;

/** 按需读取档案图片的结果状态；非 OK 响应不携带任何图片字节。 */
public enum ArchiveAssetStatus {
    OK,
    /** 快照未引用该 hash，或资产文件不存在。 */
    MISSING,
    /** 请求者不是该女仆当前主人，或 hash 未被该会话引用。 */
    NO_PERMISSION,
    /** 客户端携带的 serverWorldId 与当前连接不一致。 */
    STALE_WORLD,
    /** 每玩家 5 tick 至多一次读取的速率限制命中。 */
    RATE_LIMITED,
    /** 请求字段非法。 */
    INVALID_REQUEST,
    /** 服务端读取/校验失败，明细只写服务端日志。 */
    ERROR;

    public boolean isSuccess() {
        return this == OK;
    }

    public static ArchiveAssetStatus fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive asset status must not be null");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "unknown archive asset status: " + value, exception);
        }
    }
}
