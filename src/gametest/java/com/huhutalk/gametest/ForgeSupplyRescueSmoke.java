package com.huhutalk.gametest;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.DefaultLLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.PlayerPhoneHelper;
import com.huhutalk.core.rescue.PendingRescue;
import com.huhutalk.core.rescue.PendingRescueManager;
import com.huhutalk.core.rescue.RescuePotion;
import com.huhutalk.core.rescue.RescueSettings;
import com.huhutalk.core.supply.RemoteSupplySession;
import com.huhutalk.core.supply.RemoteSupplySessions;
import com.huhutalk.core.supply.RemoteSupplySettings;
import com.huhutalk.core.supply.TaskRemoteSupply;
import com.huhutalk.init.HuhuTalkItems;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 仅由 {@code -PsupplyRescueSmoke} 启用的真实集成服「远程补给 + 药水复活」复现驱动。
 *
 * <p>本类位于独立 {@code gametest} 源集。{@code build.gradle} 只在显式传入
 * {@code -PsupplyRescueSmoke} 时把该源集加入客户端运行，并传入
 * {@code huhutalk.supplyRescueSmoke=true} 与独立输出目录；普通 {@code runClient}、
 * 服务端运行及正式 jar 都不包含它。未设置系统属性时所有事件入口立即返回，零操作。</p>
 *
 * <p>职责划分：{@link TickEvent.ClientTickEvent} 只负责硬超时与最终
 * {@link net.minecraft.client.Minecraft#stop()}（必须在客户端主线程）；
 * {@link TickEvent.ServerTickEvent}（END）在逻辑服务端推进状态机，并只操作
 * {@code --quickPlaySingleplayer port-smoke} 里真实已连接的 {@link ServerPlayer}。
 * 驱动不创建 FakePlayer，不手动调用生产 hook/service，不手动注册监听器，
 * 也不取消/修改任何被观察的事件。</p>
 *
 * <p>阶段目标：</p>
 * <ol>
 *   <li>补给回血：真实玩家 health=6、女仆背包 3 瓶治疗 I，等待 40 tick，应消耗药水并回血。</li>
 *   <li>补给补食：真实玩家 health=20、food=2、女仆背包 3 个面包，等待 40 tick，应补食。</li>
 *   <li>死亡救援：切 Idle、清供给库存、只留 1 瓶治疗 II，登记远床位，玩家以 42/10 视角
 *       在 0.5/101/0.5 摔死，等待 140 tick；应活着、是新实例、health=8、回到死亡点且
 *       床位登记不变、监听器指向新实例、只消耗 1 瓶药水、pending/redirect 已清理。</li>
 * </ol>
 *
 * <p>若女仆的 LLM 站点处于启用状态，死亡阶段会真实触发叙事 API。驱动只对当前站点及
 * DeepSeek 默认回退站点（{@link DefaultLLMSite#DEEPSEEK}、
 * {@link AvailableSites#getLLMSite(String)} 返回的 {@code deepseek} 对象）中实际的
 * {@link LLMOpenAISite} 内存对象调用 {@code setEnabled(false)}，保存原值并在
 * {@link ServerStoppingEvent} 恢复；全程不调用 {@code saveSites}/{@code writeSites}，
 * 不读写配置，也不输出任何密钥。无法识别或无法关闭的站点一律 fail closed（跳过死亡阶段）。
 * 驱动不改生产 {@code RescueSettings} 与叙事顺序，也不绕过 {@code tryExecute}。</p>
 *
 * <p>世界边界：{@code -Dhuhutalk.supplyRescueSmokeWorld} 必须是
 * {@code server.getWorldPath(LevelResource.ROOT)} 解析后的真实路径，否则在任何世界修改前
 * fatal 并正常退出。驱动只把结果写入独立输出目录的 {@code result.json}，不写用户世界存档
 * 数据或配置。</p>
 */
@Mod.EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeSupplyRescueSmoke {

    private static final boolean ENABLED = Boolean.getBoolean("huhutalk.supplyRescueSmoke");
    private static final long TIMEOUT_MS = 180_000L;

    /** 只为清理上一次中断运行遗留的本驱动测试女仆，不碰玩家自己的女仆。 */
    private static final String TEST_MAID_TAG = "HuhuTalkSupplyRescueSmoke";

    private static final int SUPPLY_WAIT_TICKS = 40;
    private static final int IDLE_SETTLE_TICKS = 20;
    private static final int RESCUE_WAIT_TICKS = 140;

    private static final double BASE_X = 0.5D;
    private static final double BASE_Y = 101.0D;
    private static final double BASE_Z = 0.5D;
    private static final float BASE_YAW = 42.0F;
    private static final float BASE_PITCH = 10.0F;

    private static final int BED_X = 20;
    private static final int BED_Y = 101;
    private static final int BED_Z = 20;

    private static final Object LOCK = new Object();

    private static final List<String> FAILURES = new ArrayList<>();
    private static final JsonObject SETUP = new JsonObject();
    private static final JsonObject SUPPLY_HEALTH = new JsonObject();
    private static final JsonObject SUPPLY_FOOD = new JsonObject();
    private static final JsonObject RESCUE = new JsonObject();

    private static volatile boolean finished;
    private static volatile boolean stopRequested;
    private static volatile boolean timedOut;
    private static volatile String fatalError;
    private static boolean resultWritten;
    private static long startedNanos;
    private static String startedAt;

    private static Phase phase = Phase.WAIT_WORLD;
    private static int phaseTicks;
    private static int phaseStartServerTicks;
    private static int phaseStartMaidTicks;
    private static int serverTickCount;
    private static int maidTickCount;
    private static int livingDeathCount;

    private static UUID playerId;
    private static EntityMaid maid;
    private static String stableId;
    private static ServerLevel level;
    private static Path outputDir;

    private static boolean deathPhaseSkipped;
    private static String deathSkipReason;
    private static boolean pendingRegisteredAtDeath;
    private static DeathSnapshot deathSnapshot;
    private static ServerPlayer preDeathPlayer;

    private static ResourceKey<Level> bedDimension;
    private static BlockPos bedPosition;
    private static float bedAngle;
    private static boolean bedForced;

    /** 本驱动仅在内存中临时关闭的 LLM 站点；只在 ServerStoppingEvent 恢复原 enabled 值。 */
    private static final List<DisabledLlmSite> DISABLED_LLM_SITES = new ArrayList<>();
    private static String llmSiteIdBefore;
    private static String llmSiteIdAfter;

    private static int healthPotionBefore;
    private static int foodHealthBefore;

    private ForgeSupplyRescueSmoke() {
    }

    // ==================== 事件入口 ====================

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || stopRequested) {
            return;
        }
        ensureStarted();
        if (outputDir == null) {
            HuhuTalk.LOGGER.error("[supply-rescue-smoke] 缺少 -Dhuhutalk.supplyRescueSmokeOutput，无法写 result.json");
            stopRequested = true;
            net.minecraft.client.Minecraft.getInstance().stop();
            return;
        }
        if (!finished && startedNanos != 0L
                && System.nanoTime() - startedNanos > TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)) {
            timedOut = true;
            synchronized (LOCK) {
                FAILURES.add("驱动超过 " + TIMEOUT_MS + "ms 未完成，当前阶段 " + phase);
            }
            finished = true;
            writeResultSafely();
        }
        if (finished) {
            stopRequested = true;
            net.minecraft.client.Minecraft.getInstance().stop();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        ensureStarted();
        serverTickCount++;
        if (finished) {
            return;
        }
        if (outputDir == null) {
            fatal("缺少 -Dhuhutalk.supplyRescueSmokeOutput，无法写 result.json", null);
            return;
        }
        try {
            step(event.getServer());
        } catch (Throwable t) {
            fatal("服务端状态机异常: " + stackTrace(t), t);
        }
    }

    /** 只读观察每次女仆 tick，用于确认真实 MaidTickEvent 确实在跑。 */
    @SubscribeEvent
    public static void onMaidTick(com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent event) {
        if (!ENABLED || event.getMaid().level().isClientSide()) {
            return;
        }
        maidTickCount++;
    }

    /** 只读观察死亡事件；绝不取消、修改或替换它。 */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!ENABLED || playerId == null) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer dead) || !playerId.equals(dead.getUUID())) {
            return;
        }
        livingDeathCount++;
        deathSnapshot = new DeathSnapshot(dead.level().dimension(), dead.getX(), dead.getY(), dead.getZ(),
                dead.getYRot(), dead.getXRot());
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 观察到 LivingDeathEvent: player={} dim={} pos={}/{}/{} rot={}/{}",
                dead.getName().getString(), deathSnapshot.dimension().location(),
                dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), dead.getXRot());
    }

    /**
     * 只恢复本驱动在内存中改过的站点 enabled 原值；不调用 drop/save/writeSites，
     * 不读凭据，不触碰配置目录。
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (!ENABLED) {
            return;
        }
        restoreLlmSites();
    }

    // ==================== 状态机 ====================

    private static void step(MinecraftServer server) {
        if (server == null || server.isDedicatedServer()) {
            return;
        }
        phaseTicks++;
        switch (phase) {
            case WAIT_WORLD -> waitWorld(server);
            case SUPPLY_HEALTH_WAIT -> {
                if (phaseTicks >= SUPPLY_WAIT_TICKS) {
                    recordSupplyHealth(server);
                    enter(Phase.SUPPLY_FOOD_SETUP);
                }
            }
            case SUPPLY_FOOD_SETUP -> setupSupplyFood(server);
            case SUPPLY_FOOD_WAIT -> {
                if (phaseTicks >= SUPPLY_WAIT_TICKS) {
                    recordSupplyFood(server);
                    if (maid != null) {
                        maid.setTask(TaskManager.getIdleTask());
                    }
                    enter(Phase.RESCUE_IDLE_WAIT);
                }
            }
            case RESCUE_IDLE_WAIT -> {
                if (phaseTicks >= IDLE_SETTLE_TICKS) {
                    prepareRescue(server);
                }
            }
            case RESCUE_DEATH_WAIT -> {
                if (phaseTicks == 1) {
                    checkPendingAtDeath();
                }
                if (phaseTicks >= RESCUE_WAIT_TICKS) {
                    recordRescue(server);
                    finishRun();
                }
            }
            default -> {
            }
        }
    }

    private static void waitWorld(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer joined = players.get(0);
        if (joined == null) {
            return;
        }
        playerId = joined.getUUID();
        if (!setup(server, joined)) {
            return;
        }
        enter(Phase.SUPPLY_HEALTH_WAIT);
    }

    // ==================== 隔离世界与真实实体 ====================

    private static boolean setup(MinecraftServer server, ServerPlayer joined) {
        // 任何世界修改（难度/规则/方块/实体/玩家状态）之前先核对隔离世界真实路径。
        if (!verifyIsolatedWorld(server)) {
            return false;
        }
        level = joined.serverLevel();
        server.setDifficulty(Difficulty.PEACEFUL, true);
        // 默认 true 保持既有复现语义；显式掉落变体走原生 keepInventory=false 死亡丢物。
        level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY)
                .set(!Boolean.getBoolean("huhutalk.supplyRescueDropInventory"), server);
        level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, server);

        buildPlatform(level, new BlockPos(0, 100, 0), 4);
        buildPlatform(level, new BlockPos(BED_X, BED_Y - 1, BED_Z), 3);

        // 上一次中断的运行可能留下本驱动的测试女仆；按持久标记精确清理，避免多只候选干扰复现。
        for (ServerLevel each : server.getAllLevels()) {
            List<Entity> stale = new ArrayList<>();
            for (Entity entity : each.getAllEntities()) {
                if (entity instanceof EntityMaid old
                        && old.getPersistentData().getBoolean(TEST_MAID_TAG)) {
                    stale.add(old);
                }
            }
            for (Entity entity : stale) {
                entity.discard();
            }
        }

        joined.setGameMode(GameType.SURVIVAL);
        joined.getInventory().clearContent();
        joined.getInventory().selected = 0;
        joined.getInventory().add(new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
        joined.moveTo(BASE_X, BASE_Y, BASE_Z, 0.0F, 0.0F);
        joined.connection.teleport(BASE_X, BASE_Y, BASE_Z, 0.0F, 0.0F);
        joined.setHealth(20.0F);
        joined.getFoodData().setFoodLevel(20);
        joined.getFoodData().setSaturation(0.0F);

        EntityType<EntityMaid> type = InitEntities.MAID.get();
        EntityMaid created = type.create(level);
        if (created == null) {
            fatal("InitEntities.MAID.get().create(level) 返回 null，无法创建真实女仆", null);
            return false;
        }
        created.moveTo(2.5D, BASE_Y, BASE_Z, 180.0F, 0.0F);
        created.setOwnerUUID(playerId);
        created.setTame(true);
        created.setPersistenceRequired();
        created.setInSittingPose(true);
        // 负例：-PsupplyRescueNoMaidPhone 时不佩戴手机，药水/玩家手机/任务保持不变。
        if (!Boolean.getBoolean("huhutalk.supplyRescueNoMaidPhone")) {
            created.getMaidBauble().setStackInSlot(0, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
        }
        created.getPersistentData().putBoolean(TEST_MAID_TAG, true);
        if (!level.addFreshEntity(created)) {
            fatal("真实女仆加入世界失败（addFreshEntity=false）", null);
            return false;
        }
        created.setTask(TaskRemoteSupply.INSTANCE);
        maid = created;
        stableId = MaidStableIdHelper.getOrCreateStableId(maid);

        RemoteSupplySettings.setReplyEnabled(playerId, false);
        RescueSettings.set(playerId, true);

        boolean aiSiteConfigured = false;
        boolean aiSiteEnabled = false;
        boolean aiSiteDisabledInMemory = false;
        boolean aiSiteEnabledAfterDisable = false;
        try {
            LLMSite site = maid.getAiChatManager().getLLMSite();
            aiSiteConfigured = site != null;
            aiSiteEnabled = site != null && site.enabled();
            llmSiteIdBefore = site == null ? "" : site.id();
        } catch (Throwable t) {
            SETUP.addProperty("aiSiteProbeError", stackTrace(t));
        }

        // 默认 DeepSeek 站点 enabled=true；不临时关闭就永远跳过死亡阶段。
        // 只改内存对象，ServerStoppingEvent 恢复原值，不写配置。
        try {
            if (disableLlmSitesInMemory()) {
                LLMSite effective = maid.getAiChatManager().getLLMSite();
                llmSiteIdAfter = effective == null ? "" : effective.id();
                aiSiteEnabledAfterDisable = effective != null && effective.enabled();
                aiSiteDisabledInMemory = !DISABLED_LLM_SITES.isEmpty();
                if (aiSiteEnabledAfterDisable) {
                    deathPhaseSkipped = true;
                    deathSkipReason = "内存关闭后女仆 LLM 站点仍启用，拒绝执行会触发真实叙事的死亡阶段";
                }
            } else {
                deathPhaseSkipped = true;
                deathSkipReason = "存在无法在内存中关闭的已启用 LLM 站点，拒绝执行死亡阶段";
            }
        } catch (Throwable t) {
            deathPhaseSkipped = true;
            deathSkipReason = "关闭 LLM 站点时异常，拒绝执行死亡阶段: " + t.getClass().getSimpleName();
            SETUP.addProperty("aiSiteDisableError", stackTrace(t));
        }
        if (deathPhaseSkipped) {
            HuhuTalk.LOGGER.warn("[supply-rescue-smoke] {}（未记录任何密钥）", deathSkipReason);
        }

        SETUP.addProperty("playerName", joined.getName().getString());
        SETUP.addProperty("playerUuid", playerId.toString());
        SETUP.addProperty("dimension", level.dimension().location().toString());
        SETUP.addProperty("difficulty", level.getDifficulty().getKey());
        SETUP.addProperty("keepInventory", level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY));
        SETUP.addProperty("naturalRegeneration",
                level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION));
        SETUP.addProperty("playerGameMode", joined.gameMode.getGameModeForPlayer().getSerializedName());
        SETUP.addProperty("playerHasSmartPhone", PlayerPhoneHelper.hasSmartPhone(joined));
        SETUP.addProperty("maidStableId", stableId);
        SETUP.addProperty("maidPhoneTier", String.valueOf(PhoneHelper.getPhoneTier(maid)));
        SETUP.addProperty("maidOwnedByPlayer", maid.isOwnedBy(joined));
        SETUP.addProperty("maidTaskUid", maid.getTask().getUid().toString());
        SETUP.addProperty("maidTickCountAtSetup", maidTickCount);
        SETUP.addProperty("remoteSupplyReplyEnabled",
                RemoteSupplySettings.isReplyEnabled(playerId));
        SETUP.addProperty("rescueEnabled", RescueSettings.isEnabled(playerId));
        SETUP.addProperty("aiSiteConfigured", aiSiteConfigured);
        SETUP.addProperty("aiSiteEnabled", aiSiteEnabled);
        SETUP.addProperty("aiSiteDisabledInMemory", aiSiteDisabledInMemory);
        SETUP.addProperty("aiSiteEnabledAfterDisable", aiSiteEnabledAfterDisable);
        SETUP.addProperty("llmSiteIdBefore", llmSiteIdBefore == null ? "" : llmSiteIdBefore);
        SETUP.addProperty("llmSiteIdAfter", llmSiteIdAfter == null ? "" : llmSiteIdAfter);
        SETUP.addProperty("llmSitesDisabledInMemory", DISABLED_LLM_SITES.size());
        SETUP.addProperty("deathPhaseSkipped", deathPhaseSkipped);

        // 阶段 1 的初始状态：真实玩家 health=6、food=12；女仆背包 3 瓶治疗 I。
        clearMaidInventory();
        maid.getMaidInv().setStackInSlot(0, healingPotion(Potions.HEALING, 3));
        joined.setHealth(6.0F);
        joined.getFoodData().setFoodLevel(12);
        joined.getFoodData().setSaturation(0.0F);
        healthPotionBefore = countHealingPotions();
        SUPPLY_HEALTH.addProperty("healthBefore", joined.getHealth());
        SUPPLY_HEALTH.addProperty("foodBefore", joined.getFoodData().getFoodLevel());
        SUPPLY_HEALTH.addProperty("potionCountBefore", healthPotionBefore);
        SUPPLY_HEALTH.addProperty("parsedHealBefore", maxHealingValue());
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 设置完成 player={} maid={} stableId={} aiSiteEnabled={}",
                joined.getName().getString(), maid.getName().getString(), stableId, aiSiteEnabled);
        return true;
    }

    private static void buildPlatform(ServerLevel level, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = center.offset(dx, 0, dz);
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlockAndUpdate(floor.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // ==================== 隔离世界与 LLM 站点边界 ====================

    /** 任何世界修改前核对 -D 指定路径与 server 世界根目录是同一真实路径。 */
    private static boolean verifyIsolatedWorld(MinecraftServer server) {
        String expectedRaw = System.getProperty("huhutalk.supplyRescueSmokeWorld");
        if (expectedRaw == null || expectedRaw.isBlank()) {
            fatal("缺少 -Dhuhutalk.supplyRescueSmokeWorld，拒绝修改任何世界", null);
            return false;
        }
        try {
            Path expected = Paths.get(expectedRaw).toAbsolutePath().normalize().toRealPath();
            Path actual = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toRealPath();
            SETUP.addProperty("isolatedWorldExpected", expected.toString());
            SETUP.addProperty("isolatedWorldActual", actual.toString());
            if (!expected.equals(actual)) {
                fatal("隔离世界路径不匹配：期望 " + expected + "，实际 " + actual + "，拒绝修改世界", null);
                return false;
            }
            return true;
        } catch (Throwable t) {
            fatal("核对隔离世界路径失败: " + stackTrace(t), t);
            return false;
        }
    }

    /**
     * 只对当前站点与 DeepSeek 默认回退中实际的 {@link LLMOpenAISite} 内存对象关闭 enabled。
     * 先确认所有启用候选都可识别，再统一修改；任何无法识别的启用站点直接 fail closed。
     * 不调用 saveSites/writeSites，不读写配置，不读取凭据。
     */
    private static boolean disableLlmSitesInMemory() {
        List<LLMSite> candidates = new ArrayList<>();
        addCandidate(candidates, maid.getAiChatManager().getLLMSite());
        addCandidate(candidates, DefaultLLMSite.DEEPSEEK);
        addCandidate(candidates, AvailableSites.getLLMSite(DefaultLLMSite.DEEPSEEK.id()));

        for (LLMSite site : candidates) {
            if (site != null && site.enabled() && !(site instanceof LLMOpenAISite)) {
                SETUP.addProperty("unknownEnabledSiteApiType", site.getApiType());
                return false;
            }
        }
        for (LLMSite site : candidates) {
            if (!(site instanceof LLMOpenAISite openai) || !openai.enabled()) {
                continue;
            }
            if (isDisabledRecorded(openai)) {
                continue;
            }
            DISABLED_LLM_SITES.add(new DisabledLlmSite(openai, openai.enabled()));
            openai.setEnabled(false);
        }
        LLMSite effective = maid.getAiChatManager().getLLMSite();
        return effective == null || !effective.enabled();
    }

    private static void addCandidate(List<LLMSite> candidates, LLMSite site) {
        if (site == null) {
            return;
        }
        for (LLMSite existing : candidates) {
            if (existing == site) {
                return;
            }
        }
        candidates.add(site);
    }

    private static boolean isDisabledRecorded(LLMOpenAISite site) {
        for (DisabledLlmSite disabled : DISABLED_LLM_SITES) {
            if (disabled.site() == site) {
                return true;
            }
        }
        return false;
    }

    /** 只在 ServerStoppingEvent 恢复本驱动改过的 enabled 原值；不写配置。 */
    private static void restoreLlmSites() {
        for (int i = DISABLED_LLM_SITES.size() - 1; i >= 0; i--) {
            DisabledLlmSite disabled = DISABLED_LLM_SITES.get(i);
            try {
                disabled.site().setEnabled(disabled.enabledBefore());
            } catch (Throwable t) {
                HuhuTalk.LOGGER.error("[supply-rescue-smoke] 恢复 LLM 站点 enabled 失败 id={}",
                        disabled.site().id(), t);
            }
        }
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 已在内存恢复 {} 个 LLM 站点 enabled 原值（未写配置）",
                DISABLED_LLM_SITES.size());
        DISABLED_LLM_SITES.clear();
    }

    // ==================== 阶段 1：补给回血 ====================

    private static void recordSupplyHealth(MinecraftServer server) {
        ServerPlayer current = currentPlayer(server);
        RemoteSupplySession session = stableId == null ? null : RemoteSupplySessions.get(stableId);
        int potionAfter = countHealingPotions();
        float healthAfter = current == null ? -1.0F : current.getHealth();

        SUPPLY_HEALTH.addProperty("healthAfter", healthAfter);
        SUPPLY_HEALTH.addProperty("foodAfter", current == null ? -1 : current.getFoodData().getFoodLevel());
        SUPPLY_HEALTH.addProperty("potionCountAfter", potionAfter);
        SUPPLY_HEALTH.addProperty("parsedHealAfter", maxHealingValue());
        SUPPLY_HEALTH.addProperty("potionConsumed", healthPotionBefore - potionAfter);
        SUPPLY_HEALTH.addProperty("phoneOk", maid != null && PhoneHelper.hasPhone(maid));
        SUPPLY_HEALTH.addProperty("ownerOk", current != null && maid != null && maid.isOwnedBy(current));
        SUPPLY_HEALTH.addProperty("taskUid", maid == null ? "" : maid.getTask().getUid().toString());
        SUPPLY_HEALTH.addProperty("sessionPresent", session != null);
        if (session != null) {
            SUPPLY_HEALTH.addProperty("sessionPaused", session.isPaused());
            SUPPLY_HEALTH.add("sessionConsumption", consumptionJson(session));
        }
        SUPPLY_HEALTH.addProperty("observedMaidTicks", maidTickCount - phaseStartMaidTicks);
        SUPPLY_HEALTH.addProperty("observedServerTicks", serverTickCount - phaseStartServerTicks);

        boolean consumed = healthPotionBefore - potionAfter >= 1;
        boolean healed = healthAfter > 6.0F;
        SUPPLY_HEALTH.addProperty("ok", consumed && healed);
        if (!consumed) {
            FAILURES.add("阶段1 未消耗治疗药水: before=" + healthPotionBefore + " after=" + potionAfter);
        }
        if (!healed) {
            FAILURES.add("阶段1 未回血: health=" + healthAfter + " (期望 > 6)");
        }
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 阶段1 health={} potion={}->{} session={} ok={}",
                healthAfter, healthPotionBefore, potionAfter, session != null, consumed && healed);
    }

    // ==================== 阶段 2：补给补食 ====================

    private static void setupSupplyFood(MinecraftServer server) {
        ServerPlayer current = currentPlayer(server);
        if (current == null) {
            fatal("阶段2 开始时服务端玩家不存在", null);
            return;
        }
        clearMaidInventory();
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.BREAD, 3));
        current.setHealth(20.0F);
        current.getFoodData().setFoodLevel(2);
        current.getFoodData().setSaturation(0.0F);
        foodHealthBefore = current.getFoodData().getFoodLevel();
        SUPPLY_FOOD.addProperty("foodBefore", foodHealthBefore);
        SUPPLY_FOOD.addProperty("breadCountBefore", countBread());
        SUPPLY_FOOD.addProperty("potionCountAtStart", countHealingPotions());
        enter(Phase.SUPPLY_FOOD_WAIT);
    }

    private static void recordSupplyFood(MinecraftServer server) {
        ServerPlayer current = currentPlayer(server);
        int breadAfter = countBread();
        int foodAfter = current == null ? -1 : current.getFoodData().getFoodLevel();
        SUPPLY_FOOD.addProperty("foodAfter", foodAfter);
        SUPPLY_FOOD.addProperty("breadCountAfter", breadAfter);
        SUPPLY_FOOD.addProperty("observedMaidTicks", maidTickCount - phaseStartMaidTicks);
        SUPPLY_FOOD.addProperty("observedServerTicks", serverTickCount - phaseStartServerTicks);
        boolean consumed = breadAfter < 3;
        boolean fed = foodAfter > foodHealthBefore;
        SUPPLY_FOOD.addProperty("ok", consumed && fed);
        if (!consumed) {
            FAILURES.add("阶段2 未消耗面包: after=" + breadAfter);
        }
        if (!fed) {
            FAILURES.add("阶段2 未补食: food=" + foodAfter + " (期望 > " + foodHealthBefore + ")");
        }
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 阶段2 food={} bread={}->{} ok={}",
                foodAfter, 3, breadAfter, consumed && fed);
    }

    // ==================== 阶段 3：死亡救援 ====================

    private static void prepareRescue(MinecraftServer server) {
        if (deathPhaseSkipped) {
            RESCUE.addProperty("skipped", true);
            RESCUE.addProperty("skipReason", deathSkipReason);
            RESCUE.addProperty("sessionClearedBeforeDeath",
                    stableId != null && RemoteSupplySessions.get(stableId) == null);
            finishRun();
            return;
        }

        ServerPlayer current = currentPlayer(server);
        if (current == null) {
            fatal("阶段3 死亡前服务端玩家不存在", null);
            return;
        }
        RESCUE.addProperty("sessionClearedBeforeDeath",
                stableId != null && RemoteSupplySessions.get(stableId) == null);

        clearMaidInventory();
        maid.getMaidInv().setStackInSlot(0, healingPotion(Potions.STRONG_HEALING, 1));
        healthPotionBefore = countHealingPotions();
        current.setHealth(20.0F);
        current.getFoodData().setFoodLevel(20);
        current.getFoodData().setSaturation(5.0F);
        current.moveTo(BASE_X, BASE_Y, BASE_Z, BASE_YAW, BASE_PITCH);
        current.connection.teleport(BASE_X, BASE_Y, BASE_Z, BASE_YAW, BASE_PITCH);

        bedDimension = level.dimension();
        bedPosition = new BlockPos(BED_X, BED_Y, BED_Z);
        bedAngle = 0.0F;
        bedForced = true;
        current.setRespawnPosition(bedDimension, bedPosition, bedAngle, bedForced, false);
        RescueSettings.set(playerId, true);

        RESCUE.addProperty("potionCountBefore", healthPotionBefore);
        RESCUE.addProperty("parsedHealBefore", maxHealingValue());
        RESCUE.addProperty("healthBefore", current.getHealth());
        RESCUE.addProperty("foodBefore", current.getFoodData().getFoodLevel());
        RESCUE.addProperty("preYaw", current.getYRot());
        RESCUE.addProperty("prePitch", current.getXRot());
        RESCUE.addProperty("bedDimensionBefore", bedDimension.location().toString());
        RESCUE.addProperty("bedXBefore", bedPosition.getX());
        RESCUE.addProperty("bedYBefore", bedPosition.getY());
        RESCUE.addProperty("bedZBefore", bedPosition.getZ());
        RESCUE.addProperty("bedForcedBefore", current.isRespawnForced());
        RESCUE.addProperty("rescueSettingEnabled", RescueSettings.isEnabled(playerId));

        preDeathPlayer = current;
        boolean hurt = current.hurt(current.damageSources().fall(), 1000.0F);
        RESCUE.addProperty("hurtReturn", hurt);
        RESCUE.addProperty("deathEventObserved", deathSnapshot != null);
        if (deathSnapshot != null) {
            RESCUE.addProperty("deathDimension", deathSnapshot.dimension().location().toString());
            RESCUE.addProperty("deathX", deathSnapshot.x());
            RESCUE.addProperty("deathY", deathSnapshot.y());
            RESCUE.addProperty("deathZ", deathSnapshot.z());
            RESCUE.addProperty("deathYaw", deathSnapshot.yRot());
            RESCUE.addProperty("deathPitch", deathSnapshot.xRot());
        }
        RESCUE.addProperty("livingDeathEventsAtPrep", livingDeathCount);
        enter(Phase.RESCUE_DEATH_WAIT);
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 阶段3 已触发真实摔死: hurt={} deathEvent={} potion={}",
                hurt, deathSnapshot != null, healthPotionBefore);
    }

    private static void checkPendingAtDeath() {
        if (playerId == null) {
            return;
        }
        PendingRescue pending = PendingRescueManager.get(playerId);
        pendingRegisteredAtDeath = pending != null;
        RESCUE.addProperty("pendingRegistered", pendingRegisteredAtDeath);
        if (pending != null) {
            RESCUE.addProperty("pendingDimension", pending.dimension().location().toString());
            RESCUE.addProperty("pendingX", pending.x());
            RESCUE.addProperty("pendingY", pending.y());
            RESCUE.addProperty("pendingZ", pending.z());
            RESCUE.addProperty("pendingYaw", pending.yRot());
            RESCUE.addProperty("pendingPitch", pending.xRot());
            RESCUE.addProperty("pendingExecuteAtTick", pending.executeAtTick());
        }
    }

    private static void recordRescue(MinecraftServer server) {
        ServerPlayer revived = server.getPlayerList().getPlayer(playerId);
        boolean alive = revived != null && revived.isAlive() && !revived.isDeadOrDying();
        boolean newInstance = revived != null && preDeathPlayer != null && revived != preDeathPlayer;
        float health = revived == null ? -1.0F : revived.getHealth();
        int potionAfter = countHealingPotions();
        int consumed = healthPotionBefore - potionAfter;

        boolean dimensionOk = revived != null && deathSnapshot != null
                && deathSnapshot.dimension().equals(revived.level().dimension());
        boolean positionOk = revived != null && deathSnapshot != null
                && Math.abs(revived.getX() - deathSnapshot.x()) < 0.05D
                && Math.abs(revived.getY() - deathSnapshot.y()) < 0.05D
                && Math.abs(revived.getZ() - deathSnapshot.z()) < 0.05D;
        boolean rotationOk = revived != null && deathSnapshot != null
                && Math.abs(revived.getYRot() - deathSnapshot.yRot()) < 0.01F
                && Math.abs(revived.getXRot() - deathSnapshot.xRot()) < 0.01F;
        boolean healthOk = revived != null && Math.abs(health - 8.0F) < 0.01F;
        boolean bedUnchanged = revived != null
                && bedDimension != null && bedDimension.equals(revived.getRespawnDimension())
                && bedPosition != null && bedPosition.equals(revived.getRespawnPosition())
                && Math.abs(revived.getRespawnAngle() - bedAngle) < 0.01F
                && revived.isRespawnForced() == bedForced;
        boolean connectionRepointed = revived != null && revived.connection != null
                && revived.connection.player == revived;
        boolean pendingCleared = PendingRescueManager.get(playerId) == null;
        boolean redirectCleared = PendingRescueManager.peekRespawnRedirect(playerId) == null;

        RESCUE.addProperty("revivedAlive", alive);
        RESCUE.addProperty("revivedNewInstance", newInstance);
        RESCUE.addProperty("revivedHealth", health);
        RESCUE.addProperty("revivedDimension", revived == null
                ? "" : revived.level().dimension().location().toString());
        RESCUE.addProperty("revivedX", revived == null ? Double.NaN : revived.getX());
        RESCUE.addProperty("revivedY", revived == null ? Double.NaN : revived.getY());
        RESCUE.addProperty("revivedZ", revived == null ? Double.NaN : revived.getZ());
        RESCUE.addProperty("revivedYaw", revived == null ? Float.NaN : revived.getYRot());
        RESCUE.addProperty("revivedPitch", revived == null ? Float.NaN : revived.getXRot());
        RESCUE.addProperty("bedDimensionAfter", revived == null || revived.getRespawnDimension() == null
                ? "" : revived.getRespawnDimension().location().toString());
        RESCUE.addProperty("bedXAfter", revived == null || revived.getRespawnPosition() == null
                ? Integer.MIN_VALUE : revived.getRespawnPosition().getX());
        RESCUE.addProperty("bedYAfter", revived == null || revived.getRespawnPosition() == null
                ? Integer.MIN_VALUE : revived.getRespawnPosition().getY());
        RESCUE.addProperty("bedZAfter", revived == null || revived.getRespawnPosition() == null
                ? Integer.MIN_VALUE : revived.getRespawnPosition().getZ());
        RESCUE.addProperty("bedForcedAfter", revived != null && revived.isRespawnForced());
        RESCUE.addProperty("connectionPlayerIsRevived", connectionRepointed);
        RESCUE.addProperty("potionCountAfter", potionAfter);
        RESCUE.addProperty("potionConsumed", consumed);
        RESCUE.addProperty("pendingCleared", pendingCleared);
        RESCUE.addProperty("redirectCleared", redirectCleared);
        RESCUE.addProperty("livingDeathEventCount", livingDeathCount);
        RESCUE.addProperty("healthOk", healthOk);
        RESCUE.addProperty("dimensionOk", dimensionOk);
        RESCUE.addProperty("positionOk", positionOk);
        RESCUE.addProperty("rotationOk", rotationOk);
        RESCUE.addProperty("bedUnchanged", bedUnchanged);

        boolean ok = alive && newInstance && healthOk && dimensionOk && positionOk && rotationOk
                && bedUnchanged && connectionRepointed && consumed == 1
                && pendingCleared && redirectCleared && deathSnapshot != null && pendingRegisteredAtDeath;
        RESCUE.addProperty("ok", ok);
        if (!ok) {
            FAILURES.add("阶段3 救援校验未通过（详见 rescue JSON 字段）");
        }
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 阶段3 alive={} health={} new={} posOk={} rotOk={} "
                        + "bedSame={} connection={} potionUsed={} pendingCleared={} redirectCleared={} ok={}",
                alive, health, newInstance, positionOk, rotationOk, bedUnchanged,
                connectionRepointed, consumed, pendingCleared, redirectCleared, ok);
    }

    // ==================== 结果写入与退出 ====================

    private static void finishRun() {
        if (finished) {
            return;
        }
        finished = true;
        writeResultSafely();
        HuhuTalk.LOGGER.info("[supply-rescue-smoke] 驱动完成，等待客户端主线程 Minecraft.stop()");
    }

    private static void fatal(String message, Throwable throwable) {
        synchronized (LOCK) {
            FAILURES.add(message);
            fatalError = throwable == null ? message : message + "\n" + stackTrace(throwable);
        }
        HuhuTalk.LOGGER.error("[supply-rescue-smoke] {}", message, throwable);
        finished = true;
        writeResultSafely();
    }

    private static synchronized void ensureStarted() {
        if (startedNanos != 0L) {
            return;
        }
        startedNanos = System.nanoTime();
        startedAt = Instant.now().toString();
        outputDir = resolveOutputDir();
    }

    private static synchronized void writeResultSafely() {
        if (resultWritten || outputDir == null) {
            return;
        }
        resultWritten = true;
        try {
            JsonObject root = buildResult();
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
            HuhuTalk.LOGGER.info("[supply-rescue-smoke] 已写 {}", outputDir.resolve("result.json"));
        } catch (Throwable t) {
            HuhuTalk.LOGGER.error("[supply-rescue-smoke] 写 result.json 失败", t);
        }
    }

    private static JsonObject buildResult() {
        JsonObject root = new JsonObject();
        root.addProperty("taskId", "forge-supply-rescue-reproduce-20261027-01");
        root.addProperty("driver", "ForgeSupplyRescueSmoke");
        root.addProperty("status", status());
        root.addProperty("timedOut", timedOut);
        root.addProperty("fatalError", fatalError == null ? "" : fatalError);
        root.addProperty("startedAt", startedAt == null ? "" : startedAt);
        root.addProperty("finishedAt", Instant.now().toString());
        root.addProperty("durationMs", startedNanos == 0L ? 0L
                : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        root.addProperty("outputDir", outputDir == null ? "" : outputDir.toString());
        root.addProperty("phase", phase.name());
        root.addProperty("serverTickCount", serverTickCount);
        root.addProperty("maidTickEventCount", maidTickCount);
        root.addProperty("livingDeathEventCount", livingDeathCount);
        root.addProperty("deathPhaseSkipped", deathPhaseSkipped);
        if (deathSkipReason != null) {
            root.addProperty("deathSkipReason", deathSkipReason);
        }
        root.add("setup", SETUP);
        root.add("supplyHealth", SUPPLY_HEALTH);
        root.add("supplyFood", SUPPLY_FOOD);
        root.add("rescue", RESCUE);
        JsonArray failures = new JsonArray();
        synchronized (LOCK) {
            for (String failure : FAILURES) {
                failures.add(failure);
            }
        }
        root.add("failures", failures);
        return root;
    }

    private static String status() {
        if (timedOut) {
            return "TIMEOUT";
        }
        if (fatalError != null) {
            return "ERROR";
        }
        synchronized (LOCK) {
            if (!FAILURES.isEmpty()) {
                return "FAIL";
            }
        }
        return deathPhaseSkipped ? "SKIPPED_LLM_ENABLED" : "PASS";
    }

    private static Path resolveOutputDir() {
        String value = System.getProperty("huhutalk.supplyRescueSmokeOutput");
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    // ==================== 小工具 ====================

    private static ServerPlayer currentPlayer(MinecraftServer server) {
        return playerId == null || server == null ? null : server.getPlayerList().getPlayer(playerId);
    }

    private static ItemStack healingPotion(Potion potion, int count) {
        ItemStack stack = new ItemStack(Items.POTION, count);
        PotionUtils.setPotion(stack, potion);
        return stack;
    }

    private static void clearMaidInventory() {
        if (maid == null) {
            return;
        }
        for (int slot = 0; slot < maid.getMaidInv().getSlots(); slot++) {
            maid.getMaidInv().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static int countHealingPotions() {
        if (maid == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < maid.getMaidInv().getSlots(); slot++) {
            ItemStack stack = maid.getMaidInv().getStackInSlot(slot);
            if (!stack.isEmpty() && RescuePotion.isHealing(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int maxHealingValue() {
        if (maid == null) {
            return 0;
        }
        int max = 0;
        for (int slot = 0; slot < maid.getMaidInv().getSlots(); slot++) {
            ItemStack stack = maid.getMaidInv().getStackInSlot(slot);
            if (!stack.isEmpty() && RescuePotion.isHealing(stack)) {
                max = Math.max(max, RescuePotion.healAmount(stack));
            }
        }
        return max;
    }

    private static int countBread() {
        if (maid == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < maid.getMaidInv().getSlots(); slot++) {
            ItemStack stack = maid.getMaidInv().getStackInSlot(slot);
            if (!stack.isEmpty() && stack.is(Items.BREAD)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static JsonObject consumptionJson(RemoteSupplySession session) {
        JsonObject consumption = new JsonObject();
        for (Map.Entry<String, Integer> entry : session.consumption().entrySet()) {
            consumption.addProperty(entry.getKey(), entry.getValue());
        }
        return consumption;
    }

    private static String stackTrace(Throwable t) {
        StringWriter buffer = new StringWriter();
        t.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private static void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
        phaseStartServerTicks = serverTickCount;
        phaseStartMaidTicks = maidTickCount;
    }

    private record DisabledLlmSite(LLMOpenAISite site, boolean enabledBefore) {
    }

    private record DeathSnapshot(ResourceKey<Level> dimension, double x, double y, double z,
                                 float yRot, float xRot) {
    }

    private enum Phase {
        WAIT_WORLD,
        SUPPLY_HEALTH_WAIT,
        SUPPLY_FOOD_SETUP,
        SUPPLY_FOOD_WAIT,
        RESCUE_IDLE_WAIT,
        RESCUE_DEATH_WAIT
    }
}
