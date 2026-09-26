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
 *   <li>手机来源窗口开启 → 先播 {@code raise}（一次），约 0.45s 后换 {@code hold}（循环打字）</li>
 *   <li>窗口关闭 → {@code lower}（一次）：放下手臂与头，播完停止</li>
 *   <li>窗口未开启 → {@code STOP}：不驱动，骨骼回落到 {@code main} 控制器</li>
 * </ul>
 * <b>只有手机来源窗口内的女仆播动画</b>：窗口由服务端在 {@code MaidChatHandler} 入口开启并同步
 * （见 {@code PhoneChatSourceController}），确保其他通道（对话引导、离线消息播报等）不会唤起动画。
 *
 * <h2>⚠ 为什么必须用"两条单动画 builder"而不是 {@code playOnce(raise).loop(hold)} 链</h2>
 * TLM 的 {@code AnimationController} 有一个只在"链式 builder + 播完自动接下一个"时暴露的 bug：
 * 动画播完时它只对队列做 {@code peek()} 把 {@code currentAnimation} 换成下一个（{@code hold}），
 * 却**没有** {@code poll()} 出新条目、也**没有**调用 {@code switchAnimation()}。
 * 而 {@code switchAnimation()} 才是把"骨骼 → 关键帧列表"重新绑定的地方
 * （即 {@code BoneAnimationQueue.animation}）。后果：
 * <ul>
 *   <li>{@code currentAnimation} 显示的是 {@code hold}，但骨骼队列仍绑着 {@code raise} 的关键帧；</li>
 *   <li>于是 {@code hold} 的关键帧**从未被求值**——抖动自然"看不见"；</li>
 *   <li>每次 hold 循环（1.9s）把 tick 归零后，求值的是 {@code raise@0}（= 静止姿态），
 *       手臂于是"掉下去再抬起来"，表现为"每隔约 1.9 秒重播一次抬手"。</li>
 * </ul>
 * 为什么链式推进时 {@code resetTick} 一定是 false：{@code process()} 开头
 * {@code adjustedTick = adjustTick(tick)} 已经把 {@code shouldResetTick} 消费成 false，
 * 所以后面第 246 行读出的 {@code resetTick} 恒为 false；只有 {@code setAnimation()} 在
 * **predicate 内**（即 adjustTick 之后）置位的那一帧才为 true。因此只有"换 builder"才能正确重绑。
 * <p>
 * TLM 自身所有调用点都是单条 builder（{@code new AnimationBuilder().addAnimation(name, loop)}），
 * 从未走进这条路径，所以这个 bug 一直没被发现。
 * <p>
 * 所以本类改为：抬手用 {@code playOnce(raise)}，约 0.45s 后用 {@code loop(hold)} 再提交一次。
 * 换 builder 会走 {@code setAnimation} 的重建分支 → {@code justStartedTransition=true}
 * → 下一帧正常 poll + {@code switchAnimation()} → hold 的关键帧被正确绑定。
 */
public final class PhoneAnimation {

    /** 控制器名。需与 {@code GeckoMaidEntityMixin} 注册的一致。 */
    public static final String CONTROLLER_NAME = "huhutalk_phone";

    /** 抬手（一次性）。<b>必须单条</b>，原因见类注释。 */
    private static final AnimationBuilder RAISE = new AnimationBuilder()
            .playOnce(PhoneAnimationRegistration.ANIM_RAISE);

    /** 持机打字（循环）。<b>必须单条</b>，原因见类注释。 */
    private static final AnimationBuilder HOLD = new AnimationBuilder()
            .loop(PhoneAnimationRegistration.ANIM_HOLD);

    /** 放下手臂与头（一次性）。 */
    private static final AnimationBuilder LOWER = new AnimationBuilder()
            .playOnce(PhoneAnimationRegistration.ANIM_LOWER);

    /**
     * raise → hold 的交接时刻（tick，单位同 {@code event.getAnimationTick()}）。
     * <p>
     * raise 时长 0.4s = 8 tick，但 {@code setAnimation} 后还会先花 {@code transitionLengthTicks}=2 tick
     * 过渡，所以 raise 自己的 tick 0 大约出现在提交后第 2 tick、播完在第 10 tick。
     * 取 9 表示"在 raise 播完那一帧之前"换 builder：predicate 在 {@code processCurrentAnimation}
     * 之前执行，所以这一帧不会走到 raise 的结束分支，也就不会出现
     * "骨骼队列为空 → 被重置逻辑瞬间拉回静止姿态"的一帧闪烁。
     */
    private static final double RAISE_HANDOFF_TICKS = 9.0D;

    /** 每个窗口的播放阶段。 */
    private enum Phase {
        /** 抬手中（一次性动画） */
        RAISE,
        /** 持机打字中（循环动画） */
        HOLD
    }

    /**
     * 各女仆当前阶段（按 stableId）。不存在 = 不在窗口内 / 需要重新抬手。
     * <p>
     * 必须是 <b>static</b>：predicate 的实例字段挂在 {@code GeckoMaidEntity} 上，
     * 而它是 NeoForge attachment、可能被重建。若状态随实例消失，每帧都会判定为
     * "窗口刚开启" → 每帧 {@code markNeedsReload()} → raise 被无限重启。
     */
    private static final Map<String, Phase> PHASE = new ConcurrentHashMap<>();

    /** 本窗口"抬手"段的起始动画时刻（按 stableId），仅 RAISE 阶段存在。 */
    private static final Map<String, Double> RAISE_STARTED = new ConcurrentHashMap<>();

    /** 诊断：已打印过首帧状态的女仆（按 stableId 去重，避免刷屏） */
    private static final Set<String> DIAGNOSED = ConcurrentHashMap.newKeySet();

    /** 诊断：predicate 被调用的总次数（用于判断控制器是否真的被调用） */
    private static volatile long CALL_COUNT = 0L;

    // ==================== 诊断（症状确认后删除） ====================

    /** 上一次窗口状态取值；用于打印 true/false 边沿，跟服务端开关窗对时间 */
    private static final Map<String, Boolean> LAST_ACTIVE = new ConcurrentHashMap<>();

    /** 上一次看到的"当前动画名"；用于打印每一次 raise/hold/lower 切换（重播会留下痕迹） */
    private static final Map<String, String> LAST_ANIM = new ConcurrentHashMap<>();

    /** builder 切换次数（按 stableId）。正常每个窗口恰好 2 次：raise、hold（lower 看"动画切换"日志） */
    private static final Map<String, Integer> SWITCH_COUNT = new ConcurrentHashMap<>();

    /** 每只女仆一个实例（由 {@code GeckoMaidEntityMixin} 创建）。 */
    public PhoneAnimation() {
    }

    /** 退出/进入世界时清空静态状态，避免残留 stableId 让下一局的第一次抬手被跳过。 */
    public static void reset() {
        PHASE.clear();
        RAISE_STARTED.clear();
        DIAGNOSED.clear();
        LAST_ACTIVE.clear();
        LAST_ANIM.clear();
        SWITCH_COUNT.clear();
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
        double nowTick = event.getAnimationTick();

        // ---- 诊断 A：窗口状态边沿。与服务端 [PhoneChatSource] 开关窗日志对时间 ----
        Boolean prevActive = LAST_ACTIVE.put(key, active);
        if (prevActive == null || prevActive != active) {
            HuhuTalk.LOGGER.info("[HuhuTalk] 窗口状态: stableId={} active {} -> {} tick={} calls={}",
                    key, prevActive, active, nowTick, CALL_COUNT);
        }

        // 每只女仆只打印一次首帧状态：稳定 ID 是否取到、窗口是否开启、动画能否查到
        if (stableId != null && DIAGNOSED.add(stableId)) {
            HuhuTalk.LOGGER.info(
                    "[HuhuTalk] 手机动画诊断: stableId={} cacheActive={} raiseFound={} holdFound={} mergeOk={} calls={}",
                    stableId, active,
                    gecko.getAnimation(PhoneAnimationRegistration.ANIM_RAISE) != null,
                    gecko.getAnimation(PhoneAnimationRegistration.ANIM_HOLD) != null,
                    PhoneAnimationHandler.lastMergeSucceeded(),
                    CALL_COUNT);
        }

        // ---- 诊断 B：当前动画名切换。每次 raise/hold/lower 的变化都会留一条 ----
        String nowName = currentName(controller);
        String prevName = LAST_ANIM.put(key, nowName);
        if (prevName != null && !prevName.equals(nowName)) {
            HuhuTalk.LOGGER.info("[HuhuTalk] 动画切换: stableId={} {} -> {} state={} active={} tick={} calls={}",
                    key, prevName, nowName, controller.getAnimationState(), active, nowTick, CALL_COUNT);
        }

        if (active) {
            Phase phase = PHASE.get(key);
            if (phase == null) {
                // 窗口刚开启：重启队列并播抬手段
                controller.markNeedsReload();
                controller.setAnimation(RAISE);
                PHASE.put(key, Phase.RAISE);
                RAISE_STARTED.put(key, nowTick);
                logSwitch(controller, key, "窗口刚开启 → 提交 raise", nowTick);
            } else if (phase == Phase.RAISE) {
                Double started = RAISE_STARTED.get(key);
                boolean raiseDone = started == null
                        || (nowTick - started) >= RAISE_HANDOFF_TICKS
                        || controller.getAnimationState() == AnimationState.STOPPED;
                if (!raiseDone) {
                    controller.setAnimation(RAISE); // 幂等，不会重启
                } else {
                    // 抬手段结束 → 换 builder 切到 hold。
                    // 关键：必须是"不同的 builder"，才会走 setAnimation 的重建分支，
                    // 从而正确调用 switchAnimation() 把骨骼队列绑到 hold 的关键帧上。
                    controller.setAnimation(HOLD);
                    PHASE.put(key, Phase.HOLD);
                    RAISE_STARTED.remove(key);
                    logSwitch(controller, key, "raise→hold 交接", nowTick);
                }
            } else {
                controller.setAnimation(HOLD); // 幂等续提交
            }

            // ---- 诊断 C：真实骨骼姿态。直接读控制器最终写入骨骼的角度（度）与位移，
            //      可证明 hold 的抖动有没有被应用、幅度多大 ----
            if (stableId != null && CALL_COUNT % 60 == 0) {
                IBone arm = gecko.getAnimationProcessor().getBone("RightArm");
                IBone fore = gecko.getAnimationProcessor().getBone("RightForeArm");
                HuhuTalk.LOGGER.info(
                        "[HuhuTalk] 姿态: state={} current={} RightArm.x={} RightForeArm.x={} ForeArm.posY={} tick={}",
                        controller.getAnimationState(), describeCurrent(controller),
                        deg(arm), deg(fore),
                        fore == null ? "-" : String.format("%.2f", fore.getPositionY()),
                        nowTick);
            }
            return PlayState.CONTINUE;
        }

        // 窗口关闭：播放下段；播完（STOPPED）后停止驱动，骨骼回落给 main 控制器
        PHASE.remove(key);
        RAISE_STARTED.remove(key);
        if (controller.getAnimationState() != AnimationState.STOPPED) {
            controller.setAnimation(LOWER);
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    /** 诊断：记录一次真实的 builder 切换（正常每个窗口 2 次：raise、hold）。 */
    private static void logSwitch(AnimationController<?> controller, String key, String what, double nowTick) {
        int n = SWITCH_COUNT.merge(key, 1, Integer::sum);
        HuhuTalk.LOGGER.info("[HuhuTalk] builder 切换(第{}次): stableId={} {} 切换前state={} tick={}",
                n, key, what, controller.getAnimationState(), nowTick);
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

    /** 诊断：描述控制器当前正在播放的动画（名称 + 时长）。 */
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
