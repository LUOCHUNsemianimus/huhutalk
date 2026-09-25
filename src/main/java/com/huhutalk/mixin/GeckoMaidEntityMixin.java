package com.huhutalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.entity.GeckoMaidEntity;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.AnimatableEntity;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.huhutalk.client.animation.PhoneAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 TLM 的女仆 Gecko 实体追加一个独立的「使用手机」动画控制器。
 * <p>
 * TLM 的 {@code GeckoMaidEntity.registerControllers()} 把控制器写死在方法体里，
 * 没有对外扩展点；这里在其末尾注入一条自己的控制器，从而在不改动 TLM 任何代码的前提下
 * 获得一个与 {@code main} / {@code use} / {@code swing} 平级的附加动画通道。
 * <p>
 * 控制器只驱动 {@code Head} / {@code RightArm} / {@code RightForeArm}，因此
 * 行走、游泳、坐姿等基础动作（由 {@code main} 控制器负责）完全不受影响。
 * 过渡时长取 2 tick，与 TLM 自身的 {@code main}/{@code use}/{@code swing} 一致。
 */
@Mixin(GeckoMaidEntity.class)
public abstract class GeckoMaidEntityMixin {

    /** 判定状态机。每只女仆一个实例，随实体创建。 */
    @Unique
    private final PhoneAnimation huhutalk$phoneAnimation = new PhoneAnimation();

    @Inject(method = "registerControllers", at = @At("TAIL"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void huhutalk$addPhoneController(CallbackInfo ci) {
        GeckoMaidEntity self = (GeckoMaidEntity) (Object) this;
        // lambda 需要引用 controller 自身（用于 markNeedsReload / getAnimationState），
        // 而 Java 不允许在初始化表达式中自引用，故用一元数组做 holder 绕开。
        final AnimationController[] holder = new AnimationController[1];
        AnimationController controller = new AnimationController(
                (AnimatableEntity) self,
                PhoneAnimation.CONTROLLER_NAME,
                2,
                event -> huhutalk$phoneAnimation.predicate(event, holder[0]));
        holder[0] = controller;
        ((AnimatableEntity) self).addAnimationController(controller);
    }
}
