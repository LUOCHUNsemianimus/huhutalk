package com.huhutalk.core.archive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 真实 ArchiveStore 上的引用 ID 持久化、迁移、冲突与旧格式兼容测试。 */
public class PhoneQuoteArchiveTest {
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
        tempRoot = Files.createTempDirectory("huhutalk-quote-archive-");
        store = new ArchiveStore(tempRoot.resolve("archive"));
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(tempRoot);
    }

    @Test
    public void quotePersistsOnReplyAndSnapshotJson() {
        ArchiveRecord player = store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("msg-player", "turn-1", null, ArchiveRole.PLAYER,
                        "主人原话，请记住。", 0L, null));
        ArchiveRecord maid = store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("msg-maid", "turn-1", player.messageId(), ArchiveRole.MAID,
                        "女仆过去的回答。", 0L, null));

        ArchiveRecord reply = store.append(OWNER_A, MAID_A,
                record("msg-reply", "turn-2", player.messageId(), ArchiveRole.MAID,
                        "引用回复：我记得。", 0L, player.messageId()));
        ArchiveRecord replyToMaid = store.append(OWNER_A, MAID_A,
                record("msg-reply-maid", "turn-3", player.messageId(), ArchiveRole.MAID,
                        "另一个引用回复。", 0L, maid.messageId()));

        assertEquals(player.messageId(), reply.quotedMessageId());
        assertEquals(player.messageId(), reply.relatedMessageId());
        assertEquals("turn-2", reply.turnId());
        assertEquals(maid.messageId(), replyToMaid.quotedMessageId());
        assertEquals(player.messageId(), replyToMaid.relatedMessageId());

        ArchiveSnapshot snapshot = store.load(OWNER_A, MAID_A).orElseThrow();
        ArchiveRecord storedReply = store
                .findByMessageId(OWNER_A, MAID_A, "msg-reply").orElseThrow();
        assertEquals(player.messageId(), storedReply.quotedMessageId());
        assertTrue(snapshot.toJson().contains("\"quotedMessageId\": \"" + player.messageId() + "\""));

        ArchiveSnapshot roundTrip = ArchiveSnapshot.fromJson(snapshot.toJson());
        ArchiveRecord parsedReply = roundTrip.records().stream()
                .filter(record -> record.messageId().equals("msg-reply"))
                .findFirst().orElseThrow();
        assertEquals(player.messageId(), parsedReply.quotedMessageId());
        assertEquals(player.messageId(), parsedReply.relatedMessageId());

        String searchJson = PhoneMemoryProtocol.searchResult(
                store.query(OWNER_A, MAID_A, 0, 10, null, "引用回复"), "引用回复");
        JsonObject search = JsonParser.parseString(searchJson).getAsJsonObject();
        JsonObject first = search.getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("msg-reply", first.get("messageId").getAsString());
        assertEquals(player.messageId(), first.get("quotedMessageId").getAsString());
    }

    @Test
    public void quoteSurvivesExportImportToNewOwnerAndStable() {
        ArchiveRecord player = store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("migrate-player", "turn-1", null, ArchiveRole.PLAYER,
                        "迁移前主人原话。", 0L, null));
        store.append(OWNER_A, MAID_A,
                record("migrate-reply", "turn-2", player.messageId(), ArchiveRole.MAID,
                        "迁移前引用回复。", 0L, player.messageId()));
        ArchiveSnapshot original = store.load(OWNER_A, MAID_A).orElseThrow();

        ArchiveSnapshot migrated = new ArchiveSnapshot(original.schemaVersion(),
                original.archiveId(), OWNER_B.toString(), MAID_B, original.coverage(),
                original.originWorldId(), original.createdAtEpochMillis(),
                ArchiveSnapshot.fromJson(original.toJson()).records());
        ArchiveStore migratedStore = new ArchiveStore(tempRoot.resolve("migrated"));
        ArchiveImportResult result = migratedStore.importSnapshot(migrated, OWNER_B, MAID_B);
        assertEquals(2, result.importedCount());

        ArchiveRecord migratedReply = migratedStore
                .findByMessageId(OWNER_B, MAID_B, "migrate-reply").orElseThrow();
        assertEquals(player.messageId(), migratedReply.quotedMessageId());
        assertEquals(player.messageId(), migratedReply.relatedMessageId());
        assertEquals(original.records().get(0).text(), migratedStore
                .findByMessageId(OWNER_B, MAID_B, "migrate-player").orElseThrow().text());

        ArchiveSnapshot migratedSnapshot = migratedStore.load(OWNER_B, MAID_B).orElseThrow();
        ArchiveRecord reparsed = ArchiveSnapshot.fromJson(migratedSnapshot.toJson()).records()
                .stream().filter(record -> record.messageId().equals("migrate-reply"))
                .findFirst().orElseThrow();
        assertEquals(player.messageId(), reparsed.quotedMessageId());
    }

    @Test
    public void sameIdDifferentQuoteConflictsWithoutDiskChange() throws IOException {
        store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("target-a", "turn-1", null, ArchiveRole.PLAYER,
                        "目标 A", 0L, null));
        store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("target-b", "turn-1", null, ArchiveRole.MAID,
                        "目标 B", 0L, null));
        store.append(OWNER_A, MAID_A,
                record("same-reply", "turn-2", "target-a", ArchiveRole.MAID,
                        "引用回复", 0L, "target-a"));

        Path file = archiveFile(OWNER_A, MAID_A);
        byte[] before = Files.readAllBytes(file);
        expectArchiveFailure(ArchiveException.Code.CONFLICT,
                () -> store.append(OWNER_A, MAID_A,
                        record("same-reply", "turn-2", "target-a", ArchiveRole.MAID,
                                "引用回复", 0L, "target-b")));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));

        // 同 ID 同内容（含引用）仍是幂等，不重写文件。
        ArchiveRecord duplicate = store.append(OWNER_A, MAID_A,
                record("same-reply", "turn-2", "target-a", ArchiveRole.MAID,
                        "引用回复", 0L, "target-a"));
        assertEquals("target-a", duplicate.quotedMessageId());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));

        // 导入同样按 ID + 全部内容比较，不能把不同引用当幂等或按文本去重。
        ArchiveSnapshot snapshot = store.load(OWNER_A, MAID_A).orElseThrow();
        List<ArchiveRecord> conflictingRecords = new ArrayList<>();
        for (ArchiveRecord record : snapshot.records()) {
            if (!record.messageId().equals("same-reply")) {
                conflictingRecords.add(record);
            }
        }
        conflictingRecords.add(record("same-reply", "turn-2", "target-a", ArchiveRole.MAID,
                "引用回复", 2L, "target-b"));
        ArchiveSnapshot conflicting = new ArchiveSnapshot(snapshot.schemaVersion(),
                snapshot.archiveId(), OWNER_A.toString(), MAID_A, snapshot.coverage(),
                snapshot.originWorldId(), snapshot.createdAtEpochMillis(), conflictingRecords);
        expectArchiveFailure(ArchiveException.Code.CONFLICT,
                () -> store.importSnapshot(conflicting, OWNER_A, MAID_A));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
    }

    @Test
    public void oldSchemaAndOldConstructorsRemainCompatible() {
        ArchiveRecord player = store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("legacy-player", "turn-1", null, ArchiveRole.PLAYER,
                        "旧档正文", 0L, null));
        store.append(OWNER_A, MAID_A,
                record("legacy-reply", "turn-2", player.messageId(), ArchiveRole.MAID,
                        "旧档回复", 0L, player.messageId()));
        ArchiveSnapshot snapshot = store.load(OWNER_A, MAID_A).orElseThrow();

        JsonObject json = JsonParser.parseString(snapshot.toJson()).getAsJsonObject();
        for (JsonElement element : json.getAsJsonArray("records")) {
            element.getAsJsonObject().remove("quotedMessageId");
        }
        ArchiveSnapshot withoutQuote = ArchiveSnapshot.fromJson(json.toString());
        ArchiveRecord stripped = withoutQuote.records().stream()
                .filter(record -> record.messageId().equals("legacy-reply"))
                .findFirst().orElseThrow();
        assertNull(stripped.quotedMessageId());
        assertEquals(player.messageId(), stripped.relatedMessageId());
        assertEquals("旧档回复", stripped.text());

        String schema1 = json.toString()
                .replace("\"schemaVersion\":2", "\"schemaVersion\":1");
        ArchiveSnapshot legacy = ArchiveSnapshot.fromJson(schema1);
        ArchiveRecord legacyReply = legacy.records().stream()
                .filter(record -> record.messageId().equals("legacy-reply"))
                .findFirst().orElseThrow();
        assertNull(legacyReply.quotedMessageId());
        assertEquals(player.messageId(), legacyReply.relatedMessageId());

        ArchiveRecord old12 = new ArchiveRecord("old-12", "turn-old", null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.PLAYER, "old", RECORDED_AT,
                WORLD_ID, 1L, 0L, List.of(), null);
        assertNull(old12.quotedMessageId());
        ArchiveRecord old13 = new ArchiveRecord("old-13", "turn-old", null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.PLAYER, "old", RECORDED_AT,
                WORLD_ID, 1L, 0L, List.of(), null, null);
        assertNull(old13.quotedMessageId());
        assertEquals("old-13", old13.withSequence(7L).messageId());
        assertNull(old13.withSequence(7L).quotedMessageId());
    }

    private static ArchiveRecord record(String messageId, String turnId, String relatedMessageId,
                                        ArchiveRole role, String text, long sequence,
                                        String quotedMessageId) {
        return new ArchiveRecord(messageId, turnId, relatedMessageId,
                ArchiveSource.ONLINE_PHONE_TEXT, role, text, RECORDED_AT, WORLD_ID, 42L,
                sequence, List.of(), null, null, quotedMessageId);
    }

    private Path archiveFile(UUID owner, String stableId) {
        return store.root().resolve(owner.toString()).resolve(stableId + ".json");
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
