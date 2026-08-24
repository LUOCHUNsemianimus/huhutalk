package com.huhutalk.phone.anim;

/**
 * 一次性 0→1 进度补间。
 * <p>
 * 硬性规则：所有动画统一固定时长 {@link #DURATION} = 0.25 秒。
 * 用 {@link System#nanoTime()} 单调时钟推进，跨帧自动前进、与渲染调用频率无关。
 */
public final class AnimTween {

    /** 全局统一动画时长（秒） */
    public static final float DURATION = 0.25f;

    private final long startNanos;

    public AnimTween() {
        this.startNanos = System.nanoTime();
    }

    /** 线性原始进度 0..1（未 clamp，供调用方做 easing） */
    public float raw() {
        return (System.nanoTime() - startNanos) / (DURATION * 1_000_000_000f);
    }

    /** 是否已播完（raw ≥ 1） */
    public boolean finished() {
        return raw() >= 1f;
    }
}
