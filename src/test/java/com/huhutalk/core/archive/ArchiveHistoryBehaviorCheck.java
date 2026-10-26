package com.huhutalk.core.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 纯 Java 行为检查（无 JUnit、无 Minecraft）：
 * 分页不漏不重、固定上界、乱序/稀疏 sequence、预算收缩、单条超限、
 * 空档/缺档/partial、坏字段与请求状态机。
 *
 * <p>上游可直接用 javac/java 运行本类的 main；不启动游戏，也不读写磁盘。</p>
 */
public final class ArchiveHistoryBehaviorCheck {
    private static final String OWNER = UUID.randomUUID().toString();
    private static final String MAID = "maid-1";
    private static final String MAID_2 = "maid-2";
    private static final String WORLD = "world-1";
    private static final String WORLD_2 = "world-2";

    private ArchiveHistoryBehaviorCheck() {
    }

    public static void main(String[] args) {
        multipageTraversalCoversEverythingOnce();
        refreshKeepsPinnedUpperBound();
        sparseAndOutOfOrderInputSortsBySequence();
        emptyNoArchiveAndPartialCoverage();
        budgetShrinksPageAndContinuationCursor();
        singleOversizedRecordFailsExplicitly();
        longSequencePaginationNearMaxValue();
        maxCursorValidationDoesNotOverflow();
        footerButtonsUseContentLocalGeometry();
        invalidRequestsAndSchemasAreRejected();
        sessionStateMachineRejectsStaleResponses();
        System.out.println("ArchiveHistoryBehaviorCheck OK");
    }

    // ==================== 分页 ====================

    private static void multipageTraversalCoversEverythingOnce() {
        List<ArchiveRecord> records = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            records.add(record(i, i % 2 == 0 ? ArchiveRole.PLAYER : ArchiveRole.MAID,
                    "message-" + i));
        }
        Collections.shuffle(records, new Random(7L));
        ArchiveSnapshot snapshot = snapshot(records);

        ArchiveHistoryRequest request = request("req-0", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse response = page(snapshot, request);
        List<Long> collected = new ArrayList<>();
        int pages = 0;
        while (true) {
            check(response.status() == ArchiveHistoryStatus.OK,
                    "multi-page status must be OK");
            assertAscending(response.records());
            collected.addAll(response.records().stream()
                    .map(ArchiveHistoryRecord::sequence).toList());
            pages++;
            if (!response.hasMore()) {
                break;
            }
            check(response.nextBeforeSequence() >= 0, "hasMore needs a next cursor");
            request = request("req-" + pages, MAID, WORLD,
                    response.nextBeforeSequence(), response.upperSequence(), 20);
            response = page(snapshot, request);
            check(pages < 100, "pagination must terminate");
        }
        check(pages == 3, "55 records / 20 should be 3 pages, got " + pages);
        check(collected.size() == 55, "must collect every record exactly once");
        List<Long> sorted = new ArrayList<>(collected);
        sorted.sort(Comparator.naturalOrder());
        for (int i = 0; i < 55; i++) {
            check(sorted.get(i) == i, "missing or duplicated sequence: " + i);
        }
    }

    private static void refreshKeepsPinnedUpperBound() {
        List<ArchiveRecord> records = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            records.add(record(i, ArchiveRole.PLAYER, "old-" + i));
        }
        ArchiveSnapshot first = snapshot(records);
        ArchiveHistoryRequest latest = request("req-a", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse firstPage = page(first, latest);
        check(firstPage.upperSequence() == 39L, "first page pins current max");

        List<ArchiveRecord> extended = new ArrayList<>(records);
        for (int i = 40; i < 45; i++) {
            extended.add(record(i, ArchiveRole.MAID, "new-" + i));
        }
        ArchiveSnapshot withNew = snapshot(extended);

        ArchiveHistoryRequest older = request("req-b", MAID, WORLD,
                firstPage.nextBeforeSequence(), firstPage.upperSequence(), 20);
        ArchiveHistoryResponse olderPage = page(withNew, older);
        long maxSeen = olderPage.records().stream()
                .mapToLong(ArchiveHistoryRecord::sequence).max().orElse(-1);
        check(maxSeen < 40, "pinned upper must exclude records added after the first read");

        ArchiveHistoryRequest refreshed = request("req-c", MAID, WORLD, -1, -1, 20);
        ArchiveHistoryResponse refreshedPage = page(withNew, refreshed);
        check(refreshedPage.records().get(refreshedPage.records().size() - 1).sequence() == 44L,
                "refresh must resolve a new upper bound");

        ArchiveHistoryRequest futureUpper = request("req-d", MAID, WORLD, 30, 50, 20);
        check(page(withNew, futureUpper).status() == ArchiveHistoryStatus.INVALID_REQUEST,
                "explicit upper beyond the archive must be rejected");
    }

    private static void sparseAndOutOfOrderInputSortsBySequence() {
        List<ArchiveRecord> records = new ArrayList<>(List.of(
                record(100, ArchiveRole.PLAYER, "a"),
                record(0, ArchiveRole.MAID, "b"),
                record(500, ArchiveRole.PLAYER, "c"),
                record(5, ArchiveRole.MAID, "d"),
                record(101, ArchiveRole.PLAYER, "e")));
        ArchiveSnapshot snapshot = snapshot(records);
        ArchiveHistoryRequest request = request("req-sparse", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse response = page(snapshot, request);
        assertAscending(response.records());
        check(response.records().size() == 5, "all sparse records should fit one page");
        check(response.records().get(0).sequence() == 0L, "lowest sequence first");
        check(response.records().get(4).sequence() == 500L, "highest sequence last");
        check(!response.hasMore(), "no hidden records");
    }

    private static void emptyNoArchiveAndPartialCoverage() {
        ArchiveHistoryRequest request = request("req-empty", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse noArchive = ArchiveHistoryProtocol.buildPage(
                request.requestId(), request.maidStableId(), WORLD, null, request);
        check(noArchive.status() == ArchiveHistoryStatus.NO_ARCHIVE,
                "null snapshot must be NO_ARCHIVE");
        check(noArchive.records().isEmpty(), "NO_ARCHIVE must not leak records");
        check(noArchive.coveragePartial(), "missing archive is still partial coverage");

        ArchiveSnapshot empty = snapshot(List.of());
        ArchiveHistoryResponse emptyPage = page(empty, request);
        check(emptyPage.status() == ArchiveHistoryStatus.EMPTY,
                "existing archive with no records must be EMPTY");
        check(emptyPage.records().isEmpty(), "EMPTY must not carry records");
        check(emptyPage.coveragePartial(), "coverage partial flag must be preserved");

        // 只有非对话角色时，本视图同样视为没有可展示的在线文字记录。
        ArchiveSnapshot onlyTool = snapshot(List.of(
                record(0, ArchiveRole.TOOL, "tool output")));
        check(page(onlyTool, request).status() == ArchiveHistoryStatus.EMPTY,
                "non-conversation roles are outside the read-only text view");
    }

    private static void budgetShrinksPageAndContinuationCursor() {
        List<ArchiveRecord> records = new ArrayList<>();
        String bigText = "a".repeat(4_000);
        for (int i = 0; i < 20; i++) {
            records.add(record(i, i % 2 == 0 ? ArchiveRole.PLAYER : ArchiveRole.MAID, bigText));
        }
        ArchiveSnapshot snapshot = snapshot(records);
        ArchiveHistoryRequest request = request("req-budget-0", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse first = page(snapshot, request);
        check(first.status() == ArchiveHistoryStatus.OK, "budget page should still be OK");
        check(first.records().size() < 20, "budget must shrink below the 20-row limit");
        check(first.hasMore(), "shrunk page must declare more older records");
        check(first.nextBeforeSequence() == first.records().get(0).sequence(),
                "continuation cursor must equal the earliest returned sequence");

        Set<Long> seen = new HashSet<>();
        List<Long> order = new ArrayList<>();
        int pages = 0;
        ArchiveHistoryResponse response = first;
        while (true) {
            assertAscending(response.records());
            for (ArchiveHistoryRecord record : response.records()) {
                check(seen.add(record.sequence()),
                        "budget pagination duplicated sequence " + record.sequence());
                order.add(record.sequence());
            }
            pages++;
            if (!response.hasMore()) {
                break;
            }
            request = request("req-budget-" + pages, MAID, WORLD,
                    response.nextBeforeSequence(), response.upperSequence(), 20);
            response = page(snapshot, request);
            check(pages < 100, "budget pagination must terminate");
        }
        check(order.size() == 20, "budget traversal must still cover all 20 records");
        for (int i = 0; i < 20; i++) {
            check(seen.contains((long) i), "budget traversal missed sequence " + i);
        }
    }

    private static void singleOversizedRecordFailsExplicitly() {
        ArchiveSnapshot snapshot = snapshot(List.of(
                record(0, ArchiveRole.PLAYER, "x".repeat(70_000))));
        ArchiveHistoryRequest request = request("req-too-large", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse response = page(snapshot, request);
        check(response.status() == ArchiveHistoryStatus.TOO_LARGE,
                "one oversized record must fail explicitly");
        check(response.records().isEmpty(), "TOO_LARGE must not silently truncate or return text");
    }

    // ==================== 协议校验 ====================

    private static void invalidRequestsAndSchemasAreRejected() {
        expectArchive(() -> request("req", MAID, "", -2, -1, 20),
                ArchiveException.Code.VALIDATION, "negative before cursor");
        expectArchive(() -> request("req", MAID, "", 5, -1, 20),
                ArchiveException.Code.VALIDATION, "latest request with a cursor");
        expectArchive(() -> request("req", MAID, "", -1, -1, 21),
                ArchiveException.Code.VALIDATION, "limit above page size");
        expectArchive(() -> request("req", MAID, "", -1, -1, 0),
                ArchiveException.Code.VALIDATION, "limit below one");
        expectArchive(() -> request("bad id!", MAID, "", -1, -1, 20),
                ArchiveException.Code.INVALID_IDENTIFIER, "unsafe request id");

        ArchiveSnapshot snapshot = snapshot(List.of(record(0, ArchiveRole.PLAYER, "hello")));
        ArchiveHistoryRequest request = request("req-schema", MAID, "", -1, -1, 20);
        ArchiveHistoryResponse response = page(snapshot, request);
        String goodJson = response.toJson();

        expectArchive(() -> ArchiveHistoryProtocol.decodeResponse(
                        goodJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
                ArchiveException.Code.UNSUPPORTED_SCHEMA, "unknown protocol schema");
        expectArchive(() -> ArchiveHistoryProtocol.decodeResponse(
                        "{\"schemaVersion\":1}"),
                ArchiveException.Code.CORRUPT, "missing response fields");
        expectArchive(() -> ArchiveHistoryProtocol.decodeResponse(
                        goodJson.replace("\"hasMore\":false", "\"hasMore\":\"no\"")),
                ArchiveException.Code.CORRUPT, "bad boolean field");
        expectArchive(() -> ArchiveSnapshot.fromJson(
                        snapshot.toJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")),
                ArchiveException.Code.UNSUPPORTED_SCHEMA, "unknown archive schema");

        ArchiveHistoryResponse roundTrip = ArchiveHistoryProtocol.decodeResponse(goodJson);
        check(roundTrip.requestId().equals(response.requestId()), "JSON round-trip requestId");
        check(roundTrip.records().size() == 1, "JSON round-trip record count");
    }

    // ==================== 请求状态机 ====================

    private static void sessionStateMachineRejectsStaleResponses() {
        List<ArchiveRecord> records = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            records.add(record(i, i % 2 == 0 ? ArchiveRole.PLAYER : ArchiveRole.MAID, "s-" + i));
        }
        ArchiveSnapshot snapshot = snapshot(records);

        ArchiveHistorySession session = new ArchiveHistorySession();
        ArchiveHistoryRequest open = session.open(MAID).orElseThrow();
        check(session.isLoading(), "open must leave one pending request");
        ArchiveHistoryResponse firstPage = build(open, MAID, WORLD, snapshot);
        check(session.accept(firstPage) == ArchiveHistorySession.Apply.APPLIED,
                "matching first response should apply");
        check(!session.isLoading(), "applied response clears pending");
        check(session.records().size() == 20, "first page holds one page only");
        check(session.hasOlder(), "more records should enable older");
        check(!session.hasNewer(), "first page has no newer target");
        check(session.accept(firstPage) == ArchiveHistorySession.Apply.IGNORED,
                "duplicate response must be idempotent/ignored");

        ArchiveHistoryRequest older = session.older().orElseThrow();
        check(session.older().isEmpty(), "only one pending request is allowed");
        check(session.isLoading(), "older request stays pending");
        ArchiveHistoryResponse olderPage = build(older, MAID, WORLD, snapshot);
        check(session.accept(olderPage) == ArchiveHistorySession.Apply.APPLIED,
                "older response should apply");
        check(session.hasNewer(), "older page should expose newer navigation");

        ArchiveHistoryRequest newer = session.newer().orElseThrow();
        ArchiveHistoryResponse newerPage = build(newer, MAID, WORLD, snapshot);
        check(session.accept(newerPage) == ArchiveHistorySession.Apply.APPLIED,
                "newer response should re-take the previous page");
        check(session.records().get(0).sequence() >= 20,
                "newer page must not fall back to the oldest page");

        // 关闭后迟到响应无效。
        ArchiveHistoryRequest late = session.older().orElseThrow();
        session.close(MAID);
        check(session.accept(build(late, MAID, WORLD, snapshot))
                == ArchiveHistorySession.Apply.IGNORED, "closed page ignores late response");

        // 重开：旧 requestId 的响应不污染新页面。
        ArchiveHistoryRequest reopened = session.open(MAID).orElseThrow();
        check(session.accept(build(late, MAID, WORLD, snapshot))
                == ArchiveHistorySession.Apply.IGNORED, "old requestId must be ignored");
        check(session.isLoading(), "reopened request still pending");
        check(session.accept(build(reopened, MAID, WORLD, snapshot))
                == ArchiveHistorySession.Apply.APPLIED, "reopened matching response applies");

        // 换 stableId：旧女仆的响应无效。
        ArchiveHistoryRequest forMaid1 = session.refresh().orElseThrow();
        ArchiveHistoryResponse maid1Response = build(forMaid1, MAID, WORLD, snapshot);
        session.open(MAID_2);
        check(session.accept(maid1Response) == ArchiveHistorySession.Apply.IGNORED,
                "response for another maid must be ignored");

        // 旧连接代次：重置后旧响应无效。
        session.onConnectionReset();
        int generation = session.generation();
        check(generation == 1, "connection reset increments generation");
        check(session.accept(maid1Response) == ArchiveHistorySession.Apply.IGNORED,
                "old generation response must be ignored");

        // 不同 serverWorldId：不污染当前页。
        ArchiveHistorySession worldSession = new ArchiveHistorySession();
        ArchiveHistoryRequest worldOpen = worldSession.open(MAID).orElseThrow();
        check(worldSession.accept(build(worldOpen, MAID, WORLD, snapshot))
                == ArchiveHistorySession.Apply.APPLIED, "bind current server world");
        ArchiveHistoryRequest afterRefresh = worldSession.refresh().orElseThrow();
        ArchiveHistoryResponse wrongWorld = build(afterRefresh, MAID, WORLD_2, snapshot);
        check(worldSession.accept(wrongWorld) == ArchiveHistorySession.Apply.IGNORED,
                "different serverWorldId response must be ignored");
        check(worldSession.isLoading(), "ignored response must leave the request pending");
        ArchiveHistoryResponse rightWorld = build(afterRefresh, MAID, WORLD, snapshot);
        check(worldSession.accept(rightWorld) == ArchiveHistorySession.Apply.APPLIED,
                "matching serverWorldId response applies");

        // 较近导航使用已访问 cursor，而不是重新请求“最新”从而跳过页。
        ArchiveHistorySession navigation = new ArchiveHistorySession();
        ArchiveHistoryRequest nav0 = navigation.open(MAID).orElseThrow();
        ArchiveHistoryResponse nav0Response = build(nav0, MAID, WORLD, snapshot);
        check(navigation.accept(nav0Response) == ArchiveHistorySession.Apply.APPLIED,
                "nav page 0");
        ArchiveHistoryRequest nav1 = navigation.older().orElseThrow();
        check(navigation.accept(build(nav1, MAID, WORLD, snapshot))
                == ArchiveHistorySession.Apply.APPLIED, "nav page 1");
        ArchiveHistoryRequest navBack = navigation.newer().orElseThrow();
        check(navBack.upperSequence() == nav0Response.upperSequence(),
                "newer reuses the pinned upper resolved by the first response");
        check(navBack.beforeSequence() == nav0.beforeSequence(),
                "newer reuses the visited cursor");

        // 首次响应固定上界后新增的记录，即使较近请求重新取最新页也不能出现。
        List<ArchiveRecord> extended = new ArrayList<>(records);
        for (int i = 45; i < 50; i++) {
            extended.add(record(i, ArchiveRole.PLAYER, "late-" + i));
        }
        ArchiveSnapshot withLate = snapshot(extended);
        ArchiveHistoryResponse navBackPage = build(navBack, MAID, WORLD, withLate);
        assertAscending(navBackPage.records());
        check(!navBackPage.records().isEmpty(), "pinned newer page must still return records");
        check(navBackPage.upperSequence() == nav0Response.upperSequence(),
                "pinned newer page must keep the first resolved upper");
        long navBackMax = navBackPage.records().get(navBackPage.records().size() - 1).sequence();
        check(navBackMax == nav0Response.upperSequence(),
                "pinned newer page must stop at the fixed upper");
        check(navBackPage.records().stream()
                        .noneMatch(record -> record.sequence() > nav0Response.upperSequence()),
                "messages added after the first response must not leak into the pinned page");
    }

    private static void longSequencePaginationNearMaxValue() {
        long max = Long.MAX_VALUE;
        List<ArchiveRecord> records = new ArrayList<>(List.of(
                record(max - 2, ArchiveRole.PLAYER, "near-max-0"),
                record(max - 1, ArchiveRole.MAID, "near-max-1"),
                record(max, ArchiveRole.PLAYER, "near-max-2")));
        ArchiveSnapshot snapshot = snapshot(records);

        ArchiveHistoryRequest latest = request("req-max-0", MAID, "", -1, -1, 2);
        ArchiveHistoryResponse first = page(snapshot, latest);
        check(first.status() == ArchiveHistoryStatus.OK, "MAX snapshot latest page must be OK");
        check(first.upperSequence() == max, "latest page must pin upper=MAX without overflow");
        check(first.records().size() == 2, "latest page must return two records");
        check(first.records().get(0).sequence() == max - 1
                        && first.records().get(1).sequence() == max,
                "latest page must be ascending MAX-1, MAX");
        check(first.hasMore(), "MAX snapshot latest page must declare older records");
        check(first.nextBeforeSequence() == max - 1, "MAX cursor must be MAX-1");

        ArchiveHistoryRequest older = request("req-max-1", MAID, WORLD,
                first.nextBeforeSequence(), first.upperSequence(), 2);
        ArchiveHistoryResponse second = page(snapshot, older);
        check(second.status() == ArchiveHistoryStatus.OK, "MAX snapshot second page must be OK");
        check(second.upperSequence() == max, "second page must keep pinned upper=MAX");
        check(second.records().size() == 1
                        && second.records().get(0).sequence() == max - 2,
                "second page must return exactly MAX-2");
        check(!second.hasMore(), "second page must be the end");

        // 上界固定为 MAX 时，before=MAX 是合法的排他 cursor 位置，不能因 +1 溢出被拒。
        ArchiveHistoryRequest atUpper = request("req-max-2", MAID, WORLD, max, max, 20);
        ArchiveHistoryResponse atUpperPage = page(snapshot, atUpper);
        check(atUpperPage.status() == ArchiveHistoryStatus.OK,
                "before=MAX upper=MAX must be accepted");
        check(atUpperPage.records().size() == 2
                        && atUpperPage.records().get(1).sequence() == max - 1,
                "before=MAX must exclude MAX itself and read MAX-1 last");

        ArchiveSnapshot decoded = ArchiveSnapshot.fromJson(snapshot.toJson());
        check(decoded.records().stream().mapToLong(ArchiveRecord::sequence).max()
                        .orElse(-1L) == max,
                "MAX sequence must survive snapshot JSON round-trip");
    }

    private static void maxCursorValidationDoesNotOverflow() {
        long max = Long.MAX_VALUE;
        ArchiveHistoryRequest atUpper = request("req-valid-max", MAID, "", max, max, 20);
        check(atUpper.beforeSequence() == max && atUpper.upperSequence() == max,
                "before=upper=MAX must be accepted");

        ArchiveHistoryRequest exclusive = request("req-valid-max-exclusive", MAID, "",
                max, max - 1, 20);
        check(exclusive.beforeSequence() == max && exclusive.upperSequence() == max - 1,
                "before=upper+1 must stay accepted with upper=MAX-1");

        expectArchive(() -> request("req-invalid-max", MAID, "", max, max - 2, 20),
                ArchiveException.Code.VALIDATION,
                "before beyond upper+1 must still be rejected near MAX");
    }

    private static void footerButtonsUseContentLocalGeometry() {
        int[] widths = {240, 320, 411, 480};
        int[] origins = {0, 17, 137};
        int contentHeight = 480;
        for (int width : widths) {
            ArchiveHistoryGeometry geometry =
                    new ArchiveHistoryGeometry(width, 10, 6, 22, 34);
            check(geometry.buttonWidth() >= 1, "button width must stay positive");
            for (int i = 0; i < ArchiveHistoryGeometry.BUTTON_COUNT; i++) {
                check(geometry.localX(i) + geometry.buttonWidth() <= width,
                        "button " + i + " must stay inside width " + width);
                if (i > 0) {
                    check(geometry.localX(i) >= geometry.localX(i - 1)
                                    + geometry.buttonWidth() + 6,
                            "buttons must be separated by the configured gap");
                }
            }
            int localY = geometry.localY(contentHeight);
            check(localY >= geometry.footerTop(contentHeight),
                    "button row must start inside the footer");
            check(localY + geometry.buttonHeight() <= contentHeight,
                    "button row must end inside the content view");

            for (int originX : origins) {
                int originY = 23;
                for (int i = 0; i < ArchiveHistoryGeometry.BUTTON_COUNT; i++) {
                    int localX = geometry.localX(i);
                    int screenX = geometry.screenX(originX, i);
                    int screenY = geometry.screenY(originY, contentHeight);
                    check(screenX == originX + localX,
                            "screen X must be originX + local X");
                    check(screenY == originY + localY,
                            "screen Y must be originY + local Y");

                    double centerX = localX + geometry.buttonWidth() / 2.0;
                    double centerY = localY + geometry.buttonHeight() / 2.0;
                    check(geometry.hit(centerX, centerY, contentHeight, i),
                            "local center must hit button " + i);
                    check(!geometry.hit(localX - 0.5, centerY, contentHeight, i),
                            "left of button " + i + " must not hit");
                    check(!geometry.hit(centerX, localY - 0.5, contentHeight, i),
                            "above button " + i + " must not hit");
                }
            }
        }
    }

    // ==================== 工具 ====================

    private static ArchiveRecord record(long sequence, ArchiveRole role, String text) {
        String id = "m-" + sequence + "-" + Math.abs(text.hashCode());
        return new ArchiveRecord(id, "t-" + sequence, null, ArchiveSource.ONLINE_PHONE_TEXT,
                role, text, 1_000L, WORLD, sequence, sequence, List.of(), null);
    }

    private static ArchiveSnapshot snapshot(List<ArchiveRecord> records) {
        return new ArchiveSnapshot(ArchiveSnapshot.CURRENT_SCHEMA_VERSION,
                "archive-1", OWNER, MAID, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                WORLD, 1_000L, records);
    }

    private static ArchiveHistoryRequest request(String requestId, String stableId,
                                                 String worldId, long before, long upper,
                                                 int limit) {
        return new ArchiveHistoryRequest(requestId, stableId, worldId, before, upper, limit);
    }

    private static ArchiveHistoryResponse page(ArchiveSnapshot snapshot,
                                               ArchiveHistoryRequest request) {
        return ArchiveHistoryProtocol.buildPage(request.requestId(), request.maidStableId(),
                WORLD, snapshot, request);
    }

    private static ArchiveHistoryResponse build(ArchiveHistoryRequest request, String stableId,
                                                String worldId, ArchiveSnapshot snapshot) {
        return ArchiveHistoryProtocol.buildPage(request.requestId(), stableId, worldId,
                snapshot, request);
    }

    private static void assertAscending(List<ArchiveHistoryRecord> records) {
        long previous = Long.MIN_VALUE;
        for (ArchiveHistoryRecord record : records) {
            check(record.sequence() > previous, "page records must be strictly ascending");
            previous = record.sequence();
        }
    }

    private static void expectArchive(Runnable action, ArchiveException.Code code,
                                      String what) {
        try {
            action.run();
        } catch (ArchiveException exception) {
            check(exception.code() == code,
                    what + ": expected " + code + " but got " + exception.code());
            return;
        }
        throw new IllegalStateException(what + ": expected ArchiveException " + code);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
