package com.huhutalk.client;

/**
 * 手机动画的公开常量。
 * <p>
 * 实际的事件监听在 {@link PhoneAnimationHandler}（须用 {@code @SubscribeEvent} 实例方法范式注册）；
 * 本类只放动画名常量，便于 {@code PhoneAnimation} 等引用而不产生循环依赖。
 */
public final class PhoneAnimationRegistration {

    /** 抬手（一次） */
    public static final String ANIM_RAISE = "huhutalk_phone_raise";

    /** 持机打字（循环） */
    public static final String ANIM_HOLD = "huhutalk_phone_hold";

    /** 放下（一次） */
    public static final String ANIM_LOWER = "huhutalk_phone_lower";

    private PhoneAnimationRegistration() {
    }
}
