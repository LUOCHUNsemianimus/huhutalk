package com.huhutalk.core.rescue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PlayerPhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.common.SystemText;
import com.huhutalk.core.transfer.MaidTransferService;
import com.huhutalk.core.transfer.UsedItemNotifier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 救援编排（服务端）。
 *
 * <h2>T+0 登记 / T+5s 执行</h2>
 * {@code LivingDeathEvent} 发生在原版死亡结算的关键路径上，所以 T+0 只判定三件廉价事实
 * （硬模式 / 虚空 / 玩家有没有手机），女仆与药水的挑选推迟到 T+5s 的 tick 对账里做 ——
 * 那 5 秒里条件也可能变化（女仆被卸载、药水被别的逻辑消耗），推迟反而更准确。
 *
 * <h2>执行顺序（刻意如此，别调换）</h2>
 * <ol>
 *   <li>取药：先取治疗（硬前提，取不到就整体放弃、什么都没动过），再取抗火（尽力而为）。</li>
 *   <li>强制重生：先把重生位置重定向到死亡点，再走原版重生路径
 *       {@code respawn(player, false)}。<b>必须传 {@code false}</b> ——
 *       这与原版"死亡后点重生"完全同一条代码路径，从而跟随玩家游戏规则的
 *       {@code keepInventory}，并让 {@code PlayerEvent.Clone.wasDeath = true}</li>
 *   <li>{@code setHealth(药水回血量)}：重生会先把血置满，必须显式覆盖。</li>
 *   <li>补挂<b>非瞬间</b>效果（瞬间治疗已在第 3 步直接结算，再挂一次会双倍）。</li>
 *   <li>死亡坐标播破碎音效 + 药水粒子（不生成 ThrownPotion，同上避免双倍）。</li>
 *   <li>聊天页留痕：参与救援的每位女仆各一条「已使用」气泡。</li>
 *   <li><b>最后</b>才处理叙事与记账：走 {@link RescueStreakManager} 的「连续复活事件」闸门 ——
 *       前 3 次各注入一次 LLM 叙事，第 4 次起只记账不调 AI。
 *       这样即使主人在 5 秒内自己点了重生，也不会出现"刚发消息说救了，人却自己活了"的错位，
 *       同时避免为一次作废的救援白烧 token。</li>
 * </ol>
 */
public final class MaidRescueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuhuTalk.MOD_ID + ".rescue");

    private MaidRescueService() {
    }

    /**
     * T+0：死亡瞬间的资格判定与登记。任何一条不满足都<b>静默放弃</b>（按需求不给玩家提示）。
     */
    public static void trySchedule(MinecraftServer server, ServerPlayer player, DamageSource source) {
        if (server == null || player == null) {
            return;
        }
        // 玩家在手机设置里可关闭：不想让每次复活都触发一次 AI 叙事（烧 token）时关掉
        if (!RescueSettings.isEnabled(player.getUUID())) {
            return;
        }
        // 硬模式保持原版语义：死亡不可逆，绝不强行复活
        if (server.isHardcore()) {
            return;
        }
        // 虚空：把人送回虚空只会立刻再死一次，直接不救
        if (RescueEnvironment.isBeyondRescue(player, source)) {
            return;
        }
        // 联络通道：玩家身上必须有 Pro 手机
        if (!PlayerPhoneHelper.hasSmartPhone(player)) {
            return;
        }
        boolean needFireResistance = RescueEnvironment.needsFireResistance(player, source);
        // 死法在 T+0 就抓下来：DamageSource 不归我们持有，而"当场发生了什么"必须逐字还原
        String deathMessage = RescueEnvironment.describeDeath(player, source);
        PendingRescueManager.register(new PendingRescue(player, needFireResistance,
                server.getTickCount() + PendingRescueManager.DELAY_TICKS, deathMessage));
        LOGGER.info("[HuhuTalk][救援] 已登记：player={} needFireResistance={} 死因=\"{}\" 将在 {} tick 后尝试",
                player.getName().getString(), needFireResistance, deathMessage, PendingRescueManager.DELAY_TICKS);
    }

    /**
     * T+5s：执行救援。调用前 {@link PendingRescueManager} 已确认玩家仍在线且仍处于死亡状态。
     */
    public static void tryExecute(MinecraftServer server, ServerPlayer dead, PendingRescue rescue) {
        if (server == null || dead == null || rescue == null) {
            return;
        }
        RescueMaidPicker.Plan plan = RescueMaidPicker.pick(server, dead, rescue.needFireResistance());
        if (plan == null) {
            LOGGER.info("[HuhuTalk][救援] 作废：没有「已加载 + 佩戴手机 + 带治疗药水」的女仆，player={}",
                    rescue.playerName());
            return;
        }
        EntityMaid healer = plan.healer().maid();
        EntityMaid firefighter = plan.fireRes() == null ? null : plan.fireRes().maid();

        // 1) 治疗药水：硬前提。取"回血量最高的那瓶"。
        ItemStack healPotion = MaidTransferService.takeBestMatching(
                healer, RescuePotion::isHealing, RescuePotion::healAmount);
        if (healPotion.isEmpty()) {
            LOGGER.info("[HuhuTalk][救援] 作废：预判后药水已不在（被消耗/槽位变动），player={}", rescue.playerName());
            return;
        }
        // 2) 抗火药水：尽力而为。先治疗后抗火，所以取不到抗火无需回滚，直接只治疗。
        ItemStack firePotion = ItemStack.EMPTY;
        if (rescue.needFireResistance() && firefighter != null) {
            firePotion = MaidTransferService.takeBestMatching(
                    firefighter, RescuePotion::isFireResistance, stack -> 1);
        }

        // 3) 强制重生到死亡点。keepInventory=false = 原版死亡重生路径，跟随玩家游戏规则。
        //
        // ⚠⚠ 这里是本功能唯一的高危点，改动前务必读完 ⚠⚠
        //
        // PlayerList.respawn() 内部只做 `serverplayer.connection = player.connection`（把监听器
        // 挂到新玩家身上），【从不反向更新】ServerGamePacketListenerImpl.player。
        // 原版唯一的调用方 ServerGamePacketListenerImpl#handleClientCommand 自己补上了这一步：
        //     this.player = this.server.getPlayerList().respawn(this.player, false);
        // 我们是【直接调用】PlayerList.respawn，因此必须等价地手动补这一句，
        // 否则监听器会永远握着"已被 removePlayerImmediately 移除的旧玩家"，后果是毁灭性的：
        //   · 入站包全部作用在旧玩家实例上（handleInteract 会把旧玩家交给女仆做 isOwnedBy 判定）
        //     → 女仆瞬间变成"不可交互"
        //   · 经旧玩家执行魂符收放 → 女仆实体数据写坏 / 消失
        //   · 断线时 onDisconnect → PlayerList.remove(旧玩家)：保存的是旧死亡存档，
        //     且 playersByUUID.remove(uuid) 因身份不匹配被跳过 → 新玩家成幽灵
        //     → 下次进世界读到的还是"死亡状态"
        ServerPlayer revived;
        try {
            // 先把"这次死亡已被救回"告诉其他模块（远程补给的"玩家直接复活了"判定要读它，
            // 而重生的瞬间就可能有人来查，所以必须写在 respawn 之前）
            PendingRescueManager.markRescued(rescue.playerId(), server.getTickCount());
            PendingRescueManager.beginRespawnRedirect(rescue);
            revived = server.getPlayerList().respawn(dead, false);
            repointConnection(dead, revived);
        } finally {
            // 兜底清理：万一事件未被消费，也不能让残留登记影响玩家下一次正常重生
            PendingRescueManager.consumeRespawnRedirect(rescue.playerId());
        }
        if (revived == null) {
            LOGGER.warn("[HuhuTalk][救援] 重生失败（respawn 返回 null），player={}", rescue.playerName());
            return;
        }

        // 4) 血量：重生会把血置满，必须显式覆盖成"药水恢复的对应血量"
        float maxHealth = revived.getMaxHealth();
        float targetHealth = Mth.clamp((float) RescuePotion.healAmount(healPotion), 1.0F, maxHealth);
        revived.setHealth(targetHealth);

        // 5) 补挂非瞬间效果（再生 / 抗火）
        applyEffects(revived, healPotion);
        if (!firePotion.isEmpty()) {
            applyEffects(revived, firePotion);
        }

        // 6) 表现：死亡坐标播破碎音效 + 药水粒子
        playRescueFeedback(revived, rescue, healPotion, firePotion);

        // 7) 聊天页留痕：每位参与救援的女仆各一条「已使用」气泡
        int restored = Math.max(1, (int) targetHealth);
        String healEffect = firePotion.isEmpty()
                ? SystemText.of("message.huhutalk.rescue.heal", restored)
                : SystemText.of("message.huhutalk.rescue.heal_fire", restored);
        pushUsedBubble(revived, buildRecord(healer, healPotion, restored, revived.serverLevel().getGameTime(),
                healEffect), healPotion);
        if (firefighter != null && !firePotion.isEmpty() && firefighter != healer) {
            pushUsedBubble(revived, buildRecord(firefighter, firePotion, 0,
                    revived.serverLevel().getGameTime(),
                    SystemText.of("message.huhutalk.rescue.fire")), firePotion);
        }

        LOGGER.info("[HuhuTalk][救援] 完成：player={} 回血={} 抗火={} 治疗方={} 抗火方={}",
                rescue.playerName(), restored, !firePotion.isEmpty(),
                healer.getName().getString(),
                firefighter == null ? "-" : firefighter.getName().getString());

        // 8) 【最后一步】复活已落定，此时才处理叙事与记账。
        //    放在最后是刻意的：若主人在 5 秒窗口内自己点了重生，救援根本走不到这里，
        //    也就不会出现"女仆刚被通知救了人，人却自己活了"的错位，也不会白烧 token。
        //
        //    「连续复活事件」（RescueStreakManager）：第 1/2/3 次各唤一次 LLM；
        //    第 4 次起【完全静默】——不调 LLM、不写记忆，只记账；3 分钟静默后再发一条总结。
        int seq = RescueStreakManager.onRescue(revived, server.getTickCount(), plan.isCooperative());
        if (seq <= RescueStreakManager.NARRATE_LIMIT) {
            RescueNarrative.inject(revived, rescue, plan, healPotion, firePotion, seq);
            return;
        }
        // 记账区间：登记这次消耗掉了什么、是谁出的（不产生任何消息）
        RescueStreakManager.recordDelivery(revived, healer, healPotion);
        boolean coopPotion = firefighter != null && !firePotion.isEmpty() && firefighter != healer;
        if (coopPotion) {
            RescueStreakManager.recordDelivery(revived, firefighter, firePotion);
        }
        // 1.3：记账区间内治疗药水见底 → 破例让她提醒主人一次（整个事件内只说一次）
        RescueStreakManager.LowPotionNotice notice =
                RescueStreakManager.lowPotionNotice(server, revived, healer);
        if (notice != null) {
            RescueNarrative.injectLowPotionWarning(revived, notice);
        }
    }

    // ==================== 内部 ====================

    /**
     * 把数据包监听器重新指向重生后的新玩家实例。
     *
     * <p><b>为什么必须做</b>：{@code PlayerList.respawn} 只把监听器挂给新玩家
     * （{@code serverplayer.connection = player.connection}），不会回头更新
     * {@code ServerGamePacketListenerImpl.player}（该字段是 public 可变的）。
     * 原版 {@code handleClientCommand} 靠 {@code this.player = respawn(...)} 自己补这一步；
     * 直接调用 {@code PlayerList.respawn} 的一方必须等价补上，否则监听器会一直持有
     * 已被移除的旧玩家，导致：交互/物品使用全作用在幽灵玩家上、断线时保存错误的玩家存档。
     *
     * @param oldPlayer 重生前的旧玩家（仍持有同一个监听器对象）
     * @param revived   重生后的新玩家；为 {@code null} 时不做任何事
     */
    private static void repointConnection(ServerPlayer oldPlayer, @Nullable ServerPlayer revived) {
        if (oldPlayer == null || revived == null) {
            return;
        }
        ServerGamePacketListenerImpl listener = oldPlayer.connection;
        if (listener == null) {
            LOGGER.warn("[HuhuTalk][救援] 旧玩家没有 connection，无法重定向监听器（player={}）",
                    oldPlayer.getName().getString());
            return;
        }
        listener.player = revived;
        if (revived.connection != listener) {
            // respawn 正常会把 connection 挂到新玩家上；这里只是把不一致显式暴露出来
            LOGGER.warn("[HuhuTalk][救援] 监听器归属异常，已强制校正：player={}",
                    revived.getName().getString());
            revived.connection = listener;
        }
        if (listener.player != revived) {
            LOGGER.error("[HuhuTalk][救援] 监听器重定向失败！玩家后续交互会全部作用在幽灵玩家上。");
        }
    }

    /** 把药水的非瞬间效果补挂到玩家身上（瞬间效果已被 setHealth 直接结算，必须跳过）。 */
    private static void applyEffects(ServerPlayer player, ItemStack potion) {
        for (MobEffectInstance effect : RescuePotion.lastingEffects(potion)) {
            player.addEffect(effect);
        }
    }

    /**
     * 在死亡坐标播放"用掉了这瓶药水"的视听反馈。
     * <p>喷溅/滞留：破碎音效 + 水花粒子（对应"砸在玩家坐标上"）；
     * 饮用：饮用音效。两者都额外喷一圈对应液色的药水粒子。
     */
    private static void playRescueFeedback(ServerPlayer revived, PendingRescue rescue,
                                           ItemStack healPotion, ItemStack firePotion) {
        ServerLevel level = revived.serverLevel();
        double x = rescue.x();
        double y = rescue.y() + 0.6;
        double z = rescue.z();

        boolean splashed = RescuePotion.usageOf(healPotion) != RescuePotion.Usage.DRINK
                || (!firePotion.isEmpty() && RescuePotion.usageOf(firePotion) != RescuePotion.Usage.DRINK);
        if (splashed) {
            level.playSound(null, x, y, z, SoundEvents.SPLASH_POTION_BREAK, SoundSource.NEUTRAL, 1.0F, 0.9F);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 40, 0.4D, 0.2D, 0.4D, 0.12D);
        } else {
            level.playSound(null, x, y, z, SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.9F, 1.0F);
        }

        int color = RescuePotion.colorOf(healPotion);
        if (color >= 0) {
            PotionParticleFeedback.emit(level, x, y + 0.4D, z, color, 50, 0.35D, 0.5D, 0.35D);
        }
    }

    /** 组装一条投递记录。 */
    private static MaidDeliveryRecord buildRecord(EntityMaid maid, ItemStack potion, int restored,
                                                  long gameTime, String effectText) {
        String itemId = BuiltInRegistries.ITEM.getKey(potion.getItem()).toString();
        return new MaidDeliveryRecord(stableIdOf(maid), maid.getName().getString(),
                itemId, RescuePotion.displayName(potion), restored, true, effectText, gameTime);
    }

    /**
     * 推送一条「已使用」物品气泡（不可领取，仅作发送历史）。
     *
     * @param potion 真正被用掉的那瓶药水。<b>必须传原始栈</b>而不是只传 id：
     *               预览要带上 {@code POTION_CONTENTS} 组件，客户端才画得出正确的液色与图标。
     */
    private static void pushUsedBubble(ServerPlayer player, MaidDeliveryRecord record, ItemStack potion) {
        UsedItemNotifier.pushUsed(player, record.stableId(), potion, record.effectText());
    }

    /** 取女仆的稳定 ID；尚未生成时补生成一次（与全模组的主键约定一致）。 */
    private static String stableIdOf(EntityMaid maid) {
        if (maid instanceof StableIdProvider provider) {
            String id = provider.getStableId();
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return MaidStableIdHelper.getOrCreateStableId(maid);
    }
}
