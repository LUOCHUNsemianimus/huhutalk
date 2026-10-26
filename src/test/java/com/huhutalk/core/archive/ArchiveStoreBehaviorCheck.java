package com.huhutalk.core.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 不依赖 JUnit 的 ArchiveStore 行为检查。由上游在 CMD 中直接运行 main。
 * 覆盖：写入/重开/旧原文、角色与 turn、分页与过滤、隔离、快照往返、
 * 幂等导入、冲突/schema/坏 JSON/非法标识/写入失败。
 */
public final class ArchiveStoreBehaviorCheck {
    private static final UUID OWNER_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String MAID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String MAID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String WORLD_ID = "33333333-3333-3333-3333-333333333333";
    private static final long RECORDED_AT = 1_700_000_000_000L;

    private static int passed;

    private ArchiveStoreBehaviorCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("huhutalk-archive-check-");
        try {
            run(base);
            System.out.println("ALL PASS (" + passed + " checks)");
        } catch (Throwable throwable) {
            System.err.println("FAIL: " + throwable.getMessage());
            throwable.printStackTrace();
            System.exit(1);
        } finally {
            deleteRecursively(base);
        }
    }

    private static void run(Path base) throws Exception {
        ArchiveStore store = new ArchiveStore(base.resolve("root-a"));

        // 1. 玩家原话 + 女仆回复共用 turnId，角色和关联正确，原文完整。
        ArchiveRecord player = store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("msg-p1", "turn-1", null, ArchiveRole.PLAYER,
                        "OLD-ORIGINAL-PLAYER-原话-0001", 0L));
        ArchiveRecord maid = store.append(OWNER_A, MAID_A,
                record("msg-m1", "turn-1", player.messageId(), ArchiveRole.MAID,
                        "OLD-ORIGINAL-MAID-回复-0001", 0L));
        checkEquals("player role", ArchiveRole.PLAYER, player.role());
        checkEquals("maid role", ArchiveRole.MAID, maid.role());
        checkEquals("shared turnId", player.turnId(), maid.turnId());
        checkEquals("maid relatedMessageId", player.messageId(), maid.relatedMessageId());
        checkEquals("player sequence", 0L, player.sequence());
        checkEquals("maid sequence", 1L, maid.sequence());
        checkEquals("player text is verbatim", "OLD-ORIGINAL-PLAYER-原话-0001", player.text());

        // 同文本不同 messageId 必须保留。
        ArchiveRecord sameText = store.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("msg-p2", "turn-2", null, ArchiveRole.PLAYER,
                        "OLD-ORIGINAL-PLAYER-原话-0001", 0L));
        checkEquals("same text different id kept", 2L, sameText.sequence());
        checkEquals("same text kept twice", 2,
                store.query(OWNER_A, MAID_A, 0, 1000, ArchiveRole.PLAYER, null).totalMatching());

        // 2. 重建存储对象读回相同 archiveId/messageId/turnId 与原文。
        ArchiveSnapshot before = store.load(OWNER_A, MAID_A).orElseThrow();
        ArchiveStore reopened = new ArchiveStore(store.root());
        ArchiveSnapshot after = reopened.load(OWNER_A, MAID_A).orElseThrow();
        checkEquals("archiveId survives reopen", before.archiveId(), after.archiveId());
        ArchiveRecord reopenedPlayer = reopened.findByMessageId(OWNER_A, MAID_A, "msg-p1").orElseThrow();
        checkEquals("messageId survives reopen", "msg-p1", reopenedPlayer.messageId());
        checkEquals("turnId survives reopen", "turn-1", reopenedPlayer.turnId());
        checkEquals("text survives reopen", player.text(), reopenedPlayer.text());

        // 3. 追加 520 条，模拟 TLM 历史丢弃后仍能查询旧原话。
        for (int i = 0; i < 520; i++) {
            ArchiveRole role = i % 2 == 0 ? ArchiveRole.PLAYER : ArchiveRole.MAID;
            String turn = role == ArchiveRole.PLAYER ? "bulk-turn-" + i : "bulk-turn-" + (i - 1);
            reopened.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                    ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                    record("bulk-" + i, turn, null, role,
                            "bulk 原文 " + i + " needle-" + i, 0L));
        }
        ArchivePage oldPage = reopened.query(OWNER_A, MAID_A, 0, 10, null, "OLD-ORIGINAL-PLAYER");
        checkEquals("old text query after 520 appends", 2, oldPage.totalMatching());
        checkEquals("old text query keeps verbatim", true,
                oldPage.records().stream().allMatch(r -> r.text().startsWith("OLD-ORIGINAL-PLAYER")));
        checkEquals("bulk needle lookup", 1,
                reopened.query(OWNER_A, MAID_A, 0, 10, null, "needle-517").totalMatching());

        ArchiveSnapshot full = reopened.load(OWNER_A, MAID_A).orElseThrow();
        checkEquals("record count after bulk", 523, full.records().size());

        // 4. 分页按 sequence 无重复，角色过滤正确。
        ArchivePage first = reopened.query(OWNER_A, MAID_A, 0, 25, null, null);
        ArchivePage second = reopened.query(OWNER_A, MAID_A, 25, 25, null, null);
        checkEquals("page size 1", 25, first.records().size());
        checkEquals("page size 2", 25, second.records().size());
        checkEquals("page has more", true, first.hasMore());
        Set<Long> pageSequences = new HashSet<>();
        for (ArchiveRecord record : first.records()) {
            checkEquals("page 1 sequence unique", true, pageSequences.add(record.sequence()));
        }
        for (ArchiveRecord record : second.records()) {
            checkEquals("page 2 sequence unique", true, pageSequences.add(record.sequence()));
        }
        checkEquals("pages ordered", true, isOrdered(first.records()) && isOrdered(second.records()));
        checkEquals("page 1 last < page 2 first", true,
                first.records().get(24).sequence() < second.records().get(0).sequence());

        ArchivePage players = reopened.query(OWNER_A, MAID_A, 0, 1000, ArchiveRole.PLAYER, null);
        ArchivePage maids = reopened.query(OWNER_A, MAID_A, 0, 1000, ArchiveRole.MAID, null);
        checkEquals("role filter sums to total", 523, players.totalMatching() + maids.totalMatching());
        checkEquals("role filter all player", true,
                players.records().stream().allMatch(r -> r.role() == ArchiveRole.PLAYER));
        checkEquals("role filter all maid", true,
                maids.records().stream().allMatch(r -> r.role() == ArchiveRole.MAID));

        // 5. 两个 owner / 两个女仆 / 两个 root 隔离。
        ArchiveStore otherRootStore = new ArchiveStore(base.resolve("root-b"));
        otherRootStore.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("other-root-1", "other-turn", null, ArchiveRole.PLAYER, "other root", 0L));
        store.appendOrCreate(OWNER_B, MAID_B, WORLD_ID,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                record("other-owner-1", "other-turn", null, ArchiveRole.PLAYER, "other owner", 0L));
        checkEquals("owner B/maid B isolated", 1,
                store.query(OWNER_B, MAID_B, 0, 100, null, null).totalMatching());
        checkEquals("owner A/maid A unaffected", 523,
                store.query(OWNER_A, MAID_A, 0, 100, null, null).totalMatching());
        checkEquals("root B isolated", 1,
                otherRootStore.query(OWNER_A, MAID_A, 0, 100, null, null).totalMatching());
        checkEquals("root A does not see root B", 523,
                store.query(OWNER_A, MAID_A, 0, 100, null, null).totalMatching());
        checkEquals("owner A/maid B missing", 0,
                store.query(OWNER_A, MAID_B, 0, 100, null, null).totalMatching());

        // 6. 快照往返与幂等导入；同 ID 同内容重复导入不新增。
        ArchiveSnapshot exported = reopened.load(OWNER_A, MAID_A).orElseThrow();
        String json = exported.toJson();
        ArchiveSnapshot parsed = ArchiveSnapshot.fromJson(json);
        checkEquals("snapshot json archiveId", exported.archiveId(), parsed.archiveId());
        checkEquals("snapshot json record count", exported.records().size(), parsed.records().size());
        checkEquals("snapshot json first text", exported.records().get(0).text(),
                parsed.records().get(0).text());

        Path importRoot = base.resolve("import-root");
        ArchiveStore importStore = new ArchiveStore(importRoot);
        ArchiveImportResult imported = importStore.importSnapshot(parsed, OWNER_A, MAID_A);
        checkEquals("import count", 523, imported.importedCount());
        checkEquals("import skipped", 0, imported.skippedCount());
        checkEquals("import archiveId preserved", exported.archiveId(),
                importStore.load(OWNER_A, MAID_A).orElseThrow().archiveId());
        checkEquals("import first text preserved", player.text(),
                importStore.findByMessageId(OWNER_A, MAID_A, "msg-p1").orElseThrow().text());

        Path importedFile = archiveFile(importStore, OWNER_A, MAID_A);
        byte[] beforeDuplicate = Files.readAllBytes(importedFile);
        ArchiveImportResult duplicate = importStore.importSnapshot(parsed, OWNER_A, MAID_A);
        checkEquals("duplicate import adds nothing", 0, duplicate.importedCount());
        checkEquals("duplicate import skipped", 523, duplicate.skippedCount());
        checkEquals("duplicate import bytes unchanged", true,
                java.util.Arrays.equals(beforeDuplicate, Files.readAllBytes(importedFile)));

        // 7. 同 ID 不同内容拒绝整次合并，文件原字节不变。
        List<ArchiveRecord> conflictRecords = new ArrayList<>(parsed.records());
        ArchiveRecord original = conflictRecords.get(0);
        conflictRecords.set(0, new ArchiveRecord(original.messageId(), original.turnId(),
                original.relatedMessageId(), original.source(), original.role(),
                "DIFFERENT TEXT", original.recordedAtEpochMillis(), original.originWorldId(),
                original.originGameTime(), original.sequence(), original.attachmentRefs(),
                original.relatedEventId()));
        ArchiveSnapshot conflicting = parsed.withRecords(conflictRecords);
        byte[] beforeConflict = Files.readAllBytes(importedFile);
        expectFailure("same id different content rejected", ArchiveException.Code.CONFLICT,
                () -> importStore.importSnapshot(conflicting, OWNER_A, MAID_A));
        checkEquals("conflict leaves file bytes unchanged", true,
                java.util.Arrays.equals(beforeConflict, Files.readAllBytes(importedFile)));

        // 8. 已绑定不同 archiveId 的合并拒绝。
        ArchiveSnapshot differentArchive = new ArchiveSnapshot(1, UUID.randomUUID().toString(),
                OWNER_A.toString(), MAID_A, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                WORLD_ID, 1L, List.of());
        byte[] beforeDifferentArchive = Files.readAllBytes(importedFile);
        expectFailure("different archiveId rejected", ArchiveException.Code.CONFLICT,
                () -> importStore.importSnapshot(differentArchive, OWNER_A, MAID_A));
        checkEquals("different archiveId leaves file bytes unchanged", true,
                java.util.Arrays.equals(beforeDifferentArchive, Files.readAllBytes(importedFile)));

        // 9. 归属不符拒绝，且不创建目标文件。
        Path ownershipFile = archiveFile(importStore, OWNER_B, MAID_B);
        expectFailure("ownership mismatch rejected", ArchiveException.Code.OWNERSHIP,
                () -> importStore.importSnapshot(parsed, OWNER_B, MAID_B));
        checkEquals("ownership mismatch creates no file", false, Files.exists(ownershipFile));

        // 10. 新记录按目标顺序追加，保留原始时间与来源；重复记录跳过。
        ArchiveRecord newRecord = record("import-new-1", "import-turn", null,
                ArchiveRole.MAID, "new imported reply", 900L);
        ArchiveSnapshot appendSnapshot = parsed.withRecords(List.of(parsed.records().get(0), newRecord));
        ArchiveImportResult appended = importStore.importSnapshot(appendSnapshot, OWNER_A, MAID_A);
        checkEquals("mixed import adds one", 1, appended.importedCount());
        checkEquals("mixed import skips duplicate", 1, appended.skippedCount());
        ArchiveRecord importedNew = importStore.findByMessageId(OWNER_A, MAID_A, "import-new-1")
                .orElseThrow();
        checkEquals("import appends target sequence", 523L, importedNew.sequence());
        checkEquals("import keeps original time", newRecord.recordedAtEpochMillis(),
                importedNew.recordedAtEpochMillis());
        checkEquals("import keeps original source", newRecord.source(), importedNew.source());

        // 11. 空归档明确覆盖范围；不声称完整历史。
        ArchiveSnapshot empty = new ArchiveSnapshot(1, UUID.randomUUID().toString(),
                OWNER_B.toString(), MAID_B, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                WORLD_ID, 1L, List.of());
        ArchiveStore emptyStore = new ArchiveStore(base.resolve("empty-root"));
        ArchiveImportResult emptyResult = emptyStore.importSnapshot(empty, OWNER_B, MAID_B);
        checkEquals("empty import count", 0, emptyResult.importedCount());
        ArchiveSnapshot emptyLoaded = emptyStore.load(OWNER_B, MAID_B).orElseThrow();
        checkEquals("empty archive keeps partial coverage", true, emptyLoaded.coverage().partial());
        checkEquals("empty archive scopes online_phone_text", List.of("online_phone_text"),
                emptyLoaded.coverage().scopes());

        // 12. 未知 schema / 坏 JSON / 非法标识 / 写入失败均显式报错且不覆盖坏档。
        Path corruptRoot = base.resolve("corrupt-root");
        Path schemaFile = corruptRoot.resolve(OWNER_A.toString()).resolve(MAID_A + ".json");
        Files.createDirectories(schemaFile.getParent());
        Files.writeString(schemaFile,
                exported.toJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"),
                StandardCharsets.UTF_8);
        byte[] beforeSchema = Files.readAllBytes(schemaFile);
        expectFailure("unknown schema rejected", ArchiveException.Code.UNSUPPORTED_SCHEMA,
                () -> new ArchiveStore(corruptRoot).load(OWNER_A, MAID_A));
        checkEquals("unknown schema leaves file bytes unchanged", true,
                java.util.Arrays.equals(beforeSchema, Files.readAllBytes(schemaFile)));

        Path badJsonRoot = base.resolve("bad-json-root");
        Path badJsonFile = badJsonRoot.resolve(OWNER_A.toString()).resolve(MAID_B + ".json");
        Files.createDirectories(badJsonFile.getParent());
        Files.writeString(badJsonFile, "{not valid json", StandardCharsets.UTF_8);
        byte[] beforeBadJson = Files.readAllBytes(badJsonFile);
        expectFailure("bad json rejected", ArchiveException.Code.CORRUPT,
                () -> new ArchiveStore(badJsonRoot).load(OWNER_A, MAID_B));
        checkEquals("bad json leaves file bytes unchanged", true,
                java.util.Arrays.equals(beforeBadJson, Files.readAllBytes(badJsonFile)));

        String badRoleJson = exported.toJson()
                .replace("\"role\": \"PLAYER\"", "\"role\": \"HACKER\"");
        expectFailure("unknown role rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveSnapshot.fromJson(badRoleJson));

        expectFailure("path traversal stable id rejected", ArchiveException.Code.INVALID_IDENTIFIER,
                () -> reopened.load(OWNER_A, "../../escape"));
        expectFailure("path traversal in message id rejected", ArchiveException.Code.INVALID_IDENTIFIER,
                () -> new ArchiveRecord("../bad", "turn", null, ArchiveSource.ONLINE_PHONE_TEXT,
                        ArchiveRole.PLAYER, "text", 1L, WORLD_ID, 0L, 0L, List.of(), null));
        expectFailure("null owner rejected", ArchiveException.Code.VALIDATION,
                () -> reopened.load(null, MAID_A));

        Path notDirectory = base.resolve("not-a-directory");
        Files.writeString(notDirectory, "x", StandardCharsets.UTF_8);
        ArchiveStore brokenStore = new ArchiveStore(notDirectory);
        expectFailure("write failure reported", ArchiveException.Code.IO,
                () -> brokenStore.appendOrCreate(OWNER_A, MAID_A, WORLD_ID,
                        ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                        record("write-fail", "turn", null, ArchiveRole.PLAYER, "x", 0L)));

        // 13. 旧构造入口/图片/系统注入不经过本归档：纯文本 scope 只含本路径。
        checkEquals("coverage remains partial", true,
                reopened.load(OWNER_A, MAID_A).orElseThrow().coverage().partial());
        checkEquals("only online phone text scope", List.of("online_phone_text"),
                reopened.load(OWNER_A, MAID_A).orElseThrow().coverage().scopes());

        // 14. 分页显式区分缺档、存在空档、存在但无命中，并直接携带 coverage。
        ArchivePage missingPage = reopened.query(OWNER_A, MAID_B, 0, 10, null, null);
        checkEquals("missing archive page not exists", false, missingPage.archiveExists());
        checkEquals("missing archive page archiveId null", null, missingPage.archiveId());
        checkEquals("missing archive page partial", true, missingPage.coverage().partial());
        checkEquals("missing archive page no scopes", List.of(), missingPage.coverage().scopes());
        ArchivePage emptyPage = emptyStore.query(OWNER_B, MAID_B, 0, 10, null, null);
        checkEquals("empty archive page exists", true, emptyPage.archiveExists());
        checkEquals("empty archive page zero matches", 0, emptyPage.totalMatching());
        checkEquals("empty archive page real coverage", List.of("online_phone_text"),
                emptyPage.coverage().scopes());
        ArchivePage noHitPage = reopened.query(OWNER_A, MAID_A, 0, 10, null, "no-such-keyword-xyz");
        checkEquals("no-hit page exists", true, noHitPage.archiveExists());
        checkEquals("no-hit page zero matches", 0, noHitPage.totalMatching());
        checkEquals("no-hit page keeps coverage", ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                noHitPage.coverage());

        // 15. 世界A归档导入世界B后，同一turn的玩家与女仆消息可继续追加。
        String worldB = "44444444-4444-4444-4444-444444444444";
        ArchiveRecord worldBPlayer = importStore.appendOrCreate(OWNER_A, MAID_A, worldB,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                recordInWorld("cross-p1", "cross-turn-1", null, ArchiveRole.PLAYER,
                        "world B player", 0L, worldB, 900_001L));
        ArchiveRecord worldBMaid = importStore.append(OWNER_A, MAID_A,
                recordInWorld("cross-m1", "cross-turn-1", worldBPlayer.messageId(),
                        ArchiveRole.MAID, "world B maid", 0L, worldB, 900_002L));
        ArchiveSnapshot crossSnapshot = importStore.load(OWNER_A, MAID_A).orElseThrow();
        checkEquals("cross-world keeps archiveId", exported.archiveId(), crossSnapshot.archiveId());
        checkEquals("cross-world keeps initial origin", WORLD_ID, crossSnapshot.originWorldId());
        checkEquals("old record origin world stays A", WORLD_ID,
                importStore.findByMessageId(OWNER_A, MAID_A, "msg-p1").orElseThrow().originWorldId());
        checkEquals("new player record origin world B", worldB, worldBPlayer.originWorldId());
        checkEquals("new maid record origin world B", worldB, worldBMaid.originWorldId());
        checkEquals("cross-world same turn", worldBPlayer.turnId(), worldBMaid.turnId());
        checkEquals("cross-world maid relates player", worldBPlayer.messageId(),
                worldBMaid.relatedMessageId());
        checkEquals("cross-world player tick", 900_001L, worldBPlayer.originGameTime());
        checkEquals("cross-world maid tick", 900_002L, worldBMaid.originGameTime());
        checkEquals("cross-world player sequence", 524L, worldBPlayer.sequence());
        checkEquals("cross-world maid sequence", 525L, worldBMaid.sequence());

        byte[] beforeCrossReimport = Files.readAllBytes(archiveFile(importStore, OWNER_A, MAID_A));
        ArchiveSnapshot crossExported = new ArchiveStore(importStore.root())
                .load(OWNER_A, MAID_A).orElseThrow();
        ArchiveImportResult crossAgain = importStore.importSnapshot(
                ArchiveSnapshot.fromJson(crossExported.toJson()), OWNER_A, MAID_A);
        checkEquals("cross-world reimport adds nothing", 0, crossAgain.importedCount());
        checkEquals("cross-world reimport skips all", 526, crossAgain.skippedCount());
        checkEquals("cross-world reimport bytes unchanged", true,
                java.util.Arrays.equals(beforeCrossReimport,
                        Files.readAllBytes(archiveFile(importStore, OWNER_A, MAID_A))));

        // 16. 乱序源快照按源 sequence 升序导入，并重排为目标连续 sequence。
        ArchiveStore orderStore = new ArchiveStore(base.resolve("order-root"));
        ArchiveSnapshot unordered = new ArchiveSnapshot(1, UUID.randomUUID().toString(),
                OWNER_A.toString(), MAID_B, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                WORLD_ID, 1L,
                List.of(record("order-9", "order-turn", null, ArchiveRole.PLAYER, "nine", 9L),
                        record("order-2", "order-turn", null, ArchiveRole.PLAYER, "two", 2L),
                        record("order-5", "order-turn", null, ArchiveRole.PLAYER, "five", 5L)));
        orderStore.importSnapshot(unordered, OWNER_A, MAID_B);
        ArchivePage ordered = orderStore.query(OWNER_A, MAID_B, 0, 10, null, null);
        checkEquals("import ordered by source sequence",
                List.of("order-2", "order-5", "order-9"),
                ordered.records().stream().map(ArchiveRecord::messageId).toList());
        checkEquals("import reassigns contiguous sequence", List.of(0L, 1L, 2L),
                ordered.records().stream().map(ArchiveRecord::sequence).toList());

        // 17. 快照拒绝重复 sequence；小数/超范围数值精确拒绝。
        expectFailure("duplicate sequence rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveSnapshot(1, UUID.randomUUID().toString(), OWNER_A.toString(),
                        MAID_B, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL, WORLD_ID, 1L,
                        List.of(record("dup-a", "t", null, ArchiveRole.PLAYER, "a", 2L),
                                record("dup-b", "t", null, ArchiveRole.PLAYER, "b", 2L))));
        ArchiveSnapshot numericBase = new ArchiveSnapshot(1, UUID.randomUUID().toString(),
                OWNER_A.toString(), MAID_B, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                WORLD_ID, 1L, List.of(record("num-1", "num-turn", null,
                        ArchiveRole.PLAYER, "numeric", 0L)));
        String numericJson = numericBase.toJson();
        expectFailure("fractional schema rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"schemaVersion\": 1", "\"schemaVersion\": 1.5")));
        expectFailure("out-of-int schema rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"schemaVersion\": 1", "\"schemaVersion\": 3000000000")));
        expectFailure("fractional createdAt rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"createdAtEpochMillis\": 1", "\"createdAtEpochMillis\": 1.5")));
        expectFailure("fractional recordedAt rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"recordedAtEpochMillis\": 1700000000000",
                        "\"recordedAtEpochMillis\": 1700000000000.5")));
        expectFailure("out-of-long recordedAt rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"recordedAtEpochMillis\": 1700000000000",
                        "\"recordedAtEpochMillis\": 9223372036854775808")));
        expectFailure("fractional sequence rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"sequence\": 0", "\"sequence\": 0.5")));
        expectFailure("out-of-long sequence rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveSnapshot.fromJson(numericJson.replace(
                        "\"sequence\": 0", "\"sequence\": 9223372036854775808")));

        Path numericRoot = base.resolve("numeric-root");
        Path numericFile = numericRoot.resolve(OWNER_A.toString()).resolve(MAID_B + ".json");
        Files.createDirectories(numericFile.getParent());
        Files.writeString(numericFile, numericJson.replace(
                "\"schemaVersion\": 1", "\"schemaVersion\": 1.5"), StandardCharsets.UTF_8);
        byte[] beforeNumericFile = Files.readAllBytes(numericFile);
        expectFailure("fractional schema file rejected", ArchiveException.Code.CORRUPT,
                () -> new ArchiveStore(numericRoot).load(OWNER_A, MAID_B));
        checkEquals("fractional schema file unchanged", true,
                java.util.Arrays.equals(beforeNumericFile, Files.readAllBytes(numericFile)));

        // 18. sequence 耗尽显式拒绝且不写文件。
        Path maxRoot = base.resolve("max-sequence-root");
        Path maxFile = maxRoot.resolve(OWNER_A.toString()).resolve(MAID_B + ".json");
        Files.createDirectories(maxFile.getParent());
        ArchiveRecord maxRecord = record("max-existing", "max-turn", null, ArchiveRole.PLAYER,
                "max sequence", Long.MAX_VALUE);
        ArchiveSnapshot maxSnapshot = new ArchiveSnapshot(1, UUID.randomUUID().toString(),
                OWNER_A.toString(), MAID_B, ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                WORLD_ID, 1L, List.of(maxRecord));
        Files.writeString(maxFile, maxSnapshot.toJson(), StandardCharsets.UTF_8);
        ArchiveStore maxStore = new ArchiveStore(maxRoot);
        byte[] beforeMaxAppend = Files.readAllBytes(maxFile);
        expectFailure("append at sequence limit rejected", ArchiveException.Code.VALIDATION,
                () -> maxStore.appendOrCreate(OWNER_A, MAID_B, WORLD_ID,
                        ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL,
                        record("max-new", "max-turn", null, ArchiveRole.PLAYER, "overflow", 0L)));
        checkEquals("append at sequence limit leaves file unchanged", true,
                java.util.Arrays.equals(beforeMaxAppend, Files.readAllBytes(maxFile)));
        ArchiveSnapshot maxIncoming = maxSnapshot.withRecords(List.of(maxRecord,
                record("max-import", "max-turn", null, ArchiveRole.MAID, "import overflow", 0L)));
        expectFailure("import at sequence limit rejected", ArchiveException.Code.VALIDATION,
                () -> maxStore.importSnapshot(maxIncoming, OWNER_A, MAID_B));
        checkEquals("import at sequence limit leaves file unchanged", true,
                java.util.Arrays.equals(beforeMaxAppend, Files.readAllBytes(maxFile)));

        System.out.println("PASS - archive store behavior");
    }

    private static ArchiveRecord record(String messageId, String turnId, String relatedMessageId,
                                        ArchiveRole role, String text, long sequence) {
        return recordInWorld(messageId, turnId, relatedMessageId, role, text, sequence,
                WORLD_ID, 12345L);
    }

    private static ArchiveRecord recordInWorld(String messageId, String turnId, String relatedMessageId,
                                               ArchiveRole role, String text, long sequence,
                                               String worldId, long gameTime) {
        return new ArchiveRecord(messageId, turnId, relatedMessageId, ArchiveSource.ONLINE_PHONE_TEXT,
                role, text, RECORDED_AT, worldId, gameTime, sequence, List.of(), null);
    }

    private static boolean isOrdered(List<ArchiveRecord> records) {
        List<ArchiveRecord> copy = new ArrayList<>(records);
        copy.sort(Comparator.comparingLong(ArchiveRecord::sequence));
        return copy.equals(records);
    }

    private static Path archiveFile(ArchiveStore store, UUID ownerUuid, String maidStableId) {
        return store.root().resolve(ownerUuid.toString()).resolve(maidStableId + ".json");
    }

    private static void expectFailure(String name, ArchiveException.Code expected, Runnable action) {
        try {
            action.run();
            throw new AssertionError(name + " expected ArchiveException " + expected);
        } catch (ArchiveException exception) {
            if (exception.code() != expected) {
                throw new AssertionError(name + " expected " + expected + " but got "
                        + exception.code() + ": " + exception.getMessage(), exception);
            }
            check(name);
        }
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but was <" + actual + ">");
        }
        check(name);
    }

    private static void check(String name) {
        passed++;
        System.out.println("PASS - " + name);
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
