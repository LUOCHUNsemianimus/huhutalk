package com.huhutalk.core.supply;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.SystemText;
import com.huhutalk.core.rescue.PotionParticleFeedback;
import com.huhutalk.core.rescue.RescuePotion;
import com.huhutalk.core.transfer.MaidTransferService;
import com.huhutalk.core.transfer.UsedItemNotifier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 「远程补给」的执行核心（服务端）。
 *
 * <h2>触发节奏</h2>
 * 每 {@link #CHECK_INTERVAL_TICKS} tick 扫一遍在线玩家：谁的血量 / 饱食度掉到阈值以下，
 * 就让他的补给女仆出手。同一玩家有 {@link #COOLDOWN_TICKS} 的冷却（需求方定稿：1 秒）。
 *
 * <h2>多只女仆（需求 2.2）</h2>
 * 候选 = 该玩家名下所有在补给模式、未被瞬停、且佩戴手机的女仆；
 * 只在"确实拿得出合适物品"的候选里<b>随机挑一只</b>，从她身上取一件用掉。
 * 全程只在玩家聊天页留一条「已使用」气泡，<b>不调用 LLM</b>。
 *
 * <h2>挑哪一件（"符合补充数值"）</h2>
 * 打分规则：<b>先看能不能补满缺口</b>——能补满的里挑超出最少的；都补不满就挑补得最多的。
 * 这样不会出现"拿金苹果去补 1 点饱食"这种浪费。
 *
 * <h2>与救援的分工</h2>
 * 只在玩家<b>活着</b>时动作；玩家一旦死亡，补给立即停手（见 {@link RemoteSupplyHook}），
 * 后续由救援链路接手。
 */
public final class RemoteSupplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuhuTalk.MOD_ID + ".supply");

    /** 多少 tick 扫一次在线玩家（10 tick = 0.5 秒） */
    private static final int CHECK_INTERVAL_TICKS = 10;

    /** 同一玩家的补给冷却（需求方定稿：1 秒） */
    private static final int COOLDOWN_TICKS = 20;

    /** 血量低于最大血的这个比例就考虑补血 */
    private static final float HEALTH_THRESHOLD_RATIO = 0.5F;

    /** 饱食度低于这个值就考虑补饭 */
    private static final int FOOD_THRESHOLD = 6;

    /** 打分用：能补满缺口的那一档（越大越优先，档内按"超出最少"细分） */
    private static final int SCORE_FITS = 1000;
    /** 打分用：补不满缺口的那一档 */
    private static final int SCORE_SHORT = 0;

    private static final Random RANDOM = new Random();

    /** 玩家 UUID -> 上次补给 tick（冷却用） */
    private static final Map<UUID, Long> LAST_SUPPLY = new HashMap<>();
    /** 已经提醒过"没补给了"的玩家（拿到补给 / 状态恢复后自动解除） */
    private static final Set<UUID> NO_SUPPLY_WARNED = new HashSet<>();

    private RemoteSupplyService() {
    }

    // ==================== 每个玩家一次判定 ====================

    /** 服务端 tick 入口（由 {@link RemoteSupplyHook} 每 tick 调用，内部自行限流）。 */
    public static void tickServer(MinecraftServer server) {
        if (server == null || RemoteSupplySessions.isEmpty()) {
            return;
        }
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            trySupplyFor(player, server.getTickCount());
        }
    }

    private static void trySupplyFor(ServerPlayer player, long nowTick) {
        if (player == null || !player.isAlive() || player.isDeadOrDying() || player.isSpectator()) {
            return;
        }
        List<RemoteSupplySession> sessions = RemoteSupplySessions.ofPlayer(player.getUUID());
        if (sessions.isEmpty()) {
            return;
        }

        float maxHealth = player.getMaxHealth();
        float health = player.getHealth();
        int foodLevel = player.getFoodData().getFoodLevel();
        int healthDeficit = (int) Math.ceil(maxHealth - health);
        int foodDeficit = 20 - foodLevel;
        boolean needHealth = health < maxHealth * HEALTH_THRESHOLD_RATIO;
        boolean needFood = foodLevel < FOOD_THRESHOLD;

        // 状态还行：把"没补给了"的提醒重新武装 —— 下次真的没药了她才会再开口
        if (!needHealth && !needFood) {
            NO_SUPPLY_WARNED.remove(player.getUUID());
            return;
        }

        Long lastTick = LAST_SUPPLY.get(player.getUUID());
        if (lastTick != null && nowTick - lastTick < COOLDOWN_TICKS) {
            return;
        }

        // 1) 先解决更急的血量：找一只拿得出治疗药水的女仆
        Candidate pick = needHealth ? pickCandidate(player, sessions, true, healthDeficit) : null;
        // 2) 血量这边没人拿得出药水（或本来就不缺血）：退而求其次补饱食度
        boolean asFood = false;
        if (pick == null && needFood) {
            pick = pickCandidate(player, sessions, false, foodDeficit);
            asFood = pick != null;
        }
        if (pick == null) {
            // 有需求但谁都拿不出东西 → 提醒一次"手头没有能用的补给了"
            warnNoSupply(player, sessions, nowTick);
            return;
        }

        ItemStack taken = asFood
                ? MaidTransferService.takeBestMatching(pick.maid(), RemoteSupplyService::isFood,
                stack -> scoreFood(stack, foodDeficit))
                : MaidTransferService.takeBestMatching(pick.maid(), RescuePotion::isHealing,
                stack -> scoreHeal(stack, healthDeficit));
        if (taken.isEmpty()) {
            // 预判与实取之间条件变了（被别的逻辑拿走 / 槽位变动）：安静跳过，下一次再试
            return;
        }

        String effectText = applyTo(player, taken, asFood);
        // 记账（需求 2.3.1）+ 手机页留痕（需求 2.2，不调 LLM）
        pick.session().countConsume(RescuePotion.displayName(taken));
        pick.session().markSupplied(nowTick);
        UsedItemNotifier.pushUsed(player, pick.session().maidStableId(), taken, effectText);

        LAST_SUPPLY.put(player.getUUID(), nowTick);
        NO_SUPPLY_WARNED.remove(player.getUUID());
        LOGGER.info("[HuhuTalk][补给] 已补给：player={} maid={} 物品={} 气泡=\"{}\" 血={}/{} 饱食={}",
                player.getName().getString(), pick.maid().getName().getString(),
                RescuePotion.displayName(taken), effectText, player.getHealth(), maxHealth, foodLevel);
    }

    /**
     * 把选中的物品真正用在玩家身上，返回气泡上要显示的效果文案。
     *
     * <h2>为什么药水只加"瞬间治疗"那部分</h2>
     * 非瞬间效果（再生 / 抗火…）在这里按原版正常挂上去、由游戏自己结算，
     * 所以直接加血时只能加瞬间的那部分，否则再生会被算两次
     * （救援那边走 {@code setHealth} 一次性结算，口径不同，见 {@link RescuePotion#instantHealAmount}）。
     */
    private static String applyTo(ServerPlayer player, ItemStack stack, boolean asFood) {
        FoodProperties food = stack.getFoodProperties(player);
        if (asFood && food != null) {
            player.getFoodData().eat(food.getNutrition(), food.getSaturationModifier());
            playFeedback(player, -1, false);
            return SystemText.of("message.huhutalk.supply.food", food.getNutrition());
        }

        int instant = RescuePotion.instantHealAmount(stack);
        if (instant > 0) {
            player.heal(instant);
        }
        List<MobEffectInstance> lasting = RescuePotion.lastingEffects(stack);
        for (MobEffectInstance effect : lasting) {
            player.addEffect(effect);
        }
        playFeedback(player, RescuePotion.colorOf(stack),
                RescuePotion.usageOf(stack) != RescuePotion.Usage.DRINK);

        int restored = Math.max(0, instant);
        return lasting.isEmpty()
                ? SystemText.of("message.huhutalk.supply.heal", restored)
                : SystemText.of("message.huhutalk.supply.heal_effect", restored);
    }

    /** 在玩家身上播一点"刚被喂了东西"的视听反馈（不生成实体，避免二次结算）。 */
    private static void playFeedback(ServerPlayer player, int potionColor, boolean splashed) {
        ServerLevel level = player.serverLevel();
        double x = player.getX();
        double y = player.getY() + 1.2D;
        double z = player.getZ();
        if (splashed) {
            level.playSound(null, x, y, z, SoundEvents.SPLASH_POTION_BREAK, SoundSource.NEUTRAL, 0.7F, 1.0F);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 16, 0.3D, 0.2D, 0.3D, 0.1D);
        } else {
            level.playSound(null, x, y, z, SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.6F, 1.1F);
        }
        if (potionColor >= 0) {
            // 1.20.1 没有 ColorParticleOption，但 ENTITY_EFFECT 的原生 count=0 协议
            // 可以把 offsetXYZ 当 RGB；共享发射器保留原数量/液色/高斯扩散。
            PotionParticleFeedback.emit(level, x, y, z, potionColor, 24, 0.3D, 0.4D, 0.3D);
        }
    }

    // ==================== 候选与择优 ====================

    /** 一只候选女仆 + 她的工作期 */
    private record Candidate(EntityMaid maid, RemoteSupplySession session) {
    }

    /**
     * 在候选女仆里挑一只<b>拿得出合适物品</b>的：先只读预判，再随机取一只。
     * <p>需求 2.2 的"随机挑选一个女仆背包里的物品"就是在这里落实的。
     */
    @Nullable
    private static Candidate pickCandidate(ServerPlayer player, List<RemoteSupplySession> sessions,
                                           boolean forHealth, int deficit) {
        List<Candidate> usable = new ArrayList<>();
        for (RemoteSupplySession session : sessions) {
            if (session.isPaused()) {
                continue;
            }
            EntityMaid maid = resolveReadyMaid(player.getServer(), player, session.maidStableId());
            if (maid == null) {
                continue;
            }
            ItemStack best = forHealth
                    ? MaidTransferService.findBestMatching(maid, RescuePotion::isHealing,
                    stack -> scoreHeal(stack, deficit))
                    : MaidTransferService.findBestMatching(maid, RemoteSupplyService::isFood,
                    stack -> scoreFood(stack, deficit));
            if (!best.isEmpty()) {
                usable.add(new Candidate(maid, session));
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        return usable.get(RANDOM.nextInt(usable.size()));
    }

    /**
     * 取一只"现在能用"的女仆：已加载、存活、仍属于该玩家、仍佩戴手机。
     * <p>手机是硬条件（需求方定稿）：气泡要落在她的聊天页上，而手机是她发消息的通道。
     */
    @Nullable
    private static EntityMaid resolveReadyMaid(MinecraftServer server, ServerPlayer owner, String stableId) {
        if (server == null) {
            return null;
        }
        EntityMaid maid = MaidStableIdHelper.findMaid(server, stableId);
        if (maid == null || !maid.isAlive() || !maid.isOwnedBy(owner)) {
            return null;
        }
        if (!PhoneHelper.hasPhone(maid)) {
            return null;
        }
        return maid;
    }

    /** 治疗药水打分：能补满 → 高分，超出越少分越高；补不满 → 按能补多少排。 */
    private static int scoreHeal(ItemStack stack, int deficit) {
        int heal = RescuePotion.healAmount(stack);
        if (heal <= 0) {
            return Integer.MIN_VALUE;
        }
        return heal >= deficit
                ? SCORE_FITS + Math.max(0, 200 - (heal - deficit))
                : SCORE_SHORT + heal;
    }

    /** 食物打分：与治疗药水同一口径（按营养值算）。 */
    private static int scoreFood(ItemStack stack, int deficit) {
        FoodProperties food = stack.getFoodProperties(null);
        if (food == null) {
            return Integer.MIN_VALUE;
        }
        int nutrition = food.getNutrition();
        if (nutrition <= 0) {
            return Integer.MIN_VALUE;
        }
        return nutrition >= deficit
                ? SCORE_FITS + Math.max(0, 200 - (nutrition - deficit))
                : SCORE_SHORT + nutrition;
    }

    /** 是不是可以喂给玩家的食物（有食物属性即可）。 */
    private static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getFoodProperties(null) != null;
    }

    // ==================== 供 Hook / 叙事使用 ====================

    /** 挑一只要开口的女仆：优先"最近还在补给的"，否则随机 —— 与救援的"最近投递者优先"一致。 */
    @Nullable
    public static EntityMaid pickSpeaker(MinecraftServer server, ServerPlayer owner,
                                         List<RemoteSupplySession> sessions) {
        EntityMaid best = null;
        long bestTick = Long.MIN_VALUE;
        List<EntityMaid> fallback = new ArrayList<>();
        for (RemoteSupplySession session : sessions) {
            EntityMaid maid = resolveReadyMaid(server, owner, session.maidStableId());
            if (maid == null) {
                continue;
            }
            if (!hasUsableAi(maid)) {
                continue;
            }            fallback.add(maid);
            if (session.lastSupplyTick() > bestTick) {
                bestTick = session.lastSupplyTick();
                best = maid;
            }
        }
        if (best != null) {
            return best;
        }
        return fallback.isEmpty() ? null : fallback.get(RANDOM.nextInt(fallback.size()));
    }

    /** 女仆是否配置并启用了 AI（没配就没必要白跑一次注入）。 */
    public static boolean hasUsableAi(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        LLMSite site = maid.getAiChatManager().getLLMSite();
        return site != null && site.enabled();
    }

    /** 有需求但谁都没补给品：提醒一次（拿到补给 / 状态恢复后自动解除，才会再次提醒）。 */
    private static void warnNoSupply(ServerPlayer player, List<RemoteSupplySession> sessions, long nowTick) {
        if (!NO_SUPPLY_WARNED.add(player.getUUID())) {
            return;
        }
        EntityMaid speaker = pickSpeaker(player.getServer(), player, sessions);
        if (speaker == null) {
            return;
        }
        LOGGER.info("[HuhuTalk][补给] 补给品用尽：player={} 开口者={}",
                player.getName().getString(), speaker.getName().getString());
        RemoteSupplyNarrative.injectOutOfSupplies(player, speaker);
        LAST_SUPPLY.put(player.getUUID(), nowTick);
    }

    /** 玩家登出：清掉冷却与提醒状态。 */
    public static void onPlayerGone(UUID playerId) {
        if (playerId != null) {
            LAST_SUPPLY.remove(playerId);
            NO_SUPPLY_WARNED.remove(playerId);
        }
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        LAST_SUPPLY.clear();
        NO_SUPPLY_WARNED.clear();
    }
}
