package com.huhutalk.debug;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.archive.ArchiveCoverage;
import com.huhutalk.core.archive.ArchiveEvent;
import com.huhutalk.core.archive.ArchiveEventKind;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveItemSummary;
import com.huhutalk.core.archive.ArchiveRecord;
import com.huhutalk.core.archive.ArchiveRole;
import com.huhutalk.core.archive.ArchiveService;
import com.huhutalk.core.archive.ArchiveSource;
import com.huhutalk.core.archive.ArchiveStore;
import com.huhutalk.core.archive.ArchiveTransferDirection;
import com.huhutalk.core.archive.ArchiveTransferOutcome;
import com.huhutalk.core.archive.ArchiveTurnContext;
import com.huhutalk.core.archive.GetPhoneMessageTool;
import com.huhutalk.core.archive.SearchPhoneHistoryTool;
import com.huhutalk.core.chat.HuhuTalkChatCallback;
import com.huhutalk.core.chat.PhoneMessageQuote;
import com.huhutalk.init.HuhuTalkItems;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 原生手机回忆验收的服务端部分：隔离世界/真实女仆、真实 ArchiveStore fixtures、
 * 通过 ToolRegister 取得并调用的检索/读取工具、权限与只读存储检查，以及六种
 * 真实归档 turn → HuhuTalkChatCallback → SignalDelay/S2C 路径。
 *
 * <p>只由 {@code -PphoneMemorySmoke} 的 debugSmoke 源集编译；不进入主 jar。</p>
 */
public final class PhoneMemorySmokeBackend {
    public static final String MAID_NAME = "SmokeMaid";
    public static final String PROP_ENABLED = "huhutalk.phoneMemorySmoke";
    public static final String PROP_WORLD = "huhutalk.phoneMemorySmokeWorld";
    public static final String PROP_OUTPUT = "huhutalk.phoneMemorySmokeOutput";
    public static final String[] SHOT_NAMES = {
            "01-text", "02-player-image", "03-maid-image",
            "04-player-items", "05-maid-items", "06-unknown-time"};
    private static final long PAST_MILLIS = 1_704_153_845_000L; // 2024-01-02T03:04:05Z

    static final Map<String, Object> CHECKS = new ConcurrentHashMap<>();
    static final List<Map<String, Object>> ERRORS = new CopyOnWriteArrayList<>();

    public static volatile boolean ready = false;
    public static volatile boolean fatal = false;
    public static volatile String fatalDetail = "";
    public static volatile int scenarioRequest = -1;
    public static volatile int scenarioHandled = -1;
    public static volatile String lastQuoteId = "";

    private static EntityMaid maid;
    private static EntityMaid secondMaid;
    private static ServerPlayer owner;
    private static String stableId;
    private static String secondStableId;
    private static String otherOwnerStableId;
    private static String sameOwnerSecretId;
    private static String otherOwnerSecretId;
    private static final String[] CASE_IDS = new String[6];
    private static boolean worldChecked;
    private static int prepareTicks;
    private static int pendingScenario = -1;
    private static int pendingWait;
    private static ArchiveTurnContext pendingTurn;
    private static PhoneMessageQuote pendingQuote;
    private static final String REPLY_TEXT =
            "我翻到那段话了，星糖的事我一直记得，现在就告诉你。";
    private static final String IMAGE_REPLY =
            "照片我找到了，就是这一张，你看是不是当时那张。";
    private static final String MAID_IMAGE_REPLY =
            "这张是我以前存下的女仆照片，只作为归档里的图片记录。";
    private static final String PLAYER_ITEM_REPLY =
            "那三颗钻石确实送到了我这里，我收好了。";
    private static final String MAID_ITEM_REPLY =
            "面包已经放进你的收件箱了，记得领取。";
    private static final String UNKNOWN_REPLY =
            "这段旧记录没有时间，我只能按归档原文念给你听。";

    /** 前端可读的服务端状态。 */
    public static boolean isReady() { return ready; }
    public static boolean isFatal() { return fatal; }
    public static String getFatalDetail() { return fatalDetail; }
    public static int getHandled() { return scenarioHandled; }
    public static String getLastQuoteId() { return lastQuoteId; }
    public static String getStableId() { return stableId; }

    public static void requestScenario(int index) { scenarioRequest = index; }

    public static void check(String name, boolean ok, String detail) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("ok", ok);
        entry.put("detail", detail);
        CHECKS.put(name, entry);
    }

    public static void error(String where, Throwable throwable) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("where", where);
        entry.put("error", String.valueOf(throwable));
        StringBuilder stack = new StringBuilder();
        for (StackTraceElement element : throwable.getStackTrace()) {
            stack.append(element).append('\n');
        }
        entry.put("stacktrace", stack.toString());
        ERRORS.add(entry);
    }

    public static boolean allChecksOk() {
        for (Object value : CHECKS.values()) {
            if (value instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"))) {
                return false;
            }
        }
        return true;
    }

    private static void fail(String detail) {
        fatalDetail = detail;
        fatal = true;
        error("fatal", new IllegalStateException(detail));
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || fatal) {
            return;
        }
        try {
            if (!ready) {
                prepare(server);
                return;
            }
            if (pendingScenario >= 0) {
                tickPending(server);
                return;
            }
            int request = scenarioRequest;
            if (request > scenarioHandled) {
                if (request >= CASE_IDS.length) {
                    scenarioHandled = request;
                    return;
                }
                runScenario(server, request);
            }
        } catch (Throwable throwable) {
            error("serverTick", throwable);
            scenarioHandled = Math.max(scenarioHandled, scenarioRequest);
            pendingScenario = -1;
        }
    }

    private static void prepare(MinecraftServer server) {
        if (fatal) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer player = players.getFirst();
        if (!worldChecked) {
            worldChecked = true;
            String expected = System.getProperty(PROP_WORLD, "");
            try {
                Path expectedPath = Path.of(expected).toRealPath();
                Path actualPath = server.getWorldPath(LevelResource.ROOT).toRealPath();
                boolean ok = expectedPath.equals(actualPath);
                check("isolatedWorldPath", ok, "actual=" + actualPath);
                if (!ok) {
                    fail("world path mismatch: expected=" + expectedPath + " actual=" + actualPath);
                    return;
                }
            } catch (Throwable throwable) {
                check("isolatedWorldPath", false, String.valueOf(throwable));
                fail("cannot resolve isolated world path: " + throwable);
                return;
            }
        }
        if (player.isDeadOrDying() || !player.isAlive()) {
            check("playerAliveAtStart", false, "player is dead; upstream seed must be alive");
            fail("player not alive at start");
            return;
        }
        check("playerAliveAtStart", true, "connected player alive before test");
        if (++prepareTicks < 80) {
            return;
        }
        check("spawnWait80", true, "waited " + prepareTicks + " natural server ticks");
        try {
            setupWorld(server, player);
            resetArchive(server);
            createFixtures(server);
            runBackendChecks(server);
            ready = true;
        } catch (Throwable throwable) {
            error("prepare", throwable);
            fail("server preparation failed: " + throwable);
        }
    }

    private static void setupWorld(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        player.setGameMode(GameType.SURVIVAL);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, server);
        BlockPos base = new BlockPos(0, 99, 0);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
            }
        }
        player.teleportTo(0.5D, 100.0D, 0.5D);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.clearFire();
        AIConfig.TTS_ENABLED.set(false);

        maid = new EntityMaid(level);
        maid.setPos(2.5D, 100.0D, 0.5D);
        maid.setTame(true, true);
        maid.setOwnerUUID(player.getUUID());
        maid.setPersistenceRequired();
        maid.setInSittingPose(true);
        level.addFreshEntity(maid);
        maid.getMaidBauble().setStackInSlot(0, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
        stableId = MaidStableIdHelper.getOrCreateStableId(maid);

        secondMaid = new EntityMaid(level);
        secondMaid.setPos(4.5D, 100.0D, 0.5D);
        secondMaid.setTame(true, true);
        secondMaid.setOwnerUUID(player.getUUID());
        secondMaid.setPersistenceRequired();
        level.addFreshEntity(secondMaid);
        secondStableId = MaidStableIdHelper.getOrCreateStableId(secondMaid);

        owner = player;
        maid.getAiChatManager().clearAllChatMemory();
        check("historyMemoryCleared", maid.getAiChatManager().getHistory().getDeque().isEmpty(),
                "deque.empty=" + maid.getAiChatManager().getHistory().getDeque().isEmpty());
        check("maidPhoneEquipped", PhoneHelper.hasPhone(maid), "bauble slot 0");
        check("futureSecondMaidReal", secondMaid.isAlive() && !secondStableId.equals(stableId),
                "second stable=" + secondStableId);
    }

    /** 只在已核验的隔离世界内清理本驱动自己的 huhutalk_archive。 */
    private static void resetArchive(MinecraftServer server) throws Exception {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path archiveRoot = ArchiveService.store(server).root();
        Path expectedRoot = worldRoot.resolve("huhutalk_archive").normalize();
        boolean inside = archiveRoot.equals(expectedRoot);
        check("archiveFileWithinWorld", inside, "archiveRoot=" + archiveRoot);
        if (!inside) {
            throw new IllegalStateException("archive root is not the verified world path: " + archiveRoot);
        }
        deleteRecursively(archiveRoot);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path child : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private static String fixtureId(String name) {
        return UUID.nameUUIDFromBytes(("phone-memory-smoke:" + name).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static void createFixtures(MinecraftServer server) throws Exception {
        UUID ownerId = owner.getUUID();
        String worldId = ArchiveService.originWorldId(server);
        ArchiveStore store = ArchiveService.store(server);
        ArchiveCoverage coverage = ArchiveCoverage.ONLINE_PHONE_ITEMS_PARTIAL;

        CASE_IDS[0] = fixtureId("case-player-text");
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                CASE_IDS[0], fixtureId("turn-player-text"), null, ArchiveSource.ONLINE_PHONE_TEXT,
                ArchiveRole.PLAYER, "去年你说想吃星糖，我记到现在。", PAST_MILLIS, worldId,
                1200L, 0L, List.of(), null, null));

        CASE_IDS[1] = fixtureId("case-player-image");
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                CASE_IDS[1], fixtureId("turn-player-image"), null, ArchiveSource.ONLINE_PHONE_IMAGE,
                ArchiveRole.PLAYER, "", PAST_MILLIS, worldId, 1300L, 0L,
                List.of("smoke-player-image-hash-01"), null, ArchiveEvent.imageSent()));

        CASE_IDS[2] = fixtureId("case-maid-image");
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                CASE_IDS[2], fixtureId("turn-maid-image"), null, ArchiveSource.ONLINE_PHONE_IMAGE,
                ArchiveRole.MAID, "", PAST_MILLIS, worldId, 1400L, 0L,
                List.of("smoke-future-maid-image-hash-02"), null, ArchiveEvent.imageSent()));

        CASE_IDS[3] = fixtureId("case-player-items");
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                CASE_IDS[3], fixtureId("turn-player-items"), null, ArchiveSource.ONLINE_PHONE_ITEMS,
                ArchiveRole.SYSTEM, "", PAST_MILLIS, worldId, 1500L, 0L,
                List.of(), null, ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                        ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                        List.of(new ArchiveItemSummary("minecraft:diamond", "Diamond", 3, null)),
                        fixtureId("transfer-player-items"))));

        CASE_IDS[4] = fixtureId("case-maid-items");
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                CASE_IDS[4], fixtureId("turn-maid-items"), null, ArchiveSource.ONLINE_PHONE_ITEMS,
                ArchiveRole.TOOL, "", PAST_MILLIS, worldId, 1600L, 0L,
                List.of(), null, ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                        ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.QUEUED,
                        List.of(new ArchiveItemSummary("minecraft:bread", "Bread", 2, null)),
                        fixtureId("transfer-maid-items"))));

        CASE_IDS[5] = fixtureId("case-unknown-time");
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                CASE_IDS[5], fixtureId("turn-unknown-time"), null, ArchiveSource.ONLINE_PHONE_TEXT,
                ArchiveRole.PLAYER, "这段旧档没有时间，但我还记得那天的风。", 0L, worldId,
                1700L, 0L, List.of(), null, null));

        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                fixtureId("neg-maid-text"), fixtureId("turn-neg-maid"), null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.MAID,
                "星糖是我帮你收着的。", PAST_MILLIS, worldId, 1800L, 0L, List.of(), null, null));
        store.appendOrCreate(ownerId, stableId, worldId, coverage, new ArchiveRecord(
                fixtureId("neg-observation"), fixtureId("turn-neg-observation"), null,
                ArchiveSource.OBSERVATION, ArchiveRole.OBSERVATION,
                "星糖在图片里看起来像糖果。", PAST_MILLIS, worldId, 1900L, 0L, List.of(), null,
                ArchiveEvent.visionObservation("smoke", "fixture")));

        sameOwnerSecretId = fixtureId("secret-same-owner-other-maid");
        store.appendOrCreate(ownerId, secondStableId, worldId, coverage, new ArchiveRecord(
                sameOwnerSecretId, fixtureId("turn-secret-a"), null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.PLAYER,
                "秘密口令：星糖保险箱在二楼。", PAST_MILLIS, worldId, 2000L, 0L, List.of(), null, null));

        UUID otherOwner = UUID.fromString(fixtureId("other-owner"));
        otherOwnerStableId = fixtureId("other-owner-maid");
        otherOwnerSecretId = fixtureId("secret-other-owner");
        store.appendOrCreate(otherOwner, otherOwnerStableId, worldId, coverage, new ArchiveRecord(
                otherOwnerSecretId, fixtureId("turn-secret-b"), null,
                ArchiveSource.ONLINE_PHONE_TEXT, ArchiveRole.PLAYER,
                "秘密口令：星糖地窖在地下。", PAST_MILLIS, worldId, 2100L, 0L, List.of(), null, null));

        boolean six = true;
        for (String id : CASE_IDS) {
            six &= id != null && ArchiveService.findByMessageId(server, ownerId, stableId, id).isPresent();
        }
        check("fixturesSix", six, java.util.Arrays.toString(CASE_IDS));
    }

    private static HuhuTalkChatCallback phoneCallback(String stable, ArchiveTurnContext turn) {
        return new HuhuTalkChatCallback(maid.getAiChatManager(), new ArrayList<>(), owner, stable,
                false, turn);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> String invoke(ITool<T> tool, JsonObject arguments, LLMCallback callback) {
        T parsed = tool.codec().parse(JsonOps.INSTANCE, arguments)
                .resultOrPartial(message -> check("toolParseError", false, message))
                .orElseThrow(() -> new IllegalArgumentException("tool arguments rejected"));
        LLMCallback returned = tool.onCall("smoke-" + System.nanoTime(), parsed, callback);
        return lastToolJson(returned);
    }

    private static String lastToolJson(LLMCallback callback) {
        List<com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage> messages =
                callback.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if ("TOOL".equals(message.role().name())) {
                return message.message();
            }
        }
        throw new IllegalStateException("tool result message missing");
    }

    private static JsonObject search(LLMCallback callback, String query, String role) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", query);
        if (role != null) {
            arguments.addProperty("role", role);
        }
        ITool<?> tool = ToolRegister.getTool(SearchPhoneHistoryTool.TOOL_ID);
        if (tool == null) {
            throw new IllegalStateException("search tool not registered");
        }
        return JsonParser.parseString(invoke(tool, arguments, callback)).getAsJsonObject();
    }

    private static JsonObject read(LLMCallback callback, String messageId, boolean quote) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("message_id", messageId);
        arguments.addProperty("quote", quote);
        ITool<?> tool = ToolRegister.getTool(GetPhoneMessageTool.TOOL_ID);
        if (tool == null) {
            throw new IllegalStateException("get tool not registered");
        }
        return JsonParser.parseString(invoke(tool, arguments, callback)).getAsJsonObject();
    }

    private static String status(JsonObject object) {
        JsonElement element = object.get("status");
        return element == null ? "" : element.getAsString();
    }

    private static JsonArray records(JsonObject object) {
        JsonElement element = object.get("records");
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static List<String> recordIds(JsonObject object) {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : records(object)) {
            JsonObject record = element.getAsJsonObject();
            JsonElement id = record.get("messageId");
            if (id != null) {
                ids.add(id.getAsString());
            }
        }
        return ids;
    }

    private static List<String> recordRoles(JsonObject object) {
        List<String> roles = new ArrayList<>();
        for (JsonElement element : records(object)) {
            roles.add(element.getAsJsonObject().get("role").getAsString());
        }
        return roles;
    }

    private static void runBackendChecks(MinecraftServer server) {
        check("toolsRegistered",
                ToolRegister.getTool(SearchPhoneHistoryTool.TOOL_ID) != null
                        && ToolRegister.getTool(GetPhoneMessageTool.TOOL_ID) != null,
                "search+get via ToolRegister");

        HuhuTalkChatCallback callback = phoneCallback(stableId, null);
        JsonObject playerSearch = search(callback, "星糖", null);
        check("toolSearchDefaultPlayerOnly",
                "found".equals(status(playerSearch)) && recordIds(playerSearch).equals(List.of(CASE_IDS[0])),
                "role omitted -> codec default player ids=" + recordIds(playerSearch));

        JsonObject allSearch = search(callback, "星糖", "all");
        check("toolSearchAllRoles",
                "found".equals(status(allSearch)) && recordIds(allSearch).containsAll(List.of(
                        CASE_IDS[0], fixtureId("neg-maid-text"), fixtureId("neg-observation"))),
                "all roles=" + recordRoles(allSearch));

        JsonObject imageSearch = search(callback, "图片", "all");
        check("toolSearchImageAlias",
                recordIds(imageSearch).containsAll(List.of(CASE_IDS[1], CASE_IDS[2])),
                "image ids=" + recordIds(imageSearch));

        JsonObject itemSearch = search(callback, "物品", "all");
        check("toolSearchItemAlias",
                recordIds(itemSearch).containsAll(List.of(CASE_IDS[3], CASE_IDS[4])),
                "item ids=" + recordIds(itemSearch));

        JsonObject idSearch = search(callback, "minecraft:diamond", "all");
        check("toolSearchItemId", recordIds(idSearch).equals(List.of(CASE_IDS[3])),
                "diamond ids=" + recordIds(idSearch));

        JsonObject noMatch = search(callback, "minecraft:item_frame", "all");
        check("toolSearchItemFrameNoMatch", "no_match".equals(status(noMatch)), status(noMatch));

        JsonObject plainRead = read(callback, CASE_IDS[0], false);
        check("getNoQuote", "found".equals(status(plainRead))
                        && callback.getQuote() == null
                        && plainRead.get("text").getAsString().contains("星糖"),
                "quote=" + callback.getQuote());

        JsonObject quotedRead = read(callback, CASE_IDS[0], true);
        PhoneMessageQuote quote = callback.getQuote();
        check("getQuoteRealFields", "found".equals(status(quotedRead)) && quote != null
                        && CASE_IDS[0].equals(quote.messageId())
                        && quote.role() == ArchiveRole.PLAYER
                        && quote.kind() == PhoneMessageQuote.Kind.TEXT
                        && quote.recordedAtEpochMillis() == PAST_MILLIS,
                "quote=" + quote);

        read(callback, CASE_IDS[0], true);
        read(callback, fixtureId("neg-observation"), true);
        check("quoteClearedOnObservation", callback.getQuote() == null,
                "quote after observation quote attempt=" + callback.getQuote());

        read(callback, CASE_IDS[0], true);
        JsonObject missing = read(callback, fixtureId("missing-message"), true);
        check("quoteClearedOnMissing", callback.getQuote() == null,
                "status=" + status(missing) + " quote=" + callback.getQuote());
        storageReadOnly(server);
        permissionChecks(server);
    }

    private static Path primaryArchiveFile(MinecraftServer server) {
        Path root = ArchiveService.store(server).root();
        return root.resolve(owner.getUUID().toString()).resolve(stableId + ".json").normalize();
    }

    private static void storageReadOnly(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path expectedArchiveRoot = worldRoot.resolve("huhutalk_archive").normalize();
        Path file = primaryArchiveFile(server);
        byte[] before = null;
        try {
            if (!file.startsWith(expectedArchiveRoot)) {
                throw new IllegalStateException(
                        "archive file is outside the verified world path: " + file);
            }
            check("storedFileWithinWorld", true, file.toString());

            before = Files.readAllBytes(file);
            int recordsBefore = ArchiveService.snapshot(server, owner.getUUID(), stableId)
                    .map(snapshot -> snapshot.records().size()).orElse(-1);
            HuhuTalkChatCallback callback = phoneCallback(stableId, null);
            read(callback, CASE_IDS[0], false);
            search(callback, "星糖", "all");
            int recordsAfter = ArchiveService.snapshot(server, owner.getUUID(), stableId)
                    .map(snapshot -> snapshot.records().size()).orElse(-1);
            byte[] after = Files.readAllBytes(file);
            check("storedReadOnlyRecords", recordsAfter == recordsBefore,
                    "before=" + recordsBefore + " after=" + recordsAfter);
            check("storedReadOnlyBytes", java.util.Arrays.equals(before, after),
                    "bytes before=" + before.length + " after=" + after.length);

            Files.delete(file);
            JsonObject missing = read(callback, CASE_IDS[0], false);
            check("missingArchiveReturnsUnavailable",
                    "archive_unavailable".equals(status(missing)), status(missing));
            Files.write(file, before);

            Files.writeString(file, "{broken-json", StandardCharsets.UTF_8);
            JsonObject broken = read(callback, CASE_IDS[0], false);
            check("corruptReturnsArchiveUnavailable",
                    "archive_unavailable".equals(status(broken)), status(broken));
        } catch (Throwable throwable) {
            error("storageReadOnly", throwable);
            check("storedReadOnlyRecords", false, String.valueOf(throwable));
            check("storedReadOnlyBytes", false, String.valueOf(throwable));
            check("missingArchiveReturnsUnavailable", false, String.valueOf(throwable));
            check("corruptReturnsArchiveUnavailable", false, String.valueOf(throwable));
        } finally {
            try {
                if (before != null) {
                    Files.write(file, before);
                }
            } catch (Throwable restoreFailure) {
                error("restoreArchiveFile", restoreFailure);
            }
        }
        try {
            check("corruptRestored", before != null
                            && java.util.Arrays.equals(before, Files.readAllBytes(file)),
                    "restored bytes=" + (before == null ? -1 : before.length));
        } catch (Throwable throwable) {
            error("verifyArchiveRestore", throwable);
            check("corruptRestored", false, String.valueOf(throwable));
        }
    }

    private static void permissionChecks(MinecraftServer server) {
        try {
            LLMCallback plain = new LLMCallback(maid.getAiChatManager(), new ArrayList<>());
            check("authNonPhoneCallbackRejected",
                    "context_unavailable".equals(status(read(plain, CASE_IDS[0], false))),
                    "ordinary LLMCallback");

            HuhuTalkChatCallback wrongStable = phoneCallback(secondStableId, null);
            check("authStableMismatchRejected",
                    "forbidden".equals(status(read(wrongStable, CASE_IDS[0], false))),
                    "callback stable != maid stable");

            ServerPlayer replaced = new ServerPlayer(server, owner.serverLevel(),
                    owner.getGameProfile(), ClientInformation.createDefault());
            HuhuTalkChatCallback replacedCallback = new HuhuTalkChatCallback(
                    maid.getAiChatManager(), new ArrayList<>(), replaced, stableId, false, null);
            check("authReplacedPlayerRejected",
                    "context_unavailable".equals(status(read(replacedCallback, CASE_IDS[0], false))),
                    "same UUID but not in PlayerList");

            UUID realOwner = owner.getUUID();
            try {
                maid.setOwnerUUID(UUID.randomUUID());
                check("authMaidOwnerChangedRejected",
                        "forbidden".equals(status(read(phoneCallback(stableId, null),
                                CASE_IDS[0], false))), "maid temporarily owned by other player");
            } finally {
                maid.setOwnerUUID(realOwner);
            }
            try {
                maid.getMaidBauble().setStackInSlot(0, ItemStack.EMPTY);
                check("authPhoneRemovedRejected",
                        "forbidden".equals(status(read(phoneCallback(stableId, null),
                                CASE_IDS[0], false))), "phone removed");
            } finally {
                maid.getMaidBauble().setStackInSlot(0, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
            }

            HuhuTalkChatCallback callback = phoneCallback(stableId, null);
            JsonObject secretSearch = search(callback, "秘密", "all");
            JsonObject sameOwnerSecret = read(callback, sameOwnerSecretId, false);
            JsonObject otherOwnerSecret = read(callback, otherOwnerSecretId, false);
            String combined = secretSearch + sameOwnerSecret.toString() + otherOwnerSecret;
            boolean leaked = combined.contains("保险箱") || combined.contains("地窖");
            check("authSecretNotVisible", !leaked
                            && "no_match".equals(status(secretSearch))
                            && !"found".equals(status(sameOwnerSecret))
                            && !"found".equals(status(otherOwnerSecret)),
                    "search=" + status(secretSearch) + " sameOwner=" + status(sameOwnerSecret)
                            + " otherOwner=" + status(otherOwnerSecret) + " leaked=" + leaked);
            check("ownerPhoneRestored", PhoneHelper.hasPhone(maid)
                            && realOwner.equals(maid.getOwnerUUID()),
                    "phone+owner restored");
        } catch (Throwable throwable) {
            error("permissionChecks", throwable);
            check("authSecretNotVisible", false, String.valueOf(throwable));
        }
    }

    private static String prompt(int index) {
        return "smoke prompt " + index;
    }

    private static String replyText(int index) {
        return switch (index) {
            case 1 -> IMAGE_REPLY;
            case 2 -> MAID_IMAGE_REPLY;
            case 3 -> PLAYER_ITEM_REPLY;
            case 4 -> MAID_ITEM_REPLY;
            case 5 -> UNKNOWN_REPLY;
            default -> REPLY_TEXT;
        };
    }

    private static void runScenario(MinecraftServer server, int index) {
        lastQuoteId = CASE_IDS[index];
        pendingScenario = index;
        pendingWait = 0;
        try {
            ArchiveTurnContext turn = ArchiveService.recordPlayerText(server, owner, maid, stableId,
                    prompt(index));
            HuhuTalkChatCallback callback = phoneCallback(stableId, turn);
            JsonObject result = read(callback, CASE_IDS[index], true);
            PhoneMessageQuote quote = callback.getQuote();
            boolean expectedKind;
            boolean expectedRole;
            boolean expectedTime;
            switch (index) {
                case 1 -> { expectedKind = quote != null && quote.kind() == PhoneMessageQuote.Kind.IMAGE;
                    expectedRole = quote != null && quote.role() == ArchiveRole.PLAYER;
                    expectedTime = quote != null && quote.recordedAtEpochMillis() == PAST_MILLIS; }
                case 2 -> { expectedKind = quote != null && quote.kind() == PhoneMessageQuote.Kind.IMAGE;
                    expectedRole = quote != null && quote.role() == ArchiveRole.MAID;
                    expectedTime = quote != null && quote.recordedAtEpochMillis() == PAST_MILLIS; }
                case 3 -> { expectedKind = quote != null && quote.kind() == PhoneMessageQuote.Kind.ITEMS
                            && "Diamond×3".equals(quote.preview());
                    expectedRole = quote != null && quote.role() == ArchiveRole.PLAYER;
                    expectedTime = quote != null && quote.recordedAtEpochMillis() == PAST_MILLIS; }
                case 4 -> { expectedKind = quote != null && quote.kind() == PhoneMessageQuote.Kind.ITEMS
                            && "Bread×2".equals(quote.preview());
                    expectedRole = quote != null && quote.role() == ArchiveRole.MAID;
                    expectedTime = quote != null && quote.recordedAtEpochMillis() == PAST_MILLIS; }
                case 5 -> { expectedKind = quote != null && quote.kind() == PhoneMessageQuote.Kind.TEXT;
                    expectedRole = quote != null && quote.role() == ArchiveRole.PLAYER;
                    expectedTime = quote != null && quote.recordedAtEpochMillis() == 0L; }
                default -> { expectedKind = quote != null && quote.kind() == PhoneMessageQuote.Kind.TEXT;
                    expectedRole = quote != null && quote.role() == ArchiveRole.PLAYER;
                    expectedTime = quote != null && quote.recordedAtEpochMillis() == PAST_MILLIS; }
            }
            check("scenario_" + (index + 1) + "_toolQuote",
                    "found".equals(status(result)) && quote != null
                            && CASE_IDS[index].equals(quote.messageId())
                            && expectedKind && expectedRole && expectedTime,
                    "status=" + status(result) + " quote=" + quote);
            if (index == 2) {
                check("futureMaidImageFixture", quote != null
                        && quote.kind() == PhoneMessageQuote.Kind.IMAGE
                        && quote.role() == ArchiveRole.MAID, "fixture only, no real maid image sent");
            }
            if (index == 3 || index == 4) {
                check("itemsBothDirections", quote != null && quote.kind() == PhoneMessageQuote.Kind.ITEMS,
                        "index=" + index + " preview=" + (quote == null ? "null" : quote.preview()));
            }
            String text = replyText(index);
            callback.onSuccess(new ResponseChat(text, text));
            pendingTurn = turn;
            check("ttsIsolatedOff", !AIConfig.TTS_ENABLED.get(), "AIConfig.TTS_ENABLED=false");
        } catch (Throwable throwable) {
            error("scenario_" + (index + 1), throwable);
            check("scenario_" + (index + 1) + "_toolQuote", false, String.valueOf(throwable));
            scenarioHandled = index;
            pendingScenario = -1;
        }
    }

    private static void tickPending(MinecraftServer server) {
        if (++pendingWait < 5) {
            return;
        }
        int index = pendingScenario;
        try {
            ArchiveTurnContext turn = pendingTurn;
            Optional<ArchiveRecord> reply = ArchiveService.snapshot(server, owner.getUUID(), stableId)
                    .flatMap(snapshot -> snapshot.records().stream()
                            .filter(record -> record.role() == ArchiveRole.MAID
                                    && record.turnId().equals(turn.turnId()))
                            .findFirst());
            boolean present = reply.isPresent();
            boolean related = present && turn.playerMessageId().equals(reply.get().relatedMessageId());
            boolean quoted = present && CASE_IDS[index].equals(reply.get().quotedMessageId());
            check("scenario_" + (index + 1) + "_archiveReply", present, "maid reply archived");
            check("scenario_" + (index + 1) + "_replyQuoteSeparated", related && quoted,
                    "related=" + (present ? reply.get().relatedMessageId() : "none")
                            + " quoted=" + (present ? reply.get().quotedMessageId() : "none"));
            check("scenario_" + (index + 1) + "_history", !maid.getAiChatManager()
                            .getHistory().getDeque().isEmpty(),
                    "assistant history recorded");
            pendingTurn = null;
        } catch (Throwable throwable) {
            error("verifyScenario_" + (index + 1), throwable);
            check("scenario_" + (index + 1) + "_archiveReply", false, String.valueOf(throwable));
            check("scenario_" + (index + 1) + "_replyQuoteSeparated", false, String.valueOf(throwable));
        } finally {
            scenarioHandled = index;
            pendingScenario = -1;
        }
    }
}
