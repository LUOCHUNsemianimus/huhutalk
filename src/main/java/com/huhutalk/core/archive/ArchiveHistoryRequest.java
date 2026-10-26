package com.huhutalk.core.archive;

/**
 * 客户端 → 服务端的档案分页请求（纯 Java，可脱离 Minecraft 校验）。
 *
 * <p>{@code beforeSequence == -1} 表示取最新一页；否则按 sequence 排他地取更早记录。
 * {@code upperSequence == -1} 只允许出现在最新一页请求上，由服务端固定本次快照上界；
 * 后续翻页必须显式携带同一个上界，服务端不会偷偷扩大范围。</p>
 *
 * <p>身份不从请求里取：服务端始终使用 {@code context.player.getUUID()}，
 * 本请求没有 ownerUuid 字段，避免客户端冒充他人。</p>
 */
public record ArchiveHistoryRequest(
        String requestId,
        String maidStableId,
        String expectedServerWorldId,
        long beforeSequence,
        long upperSequence,
        int limit
) {
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 20;
    public static final int MAX_REQUEST_ID_CHARS = 64;
    public static final int MAX_STABLE_ID_CHARS = 128;
    public static final int MAX_WORLD_ID_CHARS = 128;
    public static final long LATEST = -1L;

    public ArchiveHistoryRequest {
        requestId = ArchiveHistoryProtocol.requireRequestId(requestId);
        maidStableId = ArchiveHistoryProtocol.requireStableId(maidStableId);
        expectedServerWorldId = ArchiveHistoryProtocol.requireOptionalWorldId(expectedServerWorldId);
        if (beforeSequence < LATEST) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "beforeSequence must be -1 or non-negative");
        }
        if (upperSequence < LATEST) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "upperSequence must be -1 or non-negative");
        }
        if (upperSequence == LATEST && beforeSequence != LATEST) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "a latest-page request must use beforeSequence=-1");
        }
        // 允许 beforeSequence == upperSequence + 1 作为排他 cursor；直接比较
        // beforeSequence - 1 可避免 upperSequence == Long.MAX_VALUE 时 +1 溢出。
        if (beforeSequence != LATEST && upperSequence != LATEST
                && beforeSequence - 1L > upperSequence) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "beforeSequence must not exceed upperSequence + 1");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }

    public boolean isLatestPage() {
        return beforeSequence == LATEST;
    }
}
