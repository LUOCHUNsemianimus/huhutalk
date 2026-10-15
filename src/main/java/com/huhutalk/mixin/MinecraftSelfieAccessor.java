package com.huhutalk.mixin;

import com.huhutalk.client.render.SelfieCameraTarget;
import com.huhutalk.client.render.SelfieRenderAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自拍 pass 需要的两件事，都落在 {@link Minecraft} 上：
 *
 * <ol>
 *   <li>暴露 {@code mainRenderTarget} 的写入口（{@code private final}，故 {@code @Mutable}），
 *       把「主靶」临时指向自拍 Fbo；</li>
 *   <li><b>自拍 pass 内让 {@code useShaderTransparency()} 返回 false</b>。
 *       原版 {@code RenderStateShard.TRANSLUCENT_TARGET / PARTICLES_TARGET / WEATHER_TARGET /
 *       CLOUDS_TARGET / ITEM_ENTITY_TARGET} 的 setup/clear 回调判断的是
 *       {@code Minecraft.useShaderTransparency()}，<b>不是</b> {@code transparencyChain != null}。
 *       只置空链的话，这些回调仍会把 Fabulous 的窗口尺寸临时靶绑上、并在 clear 时绑回
 *       {@code getMainRenderTarget()}（此时是自拍靶），造成状态错乱。
 *       这里在自拍 pass 内统一返回 false，与 {@link LevelRendererSelfieAccessor} 的置空互补。
 *       不改 {@code options.graphicsMode()}，退出 pass 立刻恢复真实值。</li>
 * </ol>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSelfieAccessor implements SelfieRenderAccess.MinecraftAccess {

    @Override
    @Mutable
    @Accessor("mainRenderTarget")
    public abstract void huhutalk$setMainRenderTarget(RenderTarget target);

    @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
    private static void huhutalk$selfieDisableShaderTransparency(CallbackInfoReturnable<Boolean> cir) {
        if (SelfieCameraTarget.isRendering()) {
            cir.setReturnValue(false);
        }
    }
}
