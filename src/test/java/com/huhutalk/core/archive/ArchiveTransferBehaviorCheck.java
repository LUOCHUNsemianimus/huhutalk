package com.huhutalk.core.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 纯 Java 物品归档行为检查（无 JUnit / 无 Minecraft）：
 * 真实临时目录落盘、captions、QUEUED→领取→丢地关联、导出/导入去重与只读 DTO。
 */
public final class ArchiveTransferBehaviorCheck {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String MAID = "maid-transfer-1";
    private static final String WORLD = "world-transfer-1";

    private ArchiveTransferBehaviorCheck() {
    }

    public static void main(String[] args) throws Exception {
        factsChecks();
        Path root = Files.createTempDirectory("huhutalk-transfer-check-");
        transferRoundTrip(root);
        importChecks(root);
        System.out.println("ArchiveTransferBehaviorCheck OK");
    }

    private static void factsChecks() {
        checkEquals("64 -> leftover 0", new ArchiveTransferFacts(64, 0),
                ArchiveTransferFacts.of(64, 0));
        checkEquals("64 -> leftover 20", new ArchiveTransferFacts(44, 20),
                ArchiveTransferFacts.of(64, 20));
        checkEquals("64 -> leftover 64", new ArchiveTransferFacts(0, 64),
                ArchiveTransferFacts.of(64, 64));
        checkEquals("empty stack", new ArchiveTransferFacts(0, 0),
                ArchiveTransferFacts.of(0, 0));
        expectArchive("negative before rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveTransferFacts.of(-1, 0));
        expectArchive("leftover above before rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveTransferFacts.of(10, 11));
        expectArchive("negative leftover rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveTransferFacts.of(10, -1));

        int[] before = {64, 20, 10, 1};
        int[] left = {0, 0, 4, 1};
        int received = 0;
        int remaining = 0;
        for (int i = 0; i < before.length; i++) {
            ArchiveTransferFacts facts = ArchiveTransferFacts.of(before[i], left[i]);
            received += facts.received();
            remaining += facts.remaining();
        }
        checkEquals("multi-stack received", 90, received);
        checkEquals("multi-stack remaining", 5, remaining);
        checkEquals("event item slot limit is not item quantity", 256, ArchiveEvent.MAX_ITEMS);
        check(!ArchiveTransferFacts.of(256, 0).nothingReceived(),
                "256 item quantity is a real received amount");
        ArchiveEvent oneStack = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.QUEUED,
                List.of(new ArchiveItemSummary("minecraft:stone", "Stone", 256, null)),
                "transfer-one-stack");
        checkEquals("one stack of 256 is one summary, not 256 entries", 1,
                oneStack.items().size());
        checkEquals("one stack keeps quantity", 256, oneStack.items().get(0).count());
    }

    private static void transferRoundTrip(Path root) {
        ArchiveStore store = new ArchiveStore(root);
        ArchiveItemSummary apple = new ArchiveItemSummary("minecraft:apple", "Apple", 64, null);
        ArchiveItemSummary bread = new ArchiveItemSummary("minecraft:bread", "Bread", 20, null);

        ArchiveEvent sent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                List.of(apple), "transfer-p2m");
        ArchiveRecord player = new ArchiveRecord("msg-player", "turn-player", null,
                ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.PLAYER, "给你一些苹果",
                1_000L, WORLD, 10L, 0L, List.of(), null, sent);
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL, player);

        ArchiveEvent emptyCaptionSent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                List.of(bread), "transfer-empty-caption");
        ArchiveRecord emptyCaption = new ArchiveRecord("msg-empty-caption", "turn-empty-caption",
                null, ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.PLAYER, "", 1_001L, WORLD,
                11L, 0L, List.of(), null, emptyCaptionSent);
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL,
                emptyCaption);

        ArchiveEvent queued = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.QUEUED,
                List.of(apple), "transfer-m2p");
        ArchiveRecord queuedRecord = new ArchiveRecord("msg-queued", "turn-player", "msg-player",
                ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.TOOL, "", 1_002L, WORLD, 12L, 0L,
                List.of(), null, queued);
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL,
                queuedRecord);

        ArchiveEvent claimedPartial = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_CLAIMED,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.PARTIAL,
                List.of(new ArchiveItemSummary("minecraft:apple", "Apple", 44, null)),
                "transfer-m2p");
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL,
                new ArchiveRecord("msg-claimed-partial", "turn-player", null,
                        ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.TOOL, "", 1_003L, WORLD,
                        13L, 0L, List.of(), null, claimedPartial));

        ArchiveEvent claimedDone = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_CLAIMED,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.SUCCESS,
                List.of(new ArchiveItemSummary("minecraft:apple", "Apple", 20, null)),
                "transfer-m2p");
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL,
                new ArchiveRecord("msg-claimed-done", "turn-player", null,
                        ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.TOOL, "", 1_004L, WORLD,
                        14L, 0L, List.of(), null, claimedDone));

        ArchiveEvent dropped = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_DROPPED,
                ArchiveTransferDirection.MAID_TO_WORLD, ArchiveTransferOutcome.SUCCESS,
                List.of(new ArchiveItemSummary("minecraft:bread", "Bread", 1, null)),
                "transfer-m2p");
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL,
                new ArchiveRecord("msg-dropped", "turn-player", null,
                        ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.TOOL, "", 1_005L, WORLD,
                        15L, 0L, List.of(), null, dropped));

        ArchiveEvent droppedFailed = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_DROPPED,
                ArchiveTransferDirection.MAID_TO_WORLD, ArchiveTransferOutcome.FAILED,
                List.of(new ArchiveItemSummary("minecraft:bread", "Bread", 2, null)),
                "transfer-m2p");
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL,
                new ArchiveRecord("msg-dropped-failed", "turn-player", null,
                        ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.TOOL, "", 1_006L, WORLD,
                        16L, 0L, List.of(), null, droppedFailed));

        ArchiveRecord emote = new ArchiveRecord("msg-emote", "turn-player", "msg-player",
                ArchiveSource.ONLINE_PHONE_ITEMS, ArchiveRole.MAID, "谢谢主人", 1_007L, WORLD,
                17L, 0L, List.of(), null, ArchiveEvent.emote("huhutalk:emote/happy"));
        store.appendOrCreate(OWNER, MAID, WORLD, ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL, emote);

        ArchiveSnapshot reopened = new ArchiveStore(root).load(OWNER, MAID).orElseThrow();
        checkEquals("reopen keeps all transfer records", 8, reopened.records().size());
        checkEquals("coverage includes item scope", true,
                reopened.coverage().scopes().contains("online_phone_items"));
        checkEquals("coverage stays partial", true, reopened.coverage().partial());

        ArchiveRecord storedPlayer = findByMessageId(reopened, "msg-player");
        checkEquals("caption stored once", "给你一些苹果", storedPlayer.text());
        checkEquals("player event direction", ArchiveTransferDirection.PLAYER_TO_MAID,
                storedPlayer.event().direction());
        checkEquals("player event item count", 64, storedPlayer.event().items().get(0).count());
        checkEquals("player event keeps transfer id", "transfer-p2m",
                storedPlayer.event().transferId());

        ArchiveRecord storedEmpty = findByMessageId(reopened, "msg-empty-caption");
        checkEquals("empty caption stays empty", "", storedEmpty.text());
        checkEquals("empty caption still has item event", ArchiveEventKind.ITEMS_SENT,
                storedEmpty.event().kind());

        ArchiveRecord storedQueued = findByMessageId(reopened, "msg-queued");
        checkEquals("queued outcome survives", ArchiveTransferOutcome.QUEUED,
                storedQueued.event().outcome());
        checkEquals("queued related message survives", "msg-player",
                storedQueued.relatedMessageId());
        checkEquals("queued transfer id survives", "transfer-m2p",
                storedQueued.event().transferId());

        ArchiveRecord storedPartial = findByMessageId(reopened, "msg-claimed-partial");
        checkEquals("partial claimed count", 44, storedPartial.event().items().get(0).count());
        checkEquals("partial claimed outcome", ArchiveTransferOutcome.PARTIAL,
                storedPartial.event().outcome());
        ArchiveRecord storedDone = findByMessageId(reopened, "msg-claimed-done");
        checkEquals("success claimed outcome", ArchiveTransferOutcome.SUCCESS,
                storedDone.event().outcome());
        ArchiveRecord storedDropped = findByMessageId(reopened, "msg-dropped");
        checkEquals("dropped direction", ArchiveTransferDirection.MAID_TO_WORLD,
                storedDropped.event().direction());
        ArchiveRecord storedDropFailed = findByMessageId(reopened, "msg-dropped-failed");
        checkEquals("failed drop outcome", ArchiveTransferOutcome.FAILED,
                storedDropFailed.event().outcome());
        checkEquals("failed drop count is failed quantity", 2,
                storedDropFailed.event().items().get(0).count());

        historyReadOnly(reopened);
        exportFormat(reopened);
    }

    private static void historyReadOnly(ArchiveSnapshot snapshot) {
        ArchiveHistoryRequest request = new ArchiveHistoryRequest("req-transfer", MAID, WORLD,
                -1, -1, 20);
        ArchiveHistoryResponse response = ArchiveHistoryProtocol.buildPage("req-transfer", MAID,
                WORLD, snapshot, request);
        checkEquals("history page status", ArchiveHistoryStatus.OK, response.status());
        checkEquals("history includes all transfer records", 8, response.records().size());

        ArchiveHistoryRecord queued = historyRecord(response, "msg-queued");
        checkEquals("DTO queued outcome", ArchiveTransferOutcome.QUEUED,
                queued.event().outcome());
        check(queued.event().transferId() == null, "DTO strips event transferId");
        check(queued.event().items().get(0).relatedTransferId() == null,
                "DTO strips item transferId");

        ArchiveHistoryRecord partial = historyRecord(response, "msg-claimed-partial");
        checkEquals("DTO claimed outcome", ArchiveTransferOutcome.PARTIAL,
                partial.event().outcome());
        checkEquals("DTO claimed real count", 44, partial.event().items().get(0).count());

        ArchiveHistoryRecord dropped = historyRecord(response, "msg-dropped");
        checkEquals("DTO dropped direction", ArchiveTransferDirection.MAID_TO_WORLD,
                dropped.event().direction());
        ArchiveHistoryRecord failed = historyRecord(response, "msg-dropped-failed");
        checkEquals("DTO failed outcome", ArchiveTransferOutcome.FAILED,
                failed.event().outcome());

        ArchiveHistoryRecord emote = historyRecord(response, "msg-emote");
        checkEquals("DTO emote kind", ArchiveEventKind.EMOTE, emote.event().kind());
        checkEquals("DTO emote resource", "huhutalk:emote/happy",
                emote.event().emoteResource());
        check(emote.event().transferId() == null, "emote DTO has no transfer id");

        String json = response.toJson();
        ArchiveHistoryResponse decoded = ArchiveHistoryProtocol.decodeResponse(json);
        checkEquals("history round-trip record count", response.records().size(),
                decoded.records().size());
        checkEquals("history round-trip queued", ArchiveTransferOutcome.QUEUED,
                historyRecord(decoded, "msg-queued").event().outcome());
    }

    private static void exportFormat(ArchiveSnapshot snapshot) {
        String json = snapshot.toJson();
        check(!json.contains("ItemStack"), "export must not contain ItemStack");
        check(!json.contains("\"nbt\""), "export must not contain NBT");
        check(!json.contains("\"components\""), "export must not contain item components");
        check(json.contains("\"itemId\": \"minecraft:apple\""), "export keeps registry id");
        check(json.contains("\"count\": 64"), "export keeps real count");
    }

    private static void importChecks(Path sourceRoot) throws Exception {
        ArchiveSnapshot source = new ArchiveStore(sourceRoot).load(OWNER, MAID).orElseThrow();
        Path targetRoot = Files.createTempDirectory("huhutalk-transfer-import-");
        ArchiveStore target = new ArchiveStore(targetRoot);
        ArchiveImportResult first = target.importSnapshot(source, OWNER, MAID);
        checkEquals("first import adds all records", source.records().size(), first.importedCount());
        ArchiveImportResult duplicate = target.importSnapshot(source, OWNER, MAID);
        checkEquals("same transfer import adds nothing", 0, duplicate.importedCount());
        checkEquals("same transfer import skips duplicates", source.records().size(),
                duplicate.skippedCount());
        ArchiveSnapshot reopened = target.load(OWNER, MAID).orElseThrow();
        checkEquals("duplicate import does not duplicate storage", source.records().size(),
                reopened.records().size());
        ArchiveRecord imported = findByMessageId(reopened, "msg-queued");
        checkEquals("import keeps fact not executable item", "minecraft:apple",
                imported.event().items().get(0).itemId());
        checkEquals("import keeps queued fact", ArchiveTransferOutcome.QUEUED,
                imported.event().outcome());
    }

    private static ArchiveRecord findByMessageId(ArchiveSnapshot snapshot, String messageId) {
        for (ArchiveRecord record : snapshot.records()) {
            if (record.messageId().equals(messageId)) {
                return record;
            }
        }
        throw new IllegalStateException("missing record " + messageId);
    }

    private static ArchiveHistoryRecord historyRecord(ArchiveHistoryResponse response,
                                                      String messageId) {
        List<ArchiveHistoryRecord> records = new ArrayList<>(response.records());
        for (ArchiveHistoryRecord record : records) {
            if (record.messageId().equals(messageId)) {
                return record;
            }
        }
        throw new IllegalStateException("missing history record " + messageId);
    }

    private static void expectArchive(String name, ArchiveException.Code code, Runnable action) {
        try {
            action.run();
        } catch (ArchiveException exception) {
            checkEquals(name + " code", code, exception.code());
            return;
        }
        throw new IllegalStateException(name + ": expected ArchiveException " + code);
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(name + ": expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
