package com.huhutalk.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostChain;

/**
 * 自拍离屏渲染需要的「原版私有成员」访问接口。
 *
 * <h2>为什么要有这个类</h2>
 * 自拍 pass 直接调用原版 {@code GameRenderer.renderLevel(DeltaTracker)} 复用整条世界渲染管线，
 * 但必须临时改写几处原版私有状态（主渲染靶、Fbo 特效链、手持开关、方块描边开关），
 * 并在 {@code finally} 里逐字恢复。这些字段在原版里是 private：
 * <ul>
 *   <li>{@code Minecraft.mainRenderTarget} —— <b>private final</b>，因此 Accessor 还要加 {@code @Mutable}；</li>
 *   <li>{@code GameRenderer.renderHand} / {@code renderBlockOutline} —— 只有 setter 没有 getter，必须读回原值；</li>
 *   <li>{@code LevelRenderer.transparencyChain} / {@code entityEffect} / {@code entityTarget} —— Fabulous 特效链，
 *       见 {@link SelfieCameraTarget} 的风险说明。</li>
 * </ul>
 *
 * <p>Mixin 类 {@code implements} 这里的接口（{@code @Accessor} 生成实现），
 * 调用侧只依赖接口做强制转换，编译期不必知道 Mixin 的存在。
 *
 * <p><b>注意</b>：接口方法名全部带 {@code huhutalk$} 前缀，避免和目标类上可能存在的同名方法冲突。
 */
public final class SelfieRenderAccess {

    private SelfieRenderAccess() {
    }

    /** {@code GameRenderer} 上的读写能力 */
    public interface GameRendererAccess {
        /** 读 {@code renderHand} 原值，用于 finally 精确恢复（原版只有 setter） */
        boolean huhutalk$getRenderHand();

        /** 读 {@code renderBlockOutline} 原值 */
        boolean huhutalk$getRenderBlockOutline();

        /** 自拍 pass 里关掉方块描边（描边是准星附属物，不该进照片） */
        void huhutalk$setRenderBlockOutline(boolean value);
    }

    /** {@code Minecraft} 上的主渲染靶读写能力 */
    public interface MinecraftAccess {
        /** 把主渲染靶临时指向自拍离屏靶（{@code mainRenderTarget} 是 private final，Mixin 侧需 {@code @Mutable}） */
        void huhutalk$setMainRenderTarget(RenderTarget target);
    }

    /** {@code LevelRenderer} 上的 Fabulous 特效链读写能力 */
    public interface LevelRendererAccess {
        PostChain huhutalk$getTransparencyChain();

        void huhutalk$setTransparencyChain(PostChain chain);

        PostChain huhutalk$getEntityEffect();

        void huhutalk$setEntityEffect(PostChain chain);

        RenderTarget huhutalk$getEntityTarget();

        void huhutalk$setEntityTarget(RenderTarget target);

        // ⚠ 下面 5 个 Fabulous 临时靶即使 transparencyChain 已置空也要一并置空：
        // LevelRenderer.renderLevel 里有若干「只看字段是否为 null」的分支
        // （itemEntityTarget.copyDepthFrom(mainRenderTarget)、weatherTarget.clear 等），
        // 会让窗口尺寸的旧靶和竖屏自拍靶互拷。置空后这些分支整体跳过。
        RenderTarget huhutalk$getTranslucentTarget();

        void huhutalk$setTranslucentTarget(RenderTarget target);

        RenderTarget huhutalk$getItemEntityTarget();

        void huhutalk$setItemEntityTarget(RenderTarget target);

        RenderTarget huhutalk$getParticlesTarget();

        void huhutalk$setParticlesTarget(RenderTarget target);

        RenderTarget huhutalk$getWeatherTarget();

        void huhutalk$setWeatherTarget(RenderTarget target);

        RenderTarget huhutalk$getCloudsTarget();

        void huhutalk$setCloudsTarget(RenderTarget target);
    }
}
