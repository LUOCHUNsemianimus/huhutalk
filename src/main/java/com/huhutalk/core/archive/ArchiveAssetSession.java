package com.huhutalk.core.archive;

import java.util.Optional;
import java.util.UUID;

/**
 * 手机档案图片查看的纯 Java 状态机：同一时刻最多一个 pending、一张图。
 *
 * <p>响应必须同时匹配 requestId、stableId、非空 serverWorldId 与 hash；关闭查看、
 * 重开、连接重置（登录/登出/换世界）都会让迟到响应失效。重试只由用户点击触发，
 * 不自动循环。</p>
 */
public final class ArchiveAssetSession {
    public enum Apply {
        APPLIED,
        IGNORED
    }

    private int generation;
    private boolean active;
    private String stableId;
    private String serverWorldId;
    private ArchiveAssetRequest pending;
    private ArchiveAssetResponse response;

    public int generation() {
        return generation;
    }

    public boolean isActive() {
        return active;
    }

    public String stableId() {
        return stableId;
    }

    public String serverWorldId() {
        return serverWorldId;
    }

    public ArchiveAssetRequest pendingRequest() {
        return pending;
    }

    public ArchiveAssetResponse response() {
        return response;
    }

    public boolean isLoading() {
        return active && pending != null;
    }

    /** 打开一张图：绑定当前会话世界后发一次请求。 */
    public Optional<ArchiveAssetRequest> open(String maidStableId, String currentServerWorldId,
                                              String assetHash) {
        String id = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        String world = ArchiveHistoryProtocol.requireOptionalWorldId(currentServerWorldId);
        if (world.isEmpty()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "current server world id is required to request an archive asset");
        }
        active = true;
        stableId = id;
        serverWorldId = world;
        response = null;
        pending = null;
        return Optional.of(send(assetHash));
    }

    /** 用户点击重试：仅在没有 pending、上一响应失败且 hash 未变时允许。 */
    public Optional<ArchiveAssetRequest> retry(String assetHash) {
        if (!canRetry()) {
            return Optional.empty();
        }
        if (assetHash == null || !assetHash.equals(response.assetHash())) {
            return Optional.empty();
        }
        response = null;
        return Optional.of(send(assetHash));
    }

    public boolean canRetry() {
        return active && pending == null && response != null && !response.status().isSuccess();
    }

    public void close() {
        active = false;
        stableId = null;
        serverWorldId = null;
        pending = null;
        response = null;
    }

    public void onConnectionReset() {
        generation++;
        close();
    }

    /**
     * 应用一个响应。校验顺序：活跃 → pending → requestId → stableId →
     * serverWorldId → hash。任一不符都保持当前状态不变。
     */
    public Apply accept(ArchiveAssetResponse incoming) {
        if (!active || pending == null || incoming == null) {
            return Apply.IGNORED;
        }
        if (!pending.requestId().equals(incoming.requestId())) {
            return Apply.IGNORED;
        }
        if (!stableId.equals(incoming.maidStableId())) {
            return Apply.IGNORED;
        }
        if (!serverWorldId.equals(incoming.serverWorldId())) {
            return Apply.IGNORED;
        }
        if (!pending.assetHash().equals(incoming.assetHash())) {
            return Apply.IGNORED;
        }
        response = incoming;
        pending = null;
        return Apply.APPLIED;
    }

    /**
     * 一个无法解码的畸形回包：只有四个身份字段都严格匹配当前 pending 时才终止它。
     * 任何不匹配（包括迟到请求、旧世界、错 hash）都保持 pending 不变，避免误伤新请求。
     */
    public Apply failPending(String requestId, String maidStableId, String serverWorldId,
                             String assetHash) {
        if (!active || pending == null || requestId == null || maidStableId == null
                || assetHash == null) {
            return Apply.IGNORED;
        }
        if (!pending.requestId().equals(requestId)
                || !stableId.equals(maidStableId)
                || !this.serverWorldId.equals(serverWorldId)
                || !pending.assetHash().equals(assetHash)) {
            return Apply.IGNORED;
        }
        response = ArchiveAssetResponse.error(pending.requestId(), stableId, this.serverWorldId,
                ArchiveAssetStatus.ERROR, assetHash);
        pending = null;
        return Apply.APPLIED;
    }

    /**
     * 已接受的 OK 响应在本地解码失败：用同一 request/身份/hash 的 ERROR 替掉原响应并清空字节。
     * 只接受当前已保存的那一条响应；关闭/重开或已重试后的旧失败不能释放新图。
     */
    public Apply markDecodeFailure(ArchiveAssetResponse accepted) {
        if (!active || pending != null || response == null || accepted == null) {
            return Apply.IGNORED;
        }
        if (!response.status().isSuccess()
                || !accepted.status().isSuccess()
                || !response.requestId().equals(accepted.requestId())
                || !response.assetHash().equals(accepted.assetHash())) {
            return Apply.IGNORED;
        }
        response = ArchiveAssetResponse.error(response.requestId(), response.maidStableId(),
                response.serverWorldId(), ArchiveAssetStatus.ERROR, response.assetHash());
        return Apply.APPLIED;
    }

    private ArchiveAssetRequest send(String assetHash) {
        ArchiveAssetRequest request = new ArchiveAssetRequest(UUID.randomUUID().toString(),
                stableId, serverWorldId, assetHash);
        pending = request;
        return request;
    }
}
