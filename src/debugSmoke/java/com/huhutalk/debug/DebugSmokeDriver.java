package com.huhutalk.debug;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsLLMSiteScreen;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.huhutalk.api.phone.ui.PhoneDropdown;
import com.huhutalk.api.phone.ui.PhonePage;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.vision.VisionSiteCache;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.vision.VisionProviders;
import com.huhutalk.core.chat.MaidNarrativeBus;
import com.huhutalk.core.rescue.PendingRescueManager;
import com.huhutalk.core.rescue.RescueMaidPicker;
import com.huhutalk.core.rescue.RescueSettings;
import com.huhutalk.core.transfer.MaidTransferService;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.pages.SettingsPage;
import com.huhutalk.phone.pages.VisionModelSettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 隔离的原生 NeoForge client 验收驱动（仅 -PdebugSmoke 运行）。
 *
 * <p>它只操作显式绝对路径核对通过的 debug-smoke 隔离世界；不访问用户实例/凭据，也不发真实
 * API 请求（测试站点 client() 被替换为纯计数 mock）。普通 client/server 与正式 jar 不含本类。</p>
 */
@EventBusSubscriber(modid = "huhutalk", value = Dist.CLIENT)
public final class DebugSmokeDriver {

    private static final String PROP_ENABLED = "huhutalk.debugSmoke";
    private static final String PROP_WORLD = "huhutalk.debugSmokeWorld";
    private static final String PROP_OUTPUT = "huhutalk.debugSmokeOutput";
    private static final String TEST_SITE_ID = "huhutalk_debug_smoke";
    private static final long TIMEOUT_NANOS = 240_000_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DebugSmokeDriver() {
    }

    private static final Map<String, Object> CHECKS = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> ERRORS = new CopyOnWriteArrayList<>();
    private static final List<String> SCREENSHOTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger MOCK_CALLS = new AtomicInteger();
    private static final List<List<LLMMessage>> MOCK_CALLBACKS = new CopyOnWriteArrayList<>();

    /** 成功路径必须出现的检查；缺失即视为失败，避免"少跑一条断言"被当成通过。 */
    private static final Set<String> REQUIRED_CHECKS = Set.of(
            "isolatedWorldPath",
            "playerAliveAtStart",
            "spawnProtectionWait80",
            "mockSiteEnabled",
            "maidPhoneEquipped",
            "maidHasHealingPotion",
            "deathTriggered",
            "revivedAlive",
            "revivedNewInstance",
            "revivedHealth8",
            "connectionRepointed",
            "potionConsumed",
            "respawnAnchorUnchanged",
            "revivedAtDeathPoint",
            "pendingRescueCleared",
            "rescueOffMockCallsZero",
            "queueOnlySupplyWhenOff",
            "onThenOffDropped",
            "transferResultOk",
            "transferPlayerDiamondsThreeBefore",
            "transferMaidDiamondsZeroBefore",
            "transferPlayerDiamondsZero",
            "transferMaidDiamondsThree",
            "transferItemDescription",
            "transferStrictLastUserCaption",
            "transferStrictItemSystem",
            "transferMockCalled",
            "platformIcons4Decoded",
            "settingsRescueRowScrolled",
            "doubaoExpandedWithModels",
            "screenshot_01-platform-icons",
            "screenshot_02-rescue-setting",
            "screenshot_03-doubao-models"
    );

    private static volatile boolean finished = false;
    private static volatile boolean serverBusinessDone = false;
    private static volatile boolean serverBusinessOk = false;
    private static long clientStartedNanos = -1L;

    private static UUID playerId;
    private static ServerPlayer oldPlayer;
    private static ServerGamePacketListenerImpl oldConnection;
    private static BlockPos respawnBefore;
    private static ResourceKey<Level> respawnDimBefore;
    private static Vec3 deathPos;
    private static EntityMaid maid;
    private static String stableId;
    private static int mockCallsAtDeath = 0;

    private static volatile int serverPhase = 0;
    private static int serverTicks = 0;
    private static int spawnWaitTicks = 0;
    private static boolean worldValidated = false;
    private static boolean deadPreprocessed = false;
    private static int queueCallsBefore = 0;
    private static int queueCallbacksBefore = 0;
    private static int onOffCallsBefore = 0;
    private static int transferCallsBefore = 0;
    private static int transferCallbacksBefore = 0;

    private static int clientPhase = 0;
    private static int clientTicks = 0;
    private static boolean settingsPushed = false;
    private static boolean visionPushed = false;
    private static boolean visionPrepared = false;
    private static boolean shotPending = false;
    private static boolean shotDone = false;
    private static String shotName = null;
    private static Map<String, Object> platformIconCheck = new LinkedHashMap<>();
    private static Map<String, Object> settingsCheck = new LinkedHashMap<>();
    private static Map<String, Object> doubaoCheck = new LinkedHashMap<>();

    // ==================== 事件入口 ====================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled() || finished) {
            return;
        }
        try {
            tickServer(event.getServer());
            if (serverPhase >= 90) {
                serverBusinessOk = ERRORS.isEmpty() && allChecksOk()
                        && CHECKS.containsKey("transferMockCalled");
                serverBusinessDone = true;
            }
        } catch (Throwable t) {
            error("serverTick", t);
            serverPhase = 90;
            serverBusinessOk = false;
            serverBusinessDone = true;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled() || finished) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (clientStartedNanos < 0) {
            clientStartedNanos = System.nanoTime();
        }
        if (System.nanoTime() - clientStartedNanos > TIMEOUT_NANOS) {
            check("hardTimeout240s", false, "elapsed=" + elapsedMs() + "ms");
            finish(mc);
            return;
        }
        try {
            tickClient(mc);
        } catch (Throwable t) {
            error("clientTick", t);
            finish(mc);
        }
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        if (!enabled() || finished || !shotPending) {
            return;
        }
        shotPending = false;
        Minecraft mc = Minecraft.getInstance();
        Path out = outputDir().resolve(shotName + ".png");
        try {
            Files.createDirectories(out.getParent());
            com.mojang.blaze3d.platform.NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            if (image == null) {
                check("screenshot_" + shotName, false, "Screenshot.takeScreenshot returned null");
            } else {
                try {
                    image.writeToFile(out.toFile());
                    SCREENSHOTS.add(out.toAbsolutePath().toString());
                    check("screenshot_" + shotName, Files.isRegularFile(out), out.toAbsolutePath().toString());
                } catch (Throwable t) {
                    error("screenshot:" + shotName, t);
                    check("screenshot_" + shotName, false, String.valueOf(t));
                } finally {
                    image.close();
                }
            }
        } catch (Throwable t) {
            error("screenshotRequest:" + shotName, t);
            check("screenshot_" + shotName, false, String.valueOf(t));
        }
        shotDone = true;
    }

    // ==================== 服务端阶段 ====================

    private static void tickServer(MinecraftServer server) {
        if (server == null) {
            return;
        }
        switch (serverPhase) {
            case 0 -> prepareServer(server);
            case 1 -> {
                serverTicks++;
                if (serverTicks >= 140) {
                    verifyRescue(server);
                }
            }
            case 2 -> {
                serverTicks++;
                if (serverTicks >= 3) {
                    verifyQueueGate(server);
                }
            }
            case 3 -> {
                serverTicks++;
                if (serverTicks >= 3) {
                    verifyOnOffGate();
                }
            }
            case 4 -> {
                serverTicks++;
                if (serverTicks >= 1) {
                    runTransferTest(server);
                }
            }
            default -> {
            }
        }
    }

    private static void prepareServer(MinecraftServer server) {
        // 1) 世界路径必须与显式属性一致；否则绝不改世界。
        String expected = System.getProperty(PROP_WORLD, "");
        Path expectedPath;
        Path actualPath;
        try {
            expectedPath = Path.of(expected).toRealPath();
            actualPath = server.getWorldPath(LevelResource.ROOT).toRealPath();
        } catch (Throwable t) {
            check("isolatedWorldPath", false, "expected='" + expected + "' error=" + t);
            serverPhase = 90;
            return;
        }
        boolean worldOk = expectedPath.equals(actualPath);
        check("isolatedWorldPath", worldOk, "actual=" + actualPath);
        if (!worldOk) {
            serverPhase = 90;
            return;
        }

        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        if (player.isDeadOrDying() || !player.isAlive()) {
            check("playerAliveAtStart", false, "player is dead at start; upstream seed must be alive");
            serverPhase = 90;
            return;
        }

        check("playerAliveAtStart", true, "connected player alive before test");
        if (++spawnWaitTicks < 80) {
            return;
        }
        check("spawnProtectionWait80", true, "waited " + spawnWaitTicks + " natural server ticks");
        try {
            setupDeathScenario(server, player);
            serverPhase = 1;
            serverTicks = 0;
        } catch (Throwable t) {
            error("setupDeathScenario", t);
            serverPhase = 90;
        }
    }

    private static void setupDeathScenario(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        player.setGameMode(GameType.SURVIVAL);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false, server);
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION).set(false, server);

        // 安全平台：Y99 石头层，玩家脚在 Y100。
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
        player.getInventory().clearContent();
        player.getInventory().items.set(20, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));

        installMockSite();

        EntityMaid entityMaid = new EntityMaid(level);
        entityMaid.setPos(2.5D, 100.0D, 0.5D);
        entityMaid.setTame(true, true);
        entityMaid.setOwnerUUID(player.getUUID());
        entityMaid.setPersistenceRequired();
        entityMaid.setInSittingPose(true);
        level.addFreshEntity(entityMaid);
        entityMaid.getMaidBauble().setStackInSlot(0, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
        entityMaid.getAvailableBackpackInv().setStackInSlot(0, strongHealingPotion());
        entityMaid.getAiChatManager().llmSite = TEST_SITE_ID;
        maid = entityMaid;
        stableId = MaidStableIdHelper.getOrCreateStableId(entityMaid);
        check("mockSiteEnabled", AvailableSites.getLLMSite(TEST_SITE_ID) != null
                && AvailableSites.getLLMSite(TEST_SITE_ID).enabled(), TEST_SITE_ID);
        check("maidPhoneEquipped", com.huhutalk.common.PhoneHelper.hasPhone(entityMaid), "bauble slot 0");
        check("maidHasHealingPotion", RescueMaidPicker.hasHealingPotion(entityMaid), "backpack slot 0");

        RescueSettings.set(player.getUUID(), false);

        playerId = player.getUUID();
        oldPlayer = player;
        oldConnection = player.connection;
        respawnBefore = player.getRespawnPosition();
        respawnDimBefore = player.getRespawnDimension();
        deathPos = player.position();
        mockCallsAtDeath = MOCK_CALLS.get();

        player.fallDistance = 4.0F;
        player.hurt(level.damageSources().fall(), 1000.0F);
        check("deathTriggered", player.isDeadOrDying(), "hurt fall 1000 at Y100");
    }

    private static void verifyRescue(MinecraftServer server) {
        ServerPlayer revived = server.getPlayerList().getPlayer(playerId);
        boolean alive = revived != null && revived.isAlive() && !revived.isDeadOrDying();
        check("revivedAlive", alive, revived == null ? "null" : ("alive=" + revived.isAlive()
                + " dead=" + revived.isDeadOrDying()));
        check("revivedNewInstance", revived != null && revived != oldPlayer, "new ServerPlayer instance");
        check("revivedHealth8", revived != null && Math.abs(revived.getHealth() - 8.0F) < 0.01F,
                revived == null ? "null" : ("hp=" + revived.getHealth()));
        check("connectionRepointed", revived != null && oldConnection != null
                        && oldConnection.player == revived && revived.connection == oldConnection,
                "old listener points at new player");
        check("potionConsumed", maid != null && !RescueMaidPicker.hasHealingPotion(maid),
                maid == null ? "maid null" : ("backpack0=" + maid.getAvailableBackpackInv().getStackInSlot(0)));
        check("respawnAnchorUnchanged", revived != null
                        && Objects.equals(respawnBefore, revived.getRespawnPosition())
                        && Objects.equals(respawnDimBefore, revived.getRespawnDimension()),
                "before=" + respawnBefore + " after=" + (revived == null ? "null" : revived.getRespawnPosition()));
        check("revivedAtDeathPoint", revived != null && revived.position().distanceTo(deathPos) < 2.0D,
                revived == null ? "null" : ("pos=" + revived.position()));
        check("pendingRescueCleared", PendingRescueManager.get(playerId) == null, "PendingRescueManager.get == null");
        check("rescueOffMockCallsZero", MOCK_CALLS.get() == mockCallsAtDeath,
                "calls=" + MOCK_CALLS.get() + " expected=" + mockCallsAtDeath);

        // 队列门控（开关 false）：三种救援类型 + 一个普通供给 marker。
        queueCallsBefore = MOCK_CALLS.get();
        queueCallbacksBefore = MOCK_CALLBACKS.size();
        RescueSettings.set(playerId, false);
        submitMarker(MaidNarrativeBus.Kind.RESCUE, "SMOKE_RESCUE_MARKER");
        submitMarker(MaidNarrativeBus.Kind.RESCUE_LOW_POTION, "SMOKE_RESCUE_LOW_MARKER");
        submitMarker(MaidNarrativeBus.Kind.RESCUE_STREAK_SUMMARY, "SMOKE_RESCUE_STREAK_MARKER");
        submitMarker(MaidNarrativeBus.Kind.SUPPLY_USED_UP, "SMOKE_SUPPLY_MARKER");
        serverPhase = 2;
        serverTicks = 0;
    }

    private static void verifyQueueGate(MinecraftServer server) {
        int delta = MOCK_CALLS.get() - queueCallsBefore;
        String text = newCallbacksText(queueCallbacksBefore);
        boolean supplySeen = text.contains("SMOKE_SUPPLY_MARKER");
        boolean rescueSeen = text.contains("SMOKE_RESCUE_MARKER")
                || text.contains("SMOKE_RESCUE_LOW_MARKER")
                || text.contains("SMOKE_RESCUE_STREAK_MARKER");
        check("queueOnlySupplyWhenOff", delta == 1 && supplySeen && !rescueSeen,
                "callsDelta=" + delta + " supply=" + supplySeen + " rescue=" + rescueSeen);

        // ON 排队、随后立刻 OFF：下一 tick 必须是"不漏网地丢弃"，而不是补播。
        onOffCallsBefore = MOCK_CALLS.get();
        RescueSettings.set(playerId, true);
        submitMarker(MaidNarrativeBus.Kind.RESCUE, "SMOKE_RESCUE_TOGGLE_MARKER");
        RescueSettings.set(playerId, false);
        serverPhase = 3;
        serverTicks = 0;
    }

    private static void verifyOnOffGate() {
        check("onThenOffDropped", MOCK_CALLS.get() == onOffCallsBefore,
                "calls=" + MOCK_CALLS.get() + " expected=" + onOffCallsBefore);
        serverPhase = 4;
        serverTicks = 0;
    }

    private static void runTransferTest(MinecraftServer server) {
        transferCallsBefore = MOCK_CALLS.get();
        transferCallbacksBefore = MOCK_CALLBACKS.size();
        try {
            // 女仆可用背包清空，玩家 slot1 放钻石 x3。
            var maidInv = maid.getAvailableBackpackInv();
            for (int i = 0; i < maidInv.getSlots(); i++) {
                maidInv.setStackInSlot(i, ItemStack.EMPTY);
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                check("transferPlayerOnline", false, "player null");
                serverPhase = 90;
                return;
            }
            player.getInventory().clearContent();
            player.getInventory().items.set(1, new ItemStack(Items.DIAMOND, 3));
            check("transferPlayerDiamondsThreeBefore", player.getInventory().items.get(1).getCount() == 3,
                    "player diamonds before transfer=3");
            check("transferMaidDiamondsZeroBefore", countItem(maidInv, Items.DIAMOND) == 0,
                    "maid diamonds before transfer=0");

            MaidTransferService.ChatWithItemsResult result =
                    MaidTransferService.trySendChatWithItems(player, stableId, "smoke-caption",
                            List.of(1), false, false);
            check("transferResultOk", result != null && result.ok(),
                    result == null ? "null" : ("problem=" + result.problem()));
            check("transferPlayerDiamondsZero", player.getInventory().items.get(1).getCount() == 0,
                    "slot1=" + player.getInventory().items.get(1));
            int maidDiamonds = countItem(maidInv, Items.DIAMOND);
            check("transferMaidDiamondsThree", maidDiamonds == 3, "maidDiamonds=" + maidDiamonds);

            String diamondName = new ItemStack(Items.DIAMOND).getHoverName().getString();
            String text = newCallbacksText(transferCallbacksBefore);
            boolean itemNote = text.contains(diamondName) && text.contains("x3");
            check("transferItemDescription", itemNote,
                    "contains '" + diamondName + "' and x3");
            check("transferLastUserCaption", "smoke-caption".equals(lastUserMessage(transferCallbacksBefore)),
                    "lastUser=" + lastUserMessage(transferCallbacksBefore));
            List<LLMMessage> latest = MOCK_CALLBACKS.size() > transferCallbacksBefore
                    ? MOCK_CALLBACKS.get(MOCK_CALLBACKS.size() - 1) : List.of();
            int lastUser = -1;
            for (int i = 0; i < latest.size(); i++) {
                if ("USER".equals(latest.get(i).role().name())) lastUser = i;
            }
            check("transferStrictLastUserCaption", lastUser >= 0
                    && "smoke-caption".equals(latest.get(lastUser).message()), "last USER is exact raw caption");
            boolean adjacent = lastUser > 0
                    && "SYSTEM".equals(latest.get(lastUser - 1).role().name())
                    && latest.get(lastUser - 1).message().contains(diamondName)
                    && latest.get(lastUser - 1).message().contains("x3");
            check("transferStrictItemSystem", adjacent, "item SYSTEM immediately before last USER");
            check("transferMockCalled", MOCK_CALLS.get() > transferCallsBefore,
                    "calls=" + MOCK_CALLS.get());
        } catch (Throwable t) {
            error("transferTest", t);
        }
        serverPhase = 90;
    }

    // ==================== 客户端阶段 ====================

    private static void tickClient(Minecraft mc) {
        if (!serverBusinessDone) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        switch (clientPhase) {
            case 0 -> {
                mc.setScreen(null);
                HuhuTalkClientSettings.setPotionRescueEnabled(false);
                clientPhase = 1;
                clientTicks = 0;
            }
            case 1 -> {
                if (clientTicks == 0) {
                    openPlatformScreen(mc);
                }
                clientTicks++;
                if (clientTicks >= 30) {
                    requestShot("01-platform-icons");
                    clientPhase = 2;
                    clientTicks = 0;
                }
            }
            case 2 -> {
                if (!shotDone) {
                    return;
                }
                if (!settingsPushed) {
                    openSettingsScreen();
                    settingsPushed = true;
                    clientTicks = 0;
                    return;
                }
                clientTicks++;
                if (clientTicks >= 30) {
                    requestShot("02-rescue-setting");
                    clientPhase = 3;
                    clientTicks = 0;
                }
            }
            case 3 -> {
                if (!shotDone) {
                    return;
                }
                if (!visionPushed) {
                    PhoneOverlay.pushPage(new VisionModelSettingsPage());
                    visionPushed = true;
                    clientTicks = 0;
                    return;
                }
                clientTicks++;
                if (!visionPrepared) {
                    if (!VisionSiteCache.sites().isEmpty() && clientTicks > 2) {
                        prepareDoubaoPage();
                        visionPrepared = true;
                        clientTicks = 0;
                    } else if (clientTicks > 120) {
                        check("doubaoPageAvailable", false, "VisionSiteCache.sites() empty after 120 ticks");
                        visionPrepared = true;
                        clientTicks = 0;
                    }
                    return;
                }
                if (clientTicks >= 30) {
                    requestShot("03-doubao-models");
                    clientPhase = 4;
                    clientTicks = 0;
                }
            }
            case 4 -> {
                if (!shotDone) {
                    return;
                }
                if (serverPhase >= 90) {
                    finish(mc);
                }
            }
            default -> {
            }
        }
    }

    private static void openPlatformScreen(Minecraft mc) {
        Map<String, LLMSite> sites = new LinkedHashMap<>();
        String[] ids = {"huhutalk_qianfan", "huhutalk_hunyuan", "huhutalk_stepfun", "huhutalk_custom"};
        boolean allIcons = true;
        StringBuilder iconDetail = new StringBuilder();
        for (String id : ids) {
            LLMSite site = AvailableSites.getLLMSite(id);
            if (site == null) {
                check("platformSite_" + id, false, "actual injected site missing");
                allIcons = false;
                continue;
            }
            ResourceLocation icon = site.icon();
            sites.put(id, site);
            boolean decoded = false;
            try {
                var resource = mc.getResourceManager().getResource(icon);
                if (resource.isPresent()) {
                    try (InputStream in = resource.get().open();
                         var image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                        decoded = image.getWidth() > 0 && image.getHeight() > 0;
                    }
                }
            } catch (Throwable t) {
                error("decodeIcon:" + id, t);
            }
            allIcons &= decoded;
            iconDetail.append(id).append('=').append(decoded).append(' ');
        }
        platformIconCheck.put("ok", allIcons);
        platformIconCheck.put("detail", iconDetail.toString().trim());
        check("platformIcons4Decoded", allIcons, iconDetail.toString().trim());
        mc.setScreen(new AIChatSettingsLLMSiteScreen(null, sites, Map.of(), false));
        clientTicks = 0;
    }

    private static void openSettingsScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        PhoneOverlay.toggle();
        PhoneOverlay.openChatApp();
        SettingsPage page = new SettingsPage();
        PhoneOverlay.pushPage(page);
        int offset = 0;
        try {
            Object layout = invokeDeclared(page, "layout");
            Object titleYsObj = fieldValue(layout, "titleYs");
            if (titleYsObj instanceof List<?> titleYs && titleYs.size() > 1 && titleYs.get(1) instanceof Integer y) {
                offset = Math.max(0, y - 12);
            }
        } catch (Throwable t) {
            error("settingsLayoutReflection", t);
        }
        setScrollOffset(page, offset);
        settingsCheck.put("ok", offset > 0);
        settingsCheck.put("detail", "scrollOffset=" + offset);
        check("settingsRescueRowScrolled", offset > 0, "offset=" + offset);
    }

    private static void prepareDoubaoPage() {
        try {
            Object page = PhoneOverlay.getCurrentPage();
            if (!(page instanceof VisionModelSettingsPage visionPage)) {
                check("doubaoPageIsVision", false, String.valueOf(page));
                return;
            }
            Object expandedObj = fieldValue(visionPage, "expanded");
            @SuppressWarnings("unchecked")
            java.util.Set<String> expanded = (java.util.Set<String>) expandedObj;
            expanded.add("doubao");
            Object dropdownsObj = fieldValue(visionPage, "dropdowns");
            @SuppressWarnings("unchecked")
            Map<String, PhoneDropdown> dropdowns = (Map<String, PhoneDropdown>) dropdownsObj;
            PhoneDropdown doubao = dropdowns.get("doubao");
            boolean expandedOk = doubao != null;
            if (doubao != null) {
                doubao.setExpanded(true);
            }

            Object layout = invokeDeclared(visionPage, "layout");
            int modelRowY = -1;
            int cardCount = 0;
            if (layout instanceof List<?> cards) {
                cardCount = cards.size();
                for (Object card : cards) {
                    Object preset = fieldValue(card, "preset");
                    if (preset == VisionProviders.DOUBAO) {
                        Object y = fieldValue(card, "modelRowY");
                        if (y instanceof Integer i) {
                            modelRowY = i;
                        }
                        break;
                    }
                }
            }
            int target = Math.max(0, modelRowY - 15);
            setScrollOffset(visionPage, target);
            doubaoCheck.put("ok", expandedOk && modelRowY >= 0);
            doubaoCheck.put("detail", "cards=" + cardCount + " modelRowY=" + modelRowY
                    + " scroll=" + target + " dropdownExpanded=" + expandedOk);
            check("doubaoExpandedWithModels", expandedOk && modelRowY >= 0,
                    String.valueOf(doubaoCheck.get("detail")));
        } catch (Throwable t) {
            error("prepareDoubaoPage", t);
            doubaoCheck.put("ok", false);
            doubaoCheck.put("detail", String.valueOf(t));
            check("doubaoExpandedWithModels", false, String.valueOf(t));
        }
    }

    private static void requestShot(String name) {
        shotName = name;
        shotPending = true;
        shotDone = false;
    }

    private static void finish(Minecraft mc) {
        if (finished) {
            return;
        }
        finished = true;
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("task", "debug-native-smoke");
            root.put("worldProperty", System.getProperty(PROP_WORLD, ""));
            var missing = REQUIRED_CHECKS.stream().filter(k -> !CHECKS.containsKey(k)).sorted().toList();
            root.put("missingChecks", missing);
            root.put("completed", missing.isEmpty() && ERRORS.isEmpty() && allChecksOk()
                    && serverBusinessDone && serverBusinessOk && SCREENSHOTS.size() == 3);
            root.put("checks", CHECKS);
            root.put("errors", ERRORS);
            root.put("screenshots", SCREENSHOTS);
            root.put("mockCalls", MOCK_CALLS.get());
            root.put("elapsedMs", elapsedMs());
            Map<String, Object> display = new LinkedHashMap<>();
            if (mc != null) {
                display.put("windowWidth", mc.getWindow().getWidth());
                display.put("windowHeight", mc.getWindow().getHeight());
                display.put("guiScaledWidth", mc.getWindow().getGuiScaledWidth());
                display.put("guiScaledHeight", mc.getWindow().getGuiScaledHeight());
                display.put("guiScale", mc.options.guiScale().get());
                display.put("fov", mc.options.fov().get());
                display.put("pitch", mc.player == null ? null : mc.player.getXRot());
            }
            root.put("display", display);
            Path out = outputDir().resolve("result.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            error("writeResult", t);
        }
        if (mc != null) {
            mc.stop();
        }
    }

    // ==================== 测试站点 / 工具 ====================

    private static void installMockSite() {
        AvailableSites.LLM_SITES.put(TEST_SITE_ID, new MockSite());
    }

    /** enabled=true，但 client() 只计数/抓 messages，绝不发 HTTP、不返回假生产回复。 */
    private static final class MockSite extends LLMOpenAISite {
        private MockSite() {
            super(TEST_SITE_ID,
                    ResourceLocation.fromNamespaceAndPath("touhou_little_maid",
                            "textures/gui/ai_chat/huhutalk_custom.png"),
                    "http://127.0.0.1:1/never-requested", true, "", Map.of(),
                    List.of(new LLMOpenAISite.ModelEntry("mock-model")));
        }

        @Override
        public LLMClient client() {
            return (LLMCallback callback) -> {
                MOCK_CALLS.incrementAndGet();
                MOCK_CALLBACKS.add(new ArrayList<>(callback.getMessages()));
            };
        }
    }

    private static ItemStack strongHealingPotion() {
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_HEALING));
        return stack;
    }

    private static void submitMarker(MaidNarrativeBus.Kind kind, String marker) {
        MaidNarrativeBus.submit(playerId, stableId, kind, marker, null, "smoke-closing", 0);
    }

    private static String newCallbacksText(int fromIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, fromIndex); i < MOCK_CALLBACKS.size(); i++) {
            for (LLMMessage message : MOCK_CALLBACKS.get(i)) {
                sb.append(message.role()).append(':').append(message.message()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String lastUserMessage(int fromIndex) {
        String last = null;
        for (int i = Math.max(0, fromIndex); i < MOCK_CALLBACKS.size(); i++) {
            for (LLMMessage message : MOCK_CALLBACKS.get(i)) {
                if ("USER".equals(message.role().name())) {
                    last = message.message();
                }
            }
        }
        return last;
    }

    private static int countItem(net.neoforged.neoforge.items.IItemHandler handler, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean enabled() {
        return Boolean.getBoolean(PROP_ENABLED);
    }

    private static Path outputDir() {
        return Path.of(System.getProperty(PROP_OUTPUT, "debug-smoke-results"));
    }

    private static long elapsedMs() {
        return clientStartedNanos < 0 ? 0L : (System.nanoTime() - clientStartedNanos) / 1_000_000L;
    }

    private static void check(String name, boolean ok, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ok", ok);
        entry.put("detail", detail);
        CHECKS.put(name, entry);
    }

    private static boolean allChecksOk() {
        for (Object value : CHECKS.values()) {
            if (value instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"))) {
                return false;
            }
        }
        return true;
    }

    private static void error(String where, Throwable t) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("where", where);
        entry.put("error", String.valueOf(t));
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append(element).append('\n');
        }
        entry.put("stacktrace", sb.toString());
        ERRORS.add(entry);
    }

    // ==================== 反射（仅测试代码；不改生产可见性） ====================

    private static Object invokeDeclared(Object target, String name) throws Exception {
        Method method = findMethod(target.getClass(), name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // 继续向父类找
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static void setScrollOffset(Object page, int offset) {
        try {
            Method method = findMethod(PhonePage.class, "setScrollOffset", int.class);
            method.setAccessible(true);
            method.invoke(page, offset);
        } catch (Throwable t) {
            error("setScrollOffset", t);
        }
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类找
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "#" + name);
    }
}
