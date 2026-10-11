package com.huhutalk.core.supply;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.chat.MaidChatHandler;
import com.huhutalk.core.chat.MaidNarrativeStyle;
import com.huhutalk.core.chat.PendingSystemMessageBus;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 「远程补给」的叙事文案（服务端，须在主线程调用）。
 *
 * <h2>三处会让她开口（都受设置「远程补给触发女仆回复」管）</h2>
 * <ol>
 *   <li>{@link #injectOutOfSupplies}：主人状态不好，可她手头已经没补给品了（附加需求 1）；</li>
 *   <li>{@link #injectDiedWithoutRescue}：主人没撑到补给到位、而且这次也没被救回来，
 *       还带上<b>原版死因</b>让她知道他是怎么没的（附加需求 2 / 2.1）；</li>
 *   <li>{@link #injectManualSwitch}：玩家<b>手动</b>把她的模式切走 —— 告诉她消耗明细 + 新模式名
 *       （需求 2.3.2）。</li>
 * </ol>
 *
 * <h2>第四处刻意"只写记忆、不开口"</h2>
 * {@link #queueSwitchMemory}：模型自己用 {@code switch_work_task} 切走时（需求 2.3.3），
 * 模型那一轮本来就拿到了 {@code "Switched to task X"} 的工具返回值，再唤起一次 LLM 就会变成
 * "她刚说完换了、过会儿又说一遍"的双重调用。所以这里只写进记忆通道（零 token），
 * 她下次自然聊天时会知道。
 *
 * <h2>开关关闭时</h2>
 * 三种"开口"全部降级为写记忆 —— 事实不丢，只是不主动冒泡。
 */
public final class RemoteSupplyNarrative {

    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.supply_narrative");

    /** 收尾 USER 语：这条链路本质都是"隔着手机"发生的。 */
    private static final String CLOSING = "你隔着手机跟主人说着话。";

    private RemoteSupplyNarrative() {
    }

    // ==================== 1) 补给品用尽 ====================

    /** 主人状态不好但手头没补给了：让她说一声（需求附加点 1）。 */
    public static void injectOutOfSupplies(ServerPlayer player, EntityMaid speaker) {
        if (player == null || speaker == null) {
            return;
        }
        String system = "【情况】主人现在状态不好，可你手头已经没有能用的补给品了（食物和药水都空了）。\n"
                + "说点什么吧：按你自己的性子来，轻松地告诉他一声你这边暂时没东西了，别让他以为还有存货。\n"
                + MaidNarrativeStyle.BREVITY_LINE;
        String memory = "【记忆】主人刚才状态不好，但你手头的补给品已经用完了（食物和药水都空了），没能帮上他。";
        speakOrRemember(player, speaker, system, memory);
    }

    // ==================== 2) 主人没撑到补给（且没被救回） ====================

    /**
     * 主人在补给期间死了、而且没被救回来（自己点了重生）：让她表示已经尽力（附加需求 2 / 2.1）。
     *
     * @param deathMessage 原版死亡提示（与救援同一口径，见 {@code RescueEnvironment#describeDeath}）
     */
    public static void injectDiedWithoutRescue(ServerPlayer player, EntityMaid speaker, String deathMessage) {
        if (player == null || speaker == null) {
            return;
        }
        String cause = deathMessage == null || deathMessage.isBlank() ? "（死因未知）" : deathMessage;
        String system = "【刚刚的事】" + cause + "。你一直在给他远程补给，"
                + "但他这次掉血太快，没等到补给到位就没了；这次也没能把他救回来。\n"
                + "说点什么吧：按你自己的性子来——你可以说自己已经尽力了，"
                + "也可以吐槽他太脆、属性不够、或者该练练级再来。\n"
                + MaidNarrativeStyle.BREVITY_LINE;
        String memory = "【记忆】" + cause + "——你一直在给他远程补给，但他掉血太快，这次没能把他救回来。";
        speakOrRemember(player, speaker, system, memory);
    }

    // ==================== 3) 玩家手动切走模式 ====================

    /** 玩家手动把她的模式切走：交代消耗明细 + 新模式名（需求 2.3.2）。 */
    public static void injectManualSwitch(ServerPlayer player, EntityMaid speaker,
                                          String newTaskName, Map<String, Integer> consumption) {
        if (player == null || speaker == null) {
            return;
        }
        String used = describeConsumption(consumption);
        String system = "【工作变了】你的工作模式刚从「远程补给」切换为「" + newTaskName + "」。\n"
                + "· 这一段远程补给一共消耗了：" + used + "\n"
                + "说点什么吧：按你自己的性子来，轻松点——跟他说一声你换工作了，顺便交代一下花掉了什么。\n"
                + MaidNarrativeStyle.BREVITY_LINE;
        String memory = "【记忆】你的工作模式从「远程补给」切换为「" + newTaskName + "」；"
                + "这一段远程补给消耗了：" + used + "。";
        speakOrRemember(player, speaker, system, memory);
    }

    // ==================== 4) 模型/程序切走模式（只写记忆） ====================

    /**
     * 模型（{@code switch_work_task}）或程序把她的模式切走：<b>只写记忆，不唤起 LLM</b>（需求 2.3.3）。
     * <p>事实与 {@link #injectManualSwitch} 完全一样（消耗明细 + 新模式名），只是不主动冒泡。
     */
    public static void queueSwitchMemory(EntityMaid maid, String newTaskName, Map<String, Integer> consumption) {
        if (maid == null) {
            return;
        }
        try {
            String stableId = MaidStableIdHelper.getOrCreateStableId(maid);
            if (stableId == null || stableId.isBlank()) {
                return;
            }
            PendingSystemMessageBus.queueSilent(stableId,
                    "【记忆】你的工作模式从「远程补给」切换为「" + newTaskName + "」；"
                            + "这一段远程补给消耗了：" + describeConsumption(consumption) + "。");
        } catch (Exception e) {
            LOGGER.error("[HuhuTalk][补给] 写入模式切换记忆失败 maid={}", maid.getName().getString(), e);
        }
    }

    // ==================== 内部 ====================

    /**
     * 按开关决定"开口"还是"只写记忆"。
     * <p>开关关掉时事实照样进记忆 —— 玩家只是不想被打扰，不是不想让她知道。
     */
    private static void speakOrRemember(ServerPlayer player, EntityMaid speaker,
                                        String systemText, String memoryFact) {
        String stableId;
        try {
            stableId = MaidStableIdHelper.getOrCreateStableId(speaker);
        } catch (Exception e) {
            LOGGER.error("[HuhuTalk][补给] 取女仆 stableId 失败 maid={}", speaker.getName().getString(), e);
            return;
        }
        if (stableId == null || stableId.isBlank()) {
            return;
        }
        if (!RemoteSupplySettings.isReplyEnabled(player.getUUID())) {
            // 关闭时：只写静默记忆，不花这次 LLM 调用。
            // 必须是静默条目 —— 可播报条目会被 MaidScanTicker 当成"她刚苏醒"再发起一次 LLM 对话，
            // 那就等于开关没关（见 §5.15）。
            PendingSystemMessageBus.queueSilent(stableId, memoryFact);
            return;
        }
        try {
            // 记忆：纯事实、无指令（她下次正常聊天时会读到）。
            // 同样必须静默：这条事实紧接着就即时注入说过了，排队播报会让同一件事被说两遍。
            PendingSystemMessageBus.queueSilent(stableId, memoryFact);
            // 即时触发：事实 + 语气引导 + 长度约束
            MaidChatHandler.handleSystemInjection(player, stableId, systemText, CLOSING, true);
        } catch (Exception e) {
            LOGGER.error("[HuhuTalk][补给] 注入叙事失败 maid={}", speaker.getName().getString(), e);
        }
    }

    /** 消耗明细："面包 ×3、治疗药水 ×1"；什么都没用时给一句人话。 */
    private static String describeConsumption(Map<String, Integer> consumption) {
        if (consumption == null || consumption.isEmpty()) {
            return "（这一段没消耗任何补给品）";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : consumption.entrySet()) {
            if (!first) {
                sb.append('、');
            }
            first = false;
            sb.append(entry.getKey()).append(" ×").append(entry.getValue());
        }
        return sb.toString();
    }
}
