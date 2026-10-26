package com.huhutalk.core.archive;

/**
 * 客户端 → 服务端的按需图片请求（纯 Java）。
 *
 * <p>身份不从请求取：服务端始终使用当前连接玩家的 UUID。requestId 用于匹配迟到
 * 响应；expectedServerWorldId 必须非空，避免把旧世界的图片回包落到新连接。</p>
 */
public record ArchiveAssetRequest(
        String requestId,
        String maidStableId,
        String expectedServerWorldId,
        String assetHash
) {
    public static final int MAX_REQUEST_ID_CHARS = 64;
    public static final int MAX_STABLE_ID_CHARS = 128;
    public static final int MAX_WORLD_ID_CHARS = 128;
    public static final int MAX_HASH_CHARS = 64;

    public ArchiveAssetRequest {
        requestId = ArchiveHistoryProtocol.requireRequestId(requestId);
        maidStableId = ArchiveHistoryProtocol.requireStableId(maidStableId);
        expectedServerWorldId = ArchiveHistoryProtocol.requireOptionalWorldId(expectedServerWorldId);
        if (expectedServerWorldId.isEmpty()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "expectedServerWorldId must not be empty for an asset request");
        }
        assetHash = ArchiveAssetStore.requireHash(assetHash);
    }
}
