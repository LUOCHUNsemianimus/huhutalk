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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <h2>「连续复活事件」里的三条叙事（见 {@link RescueStreakManager}）</h2>
 * <ul>
 *   <li>{@link #inject}：事件内第 1/2/3 次救援的即时叙事，<b>每只参与女仆各一次</b>；</li>
 *   <li>{@link #injectLowPotionWarning}：记账区间内药水见底的提醒（需求 1.3），整个事件只一次；</li>
 *   <li>{@link #injectStreakSummary}：事件以"连续 3 分钟没再死"结束后，作为<b>最后一条消息</b>
 *       发给<b>一只</b>参与女仆的统计总结（需求 1.1 / 1.2）——其他参与者只在文案里被提到，
 *       不各自再调一次 LLM，这正是要避免的 token 浪费。</li>
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
     * @param rescueSeq   本次连续复活事件内这是第几次救援（1 起；≥2 时追加"说他两句"的提示）
     */
    public static void inject(ServerPlayer revived, PendingRescue rescue, RescueMaidPicker.Plan plan,
                              ItemStack healPotion, ItemStack firePotion, int rescueSeq) {
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
                                band, rescueSeq),
                        closingLine(band),
                        true);
            } catch (Exception e) {
                LOGGER.error("[HuhuTalk][救援] 注入叙事失败 maid={}", maid.getName().getString(), e);
            }
        }
    }

    // ==================== 计数区间：濒危提醒与收尾总结 ====================

    /**
     * 收尾 USER 语（濒危提醒）。
     * <p>这两条消息本质都是"隔着手机"发的，所以不沿用 {@link #closingLine}（那是按死亡时的距离分档）。
     */
    private static final String LOW_POTION_CLOSING =
            "你隔着手机跟主人说着话。";

    /** 收尾 USER 语（收尾总结）。 */
    private static final String STREAK_CLOSING =
            "你隔着手机跟主人说着话，不知道主人成功没有，你可以稍微问一句。";

    /**
     * 需求 1.3：记账区间内"治疗药水快见底"的提醒，让女仆主动告诉主人别再死了。
     * <p>整个连续复活事件内只会走到这里一次（由 {@link RescueStreakManager} 把关）。
     */
    public static void injectLowPotionWarning(ServerPlayer player,
                                              RescueStreakManager.LowPotionNotice notice) {
        if (player == null || notice == null || notice.speaker() == null) {
            return;
        }
        try {
            String stableId = MaidStableIdHelper.getOrCreateStableId(notice.speaker());
            if (stableId == null || stableId.isBlank()) {
                return;
            }
            MaidChatHandler.handleSystemInjection(player, stableId,
                    buildLowPotionWarning(notice), LOW_POTION_CLOSING, true);
        } catch (Exception e) {
            LOGGER.error("[HuhuTalk][救援] 注入濒危提醒失败 maid={}",
                    notice.speaker().getName().getString(), e);
        }
    }

    /**
     * 需求 1.1 / 1.2：连续复活事件结束时的统计总结，作为<b>最后一条消息</b>发给一只参与女仆。
     * <p>全程只此一次 LLM 调用：其他参与救援的女仆只在文案里被提到（"我和 xx 一块救了你 x 次"），
     * 不会各自再烧一次 token。
     */
    public static void injectStreakSummary(ServerPlayer player, RescueStreak streak, EntityMaid speaker) {
        if (player == null || streak == null || speaker == null) {
            return;
        }
        try {
            String stableId = MaidStableIdHelper.getOrCreateStableId(speaker);
            if (stableId == null || stableId.isBlank()) {
                return;
            }
            MaidChatHandler.handleSystemInjection(player, stableId,
                    buildStreakSummary(streak, speaker), STREAK_CLOSING, true);
        } catch (Exception e) {
            LOGGER.error("[HuhuTalk][救援] 注入计数区间总结失败 maid={}", speaker.getName().getString(), e);
        }
    }

    /** 濒危提醒正文：点名"我/我和 xx"，并报<b>实际</b>剩几瓶（不写死 3）。 */
    private static String buildLowPotionWarning(RescueStreakManager.LowPotionNotice notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("【提醒】主人似乎正在激烈的战斗/处于危险地形，");
        sb.append(groupName(notice.otherNames())).append("手上能用的治疗药水");
        if (notice.remaining() <= 0) {
            sb.append("已经一瓶都不剩了，下次只能靠你自己了。\n");
        } else {
            sb.append("只剩 ").append(notice.remaining())
                    .append(" 瓶了，得告诉主人省着点用——再这么下去，下次就没法给他补血了。\n");
        }
        sb.append("说点什么吧：按你自己的性子来，轻松地敲打他两句就行"
                + "——再不打赢/从危险地点走出来，下次他得自己复活回出生点了。");
        return sb.toString();
    }

    /** 收尾总结正文：只统计记账区间（第 4 次起）内的死亡 / 救援 / 消耗，并点名其他出过药水的女仆。 */
    private static String buildStreakSummary(RescueStreak streak, EntityMaid speaker) {
        List<String> others = otherParticipantNames(streak, speaker);
        StringBuilder sb = new StringBuilder();
        sb.append("【这段时间的事】主人刚连着翻车了 ").append(streak.deathCount()).append(" 次，");
        if (others.isEmpty()) {
            sb.append("你一共把他捞回来 ").append(streak.rescueCount()).append(" 次");
        } else {
            sb.append("你和").append(String.join("、", others)).append("一共把他捞回来 ")
                    .append(streak.rescueCount()).append(" 次");
        }
        if (streak.coopRescueCount() > 0) {
            sb.append("（其中 ").append(streak.coopRescueCount()).append(" 次是一起动手的）");
        }
        sb.append("，用掉了：").append(describeConsumption(streak)).append("。\n");
        sb.append("说点什么吧：按你自己的性子来，轻松点——可以好好笑他一下，"
                + "问问他到底在忙什么。话不用多，一两句就够。");
        return sb.toString();
    }

    /** 「我」「我和 xx」「我和 xx 他们」——按实际一起出过药水的女仆数取名，不虚构同伴。 */
    private static String groupName(List<String> otherNames) {
        if (otherNames == null || otherNames.isEmpty()) {
            return "你";
        }
        if (otherNames.size() == 1) {
            return "你和" + otherNames.getFirst();
        }
        return "你和" + otherNames.getFirst() + "他们";
    }

    /** 本段记账区间里除开口者之外，还投递过药水的女仆名（顺序稳定，去重）。 */
    private static List<String> otherParticipantNames(RescueStreak streak, EntityMaid speaker) {
        String selfId = MaidStableIdHelper.getOrCreateStableId(speaker);
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : streak.participants().entrySet()) {
            if (entry.getKey().equals(selfId)) {
                continue;
            }
            String name = entry.getValue();
            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** 消耗明细："治疗药水 ×3、抗火药水 ×1"。 */
    private static String describeConsumption(RescueStreak streak) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : streak.consumption().entrySet()) {
            if (!first) {
                sb.append('、');
            }
            first = false;
            sb.append(entry.getKey()).append(" ×").append(entry.getValue());
        }
        return first ? "（什么都没用上）" : sb.toString();
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

    /**
     * 收尾的 USER 消息：只交代"当下这一幕"，把指令留在系统文本里。
     * <p>旧版写死的"主人就站在你面前"只在 ≤10 格时成立，远距离救援时会把她带偏。
     * <p>文案已按需求方定稿改成"游戏化 / 轻量化"口径（去掉 眼前 / 看着你 / 鬼门关 这类重词）。
     */
    private static String closingLine(DistanceBand band) {
        return switch (band) {
            case IN_SIGHT -> "主人精神饱满的站了起来。";
            case NEARBY -> "你就在不远处。";
            case VIA_PHONE -> "你是隔着手机跟他说这些话的。";
        };
    }

    // ==================== 文案 ====================

    /** 语气引导：只给"按自己性子来"和几种可能的方向，避开"担忧/悲伤"这类过重的词。 */
    private static final String TONE_GUIDE =
            "说点什么吧：按你自己的性子来，轻松点——打游戏磕磕碰碰很正常，不用搞得像生离死别。"
                    + "给他打打气，说一句你还会帮他，顺带关心他一句也行。";

    /** 长度约束：主人多半正在交战/跑图，消息刷屏是干扰。 */
    private static final String BREVITY_LINE =
            "话要短：一两句就够（主人多半正忙着打架，消息刷屏反而碍事）。";

    /**
     * 各距离档的"你的操作"整句（已含用药方式差异）。
     * <p>需求方定稿：远 = 隔着手机发过去；近 = 朝他嘴里扔；身边 = 顺手喂；
     * 喷溅 / 滞留型则把"喂 / 扔"换成"砸在他身上"（与实际的用药方式一致）。
     */
    private static String actionLine(DistanceBand band, String healName, ItemStack potion) {
        boolean splash = RescuePotion.usageOf(potion) != RescuePotion.Usage.DRINK;
        return switch (band) {
            case VIA_PHONE -> "你隔着手机发了一瓶「" + healName + "」过去，他的状态又切回了在线。";
            case NEARBY -> splash
                    ? "你就在附近，把一瓶「" + healName + "」砸在他身上，他朝你示意他状态很好。"
                    : "你就在附近，朝他嘴里扔了一瓶「" + healName + "」，他朝你示意他状态很好。";
            case IN_SIGHT -> splash
                    ? "你就在旁边，顺手把一瓶「" + healName + "」砸在他身上，他翻身爬了起来。"
                    : "你就在旁边，顺手喂了他一瓶「" + healName + "」，他翻身爬了起来。";
        };
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
        sb.append("【记忆】").append(cause).append("——");
        if (plan.isCooperative()) {
            String otherName = (selfIsHealer ? plan.fireRes().maid() : plan.healer().maid()).getName().getString();
            sb.append("是你和").append(otherName).append(remote ? "隔着手机一起：" : "一起：");
            sb.append(selfIsHealer
                    ? "你出「" + healName + "」，" + otherName + "出「" + fireName + "」"
                    : "你出「" + fireName + "」，" + otherName + "出「" + healName + "」");
        } else {
            sb.append("你").append(remote ? "隔着手机" : "").append("用「").append(healName).append("」给他补了血");
        }
        sb.append('。');
        return sb.toString();
    }

    /**
     * 即时触发用的系统文本：一句事实 + 语气引导 + 长度约束 + 能力说明。
     * <p>结构已按需求方定稿压扁：不再有"死因 / 位置 / 操作 / 结果"四段清单，距离并进动作句里。
     * <p>刻意不提"担忧/悲伤"这类过重的词，也不写"不用端着/不用瞒着"之类"要坦率"的要求
     * —— 那等于拿系统提示去覆盖玩家给女仆设的「口是心非」「傲娇」人设。
     */
    private static String buildPrompt(RescueMaidPicker.Plan plan, boolean selfIsHealer,
                                      String deathMessage, ItemStack healPotion, ItemStack firePotion,
                                      DistanceBand band, int rescueSeq) {
        String healName = RescuePotion.displayName(healPotion);
        String fireName = firePotion.isEmpty() ? "" : RescuePotion.displayName(firePotion);
        boolean hasFire = !firePotion.isEmpty();
        boolean coop = plan.isCooperative();
        String otherName = coop
                ? (selfIsHealer ? plan.fireRes().maid() : plan.healer().maid()).getName().getString()
                : "";
        String cause = deathMessage == null || deathMessage.isBlank() ? "（死因未知）" : deathMessage;

        StringBuilder sb = new StringBuilder();
        sb.append("【刚刚的事】").append(cause).append('。');
        if (coop) {
            sb.append("你和").append(otherName).append("一起动的手——");
            if (selfIsHealer) {
                sb.append("你出「").append(healName).append("」，").append(otherName)
                        .append("出「").append(fireName).append("」");
            } else {
                sb.append(otherName).append("出「").append(healName).append("」，你出「")
                        .append(fireName).append("」");
            }
            sb.append("，主人正在自己爬出危险。");
        } else {
            sb.append(actionLine(band, healName, healPotion));
            if (hasFire) {
                sb.append("顺带还补了一瓶「").append(fireName).append("」，免得他接着烧。");
            }
        }
        sb.append('\n').append(TONE_GUIDE);
        sb.append('\n').append(BREVITY_LINE);
        if (rescueSeq >= 2) {
            sb.append("\n（顺带一提：这已经是这段时间第 ").append(rescueSeq)
                    .append(" 次了——你可以拿这事打趣一下他的技术。）");
        }
        return sb.toString();
    }
}
