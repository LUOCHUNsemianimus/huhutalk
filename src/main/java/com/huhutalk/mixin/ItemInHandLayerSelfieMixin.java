package com.huhutalk.mixin;

import com.huhutalk.client.render.SelfieCameraTarget;
import com.huhutalk.init.HuhuTalkItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自拍 pass 里隐藏<b>本地玩家自己</b>手中那台 PHONE_SMART 的第三方手持渲染。
 *
 * <h2>为什么需要它</h2>
 * 自拍离屏 pass（{@link SelfieCameraTarget} 的 {@code renderSelfie}）会完整渲染本地玩家模型。
 * 本地玩家主手举着手机时，{@code ItemInHandLayer.renderArmWithItem} 会把手机本体
 * 以第三人称姿态画在胸前 —— 这是"前置镜头"视角，本机手机本体不应进入画面
 * （否则就成了"用外置镜子拍自己举着手机"）。
 *
 * <h2>它只做什么</h2>
 * 仅当三个条件同时成立时取消这一次物品渲染：
 * <ul>
 *   <li>自拍 pass 正在执行（{@link SelfieCameraTarget#isRendering()}）；</li>
 *   <li>被渲染的实体就是本地玩家（{@code entity == Minecraft.getInstance().player}）；</li>
 *   <li>被渲染的物品是 PHONE_SMART。</li>
 * </ul>
 * 只取消"手里的手机"这一件物品：<b>真实右臂模型姿势照常由 {@code HumanoidModelSelfieMixin} 渲染</b>，
 * 第一人称手持手机（{@link com.huhutalk.client.render.ProPhoneHandRenderer}）不受影响，
 * 其他玩家、其他物品、其他实体全部照常。
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerSelfieMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void huhutalk$hideSelfiePhone(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                          HumanoidArm arm, PoseStack poses, MultiBufferSource buffers,
                                          int packedLight, CallbackInfo ci) {
        if (SelfieCameraTarget.isRendering()
                && entity == Minecraft.getInstance().player
                && stack.is(HuhuTalkItems.PHONE_SMART.get())) {
            ci.cancel();
        }
    }
}
