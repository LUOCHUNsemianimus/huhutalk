package com.huhutalk.core.archive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 档案历史协议的纯 Java 核心：字段校验、按 sequence 的固定上界分页与传输预算。
 *
 * <p>分页规则：先按本次固定上界 {@code upperSequence} 过滤，再按 sequence 取最新
 * 至多 20 条，最后反转成时间正序展示。若 JSON 字符/UTF-8 字节预算装不下，
 * 从本页最早的一条开始收缩；收缩后 {@code nextBeforeSequence} 指向本页最早一条，
 * 下一轮继续向更早取，因此不会重不会漏。单条最新记录本身就超预算时返回
 * {@link ArchiveHistoryStatus#TOO_LARGE}，绝不截断原文。</p>
 *
 * <p>本类不读写文件、不持有 MinecraftServer，也不复制 {@link ArchiveStore} 的持久化逻辑。</p>
 */
public final class ArchiveHistoryProtocol {
    public static final int PROTOCOL_SCHEMA_VERSION = 1;
    public static final int MAX_PAGE_SIZE = 20;
    /** 响应 JSON 字符上限（与网络包 stringUtf8 上限一致）。 */
    public static final int MAX_JSON_CHARS = 60_000;
    /** 响应 JSON UTF-8 字节上限；留足余量，远低于 S2C 载荷限制。 */
    public static final int MAX_UTF8_BYTES = 180 * 1024;

    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern WORLD_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private ArchiveHistoryProtocol() {
    }

    static String requireRequestId(String value) {
        if (value == null || !REQUEST_ID.matcher(value).matches()) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    "requestId is not a safe identifier: " + value);
        }
        return value;
    }

    static String requireStableId(String value) {
        return ArchiveIds.requireStableId(value, "maidStableId");
    }

    static String requireOptionalWorldId(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!WORLD_ID.matcher(value).matches()) {
            throw new ArchiveException(ArchiveException.Code.INVALID_IDENTIFIER,
                    "serverWorldId is not a safe identifier: " + value);
        }
        return value;
    }

    public static boolean isValidRequestId(String value) {
        return value != null && REQUEST_ID.matcher(value).matches();
    }

    public static boolean isValidStableId(String value) {
        if (value == null) {
            return false;
        }
        try {
            ArchiveIds.requireStableId(value, "maidStableId");
            return true;
        } catch (ArchiveException exception) {
            return false;
        }
    }

    /**
     * 构造一页响应。{@code snapshot} 为 {@code null} 表示缺档。
     *
     * @param serverWorldId 当前服务器的 {@link ArchiveService#originWorldId}，
     *                      不是归档初建时的 originWorldId
     */
    public static ArchiveHistoryResponse buildPage(String requestId, String stableId,
                                                    String serverWorldId,
                                                    ArchiveSnapshot snapshot,
                                                    ArchiveHistoryRequest request) {
        if (request == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history request must not be null");
        }
        if (!request.requestId().equals(requestId)) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "requestId does not match the request");
        }
        if (!request.maidStableId().equals(stableId)) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "stableId does not match the request");
        }
        String worldId = requireOptionalWorldId(serverWorldId);
        if (worldId.isEmpty()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "serverWorldId must not be empty for a page response");
        }
        if (snapshot != null && !snapshot.maidStableId().equals(stableId)) {
            throw new ArchiveException(ArchiveException.Code.OWNERSHIP,
                    "archive snapshot does not belong to the requested maid");
        }
        if (snapshot == null) {
            return new ArchiveHistoryResponse(requestId, stableId, worldId,
                    ArchiveHistoryStatus.NO_ARCHIVE,
                    ArchiveHistoryRequest.LATEST, ArchiveHistoryRequest.LATEST, false,
                    true, List.of(), List.of());
        }

        List<ArchiveHistoryRecord> eligible = new ArrayList<>();
        for (ArchiveRecord record : snapshot.records()) {
            if (record.role() == ArchiveRole.PLAYER || record.role() == ArchiveRole.MAID) {
                eligible.add(new ArchiveHistoryRecord(record.messageId(), record.role().name(),
                        record.text(), record.sequence()));
            }
        }
        eligible.sort(Comparator.comparingLong(ArchiveHistoryRecord::sequence));
        if (eligible.isEmpty()) {
            return new ArchiveHistoryResponse(requestId, stableId, worldId,
                    ArchiveHistoryStatus.EMPTY,
                    ArchiveHistoryRequest.LATEST, ArchiveHistoryRequest.LATEST, false,
                    snapshot.coverage().partial(), snapshot.coverage().scopes(), List.of());
        }

        long actualMax = eligible.get(eligible.size() - 1).sequence();
        long upper = request.upperSequence() == ArchiveHistoryRequest.LATEST
                ? actualMax : request.upperSequence();
        if (upper > actualMax) {
            return ArchiveHistoryResponse.error(requestId, stableId, worldId,
                    ArchiveHistoryStatus.INVALID_REQUEST);
        }

        // 先按固定上界筛选，再从新到旧取至多 limit 条；随后反转为时间正序。
        List<ArchiveHistoryRecord> candidates = new ArrayList<>();
        for (int i = eligible.size() - 1; i >= 0; i--) {
            ArchiveHistoryRecord record = eligible.get(i);
            if (record.sequence() > upper) {
                continue;
            }
            if (request.beforeSequence() != ArchiveHistoryRequest.LATEST
                    && record.sequence() >= request.beforeSequence()) {
                continue;
            }
            candidates.add(record);
        }
        if (candidates.isEmpty()) {
            return new ArchiveHistoryResponse(requestId, stableId, worldId,
                    ArchiveHistoryStatus.OK, upper, ArchiveHistoryRequest.LATEST, false,
                    snapshot.coverage().partial(), snapshot.coverage().scopes(), List.of());
        }

        int take = Math.min(request.limit(), candidates.size());
        List<ArchiveHistoryRecord> selected = new ArrayList<>(candidates.subList(0, take));
        selected.sort(Comparator.comparingLong(ArchiveHistoryRecord::sequence));
        while (true) {
            boolean hasMore = candidates.size() > selected.size();
            long nextBefore = selected.isEmpty()
                    ? ArchiveHistoryRequest.LATEST : selected.get(0).sequence();
            ArchiveHistoryResponse response = new ArchiveHistoryResponse(
                    requestId, stableId, worldId, ArchiveHistoryStatus.OK, upper,
                    nextBefore, hasMore, snapshot.coverage().partial(),
                    snapshot.coverage().scopes(), selected);
            if (fits(response.toJson())) {
                return response;
            }
            if (selected.size() <= 1) {
                return ArchiveHistoryResponse.error(requestId, stableId, worldId,
                        ArchiveHistoryStatus.TOO_LARGE);
            }
            // 预算不足时从本页最早一条开始收缩；剩余更早记录留到下一页的 cursor。
            selected.remove(0);
        }
    }

    public static boolean fits(String json) {
        return json != null
                && json.length() <= MAX_JSON_CHARS
                && json.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    public static ArchiveHistoryResponse decodeResponse(String json) {
        if (json == null || json.isBlank()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive history payload must not be blank");
        }
        return ArchiveHistoryResponse.fromJson(json);
    }
}
