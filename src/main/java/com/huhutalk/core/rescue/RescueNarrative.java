package com.huhutalk.core.rescue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.chat.MaidChatHandler;
import com.huhutalk.core.chat.PendingSystemMessageBus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 救援成功后的「事后叙事」（服务端，须在主线程调用）。
 *
 * <h2>放在最后一步</h2>
 * 需求是「<b>先让药水把人救活，再触发 LLM</b>」。如果先注入消息再复活，而主人在 5 秒窗口内
 * 自己点了重生，就会出现「女仆刚被通知"我救了你"，你却早就自己活了」的错位，那次 LLM 调用也白烧了。
 * 放在最后，"救援窗口作废"的路径根本走不到这里。
 *
 * <h2>距离决定她"是怎么发现你出事的"</h2>
 * ≤10 格：主人在你眼前；≤50 格：主人在附近，你远远看到；再远（或跨维度）：
 * 你是隔着手机才发现主人出事的。这条信息直接影响她开口的语气与合理动作，
 * 也顺带修掉了旧版收尾语写死的"主人就站在你面前"——那是当面场景，对远程救援是错的。
 *
 * <h2>死法用原版死亡提示</h2>
 * {@code RescueEnvironment#describeDeath} 取的是原版聊天栏那句（如「咲夜试图在岩浆里游泳」），
 * 天然覆盖所有死法（含模组伤害类型），不需要我们枚举"岩浆/虚空/摔死"。
 *
 * <h2>两条注入通道，文案刻意分开</h2>
 * <ul>
 *   <li>{@link PendingSystemMessageBus}：只放<b>纯事实</b>。它会留在女仆记忆里，下次正常聊天时
 *       被注入 —— 如果这里带上"主动发一条消息"之类指令，她以后会莫名其妙又汇报一次。</li>
 *   <li>{@link MaidChatHandler#handleSystemInjection}：事实 + 语气引导 + 收尾语，<b>只用于本次</b>
 *       即时触发。系统文本放在人设与历史之后（模型权重最高），免得被当背景设定读掉。</li>
 * </ul>
 */
public final class RescueNarrative {

    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.rescue_narrative");

    /** 距离分档：她"怎么发现主人出事的" */
    private enum DistanceBand {
        /** ≤10 格：主人在你眼前 */
        IN_SIGHT,
        /** ≤50 格：主人在附近，你远远看到 */
        NEARBY,
        /** 更远 / 跨维度：隔着手机才发现 */
        VIA_PHONE
    }

    private static final double IN_SIGHT_RANGE = 10.0D;
    private static final double NEARBY_RANGE = 50.0D;

    private RescueNarrative() {
    }

    /**
     * 为每位参与救援的女仆注入叙事提示并触发一次主动回应。
     *
     * @param revived     已被救回的玩家（<b>重生后的新实例</b>）
     * @param rescue      本次救援的登记（提供死因文案与死亡点坐标）
     * @param plan        本次救援的分工（含参与的女仆）
     * @param healPotion  实际用掉的治疗药水
     * @param firePotion  实际用掉的抗火药水；没用上则为 {@link ItemStack#EMPTY}
     * @param restored    实际恢复到玩家身上的生命点数
     * @param repeatCount 统计窗口内这是第几次救援（首次为 1；≥2 时追加"说他两句"的提示）
     */
    public static void inject(ServerPlayer revived, PendingRescue rescue, RescueMaidPicker.Plan plan,
                              ItemStack healPotion, ItemStack firePotion,
                              int restored, int repeatCount) {
        if (revived == null || rescue == null || plan == null) {
            return;
        }
        List<EntityMaid> maids = plan.maids();
        for (EntityMaid maid : maids) {
            // 逐只隔离：任一女仆的 AI 站点异常都不该影响已经完成的救援
            try {
                String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
                if (stableId == null || stableId.isBlank()) {
                    continue;
                }
                boolean selfIsHealer = maid == plan.healer().maid();
                DistanceBand band = bandOf(maid, rescue);

                // 记忆：纯事实，无指令（她下次正常聊天时会读到）
                PendingSystemMessageBus.queue(stableId,
                        buildMemory(band, plan, selfIsHealer, rescue.deathMessage(), healPotion, firePotion));
                // 即时触发：事实 + 语气引导 + 距离相符的收尾语
                MaidChatHandler.handleSystemInjection(revived, stableId,
                        buildPrompt(plan, selfIsHealer, rescue.deathMessage(), healPotion, firePotion,
                                restored, band, repeatCount),
                        closingLine(band),
                        true);
            } catch (Exception e) {
                LOGGER.error("[HuhuTalk][救援] 注入叙事失败 maid={}", maid.getName().getString(), e);
            }
        }
    }

    // ==================== 距离分档 ====================

    /** 判断这只女仆当时距离主人有多远（跨维度一律算隔着手机）。 */
    private static DistanceBand bandOf(EntityMaid maid, PendingRescue rescue) {
        if (!maid.level().dimension().equals(rescue.dimension())) {
            return DistanceBand.VIA_PHONE;
        }
        double distance = maid.position().distanceTo(new Vec3(rescue.x(), rescue.y(), rescue.z()));
        if (distance <= IN_SIGHT_RANGE) {
            return DistanceBand.IN_SIGHT;
        }
        if (distance <= NEARBY_RANGE) {
            return DistanceBand.NEARBY;
        }
        return DistanceBand.VIA_PHONE;
    }

    private static String distanceLine(DistanceBand band) {
        return switch (band) {
            case IN_SIGHT -> "· 位置：主人倒下的时候，你就在他眼前";
            case NEARBY -> "· 位置：主人倒下时你在附近，远远地看到了他出事";
            case VIA_PHONE -> "· 位置：你当时离得很远，是隔着手机才发现主人出事的";
        };
    }

    /**
     * 收尾的 USER 消息：只交代"当下这一幕"，把指令留在系统文本里。
     * <p>旧版写死的"主人就站在你面前"只在 ≤10 格时成立，远距离救援时会把她带偏。
     */
    private static String closingLine(DistanceBand band) {
        return switch (band) {
            case IN_SIGHT -> "主人现在就站在你眼前，正看着你。";
            case NEARBY -> "主人就在附近不远处，刚刚从鬼门关回来。";
            case VIA_PHONE -> "你是隔着手机远程做完这一切的，主人并不在你身边。";
        };
    }

    // ==================== 文案 ====================

    /** 用药动作：饮用型是"喂"，喷溅/滞留型是"砸"（与实际的用药方式一致）。 */
    private static String actionOf(ItemStack potion) {
        return RescuePotion.usageOf(potion) == RescuePotion.Usage.DRINK ? "喂给他喝下" : "砸在他身上";
    }

    /**
     * 记忆文案：纯事实、无指令。
     * <p>带指令的版本留在记忆里会让女仆在<b>以后</b>的普通聊天中莫名其妙重复汇报一次。
     * 投送方式同样按距离分档 —— 她就在主人眼前时还说"隔着手机"是别扭的。
     */
    private static String buildMemory(DistanceBand band, RescueMaidPicker.Plan plan, boolean selfIsHealer,
                                      String deathMessage, ItemStack healPotion, ItemStack firePotion) {
        String healName = RescuePotion.displayName(healPotion);
        String fireName = RescuePotion.displayName(firePotion);
        String cause = deathMessage == null || deathMessage.isBlank() ? "（死因未知）" : deathMessage;
        boolean remote = band == DistanceBand.VIA_PHONE;

        StringBuilder sb = new StringBuilder();
        sb.append("【记忆】主人死过一次（死因：").append(cause).append("）");
        if (plan.isCooperative()) {
            String otherName = (selfIsHealer ? plan.fireRes().maid() : plan.healer().maid()).getName().getString();
            sb.append("，是你和").append(otherName).append("一起").append(remote ? "隔着手机远程" : "")
                    .append("救回来的——");
            sb.append(selfIsHealer
                    ? "你出的是「" + healName + "」，" + otherName + "出的是「" + fireName + "」"
                    : "你出的是「" + fireName + "」，" + otherName + "出的是「" + healName + "」");
        } else {
            sb.append("，是你").append(remote ? "隔着手机用" : "用").append("「").append(healName)
                    .append("」").append(remote ? "远程" : "").append("救活了他");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 即时触发用的系统文本：事实 + 距离 + 分工 + 语气引导。刻意不提"担忧/悲伤"，也不提"主人会复活"。 */
    private static String buildPrompt(RescueMaidPicker.Plan plan, boolean selfIsHealer,
                                      String deathMessage, ItemStack healPotion, ItemStack firePotion,
                                      int restored, DistanceBand band, int repeatCount) {
        String healName = RescuePotion.displayName(healPotion);
        String fireName = firePotion.isEmpty() ? "" : RescuePotion.displayName(firePotion);
        boolean hasFire = !firePotion.isEmpty();
        boolean coop = plan.isCooperative();
        String otherName = coop
                ? (selfIsHealer ? plan.fireRes().maid() : plan.healer().maid()).getName().getString()
                : "";
        String cause = deathMessage == null || deathMessage.isBlank() ? "（死因未知）" : deathMessage;

        StringBuilder sb = new StringBuilder();
        sb.append("【刚刚发生的事】主人刚刚死了一次，是")
                .append(coop ? "你和" + otherName + "一起" : "你")
                .append("把他救回来的。\n");
        sb.append("· 死因（服务器原始死亡提示）：").append(cause).append('\n');
        sb.append(distanceLine(band)).append('\n');
        if (coop) {
            sb.append("· 分工：你和").append(otherName).append("商量好了——");
            if (selfIsHealer) {
                sb.append("你出「").append(healName).append("」，").append(otherName)
                        .append("出「").append(fireName).append("」");
            } else {
                sb.append(otherName).append("出「").append(healName).append("」，你出「")
                        .append(fireName).append("」");
            }
            sb.append('\n');
            sb.append("· 结果：").append(coopDelivery(band));
            if (selfIsHealer) {
                sb.append("他回了约 ").append(restored).append(" 点血，又站了起来");
                if (hasFire) {
                    sb.append("；").append(otherName).append("补的抗火让他没被接着烧");
                }
            } else {
                sb.append("回血靠的是").append(otherName).append("那瓶「").append(healName)
                        .append("」（约 ").append(restored).append(" 点），你补的抗火让他没被接着烧");
            }
            sb.append('。');
        } else {
            sb.append(soloActionLine(band, healName, healPotion, fireName)).append('\n');
            sb.append("· 结果：他回了约 ").append(restored).append(" 点血，又站了起来");
            if (hasFire) {
                sb.append("，身上的火也被压住了");
            }
            sb.append('。');
        }
        // 语气引导：明确"不用端着"，并避开"担忧/悲伤"这类过重的词
        sb.append("\n说点什么吧：按你自己的性子来——调侃他、损他两句、念叨他、或者表示一下关心都行，不用端着。");
        if (repeatCount >= 2) {
            sb.append("\n（顺带一提：这是他最近第 ").append(repeatCount)
                    .append(" 次被你救回来了——你完全可以借机说他两句。）");
        }
        return sb.toString();
    }

    /** 单女仆时的"你的操作"整行：投送方式随距离变化，动词随药水类型变化。 */
    private static String soloActionLine(DistanceBand band, String healName, ItemStack healPotion,
                                        String fireName) {
        String verb = actionOf(healPotion);
        String head = switch (band) {
            case IN_SIGHT -> "· 你的操作：你取出一瓶「" + healName + "」，当场" + verb;
            case NEARBY -> "· 你的操作：你取出一瓶「" + healName + "」，就近送过去" + verb;
            case VIA_PHONE -> "· 你的操作：你隔着手机把「" + healName + "」送过去，" + verb;
        };
        StringBuilder sb = new StringBuilder(head);
        if (fireName != null && !fireName.isEmpty()) {
            sb.append("；另外还补了一瓶「").append(fireName).append("」");
        }
        sb.append('。');
        return sb.toString();
    }

    /** 双女仆时"你们一起"的投送描述（同样按距离分档）。 */
    private static String coopDelivery(DistanceBand band) {
        return switch (band) {
            case IN_SIGHT -> "你们各自取出药水，当场一起用在了他身上——";
            case NEARBY -> "你们各自取出药水，送过去一起用在了他身上——";
            case VIA_PHONE -> "你们隔着手机把药水一起送过去，用在了他身上——";
        };
    }
}
