package com.huhutalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.geckolayer.GeckoLayerMaidHeld;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.ILocationModel;
import com.huhutalk.client.render.PhoneInHandLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 女仆「正在用手机」时，把 TLM 渲染在**右手**上的真实手持物压掉。
 *
 * <h2>为什么必须在这里动手</h2>
 * 手机（{@code PhoneInHandLayer}）和女仆真正拿的东西（{@link GeckoLayerMaidHeld}）用的是
 * **同一根 {@code RightHandLocator} 骨骼**，同时渲染必然穿模。原设计是"主手非空就让位"，
 * 后果是女仆只要拿着工具/武器，手机就永远显示不出来 —— 而她在战斗中恰恰最容易错过消息。
 * 正确做法反过来了：**窗口开着的时候，手持物让位给手机**。
 *
 * <h2>只压右手</h2>
 * 注入点在 {@link GeckoLayerMaidHeld#renderArmWithItem}，它同时负责左右手，
 * 所以按 {@code arm == RIGHT} 过滤：副手/左手的东西照常显示（手机只占右手）。
 * 注：该方法内部还会遍历 {@code extraRightHandBones()} 再画一遍同一件物品，
 * 把整个方法取消掉正好一次解决。
 *
 * <h2>触发条件</h2>
 * 直接用 {@link PhoneInHandLayer#isUsingPhone} —— 与"这一帧到底画不画手机"是同一个判据
 * （窗口开着 + 客户端知道她戴着手机），因此正常情况下不存在"手持物藏了、手机却没画"的空手帧。
 * <b>唯一例外</b>：模型缺 {@code RightHandLocator} 骨骼、或该骨骼 scale 为 0 —— 这两种情况
 * 手机层会早退。实际模型都自带这根骨骼、动画也不动它的 scale，所以碰不到。
 * 窗口关闭（含播放下段动画）时条件立刻变假，手持物当帧恢复。
 */
@Mixin(GeckoLayerMaidHeld.class)
public abstract class GeckoLayerMaidHeldMixin {

    /**
     * 目标方法（TLM 1.5.3 实际签名，别漏掉第 3 个参数、别把 T 写成 LivingEntity）：
     * <pre>
     * protected void renderArmWithItem(T livingEntity, ItemStack itemStack, ILocationModel geoModel,
     *         ItemDisplayContext displayContext, HumanoidArm arm, PoseStack poseStack,
     *         MultiBufferSource bufferSource, int light)
     * </pre>
     * 泛型 {@code T extends Mob} 在字节码里擦除成 {@link Mob}，所以处理器第一个参数必须是 {@code Mob}。
     */
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void huhutalk$hideHeldItemWhileUsingPhone(
            Mob mob, ItemStack itemStack, ILocationModel geoModel,
            ItemDisplayContext displayContext, HumanoidArm arm,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            CallbackInfo ci) {
        if (arm == HumanoidArm.RIGHT && PhoneInHandLayer.isUsingPhone(mob)) {
            ci.cancel();
        }
    }
}
