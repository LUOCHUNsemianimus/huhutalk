package com.huhutalk.core.archive;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 无 JUnit 的归档媒体基础行为检查。覆盖图片内容寻址、损坏拒绝、结构化事件
 * 严格校验与快照/导入往返。由上游直接用 javac/java 运行 main。
 */
public final class ArchiveMediaBehaviorCheck {
    private static final UUID OWNER_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String MAID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String MAID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String WORLD_ID = "33333333-3333-3333-3333-333333333333";
    private static final long RECORDED_AT = 1_700_000_000_000L;

    private static int passed;

    private ArchiveMediaBehaviorCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("huhutalk-archive-media-check-");
        try {
            assetStoreChecks(base.resolve("assets-root"));
            eventRoundTripChecks(base.resolve("event-root"));
            snapshotAndImportChecks(base.resolve("import-root"));
            eventValidationChecks();
            System.out.println("ALL PASS (" + passed + " checks)");
        } catch (Throwable throwable) {
            System.err.println("FAIL: " + throwable.getMessage());
            throwable.printStackTrace();
            System.exit(1);
        } finally {
            deleteRecursively(base);
        }
    }

    private static void assetStoreChecks(Path root) throws Exception {
        ArchiveAssetStore assets = new ArchiveAssetStore(root);
        byte[] png = png(4, 4, 0xFF336699);

        String hash = assets.store(png);
        checkEquals("asset hash is 64 lowercase hex", true, hash.matches("[0-9a-f]{64}"));
        checkEquals("asset uses content address path", true,
                Files.isRegularFile(assets.assetsDir().resolve(hash + ".png")));
        checkEquals("same bytes dedupe to same hash", hash, assets.store(png));
        checkEquals("dedupe keeps one asset file", 1L, assetFileCount(assets.assetsDir()));

        ArchiveAssetStore reopened = new ArchiveAssetStore(root);
        byte[] read = reopened.read(hash);
        checkEquals("reopen reads identical bytes", true, Arrays.equals(png, read));
        read[0] = 0;
        checkEquals("read returns a defensive copy", true,
                Arrays.equals(png, reopened.read(hash)));

        expectFailure("missing asset fails explicitly", ArchiveException.Code.NOT_FOUND,
                () -> reopened.read("0".repeat(64)));
        expectFailure("non-hex asset path rejected", ArchiveException.Code.INVALID_IDENTIFIER,
                () -> reopened.read("../escape"));
        expectFailure("uppercase hash rejected", ArchiveException.Code.INVALID_IDENTIFIER,
                () -> reopened.read(hash.toUpperCase()));

        byte[] badPng = Arrays.copyOf(png, png.length);
        badPng[0] = 0;
        expectFailure("bad PNG signature rejected", ArchiveException.Code.VALIDATION,
                () -> assets.store(badPng));
        expectFailure("oversized asset bytes rejected", ArchiveException.Code.VALIDATION,
                () -> assets.store(new byte[ArchiveAssetStore.MAX_ASSET_BYTES + 1]));

        byte[] tooWide = pngHeader(ArchiveAssetStore.MAX_IMAGE_SIDE + 1, 1);
        expectFailure("oversized PNG side rejected", ArchiveException.Code.VALIDATION,
                () -> assets.store(tooWide));
        byte[] tooManyPixels = pngHeader(2048, 2049);
        checkEquals("pixel fixture sides within limit", true,
                2048 <= ArchiveAssetStore.MAX_IMAGE_SIDE && 2049 <= ArchiveAssetStore.MAX_IMAGE_SIDE);
        expectFailure("oversized PNG pixel budget rejected", ArchiveException.Code.VALIDATION,
                () -> assets.store(tooManyPixels));

        String tamperedHash = assets.store(png(5, 5, 0xFFAA0000));
        Path tamperedFile = assets.assetsDir().resolve(tamperedHash + ".png");
        byte[] goodBytes = Files.readAllBytes(tamperedFile);
        Files.write(tamperedFile, new byte[]{1, 2, 3});
        expectFailure("tampered asset rejected on read", ArchiveException.Code.CORRUPT,
                () -> reopened.read(tamperedHash));
        Files.write(tamperedFile, goodBytes);

        byte[] noOverwritePng = png(6, 6, 0xFF00AA00);
        String noOverwriteHash = sha256(noOverwritePng);
        Path occupied = assets.assetsDir().resolve(noOverwriteHash + ".png");
        Files.write(occupied, new byte[]{9, 9, 9});
        byte[] beforeOccupied = Files.readAllBytes(occupied);
        expectFailure("corrupt existing asset refuses overwrite", ArchiveException.Code.CONFLICT,
                () -> assets.store(noOverwritePng));
        checkEquals("corrupt existing asset bytes unchanged", true,
                Arrays.equals(beforeOccupied, Files.readAllBytes(occupied)));

        checkEquals("referenced hashes list from snapshot", List.of(hash),
                ArchiveAssetStore.referencedHashes(snapshot(List.of(
                        imageRecord("msg-1", "turn-1", hash)))));
    }

    private static void eventRoundTripChecks(Path root) {
        ArchiveItemSummary apple = new ArchiveItemSummary("minecraft:apple", "Apple", 3,
                "transfer-apple-1");
        ArchiveItemSummary bread = new ArchiveItemSummary("minecraft:bread", "Bread", 1, null);
        ArchiveEvent itemEvent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                List.of(apple, bread), "transfer-1");
        ArchiveEvent observation = ArchiveEvent.visionObservation("vision-site", "vision-model");
        ArchiveEvent emote = ArchiveEvent.emote("huhutalk:emote/happy");

        String hash = "a".repeat(64);
        List<ArchiveRecord> records = List.of(
                imageRecord("msg-image", "turn-image", hash),
                new ArchiveRecord("msg-observation", "turn-image", "msg-image",
                        ArchiveSource.OBSERVATION, ArchiveRole.OBSERVATION,
                        "observation original text", RECORDED_AT, WORLD_ID, 12L, 1L,
                        List.of(), null, observation),
                new ArchiveRecord("msg-item", "turn-item", null, ArchiveSource.TOOL_OUTPUT,
                        ArchiveRole.TOOL, "items sent", RECORDED_AT, WORLD_ID, 13L, 2L,
                        List.of(), "transfer-1", itemEvent),
                new ArchiveRecord("msg-emote", "turn-emote", null, ArchiveSource.ONLINE_PHONE_TEXT,
                        ArchiveRole.MAID, "emote reply", RECORDED_AT, WORLD_ID, 14L, 3L,
                        List.of(), null, emote));
        ArchiveSnapshot snapshot = snapshot(records);
        ArchiveSnapshot parsed = ArchiveSnapshot.fromJson(snapshot.toJson());
        checkEquals("event round-trip schema", ArchiveSnapshot.CURRENT_SCHEMA_VERSION,
                parsed.schemaVersion());
        checkEquals("event round-trip record count", records.size(), parsed.records().size());
        checkEquals("image event kind", ArchiveEventKind.IMAGE_SENT,
                parsed.records().get(0).event().kind());
        checkEquals("image attachment hash survives", List.of(hash),
                parsed.records().get(0).attachmentRefs());
        checkEquals("observation event source stays observation", ArchiveSource.OBSERVATION,
                parsed.records().get(1).source());
        checkEquals("observation role stays observation", ArchiveRole.OBSERVATION,
                parsed.records().get(1).role());
        checkEquals("observation original text", "observation original text",
                parsed.records().get(1).text());
        checkEquals("observation site", "vision-site",
                parsed.records().get(1).event().visualSite());
        checkEquals("observation model", "vision-model",
                parsed.records().get(1).event().visualModel());

        ArchiveEvent parsedItem = parsed.records().get(2).event();
        checkEquals("item event kind", ArchiveEventKind.ITEMS_SENT, parsedItem.kind());
        checkEquals("item direction", ArchiveTransferDirection.PLAYER_TO_MAID,
                parsedItem.direction());
        checkEquals("item outcome", ArchiveTransferOutcome.SUCCESS, parsedItem.outcome());
        checkEquals("item transfer id", "transfer-1", parsedItem.transferId());
        checkEquals("item count", 2, parsedItem.items().size());
        checkEquals("item registry id", "minecraft:apple", parsedItem.items().get(0).itemId());
        checkEquals("item display name", "Apple", parsedItem.items().get(0).displayName());
        checkEquals("item real count", 3, parsedItem.items().get(0).count());
        checkEquals("item related transfer", "transfer-apple-1",
                parsedItem.items().get(0).relatedTransferId());
        checkEquals("item second count", 1, parsedItem.items().get(1).count());
        checkEquals("emote resource survives", "huhutalk:emote/happy",
                parsed.records().get(3).event().emoteResource());

        ArchiveRecord resequenced = parsed.records().get(2).withSequence(99L);
        checkEquals("withSequence keeps event", parsed.records().get(2).event(),
                resequenced.event());
        checkEquals("withSequence keeps attachment refs", parsed.records().get(0).attachmentRefs(),
                parsed.records().get(0).withSequence(98L).attachmentRefs());

        ArchiveStore store = new ArchiveStore(root);
        store.importSnapshot(parsed, OWNER_A, MAID_A);
        ArchiveRecord storedItem = store.findByMessageId(OWNER_A, MAID_A, "msg-item").orElseThrow();
        checkEquals("stored item event kind", ArchiveEventKind.ITEMS_SENT,
                storedItem.event().kind());
        checkEquals("stored item count", 3, storedItem.event().items().get(0).count());
        checkEquals("stored item direction", ArchiveTransferDirection.PLAYER_TO_MAID,
                storedItem.event().direction());

        ArchiveSnapshot reopened = store.load(OWNER_A, MAID_A).orElseThrow();
        checkEquals("reopen keeps all structured events", 4, reopened.records().size());
        ArchiveSnapshot reparsed = ArchiveSnapshot.fromJson(reopened.toJson());
        checkEquals("reopen event round-trip item", parsedItem, reparsed.records().get(2).event());

        expectFailure("unknown schema rejected", ArchiveException.Code.UNSUPPORTED_SCHEMA,
                () -> ArchiveSnapshot.fromJson(snapshot.toJson().replace(
                        "\"schemaVersion\": " + ArchiveSnapshot.CURRENT_SCHEMA_VERSION,
                        "\"schemaVersion\": 999")));
    }

    private static void snapshotAndImportChecks(Path root) {
        String hash = "b".repeat(64);
        List<ArchiveItemSummary> items = List.of(
                new ArchiveItemSummary("minecraft:apple", "Apple", 2, null));
        ArchiveEvent event = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_CLAIMED,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.PARTIAL,
                items, "transfer-2");
        ArchiveRecord itemRecord = new ArchiveRecord("claim-1", "turn-claim", null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.MAID, "claimed", RECORDED_AT,
                WORLD_ID, 1L, 0L, List.of(), null, event);
        ArchiveSnapshot snapshot = snapshot(List.of(itemRecord));
        ArchiveStore store = new ArchiveStore(root);
        ArchiveImportResult first = store.importSnapshot(snapshot, OWNER_A, MAID_A);
        checkEquals("structured import adds record", 1, first.importedCount());
        ArchiveImportResult duplicate = store.importSnapshot(snapshot, OWNER_A, MAID_A);
        checkEquals("same event import skipped", 1, duplicate.skippedCount());
        checkEquals("same event import adds nothing", 0, duplicate.importedCount());

        ArchiveEvent changed = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_CLAIMED,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.SUCCESS,
                items, "transfer-2");
        ArchiveRecord changedRecord = new ArchiveRecord("claim-1", "turn-claim", null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.MAID, "claimed", RECORDED_AT,
                WORLD_ID, 1L, 0L, List.of(), null, changed);
        ArchiveSnapshot changedSnapshot = snapshot(List.of(changedRecord));
        expectFailure("changed event same id conflicts", ArchiveException.Code.CONFLICT,
                () -> store.importSnapshot(changedSnapshot, OWNER_A, MAID_A));

        ArchiveRecord image = new ArchiveRecord("image-1", "turn-image", null,
                ArchiveSource.ONLINE_PHONE_IMAGE, ArchiveRole.PLAYER, "", RECORDED_AT,
                WORLD_ID, 2L, 1L, List.of(hash), null, ArchiveEvent.imageSent());
        ArchiveImportResult imageImport = store.importSnapshot(
                snapshot(List.of(image)), OWNER_A, MAID_A);
        checkEquals("image attachment import adds record", 1, imageImport.importedCount());
        ArchiveRecord storedImage = store.findByMessageId(OWNER_A, MAID_A, "image-1").orElseThrow();
        checkEquals("image attachment survives import", List.of(hash),
                storedImage.attachmentRefs());
        checkEquals("image event survives import", ArchiveEventKind.IMAGE_SENT,
                storedImage.event().kind());
        checkEquals("owner B cannot see owner A event record", 0,
                store.query(OWNER_B, MAID_B, 0, 10, null, null).totalMatching());
    }

    private static void eventValidationChecks() {
        expectFailure("unknown event kind rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveEventKind.fromJson("teleport"));
        expectFailure("item event without items rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                        ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                        List.of(), null));
        expectFailure("item event without direction rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveEvent(ArchiveEventKind.ITEMS_SENT, null,
                        ArchiveTransferOutcome.SUCCESS,
                        List.of(new ArchiveItemSummary("minecraft:apple", "Apple", 1, null)),
                        null, null, null, null, null));
        expectFailure("zero item count rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveItemSummary("minecraft:apple", "Apple", 0, null));
        expectFailure("negative item count rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveItemSummary("minecraft:apple", "Apple", -1, null));
        expectFailure("too many item count rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveItemSummary("minecraft:apple", "Apple",
                        ArchiveItemSummary.MAX_COUNT + 1, null));
        expectFailure("blank display name rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveItemSummary("minecraft:apple", "  ", 1, null));
        expectFailure("unsafe item id rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveItemSummary("../bad", "Bad", 1, null));
        expectFailure("too long observation field rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveEvent.visionObservation(
                        "s".repeat(ArchiveEvent.MAX_VISUAL_FIELD_LENGTH + 1), "model"));
        expectFailure("emote without resource rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveEvent(ArchiveEventKind.EMOTE, null, null, List.of(),
                        null, null, null, null, null));
        expectFailure("bad emote resource rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveEvent.emote("../not a resource"));
        expectFailure("non-item event with items rejected", ArchiveException.Code.VALIDATION,
                () -> new ArchiveEvent(ArchiveEventKind.IMAGE_SENT, null, null,
                        List.of(new ArchiveItemSummary("minecraft:apple", "Apple", 1, null)),
                        null, null, null, null, null));

        ArchiveEvent valid = ArchiveEvent.imageSent();
        expectFailure("non-object event JSON rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveEvent.fromJson(com.google.gson.JsonParser.parseString("\"image\"")));
        expectFailure("missing event items rejected", ArchiveException.Code.CORRUPT,
                () -> ArchiveEvent.fromJson(com.google.gson.JsonParser.parseString(
                        "{\"kind\":\"image_sent\"}")));
        expectFailure("unknown event kind JSON rejected", ArchiveException.Code.VALIDATION,
                () -> ArchiveEvent.fromJson(com.google.gson.JsonParser.parseString(
                        "{\"kind\":\"teleport\",\"items\":[]}")));
        checkEquals("valid event JSON round-trip", valid,
                ArchiveEvent.fromJson(valid.toJson()));
    }

    private static ArchiveRecord imageRecord(String messageId, String turnId, String hash) {
        return new ArchiveRecord(messageId, turnId, null, ArchiveSource.ONLINE_PHONE_IMAGE,
                ArchiveRole.PLAYER, "", RECORDED_AT, WORLD_ID, 10L, 0L, List.of(hash),
                null, ArchiveEvent.imageSent());
    }

    private static ArchiveSnapshot snapshot(List<ArchiveRecord> records) {
        return new ArchiveSnapshot(ArchiveSnapshot.CURRENT_SCHEMA_VERSION,
                "archive-media-1", OWNER_A.toString(), MAID_A,
                ArchiveCoverage.ONLINE_PHONE_TEXT_PARTIAL, WORLD_ID, 1_000L, records);
    }

    private static byte[] png(int width, int height, int argb) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, argb);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            byte[] bytes = output.toByteArray();
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                if (ImageIO.read(input) == null) {
                    throw new AssertionError("fixture is not a readable PNG");
                }
            }
            return bytes;
        } catch (IOException exception) {
            throw new AssertionError("cannot create PNG fixture", exception);
        }
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] bytes = new byte[8 + 8 + 13 + 4];
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        writeInt(bytes, 8, 13);
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        writeInt(bytes, 16, width);
        writeInt(bytes, 20, height);
        return bytes;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                builder.append(Character.forDigit(value & 0x0F, 16));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static long assetFileCount(Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.filter(Files::isRegularFile).count();
        }
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
