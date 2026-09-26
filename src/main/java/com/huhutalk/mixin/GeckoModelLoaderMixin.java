package com.huhutalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.resource.GeckoModelLoader;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.huhutalk.client.PhoneAnimationHandler;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把本模组的「使用手机」动画确定性地并进 TLM 的女仆动画表。
 * <p>
 * <b>为什么不用 {@code DefaultGeckoAnimationEvent}</b>：它是 TLM 官方扩展点，但实测在本环境下
 * 未按预期触发（订阅后从未收到事件，动画始终未并入），排查成本已高于收益。改为注入
 * {@code registerMaidAnimations} 入口——这是 TLM 把"默认动画表"复制进每个 gecko 模型的必经之路：
 * <pre>
 * registerMaidAnimations(id, animationFile):
 *     DEFAULT_MAID_ANIMATION_FILE.forEach { 若 animationFile 没有则补进去 }   ← 我们的动画在此被复制
 *     GeckoLibCache.animations.put(id, animationFile)
 * </pre>
 * 因此在方法入口确保 {@code DEFAULT_MAID_ANIMATION_FILE} 含有我们的三个动画后，
 * 每个女仆 gecko 模型（含酒狐全族）都会自动带上；而模型包自带同名动画时仍以模型包为准
 * （与 TLM 原有语义一致）。
 */
@Mixin(GeckoModelLoader.class)
public abstract class GeckoModelLoaderMixin {

    @Inject(method = "registerMaidAnimations", at = @At("HEAD"))
    private static void huhutalk$injectPhoneAnimation(ResourceLocation id, AnimationFile animationFile, CallbackInfo ci) {
        PhoneAnimationHandler.ensureAnimations(animationFile);
    }
}
