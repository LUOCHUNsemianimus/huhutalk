package com.huhutalk.client.animation;

import com.github.tartaricacid.touhoulittlemaid.client.entity.GeckoMaidEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.AnimationState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.PlayState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.AnimationBuilder;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.ILoopType;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate.AnimationEvent;
import com.huhutalk.client.PhoneChatSourceCache;
import com.huhutalk.common.StableIdProvider;

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

    private static final String ANIM_RAISE = "huhutalk_phone_raise";
    private static final String ANIM_HOLD = "huhutalk_phone_hold";
    private static final String ANIM_LOWER = "huhutalk_phone_lower";

    /**
     * 抬手（一次）+ 持机打字（循环）的动画链。
     * 用 builder 串接而非自己计时：{@code raise} 播完会自动续上 {@code hold} 循环。
     */
    private static final AnimationBuilder RAISE_THEN_HOLD = new AnimationBuilder()
            .playOnce(ANIM_RAISE)
            .loop(ANIM_HOLD);

    /** 放下手臂与头（一次）。 */
    private static final AnimationBuilder LOWER = new AnimationBuilder()
            .playOnce(ANIM_LOWER);

    /** 上一帧的忙碌状态，用于只在跃变时换动画（避免每帧重置播放进度）。 */
    private boolean wasActive = false;

    /** 每只女仆一个实例（由 {@code GeckoMaidEntityMixin} 创建），持有各自的上帧状态。 */
    public PhoneAnimation() {
    }

    /** 每帧由控制器调用，决定本控制器当前该播什么。 */
    public PlayState predicate(AnimationEvent<GeckoMaidEntity<?>> event, AnimationController<?> controller) {
        GeckoMaidEntity<?> gecko = event.getAnimatableEntity();
        if (gecko == null || !(gecko.getMaid() instanceof EntityMaid maid)) {
            return PlayState.STOP;
        }
        String stableId = (maid instanceof StableIdProvider provider) ? provider.getStableId() : null;
        boolean active = PhoneChatSourceCache.isActive(stableId);

        if (active) {
            if (!wasActive) {
                // 窗口刚开启：置 reload 确保上一轮的进度被丢弃，从抬手重新播
                controller.markNeedsReload();
                wasActive = true;
            }
            // 窗口内每帧重复提交同一 builder；内容相同则控制器不会重启，只在 raise 播完后续上 hold
            controller.setAnimation(RAISE_THEN_HOLD);
            return PlayState.CONTINUE;
        }

        // 窗口关闭：播放下段；播完（STOPPED）后停止驱动，骨骼回落给 main 控制器
        wasActive = false;
        if (controller.getAnimationState() != AnimationState.STOPPED) {
            controller.setAnimation(LOWER);
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }
}
