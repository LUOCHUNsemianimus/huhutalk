package com.huhutalk.mixin;

import com.huhutalk.client.render.SelfieRenderAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@code LevelRenderer} 的 Fabulous 特效链与描边靶字段，供自拍 pass 临时置空。
 *
 * <h2>为什么必须置空（污染风险）</h2>
 * Fabulous 图形模式下 {@code transparencyChain} / {@code entityEffect} 这两个 {@link PostChain}
 * 在<b>创建时</b>就绑定了"当时的主渲染靶"（{@code LevelRenderer.initTransparency/initOutline}
 * 里 {@code new PostChain(..., minecraft.getMainRenderTarget(), ...)}）。
 * 自拍 pass 把主靶换成竖屏离屏靶后，若仍让它们 {@code process()}，输出会写进<b>原主画面</b> —— 主 pass 被污染。
 * 而 {@code transparencyChain != null} 分支里的 {@code translucentTarget.copyDepthFrom(mainRenderTarget())}
 * 还会在两个尺寸不同的 Fbo 之间做深度 blit。
 *
 * <p>同时置空 {@code entityTarget}：{@code shouldShowEntityOutlines()} 为
 * {@code !panoramic && entityTarget != null && entityEffect != null && player != null}，
 * 两者置空后描边链整体不触发，不会 {@code clear()} 到一个尺寸不符的旧 Fbo。
 *
 * <p>于是自拍 pass 走"非 Fabulous 直接渲染"路径，{@code finally} 里逐字恢复原对象。
 * 全程不写 {@code options.graphicsMode()}，不动玩家的画面设置。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSelfieAccessor implements SelfieRenderAccess.LevelRendererAccess {

    @Override
    @Accessor("transparencyChain")
    public abstract PostChain huhutalk$getTransparencyChain();

    @Override
    @Accessor("transparencyChain")
    public abstract void huhutalk$setTransparencyChain(PostChain chain);

    @Override
    @Accessor("entityEffect")
    public abstract PostChain huhutalk$getEntityEffect();

    @Override
    @Accessor("entityEffect")
    public abstract void huhutalk$setEntityEffect(PostChain chain);

    @Override
    @Accessor("entityTarget")
    public abstract RenderTarget huhutalk$getEntityTarget();

    @Override
    @Accessor("entityTarget")
    public abstract void huhutalk$setEntityTarget(RenderTarget target);

    @Override
    @Accessor("translucentTarget")
    public abstract RenderTarget huhutalk$getTranslucentTarget();

    @Override
    @Accessor("translucentTarget")
    public abstract void huhutalk$setTranslucentTarget(RenderTarget target);

    @Override
    @Accessor("itemEntityTarget")
    public abstract RenderTarget huhutalk$getItemEntityTarget();

    @Override
    @Accessor("itemEntityTarget")
    public abstract void huhutalk$setItemEntityTarget(RenderTarget target);

    @Override
    @Accessor("particlesTarget")
    public abstract RenderTarget huhutalk$getParticlesTarget();

    @Override
    @Accessor("particlesTarget")
    public abstract void huhutalk$setParticlesTarget(RenderTarget target);

    @Override
    @Accessor("weatherTarget")
    public abstract RenderTarget huhutalk$getWeatherTarget();

    @Override
    @Accessor("weatherTarget")
    public abstract void huhutalk$setWeatherTarget(RenderTarget target);

    @Override
    @Accessor("cloudsTarget")
    public abstract RenderTarget huhutalk$getCloudsTarget();

    @Override
    @Accessor("cloudsTarget")
    public abstract void huhutalk$setCloudsTarget(RenderTarget target);
}
