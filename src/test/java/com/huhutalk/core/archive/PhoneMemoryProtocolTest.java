package com.huhutalk.core.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 真实 {@link ArchiveStore} 上的手机归档协议测试：真实临时目录、真实中文记录、
 * 真实迁移导入，不写生产文件、不调用 Minecraft。
 */
public class PhoneMemoryProtocolTest {
    private static final UUID OWNER_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String MAID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String MAID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String WORLD_ID = "33333333-3333-3333-3333-333333333333";
    private static final long RECORDED_AT = 1_700_000_000_000L;

    private Path tempRoot;
    private ArchiveStore store;

    @Before
    public void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("huhutalk-phone-memory-");
        store = new ArchiveStore(tempRoot.resolve("archive"));
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(tempRoot);
    }

    @Test
    public void searchFormatsRolesAndChineseSnippet() {
        String playerText = "开场白。".repeat(100)
                + "我记得那只叫小雪的猫今天在窗台睡觉。"
                + "结尾补充。".repeat(100);
        append("msg-p1", "turn-1", null, ArchiveRole.PLAYER, playerText);
        append("msg-m1", "turn-1", "msg-p1", ArchiveRole.MAID,
                "小雪是主人收养的猫，我会继续照顾她。");
        appendWith("msg-o1", "turn-2", null, ArchiveSource.OBSERVATION,
                ArchiveRole.OBSERVATION, "图片里可能有一只橘色的猫，但我不确定。",
                null, List.of(), ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL);

        JsonObject result = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 5, null, "小雪"), "小雪"));
        assertEquals("found", result.get("status").getAsString());
        assertEquals(2, result.get("totalMatching").getAsInt());
        assertEquals(true, result.getAsJsonObject("coverage").get("partial").getAsBoolean());
        assertTrue(result.getAsJsonObject("coverage").getAsJsonArray("scopes")
                .toString().contains("online_phone_text"));
        assertTrue(result.get("evidence").getAsString().contains("PLAYER"));
        assertTrue(result.get("evidence").getAsString().contains("MAID"));
        assertTrue(result.get("evidence").getAsString().contains("OBSERVATION"));
        assertTrue(result.get("evidence").getAsString().contains("无命中"));
        assertTrue(result.get("evidence").getAsString().contains("不得重放"));
        assertTrue(result.get("evidence").getAsString().contains("部分覆盖"));

        JsonArray records = result.getAsJsonArray("records");
        assertEquals(2, records.size());
        JsonObject playerRecord = records.get(0).getAsJsonObject();
        JsonObject maidRecord = records.get(1).getAsJsonObject();
        assertEquals("PLAYER", playerRecord.get("role").getAsString());
        assertEquals("MAID", maidRecord.get("role").getAsString());
        assertEquals(RECORDED_AT, playerRecord.get("recordedAtEpochMillis").getAsLong());
        assertEquals("msg-p1", playerRecord.get("messageId").getAsString());

        Map<String, String> originals = Map.of(
                "msg-p1", playerText,
                "msg-m1", "小雪是主人收养的猫，我会继续照顾她。");
        for (JsonObject record : List.of(playerRecord, maidRecord)) {
            String original = originals.get(record.get("messageId").getAsString());
            int offset = record.get("textOffset").getAsInt();
            int length = record.get("textLength").getAsInt();
            assertEquals(original.substring(offset, offset + length),
                    record.get("text").getAsString());
            assertTrue(record.get("text").getAsString().contains("小雪"));
        }
        assertTrue(playerRecord.get("textOffset").getAsInt() > 0);
        assertEquals(PhoneMemoryProtocol.MAX_SNIPPET_CHARS,
                playerRecord.get("textLength").getAsInt());
        assertTrue(playerRecord.get("truncated").getAsBoolean());
        assertFalse(maidRecord.get("truncated").getAsBoolean());
        assertFalse(playerRecord.get("hasImageAttachment").getAsBoolean());

        JsonObject allCats = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 5, null, "猫"), "猫"));
        assertEquals(3, allCats.get("totalMatching").getAsInt());
        List<String> roles = new ArrayList<>();
        for (var element : allCats.getAsJsonArray("records")) {
            roles.add(element.getAsJsonObject().get("role").getAsString());
        }
        assertEquals(List.of("PLAYER", "MAID", "OBSERVATION"), roles);
    }

    @Test
    public void searchDistinguishesMissingNoMatchAndEmptyPageWithoutCache() throws IOException {
        append("msg-1", "turn-1", null, ArchiveRole.PLAYER, "无缓存检查，小雪在这里。");

        JsonObject found = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 5, null, "小雪"), "小雪"));
        assertEquals("found", found.get("status").getAsString());

        JsonObject missing = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_B, MAID_B, 0, 5, null, "小雪"), "小雪"));
        assertEquals("archive_unavailable", missing.get("status").getAsString());
        assertFalse(missing.get("archiveExists").getAsBoolean());
        assertEquals(0, missing.get("totalMatching").getAsInt());
        assertEquals(0, missing.getAsJsonArray("records").size());
        assertEquals(0L, missing.get("nextOffset").getAsLong());

        JsonObject noMatch = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 5, null, "完全不存在"), "完全不存在"));
        assertEquals("no_match", noMatch.get("status").getAsString());
        assertTrue(noMatch.get("archiveExists").getAsBoolean());
        assertEquals(0, noMatch.get("totalMatching").getAsInt());

        JsonObject emptyPage = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 100, 5, null, "小雪"), "小雪"));
        assertEquals("page_empty", emptyPage.get("status").getAsString());
        assertEquals(1, emptyPage.get("totalMatching").getAsInt());
        assertEquals(0, emptyPage.getAsJsonArray("records").size());
        assertFalse(emptyPage.get("hasMore").getAsBoolean());
        assertEquals(100L, emptyPage.get("nextOffset").getAsLong());

        Files.delete(archiveFile(OWNER_A, MAID_A));
        JsonObject afterDelete = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 5, null, "小雪"), "小雪"));
        assertEquals("archive_unavailable", afterDelete.get("status").getAsString());

        Files.createDirectories(archiveFile(OWNER_A, MAID_A).getParent());
        Files.writeString(archiveFile(OWNER_A, MAID_A), "", StandardCharsets.UTF_8);
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> store.query(OWNER_A, MAID_A, 0, 5, null, "小雪"));
    }

    @Test
    public void searchEventAndAttachmentAreReadOnlyAndBounded() {
        String attachmentHash = "b".repeat(64);
        appendWith("msg-image", "turn-image", null, ArchiveSource.ONLINE_PHONE_IMAGE,
                ArchiveRole.PLAYER, "旧图片消息，提到小雪。", ArchiveEvent.imageSent(),
                List.of(attachmentHash), new ArchiveCoverage(true,
                        List.of("online_phone_text", "online_phone_image")));

        List<ArchiveItemSummary> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(new ArchiveItemSummary("huhutalk:old_item_" + i,
                    "旧物品" + i + " " + "名".repeat(200), i + 1, "audit-secret-" + i));
        }
        ArchiveEvent itemEvent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                items, "transfer-secret");
        appendWith("msg-items", "turn-items", null, ArchiveSource.ONLINE_PHONE_ITEMS,
                ArchiveRole.PLAYER, "旧物品事件，提到小雪。", itemEvent, List.of(),
                ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL);

        String json = PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 5, null, "小雪"), "小雪");
        assertTrue(json.length() <= PhoneMemoryProtocol.MAX_RESPONSE_CHARS);
        assertFalse(json.contains(attachmentHash));
        assertFalse(json.contains("transfer-secret"));
        assertFalse(json.contains("audit-secret"));
        assertFalse(json.contains("\"transferId\""));
        assertFalse(json.contains("\"relatedEventId\""));
        assertFalse(json.contains("\"relatedTransferId\""));
        assertFalse(json.contains("\"attachmentRefs\""));

        JsonObject result = parse(json);
        JsonArray records = result.getAsJsonArray("records");
        assertEquals(2, records.size());
        for (var element : records) {
            JsonObject record = element.getAsJsonObject();
            if ("msg-image".equals(record.get("messageId").getAsString())) {
                assertEquals(1, record.get("attachmentCount").getAsInt());
                assertTrue(record.get("hasImageAttachment").getAsBoolean());
                assertEquals("image_sent",
                        record.getAsJsonObject("event").get("kind").getAsString());
                assertEquals(0, record.getAsJsonObject("event")
                        .getAsJsonArray("items").size());
            } else {
                assertEquals(0, record.get("attachmentCount").getAsInt());
                assertFalse(record.get("hasImageAttachment").getAsBoolean());
                JsonObject event = record.getAsJsonObject("event");
                assertEquals("items_sent", event.get("kind").getAsString());
                assertEquals("player_to_maid", event.get("direction").getAsString());
                assertEquals("success", event.get("outcome").getAsString());
                assertEquals(3, event.getAsJsonArray("items").size());
                assertTrue(event.get("itemsTruncated").getAsBoolean());
                assertEquals(items.get(0).itemId(),
                        event.getAsJsonArray("items").get(0).getAsJsonObject()
                                .get("itemId").getAsString());
            }
        }
    }

    @Test
    public void searchHonoursTotalJsonBudgetAndCursor() {
        List<String> scopes = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            scopes.add("scope_" + i + "_" + "x".repeat(115));
        }
        ArchiveCoverage coverage = new ArchiveCoverage(true, scopes);
        for (int i = 0; i < 5; i++) {
            List<ArchiveItemSummary> items = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                items.add(new ArchiveItemSummary(
                        "huhutalk:budget_item_" + i + "_" + j + "_" + "a".repeat(185),
                        "预算物品" + j + " " + "x".repeat(250), j + 1,
                        "audit-secret-" + i + "-" + j));
            }
            ArchiveEvent event = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                    ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                    items, "transfer-secret-" + i);
            String messageId = "msg-" + i + "-" + "m".repeat(110);
            String turnId = "turn-" + i + "-" + "t".repeat(110);
            store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID, coverage,
                    new ArchiveRecord(messageId, turnId, null, ArchiveSource.ONLINE_PHONE_ITEMS,
                            ArchiveRole.PLAYER, "预算测试记录" + i + " " + "字".repeat(500),
                            RECORDED_AT, WORLD_ID, 42L + i, 0L,
                            List.of(), null, event));
        }

        ArchivePage firstPage = store.query(OWNER_A, MAID_A, 0, 5, null, "预算");
        assertEquals(5, firstPage.totalMatching());
        String firstJson = PhoneMemoryProtocol.searchResult(firstPage, "预算");
        assertTrue(firstJson.length() <= PhoneMemoryProtocol.MAX_RESPONSE_CHARS);
        JsonObject first = parse(firstJson);
        assertEquals("found", first.get("status").getAsString());
        JsonArray firstRecords = first.getAsJsonArray("records");
        assertTrue(firstRecords.size() < 5);
        assertTrue(first.get("recordsTruncated").getAsBoolean());
        assertTrue(first.get("hasMore").getAsBoolean());
        assertEquals((long) firstRecords.size(), first.get("nextOffset").getAsLong());
        assertEquals(PhoneMemoryProtocol.MAX_SEARCH_RESULTS,
                Math.min(5, firstPage.records().size()));
        JsonObject firstCoverage = first.getAsJsonObject("coverage");
        assertEquals(16, firstCoverage.getAsJsonArray("scopes").size());
        assertTrue(firstCoverage.get("scopesTruncated").getAsBoolean());
        assertFalse(firstJson.contains("transfer-secret"));
        assertFalse(firstJson.contains("audit-secret"));
        for (var element : firstRecords) {
            JsonObject event = element.getAsJsonObject().getAsJsonObject("event");
            assertTrue(event.getAsJsonArray("items").size() <= 3);
            assertTrue(event.get("itemsTruncated").getAsBoolean());
        }

        Set<String> firstIds = new HashSet<>();
        for (var element : firstRecords) {
            firstIds.add(element.getAsJsonObject().get("messageId").getAsString());
        }
        int cursor = first.get("nextOffset").getAsInt();
        JsonObject second = parse(PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, cursor, 5, null, "预算"), "预算"));
        assertEquals("found", second.get("status").getAsString());
        JsonArray secondRecords = second.getAsJsonArray("records");
        assertTrue(secondRecords.size() > 0);
        for (var element : secondRecords) {
            assertFalse(firstIds.contains(
                    element.getAsJsonObject().get("messageId").getAsString()));
        }
        assertEquals((long) cursor + secondRecords.size(),
                second.get("nextOffset").getAsLong());
        assertFalse(second.get("hasMore").getAsBoolean());
    }

    @Test
    public void messageReadsLongTextAndResumesAtSafeBoundaries() {
        String longText = "a".repeat(3999) + "\uD83D\uDE00" + "尾部内容".repeat(2000);
        append("msg-long", "turn-long", null, ArchiveRole.PLAYER, longText);
        append("msg-after", "turn-long", "msg-long", ArchiveRole.MAID, "后一条消息");
        ArchiveSnapshot snapshot = store.load(OWNER_A, MAID_A).orElseThrow();

        int offset = 0;
        StringBuilder rebuilt = new StringBuilder();
        int chunks = 0;
        while (true) {
            JsonObject chunk = parse(PhoneMemoryProtocol.messageResult(
                    java.util.Optional.of(snapshot), "msg-long", offset, 4000));
            assertEquals("found", chunk.get("status").getAsString());
            int actualOffset = chunk.get("textOffset").getAsInt();
            String text = chunk.get("text").getAsString();
            if (chunks == 0) {
                assertEquals(0, actualOffset);
                assertEquals(3999, chunk.get("textLength").getAsInt());
                assertTrue(chunk.get("truncated").getAsBoolean());
                assertEquals(3999, chunk.get("nextTextOffset").getAsInt());
            }
            assertEquals(offset, actualOffset);
            assertTrue(text.length() <= PhoneMemoryProtocol.MAX_MESSAGE_CHARS);
            assertFalse(hasLoneSurrogate(text));
            rebuilt.append(text);
            chunks++;
            if (chunk.get("nextTextOffset").isJsonNull()) {
                break;
            }
            int next = chunk.get("nextTextOffset").getAsInt();
            assertTrue(next > offset);
            offset = next;
            if (chunks > 10) {
                fail("message continuation did not terminate");
            }
        }
        assertEquals(longText, rebuilt.toString());

        JsonObject backedUp = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "msg-long", 4000, 500));
        assertEquals(3999, backedUp.get("textOffset").getAsInt());
        String backedText = backedUp.get("text").getAsString();
        assertTrue(Character.isHighSurrogate(backedText.charAt(0)));
        assertEquals(longText.substring(3999, 3999 + backedText.length()), backedText);

        JsonObject pastEnd = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "msg-long", longText.length() + 10, 4000));
        assertEquals("found", pastEnd.get("status").getAsString());
        assertEquals("", pastEnd.get("text").getAsString());
        assertEquals(longText.length(), pastEnd.get("textOffset").getAsInt());
        assertEquals(0, pastEnd.get("textLength").getAsInt());
        assertFalse(pastEnd.get("truncated").getAsBoolean());
        assertTrue(pastEnd.get("nextTextOffset").isJsonNull());
    }

    @Test
    public void messageReportsAdjacentIdsAndSameTextStaysDistinct() {
        append("msg-a", "turn-1", null, ArchiveRole.PLAYER, "第一条");
        append("msg-b", "turn-1", "msg-a", ArchiveRole.MAID, "第二条");
        append("msg-c", "turn-1", "msg-b", ArchiveRole.MAID, "第三条");
        append("same-1", "turn-2", null, ArchiveRole.PLAYER, "完全相同的文本");
        append("same-2", "turn-2", null, ArchiveRole.MAID, "完全相同的文本");
        ArchiveSnapshot snapshot = store.load(OWNER_A, MAID_A).orElseThrow();

        JsonObject middle = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "msg-b", 0, 4000));
        assertEquals("msg-a", middle.get("previousMessageId").getAsString());
        assertEquals("msg-c", middle.get("nextMessageId").getAsString());

        JsonObject first = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "msg-a", 0, 4000));
        assertTrue(first.get("previousMessageId").isJsonNull());
        assertEquals("msg-b", first.get("nextMessageId").getAsString());

        JsonObject last = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "msg-c", 0, 4000));
        assertEquals("msg-b", last.get("previousMessageId").getAsString());
        assertEquals("same-1", last.get("nextMessageId").getAsString());

        JsonObject same = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "same-1", 0, 4000));
        assertEquals("same-1", same.get("messageId").getAsString());
        assertEquals("same-2", same.get("nextMessageId").getAsString());
        assertTrue(same.get("nextMessageId").getAsString()
                .equals("same-2"));

        JsonObject noMatch = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(snapshot), "no-such-id", 0, 4000));
        assertEquals("no_match", noMatch.get("status").getAsString());

        JsonObject unavailable = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.empty(), "msg-a", 0, 4000));
        assertEquals("archive_unavailable", unavailable.get("status").getAsString());
    }

    @Test
    public void searchAndMessageValidateParametersAndRoles() {
        PhoneMemoryProtocol.validateSearch("查询", 0, 5);
        PhoneMemoryProtocol.validateSearch("  查询  ", 3, 1);
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.validateSearch(null, 0, 1));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.validateSearch("   ", 0, 1));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.validateSearch("字".repeat(129), 0, 1));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.validateSearch("查询", -1, 1));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.validateSearch("查询", 0, 0));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.validateSearch("查询", 0, 6));

        assertEquals(ArchiveRole.PLAYER, PhoneMemoryProtocol.parseRole(null));
        assertEquals(ArchiveRole.PLAYER, PhoneMemoryProtocol.parseRole("   "));
        assertEquals(ArchiveRole.PLAYER, PhoneMemoryProtocol.parseRole("PLAYER"));
        assertEquals(ArchiveRole.PLAYER, PhoneMemoryProtocol.parseRole("Player"));
        assertEquals(ArchiveRole.MAID, PhoneMemoryProtocol.parseRole("maid"));
        assertEquals(ArchiveRole.OBSERVATION,
                PhoneMemoryProtocol.parseRole("observation"));
        assertEquals(ArchiveRole.SYSTEM, PhoneMemoryProtocol.parseRole("system"));
        assertEquals(ArchiveRole.TOOL, PhoneMemoryProtocol.parseRole("tool"));
        assertNull(PhoneMemoryProtocol.parseRole("all"));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.parseRole("hacker"));

        append("msg-param", "turn-param", null, ArchiveRole.PLAYER, "参数检查");
        ArchivePage page = store.query(OWNER_A, MAID_A, 0, 5, null, "参数");
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.searchResult(null, "参数"));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.searchResult(page, "   "));

        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.messageResult(null, "msg-param", 0, 100));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.messageResult(java.util.Optional.empty(),
                        "msg-param", -1, 100));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.messageResult(java.util.Optional.empty(),
                        "msg-param", 0, PhoneMemoryProtocol.MIN_MESSAGE_CHARS - 1));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.messageResult(java.util.Optional.empty(),
                        "msg-param", 0, PhoneMemoryProtocol.MAX_MESSAGE_CHARS + 1));
        expectArchiveFailure(ArchiveException.Code.INVALID_IDENTIFIER,
                () -> PhoneMemoryProtocol.messageResult(java.util.Optional.empty(),
                        "../bad", 0, 100));
    }

    @Test
    public void migrationKeepsMessageIdsAndRetrieval() {
        String playerText = "迁移保留的原话：小雪今天很乖。";
        append("migrate-p1", "migrate-turn", null, ArchiveRole.PLAYER, playerText);
        append("migrate-m1", "migrate-turn", "migrate-p1", ArchiveRole.MAID,
                "我会记住小雪今天很乖。");
        ArchiveSnapshot original = store.load(OWNER_A, MAID_A).orElseThrow();
        assertEquals(2, original.records().size());

        ArchiveSnapshot migrated = new ArchiveSnapshot(original.schemaVersion(),
                original.archiveId(), OWNER_B.toString(), MAID_B, original.coverage(),
                original.originWorldId(), original.createdAtEpochMillis(), original.records());
        ArchiveStore migratedStore = new ArchiveStore(tempRoot.resolve("migrated"));
        ArchiveImportResult imported = migratedStore.importSnapshot(migrated, OWNER_B, MAID_B);
        assertEquals(2, imported.importedCount());
        assertEquals(original.archiveId(),
                migratedStore.load(OWNER_B, MAID_B).orElseThrow().archiveId());

        ArchiveRecord migratedPlayer = migratedStore
                .findByMessageId(OWNER_B, MAID_B, "migrate-p1").orElseThrow();
        assertEquals(playerText, migratedPlayer.text());

        JsonObject search = parse(PhoneMemoryProtocol.searchResult(
                migratedStore.query(OWNER_B, MAID_B, 0, 5, null, "小雪"), "小雪"));
        assertEquals("found", search.get("status").getAsString());
        assertEquals(2, search.get("totalMatching").getAsInt());

        ArchiveSnapshot migratedSnapshot = migratedStore.load(OWNER_B, MAID_B).orElseThrow();
        JsonObject message = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(migratedSnapshot), "migrate-p1", 0, 4000));
        assertEquals("found", message.get("status").getAsString());
        assertEquals(playerText, message.get("text").getAsString());
        assertEquals("migrate-m1", message.get("nextMessageId").getAsString());

        JsonObject maid = parse(PhoneMemoryProtocol.messageResult(
                java.util.Optional.of(migratedSnapshot), "migrate-m1", 0, 4000));
        assertEquals("migrate-p1", maid.get("previousMessageId").getAsString());

        assertEquals(2, store.query(OWNER_A, MAID_A, 0, 5, null, null).totalMatching());
    }

    @Test
    public void queryMemoryFindsEmptyTextTransferAndMediaWithoutChangingTextQuery() {
        List<ArchiveItemSummary> items = List.of(
                new ArchiveItemSummary("minecraft:diamond", "钻石", 2, "audit-secret"));
        ArchiveEvent maidQueued = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.QUEUED,
                items, "transfer-secret");
        appendWith("msg-tool-items", "turn-items-1", null, ArchiveSource.ONLINE_PHONE_ITEMS,
                ArchiveRole.TOOL, "", maidQueued, List.of(), ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL);

        String imageHash = "c".repeat(64);
        appendWith("msg-maid-image", "turn-image", null, ArchiveSource.ONLINE_PHONE_IMAGE,
                ArchiveRole.MAID, "", ArchiveEvent.imageSent(), List.of(imageHash),
                new ArchiveCoverage(true, List.of("online_phone_image")));

        ArchiveEvent playerSuccess = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                List.of(new ArchiveItemSummary("minecraft:bread", "面包", 3, null)),
                "transfer-secret-2");
        appendWith("msg-system-items", "turn-items-2", null, ArchiveSource.ONLINE_PHONE_ITEMS,
                ArchiveRole.SYSTEM, "", playerSuccess, List.of(),
                ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL);

        // 旧 query 仍是纯文本匹配：这些记录正文为空，因此无命中。
        assertEquals(0, store.query(OWNER_A, MAID_A, 0, 5, null, "钻石").totalMatching());
        assertEquals(0, store.query(OWNER_A, MAID_A, 0, 5, null, "图片").totalMatching());

        ArchivePage byDisplayName = store.queryMemory(OWNER_A, MAID_A, 0, 5, null, "钻石");
        assertEquals(1, byDisplayName.totalMatching());
        assertEquals("msg-tool-items", byDisplayName.records().get(0).messageId());

        ArchivePage byItemId = store.queryMemory(OWNER_A, MAID_A, 0, 5, null,
                "MINECRAFT:DIAMOND");
        assertEquals(1, byItemId.totalMatching());
        assertEquals("msg-tool-items", byItemId.records().get(0).messageId());

        ArchivePage byImageAlias = store.queryMemory(OWNER_A, MAID_A, 0, 5, null, "照片");
        assertEquals(1, byImageAlias.totalMatching());
        assertEquals("msg-maid-image", byImageAlias.records().get(0).messageId());
        assertEquals(1, store.queryMemory(OWNER_A, MAID_A, 0, 5, null, "image")
                .totalMatching());

        ArchivePage byItemAlias = store.queryMemory(OWNER_A, MAID_A, 0, 5, null, "物品");
        assertEquals(2, byItemAlias.totalMatching());
        // 标签是完整词：item_frame 含 "item" 不能因此命中所有物品事件。
        assertEquals(0, store.queryMemory(OWNER_A, MAID_A, 0, 5, null,
                "minecraft:item_frame").totalMatching());

        assertEquals(1, store.queryMemory(OWNER_A, MAID_A, 0, 5, ArchiveRole.TOOL, "钻石")
                .totalMatching());
        assertEquals(1, store.queryMemory(OWNER_A, MAID_A, 0, 5, ArchiveRole.SYSTEM, "面包")
                .totalMatching());
        assertEquals(0, store.queryMemory(OWNER_A, MAID_A, 0, 5, ArchiveRole.PLAYER, "钻石")
                .totalMatching());

        // 同一 owner/stable 隔离，且检索不修改真实记录的 text。
        assertEquals(0, store.queryMemory(OWNER_B, MAID_B, 0, 5, null, "钻石")
                .totalMatching());
        ArchiveRecord storedItems = store.findByMessageId(OWNER_A, MAID_A, "msg-tool-items")
                .orElseThrow();
        assertEquals("", storedItems.text());
        assertEquals(ArchiveEventKind.ITEMS_SENT, storedItems.event().kind());
    }

    @Test
    public void errorJsonIsSmallAndDoesNotLeakStorageDetails() {
        String json = PhoneMemoryProtocol.error("forbidden");
        assertTrue(json.length() <= PhoneMemoryProtocol.MAX_RESPONSE_CHARS);
        JsonObject error = parse(json);
        assertEquals("forbidden", error.get("status").getAsString());
        assertTrue(error.get("evidence").getAsString().contains("PLAYER"));
        assertFalse(json.contains(tempRoot.toString()));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMemoryProtocol.error(null));
        expectArchiveFailure(ArchiveException.Code.INVALID_IDENTIFIER,
                () -> PhoneMemoryProtocol.error("bad status"));
    }

    private ArchiveRecord append(String messageId, String turnId, String relatedMessageId,
                                 ArchiveRole role, String text) {
        return appendWith(messageId, turnId, relatedMessageId, ArchiveSource.ONLINE_PHONE_TEXT,
                role, text, null, List.of(), ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL);
    }

    private ArchiveRecord appendWith(String messageId, String turnId, String relatedMessageId,
                                     ArchiveSource source, ArchiveRole role, String text,
                                     ArchiveEvent event, List<String> attachments,
                                     ArchiveCoverage coverage) {
        ArchiveRecord draft = new ArchiveRecord(messageId, turnId, relatedMessageId, source, role,
                text, RECORDED_AT, WORLD_ID, 42L, 0L, attachments, null, event);
        return store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID, coverage, draft);
    }

    private Path archiveFile(UUID owner, String stableId) {
        return store.root().resolve(owner.toString()).resolve(stableId + ".json");
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static void expectArchiveFailure(ArchiveException.Code expected, Runnable action) {
        ArchiveException exception = assertThrows(ArchiveException.class, action::run);
        assertEquals(expected, exception.code());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
