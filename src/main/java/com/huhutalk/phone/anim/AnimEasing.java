package com.huhutalk.phone.anim;

/**
 * 全局统一缓动函数。
 * <p>
 * 硬性规则：所有进场动画用 ease-out，所有退场动画用 ease-in。
 */
public final class AnimEasing {

    private AnimEasing() {
    }

    /** 进场缓动：ease-out（三次曲线，起快收慢） */
    public static float easeOut(float t) {
        float c = clamp01(t);
        return 1f - (1f - c) * (1f - c) * (1f - c);
    }

    /** 退场缓动：ease-in（三次曲线，起慢收快） */
    public static float easeIn(float t) {
        float c = clamp01(t);
        return c * c * c;
    }

    private static float clamp01(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }
}
