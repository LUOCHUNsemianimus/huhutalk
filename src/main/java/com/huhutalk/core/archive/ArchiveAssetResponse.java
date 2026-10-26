package com.huhutalk.core.archive;

import java.util.Arrays;

/**
 * 服务端 → 客户端的单张档案图片响应（纯 Java，二进制字节不 base64 塞 JSON）。
 *
 * <p>非 OK 状态必须携带空字节；OK 状态必须携带不超过
 * {@link ArchiveAssetStore#MAX_ASSET_BYTES} 的 PNG，且 hash 与实际字节一致、PNG 头
 * 与尺寸预算通过校验。构造时防御复制，读取时再复制一次。</p>
 */
public record ArchiveAssetResponse(
        String requestId,
        String maidStableId,
        String serverWorldId,
        ArchiveAssetStatus status,
        String assetHash,
        byte[] data
) {
    public ArchiveAssetResponse {
        requestId = ArchiveHistoryProtocol.requireRequestId(requestId);
        maidStableId = ArchiveHistoryProtocol.requireStableId(maidStableId);
        serverWorldId = ArchiveHistoryProtocol.requireOptionalWorldId(serverWorldId);
        if (status == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive asset status must not be null");
        }
        if (data == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive asset data must not be null");
        }
        if (status.isSuccess()) {
            if (serverWorldId.isEmpty()) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "successful asset response requires serverWorldId");
            }
            assetHash = ArchiveAssetStore.requireHash(assetHash);
            if (data.length == 0) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "successful asset response must carry bytes");
            }
            if (data.length > ArchiveAssetStore.MAX_ASSET_BYTES) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "asset response exceeds " + ArchiveAssetStore.MAX_ASSET_BYTES + " bytes");
            }
            ArchiveAssetStore.validatePngBytes(data);
            if (!ArchiveAssetStore.sha256Hex(data).equals(assetHash)) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "asset response bytes do not match the declared hash");
            }
            data = Arrays.copyOf(data, data.length);
        } else {
            if (data.length != 0) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "non-OK asset response must not carry bytes");
            }
            data = new byte[0];
            if (assetHash != null && !assetHash.isEmpty()) {
                assetHash = ArchiveAssetStore.requireHash(assetHash);
            }
        }
    }

    public static ArchiveAssetResponse error(String requestId, String maidStableId,
                                             String serverWorldId, ArchiveAssetStatus status,
                                             String assetHash) {
        if (status == null || status.isSuccess()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "error response requires a non-success status");
        }
        return new ArchiveAssetResponse(requestId, maidStableId, serverWorldId, status,
                assetHash, new byte[0]);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}
