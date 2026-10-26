package com.huhutalk.core.archive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 手机档案页的纯 Java 请求状态机。
 *
 * <p>同一时刻最多一个 pending 请求；只有 requestId、stableId、当前连接
 * serverWorldId 全部匹配的响应才会落地。关闭页面、重开、换女仆、连接重置
 * （登录/登出/切世界）都会让迟到响应失效。刷新会清空已访问 cursor 链；
 * “较近”通过已访问 cursor 栈重新取回上一页，不累计无限消息。</p>
 *
 * <p>本类不引用 Minecraft、不发送网络包、不读写本机聊天历史；客户端控制器只负责
 * 把这里产生的请求交给 NeoForge，并把响应交回来。</p>
 */
public final class ArchiveHistorySession {

    /** 一次响应是否真正作用到了当前页。 */
    public enum Apply {
        APPLIED,
        IGNORED
    }

    private record Cursor(long beforeSequence, long upperSequence) {
    }

    private int generation;
    private boolean active;
    private String stableId;
    /** 当前连接已确认的服务端 originWorldId；首次成功响应前为 null。 */
    private String serverWorldId;
    private ArchiveHistoryRequest pending;
    private ArchiveHistoryResponse page;
    private Cursor currentCursor;
    private final Deque<Cursor> previousCursors = new ArrayDeque<>();
    private boolean pendingOlder;
    private boolean pendingNewer;

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

    public ArchiveHistoryRequest pendingRequest() {
        return pending;
    }

    public ArchiveHistoryResponse page() {
        return page;
    }

    /**
     * 连接代次变化：登录、登出或切换世界时调用。
     * 旧代次的 pending 与已显示页面全部作废。
     */
    public void onConnectionReset() {
        generation++;
        active = false;
        stableId = null;
        serverWorldId = null;
        pending = null;
        page = null;
        currentCursor = null;
        previousCursors.clear();
        pendingOlder = false;
        pendingNewer = false;
    }

    /** 打开档案页：重置 cursor 链并请求最新一页。 */
    public Optional<ArchiveHistoryRequest> open(String maidStableId) {
        String id = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        active = true;
        stableId = id;
        page = null;
        pending = null;
        currentCursor = null;
        previousCursors.clear();
        pendingOlder = false;
        pendingNewer = false;
        return Optional.of(send(ArchiveHistoryRequest.LATEST, ArchiveHistoryRequest.LATEST));
    }

    /** 关闭档案页：晚到的响应不再落到页面。 */
    public void close(String maidStableId) {
        if (stableId != null && stableId.equals(maidStableId)) {
            active = false;
            pending = null;
            page = null;
            currentCursor = null;
            previousCursors.clear();
            pendingOlder = false;
            pendingNewer = false;
        }
    }

    /** 刷新：清空 cursor 链，重新按“最新”请求；之后新增记录只会在刷新后出现。 */
    public Optional<ArchiveHistoryRequest> refresh() {
        if (!active) {
            return Optional.empty();
        }
        page = null;
        currentCursor = null;
        previousCursors.clear();
        pendingOlder = false;
        pendingNewer = false;
        return Optional.of(send(ArchiveHistoryRequest.LATEST, ArchiveHistoryRequest.LATEST));
    }

    /** 向更早翻页：只允许一个 pending，且必须有真实剩余。 */
    public Optional<ArchiveHistoryRequest> older() {
        if (!active || pending != null || page == null || !page.status().isSuccess()
                || !page.hasMore() || page.nextBeforeSequence() < 0
                || page.upperSequence() < 0) {
            return Optional.empty();
        }
        pendingOlder = true;
        pendingNewer = false;
        return Optional.of(send(page.nextBeforeSequence(), page.upperSequence()));
    }

    /** 通过已访问 cursor 栈向更近翻页。 */
    public Optional<ArchiveHistoryRequest> newer() {
        if (!active || pending != null || previousCursors.isEmpty()) {
            return Optional.empty();
        }
        Cursor target = previousCursors.peek();
        pendingNewer = true;
        pendingOlder = false;
        return Optional.of(send(target.beforeSequence(), target.upperSequence()));
    }

    public boolean isLoading() {
        return active && pending != null;
    }

    public boolean hasOlder() {
        return active && pending == null && page != null && page.status().isSuccess()
                && page.hasMore() && page.nextBeforeSequence() >= 0
                && page.upperSequence() >= 0;
    }

    public boolean hasNewer() {
        return active && pending == null && !previousCursors.isEmpty();
    }

    public List<ArchiveHistoryRecord> records() {
        return page == null ? List.of() : page.records();
    }

    public ArchiveHistoryStatus status() {
        return page == null ? null : page.status();
    }

    /**
     * 尝试应用一个响应。校验顺序：活跃页面 → pending 存在 → requestId →
     * stableId → 当前连接 serverWorldId。任一不符都保持当前页不变。
     */
    public Apply accept(ArchiveHistoryResponse response) {
        if (!active || pending == null || response == null) {
            return Apply.IGNORED;
        }
        if (!pending.requestId().equals(response.requestId())) {
            return Apply.IGNORED;
        }
        if (!stableId.equals(response.maidStableId())) {
            return Apply.IGNORED;
        }
        boolean success = response.status().isSuccess();
        if (serverWorldId == null) {
            if (success && response.serverWorldId().isEmpty()) {
                return Apply.IGNORED;
            }
        } else if (!response.serverWorldId().isEmpty()
                && !serverWorldId.equals(response.serverWorldId())) {
            // 不同服务器/不同连接世界的迟到响应不能污染当前页。
            return Apply.IGNORED;
        }

        ArchiveHistoryRequest completed = pending;
        if (success) {
            if (serverWorldId == null) {
                serverWorldId = response.serverWorldId();
            }
            if (pendingNewer) {
                currentCursor = previousCursors.pop();
            } else {
                if (pendingOlder && currentCursor != null) {
                    previousCursors.push(currentCursor);
                }
                currentCursor = new Cursor(completed.beforeSequence(),
                        response.upperSequence());
            }
        }
        page = response;
        pending = null;
        pendingOlder = false;
        pendingNewer = false;
        return Apply.APPLIED;
    }

    private ArchiveHistoryRequest send(long beforeSequence, long upperSequence) {
        ArchiveHistoryRequest request = new ArchiveHistoryRequest(
                UUID.randomUUID().toString(),
                stableId,
                serverWorldId == null ? "" : serverWorldId,
                beforeSequence,
                upperSequence,
                ArchiveHistoryRequest.MAX_LIMIT);
        pending = request;
        return request;
    }
}
