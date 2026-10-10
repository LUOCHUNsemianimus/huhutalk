package com.huhutalk.core.rescue;

import com.huhutalk.HuhuTalk;
import com.huhutalk.common.PlayerPhoneHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 救援编排（服务端）。
 *
 * <h2>当前进度</h2>
 * 本阶段（P1–P4）只落地<b>判定链路</b>：死亡登记 → 5 秒对账 → 女仆挑选预判。
 * 真正的"取药 / 强制重生 / 结算回血 / 聊天页留痕 / LLM 叙事"在 P5–P6 接入
 * （见 {@link #tryExecute} 的 TODO）。
 *
 * <h2>为什么 T+0 只做"便宜"的判定</h2>
 * {@code LivingDeathEvent} 发生在原版死亡结算的关键路径上，这里不宜做遍历女仆背包之类的重活。
 * 因此 T+0 只判定三件廉价事实（硬模式 / 虚空 / 玩家有没有手机），
 * 女仆与药水的挑选推迟到 T+5s 的 tick 对账里做 —— 那 5 秒里条件也可能变化
 * （女仆被卸载、药水被别的逻辑消耗），推迟反而更准确。
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
        // 硬模式保持原版语义：死亡不可逆，绝不强行复活
        if (server.getWorldData().isHardcore()) {
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
        PendingRescueManager.register(new PendingRescue(player, needFireResistance,
                server.getTickCount() + PendingRescueManager.DELAY_TICKS));
        LOGGER.info("[HuhuTalk][救援] 已登记：player={} needFireResistance={} 将在 {} tick 后尝试",
                player.getName().getString(), needFireResistance, PendingRescueManager.DELAY_TICKS);
    }

    /**
     * T+5s：执行救援。调用前 {@link PendingRescueManager} 已确认玩家仍在线且仍处于死亡状态。
     *
     * <p><b>P5 待接入的顺序（已定稿）</b>：
     * <ol>
     *   <li>挑选女仆（本阶段已完成预判：{@link RescueMaidPicker#pick}）</li>
     *   <li>从女仆处取药（回血量最高的那瓶；需抗火时可在两只女仆间分别凑）</li>
     *   <li>{@code PlayerList.respawn(player, false, KILLED)} —— 传 {@code false} 以跟随
     *       玩家游戏规则的 {@code keepInventory} 与 {@code wasDeath=true} 语义；
     *       重生前先 {@link PendingRescueManager#beginRespawnRedirect} 定向到死亡点</li>
     *   <li>{@code setHealth(药水回血量)}（重生会把血置满，必须显式覆盖）</li>
     *   <li>补挂药水的非瞬间效果（再生 / 抗火）</li>
     *   <li>在死亡坐标播破碎音效与药水粒子（不生成 ThrownPotion，避免效果双倍）</li>
     *   <li>写投递记录 + 推送聊天页「已使用 · 恢复 N ❤」不可领取气泡</li>
     *   <li><b>复活完成之后</b>才注入 system 消息触发 LLM 叙事</li>
     * </ol>
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
        // TODO(P5): 取药 → 强制重生到死亡点 → setHealth(回血量) → 补挂效果 → 表现 → 聊天页留痕
        // TODO(P6): 复活成功后注入 system 消息触发 LLM 叙事
        LOGGER.info("[HuhuTalk][救援] 预判就绪（尚未执行）：player={} needFire={} healer={} fireRes={} 协作={}",
                rescue.playerName(),
                rescue.needFireResistance(),
                plan.healer().maid().getName().getString(),
                plan.fireRes() == null ? "-" : plan.fireRes().maid().getName().getString(),
                plan.isCooperative());
    }
}
