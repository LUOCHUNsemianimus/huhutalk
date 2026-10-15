package com.huhutalk.mixin;

import com.huhutalk.client.render.SelfieCameraTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自拍 pass 里给玩家（且只给本地玩家）摆一个「举手机」的姿态：右臂前抬、略向身体中线，
 * 并在该偏置上叠加头部角度，使手臂跟随镜头指向转动（同原版持弓跟随视线）。
 *
 * <h2>为什么注入 {@code HumanoidModel.setupAnim} 的 TAIL</h2>
 * {@code PlayerModel.setupAnim} 先 {@code super.setupAnim(...)}，<b>之后</b>才
 * {@code leftSleeve/rightSleeve.copyFrom(leftArm/rightArm)}（1.21.1 PlayerModel L142-146）。
 * 注入 HumanoidModel 的 TAIL 正好落在袖子复制之前，于是袖子/护甲层会跟着这条手臂一起动，
 * 不会出现「光手臂抬了、袖子还在原地」。
 *
 * <p>出手条件是 {@code entity == Minecraft.getInstance().player}：只影响自拍里的本地玩家，
 * 其他玩家、以及护甲层（ArmorModel 同样继承 HumanoidModel 且传入同一 player）自然一致。
 *
 * <p>姿态只在自拍 pass 生效。{@code setupAnim} 每帧都会重算全部骨骼旋转，
 * 退出自拍 pass 后正常帧自然恢复，无需显式保存/回写。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelSelfieMixin {

    /** 右臂前抬 ≈ -1.3 rad（负 xRot = 向身前抬起）。镜头俯仰会在此基础上叠加 */
    private static final float SELFIE_ARM_FORWARD = -1.3F;
    /** 右臂保留的轻微横向偏置（-0.15 rad），让手臂不完全贴在身体中线上；镜头偏航会在此基础上叠加 */
    private static final float SELFIE_ARM_INWARD = -0.15F;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void huhutalk$selfieArmPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!SelfieCameraTarget.isRendering()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || entity != mc.player) {
            return;
        }
        HumanoidModel<?> self = (HumanoidModel<?>) (Object) this;
        // 仿原版弓/准星姿态：在固定偏置上叠加头部（= 镜头）角度，让手臂指向跟着镜头转。
        // head.xRot / head.yRot 在 setupAnim 里已经算好（含玩家真实俯仰与偏航），此处直接复用，
        // 不引入任何额外 clamp 或开关，也完全不碰玩家服务器状态。
        self.rightArm.xRot = SELFIE_ARM_FORWARD + self.head.xRot;
        self.rightArm.yRot = SELFIE_ARM_INWARD + self.head.yRot;
        self.rightArm.zRot = 0.0F;
    }
}
