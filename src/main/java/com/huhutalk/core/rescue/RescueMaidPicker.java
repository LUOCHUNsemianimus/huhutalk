package com.huhutalk.core.rescue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.transfer.MaidTransferService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 救援女仆的挑选（服务端）。
 *
 * <h2>候选池</h2>
 * 满足全部条件的女仆才进池：<b>玩家名下 + 已加载 + 存活 + 佩戴手机 + 归属校验通过</b>。
 * <p>
 * 这里用<b>直接扫描已加载实体</b>而不是查联系人簿：联系人簿由
 * {@code MaidScanTicker} 每 100 tick 刷新一次，刚召唤/刚复活的女仆可能有最长 5 秒不在簿里，
 * 而救援恰恰是"分秒必争且只发生一次"的场景。扫描成本与既有的
 * {@code MaidStableIdHelper.findMaid} 同量级，且只在玩家死亡时发生一次。
 *
 * <h2>分工规则</h2>
 * <ol>
 *   <li>需要抗火时，<b>优先挑同一只既带治疗又带抗火</b>的女仆（一次搞定，叙事也更自然）。</li>
 *   <li>否则随机挑一只带治疗药水的（治疗是硬前提，没有就整场放弃）。</li>
 *   <li>还需要抗火而她身上没有时，<b>再从别的女仆里随机挑一只能出抗火的</b> ——
 *       两姐妹分头凑药，叙事时互相提及。</li>
 *   <li>谁都凑不出抗火就只治疗，不因为缺抗火而放弃救援。</li>
 * </ol>
 * 挑选只做"预判"（{@link MaidTransferService#findBestMatching} 只读），药水在真正执行时才被取走。
 */
public final class RescueMaidPicker {

    /** 一名参与救援的女仆（只记录身份） */
    public record Pick(EntityMaid maid) {
    }

    /**
     * 一次救援的分工。
     *
     * @param healer 出治疗药水的女仆（必须存在）
     * @param fireRes 出抗火药水的女仆；不需要抗火、或谁也拿不出抗火时为 {@code null}
     */
    public record Plan(Pick healer, @Nullable Pick fireRes) {

        /** 是否需要两姐妹合作（存在第二只、且不是同一只） */
        public boolean isCooperative() {
            return fireRes != null && fireRes.maid() != healer.maid();
        }

        /** 参与本次救援的女仆（去重，顺序稳定：治疗方在前） */
        public List<EntityMaid> maids() {
            Set<EntityMaid> set = new LinkedHashSet<>();
            set.add(healer.maid());
            if (fireRes != null) {
                set.add(fireRes.maid());
            }
            return new ArrayList<>(set);
        }
    }

    /**
     * 服务端主线程专用的随机源。
     * <p>救援全程都在服务端 tick 线程执行，不存在并发访问；用固定种子无关的
     * {@link Random} 只是为了让"随机挑一只"这件事不依赖世界随机源的消耗。
     */
    private static final Random RANDOM = new Random();

    private RescueMaidPicker() {
    }

    // ==================== 挑选 ====================

    /**
     * 挑出一次救援的分工。返回 {@code null} 表示<b>没人能出治疗药水</b>，整场救援放弃。
     */
    @Nullable
    public static Plan pick(MinecraftServer server, Player owner, boolean needFireResistance) {
        List<EntityMaid> reachable = reachableMaids(server, owner);
        if (reachable.isEmpty()) {
            return null;
        }

        List<EntityMaid> healers = new ArrayList<>();
        List<EntityMaid> firefighters = new ArrayList<>();
        List<EntityMaid> both = new ArrayList<>();
        for (EntityMaid maid : reachable) {
            boolean canHeal = hasHealingPotion(maid);
            boolean canFire = needFireResistance && hasFireResistancePotion(maid);
            if (canHeal) {
                healers.add(maid);
            }
            if (canFire) {
                firefighters.add(maid);
            }
            if (canHeal && canFire) {
                both.add(maid);
            }
        }
        if (healers.isEmpty()) {
            return null;
        }

        // 1) 优先"同一只兼具治疗 + 抗火"
        if (!both.isEmpty()) {
            EntityMaid maid = both.get(RANDOM.nextInt(both.size()));
            return new Plan(new Pick(maid), new Pick(maid));
        }

        // 2) 随机一只治疗方
        EntityMaid healer = healers.get(RANDOM.nextInt(healers.size()));
        if (!needFireResistance) {
            return new Plan(new Pick(healer), null);
        }
        // 3) 治疗方自己也能出抗火
        if (hasFireResistancePotion(healer)) {
            return new Plan(new Pick(healer), new Pick(healer));
        }
        // 4) 找另一只凑抗火；一只都没有就只治疗
        if (firefighters.isEmpty()) {
            return new Plan(new Pick(healer), null);
        }
        EntityMaid firefighter = firefighters.get(RANDOM.nextInt(firefighters.size()));
        return new Plan(new Pick(healer), new Pick(firefighter));
    }

    /**
     * 玩家名下、已加载、存活、佩戴手机的女仆。
     * <p>只读，不改动任何状态；顺序为"维度遍历顺序"，调用方不应依赖其稳定性。
     */
    public static List<EntityMaid> reachableMaids(MinecraftServer server, Player owner) {
        List<EntityMaid> out = new ArrayList<>();
        if (server == null || owner == null) {
            return out;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EntityMaid maid)) {
                    continue;
                }
                if (!maid.isAlive() || !maid.isOwnedBy(owner)) {
                    continue;
                }
                if (!PhoneHelper.hasPhone(maid)) {
                    continue;
                }
                out.add(maid);
            }
        }
        return out;
    }

    // ==================== 预判 ====================

    /** 她身上有没有治疗药水（只读预判；回血量最高的那瓶优先） */
    public static boolean hasHealingPotion(EntityMaid maid) {
        return !MaidTransferService
                .findBestMatching(maid, RescuePotion::isHealing, RescuePotion::healAmount)
                .isEmpty();
    }

    /** 她身上有没有抗火药水（只读预判） */
    public static boolean hasFireResistancePotion(EntityMaid maid) {
        return !MaidTransferService
                .findBestMatching(maid, RescuePotion::isFireResistance, stack -> 1)
                .isEmpty();
    }
}
