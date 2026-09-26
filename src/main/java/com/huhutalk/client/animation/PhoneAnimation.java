package com.huhutalk.client.animation;

import com.github.tartaricacid.touhoulittlemaid.client.entity.GeckoMaidEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.AnimationState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.PlayState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.AnimationBuilder;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate.AnimationEvent;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.IBone;
import com.huhutalk.HuhuTalk;
import com.huhutalk.client.PhoneAnimationHandler;
import com.huhutalk.client.PhoneAnimationRegistration;
import com.huhutalk.client.PhoneChatSourceCache;
import com.huhutalk.common.StableIdProvider;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「使用手机」附加动画判定。
 * <p>
 * 挂在 {@link GeckoMaidEntity} 独立注册的 {@code huhutalk_phone} 控制器上，
 * 与 {@code main}（身体基础动作：走/跑/游/坐）互不干扰——本控制器只驱动
 * {@code Head} / {@code RightArm} / {@code RightForeArm} 三根骨骼。
 * <p>
 * 状态机（三段动画见 {@code assets/huhutalk/animation/huhutalk_phone.animation.json}）：
 * <ul>
 *   <li>手机来源窗口开启 → {@code playOnce(raise).loop(hold)}：抬手一次后循环打字</li>
 *   <li>窗口关闭 → {@code playOnce(lower)}：放下手臂与头，播完停止</li>
 *   <li>窗口未开启 → {@code STOP}：不驱动，骨骼回落到 {@code main} 控制器</li>
 * </ul>
 * <b>只有手机来源窗口内的女仆播动画</b>：窗口由服务端在 {@code MaidChatHandler} 入口开启并同步
 * （见 {@code PhoneChatSourceController}），确保其他通道（对话引导、离线消息播报等）不会唤起动画。
 */
public final class PhoneAnimation {

    /** 控制器名。需与 {@code GeckoMaidEntityMixin} 注册的一致。 */
    public static final String CONTROLLER_NAME = "huhutalk_phone";

    /**
     * 抬手（一次）+ 持机打字（循环）的动画链。
     * 用 builder 串接而非自己计时：{@code raise} 播完会自动续上 {@code hold} 循环。
     */
    private static final AnimationBuilder RAISE_THEN_HOLD = new AnimationBuilder()
            .playOnce(PhoneAnimationRegistration.ANIM_RAISE)
            .loop(PhoneAnimationRegistration.ANIM_HOLD);

    /** 放下手臂与头（一次）。 */
    private static final AnimationBuilder LOWER = new AnimationBuilder()
            .playOnce(PhoneAnimationRegistration.ANIM_LOWER);

    /** 诊断：已打印过首帧状态的女仆（按 stableId 去重，避免刷屏） */
    private static final Set<String> DIAGNOSED = ConcurrentHashMap.newKeySet();

    /** 诊断：predicate 被调用的总次数（用于判断控制器是否真的被调用） */
    private static volatile long CALL_COUNT = 0L;

    /**
     * 当前处于「手机窗口内」的女仆（按 stableId）。
     * <p>
     * 必须是 <b>static</b>：本 predicate 的实例字段挂在 {@code GeckoMaidEntity} 上，
     * 而它是 NeoForge attachment、可能被重建。若状态随实例消失，每帧都会判定为
     * "窗口刚开启" → 每帧 {@code markNeedsReload()} → {@code _raise} 被无限重启，
     * 表现正是"手臂卡在抬手过程的起始帧、看起来像朝后伸，且反复抬起"。
     */
    private static final Set<String> ACTIVE_INSTANCES = ConcurrentHashMap.newKeySet();

    // ==================== 诊断（症状确认后删除） ====================

    /** 上一次窗口状态取值；用于打印 true/false 边沿，跟服务端开关窗对时间 */
    private static final Map<String, Boolean> LAST_ACTIVE = new ConcurrentHashMap<>();

    /** 上一次看到的"当前动画名"；用于打印每一次 raise/hold/lower 切换（重播会留下痕迹） */
    private static final Map<String, String> LAST_ANIM = new ConcurrentHashMap<>();

    /** 动画链被重建的次数（按 stableId）。正常应恒为 1（整个窗口一次） */
    private static final Map<String, Integer> RESTART_COUNT = new ConcurrentHashMap<>();

    /** 每只女仆一个实例（由 {@code GeckoMaidEntityMixin} 创建）。 */
    public PhoneAnimation() {
    }

    /** 每帧由控制器调用，决定本控制器当前该播什么。 */
    public PlayState predicate(AnimationEvent<?> event, AnimationController<?> controller) {
        CALL_COUNT++;
        // 只对女仆 Gecko 实体生效；控制器只挂在 GeckoMaidEntity 上，这里的类型检查是防御性的
        if (!(event.getAnimatableEntity() instanceof GeckoMaidEntity<?> gecko)) {
            return PlayState.STOP;
        }
        EntityMaid maid = null;
        if (gecko.getEntity() instanceof EntityMaid m) {
            maid = m;
        }
        if (maid == null) {
            return PlayState.STOP;
        }
        String stableId = (maid instanceof StableIdProvider provider) ? provider.getStableId() : null;
        String key = stableId == null ? "?" : stableId;
        boolean active = PhoneChatSourceCache.isActive(stableId);

        // ---- 诊断 A：窗口状态边沿。与服务端 [PhoneChatSource] 开关窗日志对时间，即可判断
        //      "重播"到底是服务端又开了一次窗，还是窗口没动而动画自己被重建 ----
        Boolean prevActive = LAST_ACTIVE.put(key, active);
        if (prevActive == null || prevActive != active) {
            HuhuTalk.LOGGER.info("[HuhuTalk] 窗口状态: stableId={} active {} -> {} calls={}",
                    key, prevActive, active, CALL_COUNT);
        }

        // 每只女仆只打印一次首帧状态：稳定 ID 是否取到、窗口是否开启、动画能否查到
        if (stableId != null && DIAGNOSED.add(stableId)) {
            HuhuTalk.LOGGER.info(
                    "[HuhuTalk] 手机动画诊断: stableId={} cacheActive={} raiseFound={} mergeOk={} calls={}",
                    stableId, active,
                    gecko.getAnimation(PhoneAnimationRegistration.ANIM_RAISE) != null,
                    PhoneAnimationHandler.lastMergeSucceeded(),
                    CALL_COUNT);
        }

        // ---- 诊断 B：当前动画名切换。每一次 raise/hold/lower 的变化都会留下一条，
        //      所以"卡在 raise 反复重播"会表现为多条 raise 行，与 hold 行交错 ----
        String nowName = currentName(controller);
        String prevName = LAST_ANIM.put(key, nowName);
        if (prevName != null && !prevName.equals(nowName)) {
            HuhuTalk.LOGGER.info("[HuhuTalk] 动画切换: stableId={} {} -> {} state={} active={} calls={}",
                    key, prevName, nowName, controller.getAnimationState(), active, CALL_COUNT);
        }

        if (active) {
            // 只在"窗口刚开启"这一帧重启，避免每帧 markNeedsReload 把 _raise 卡在起始帧。
            // 状态用 static 集合按 stableId 记录，不随 predicate/实体实例重建而丢失。
            boolean markedReload = false;
            if (stableId != null && ACTIVE_INSTANCES.add(stableId)) {
                controller.markNeedsReload();
                markedReload = true;
                HuhuTalk.LOGGER.info("[HuhuTalk] 重启原因=窗口刚开启: stableId={} calls={}", key, CALL_COUNT);
            }

            // ---- 诊断 C：动画链重建检测。setAnimation 只在真正要重建队列时才会把
            //      state 置为 TRANSITIONING；借此把"链被重建"这一事件精确抓出来，
            //      并记录重建原因（我们刚 markNeedsReload / 之前当前动画 / 之前状态）----
            AnimationState beforeState = controller.getAnimationState();
            controller.setAnimation(RAISE_THEN_HOLD);
            if (controller.getAnimationState() == AnimationState.TRANSITIONING
                    && beforeState != AnimationState.TRANSITIONING) {
                int n = RESTART_COUNT.merge(key, 1, Integer::sum);
                HuhuTalk.LOGGER.info(
                        "[HuhuTalk] raise 链被重建(第{}次): stableId={} 重建前动画={} 重建前state={} 我们markNeedsReload={} calls={}",
                        n, key, nowName, beforeState, markedReload, CALL_COUNT);
            }

            // ---- 诊断 D：真实骨骼姿态。直接读控制器最终写入骨骼的角度（度）与位移，
            //      可证明 hold 的抖动到底有没有被应用、幅度有多大 ----
            if (stableId != null && CALL_COUNT % 60 == 0) {
                IBone arm = gecko.getAnimationProcessor().getBone("RightArm");
                IBone fore = gecko.getAnimationProcessor().getBone("RightForeArm");
                HuhuTalk.LOGGER.info(
                        "[HuhuTalk] 姿态: state={} current={} RightArm.x={} RightForeArm.x={} ForeArm.posY={} calls={}",
                        controller.getAnimationState(), describeCurrent(controller),
                        deg(arm), deg(fore),
                        fore == null ? "-" : String.format("%.2f", fore.getPositionY()),
                        CALL_COUNT);
            }
            return PlayState.CONTINUE;
        }

        // 窗口关闭：播放下段；播完（STOPPED）后停止驱动，骨骼回落给 main 控制器
        if (stableId != null) {
            ACTIVE_INSTANCES.remove(stableId);
        }
        if (controller.getAnimationState() != AnimationState.STOPPED) {
            controller.setAnimation(LOWER);
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    /** 当前动画名（诊断用）。 */
    private static String currentName(AnimationController<?> controller) {
        try {
            var anim = controller.getCurrentAnimation();
            return anim == null ? "none" : anim.animationName;
        } catch (Exception e) {
            return "err";
        }
    }

    /** 诊断：描述控制器当前正在播放的动画（名称 + 时长），用于判断 _raise 是否被反复重启。 */
    private static String describeCurrent(AnimationController<?> controller) {
        try {
            var anim = controller.getCurrentAnimation();
            return anim == null ? "none" : (anim.animationName + "@" + anim.animationLength);
        } catch (Exception e) {
            return "err";
        }
    }

    /** 诊断：把骨骼的弧度旋转转成"度"打印，便于直接和动画 JSON 里的数值对照。 */
    private static String deg(IBone bone) {
        return bone == null ? "-" : String.format("%.2f", Math.toDegrees(bone.getRotationX()));
    }
}
