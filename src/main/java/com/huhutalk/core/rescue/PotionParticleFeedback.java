package com.huhutalk.core.rescue;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/**
 * 原生彩色药水粒子反馈（救援 / 远程补给共用）。
 *
 * <h2>1.20.1 的彩色协议</h2>
 * 1.20.1 没有 {@code ColorParticleOption}，但 {@link ParticleTypes#ENTITY_EFFECT} 本身可以携带颜色：
 * 以 {@code count = 0} 调用 {@code sendParticles} 时，协议把
 * {@code xDist/yDist/zDist} 三个"偏移"当作粒子颜色的 R/G/B 通道，而 {@code speed}
 * 是与这三个 offset 相乘的速度标量；{@link ParticleTypes#ENTITY_EFFECT} 的 provider
 * 把乘积结果当作 RGB 颜色使用，而不是 alpha。因此这里按通道除 255 传入，颜色不会再丢。
 *
 * <h2>数量与位置扩散</h2>
 * count=0 只能发一个粒子，所以这里按原数量循环，每个粒子用
 * {@code level.random.nextGaussian() * spread} 采样位置 —— 与 {@code sendParticles} 在
 * {@code count > 0} 时内部采用的分布一致，保留原来的液色、数量和高斯散布。
 *
 * <p>该旧原生协议无法独立携带 1.21 的额外速度参数；本类只承诺沿用 1.20 原生颜色协议。
 */
public final class PotionParticleFeedback {

    private PotionParticleFeedback() {
    }

    /**
     * 在指定坐标发射 {@code count} 个带液色的 {@code ENTITY_EFFECT} 粒子。
     *
     * @param level   服务端世界
     * @param x       粒子中心 X 坐标
     * @param y       粒子中心 Y 坐标
     * @param z       粒子中心 Z 坐标
     * @param color   0xRRGGBB 液色；负值表示"没有颜色"，直接不发射
     * @param count   粒子数量（原调用点的 50 / 24）
     * @param spreadX 每个粒子在 X 轴的高斯扩散
     * @param spreadY 每个粒子在 Y 轴的高斯扩散
     * @param spreadZ 每个粒子在 Z 轴的高斯扩散
     */
    public static void emit(ServerLevel level, double x, double y, double z,
                            int color, int count,
                            double spreadX, double spreadY, double spreadZ) {
        if (level == null || color < 0 || count <= 0) {
            return;
        }
        double r = ((color >> 16) & 0xFF) / 255.0D;
        double g = ((color >> 8) & 0xFF) / 255.0D;
        double b = (color & 0xFF) / 255.0D;
        for (int i = 0; i < count; i++) {
            double px = x + level.random.nextGaussian() * spreadX;
            double py = y + level.random.nextGaussian() * spreadY;
            double pz = z + level.random.nextGaussian() * spreadZ;
            level.sendParticles(ParticleTypes.ENTITY_EFFECT, px, py, pz, 0, r, g, b, 1.0D);
        }
    }
}
