package com.huhutalk.client.render;

import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.phone.PhoneOverlay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.slf4j.Logger;

/**
 * 玩家「主手持 Pro 手机」的第一人称手持渲染（地图式双手持握）。
 *
 * <h2>为什么不用 mixin</h2>
 * 第一人称手持渲染链是：
 * <pre>
 * ItemInHandRenderer.renderHandsWithItems
 *   → if (!ClientHooks.renderSpecificFirstPersonHand(MAIN_HAND, ...)) { renderArmWithItem(...); }
 * </pre>
 * {@code ClientHooks.renderSpecificFirstPersonHand} 派发 NeoForge 的 {@link RenderHandEvent}
 * （{@code ICancellableEvent}），并把它携带的 {@code poseStack / multiBufferSource / packedLight /
 * interpolatedPitch / swingProgress / equipProgress / stack} **原样**交给我们 ——
 * 这些正好是原版 {@code renderTwoHandedMap} 需要的全部参数。
 * 所以我们只需 {@code setCanceled(true)} 让原版整段让位，**不需要任何 mixin，也不会双重绘制**。
 *
 * <h2>几何与坐标系约定</h2>
 * <ul>
 *   <li>物品模型的 baked quad 位于 **0~1 空间**（原版 {@code ItemRenderer.render} 里
 *       用 {@code translate(-0.5F,-0.5F,-0.5F)} 把它居中就是这个原因）。</li>
 *   <li>本类**绕过** {@code display} 变换（自己控制位置/角度/大小），因此
 *       {@code phone_3d.json} 里那套 {@code firstperson_*} 数值对本路径**无效** —— 微调请改下面常量。</li>
 *   <li>手机几何在 0~1 空间里只有 <b>0.175 × 0.339 × 0.043</b>（2.8×5.43×0.68 / 16），
 *       所以「看起来像手机」需要把 {@link #PHONE_SCALE} 放大到 2.5~3.5 量级。</li>
 * </ul>
 *
 * <h2>期 0 的可调常量（全部集中在下面，人工验收时改这几个即可）</h2>
 * <ol>
 *   <li>{@link #PHONE_SCALE} —— 手机整体大小（对原版地图 0.38 单位做参照：手机应当明显更大）</li>
 *   <li>{@link #PHONE_OFFSET_Y} / {@link #PHONE_OFFSET_Z} —— 手机在双手之间的前后上下位置</li>
 *   <li>{@link #PHONE_ROT_X} / {@link #PHONE_ROT_Y} / {@link #PHONE_ROT_Z} —— 出厂朝向。
 *       初始值抄自原版 {@code renderMap}（屏幕朝相机）；<b>若验收时发现「屏幕朝后」，
 *       只需把 {@link #PHONE_ROT_Y} 改成 0</b>。</li>
 * </ol>
 *
 * <h2>期 0 安全开关</h2>
 * {@link #ENABLED} 置 {@code false} 立即回到原版手持渲染（本类唯一入口，改一行即可回退）。
 * 原版路径的代码**一行未动**。
 *
 * <p>TODO(期 2)：在 {@link #renderPhoneBody} 之后、同一 PoseStack 上叠加「屏幕四边形」，
 * 贴 FBO 出的 Overlay 纹理。
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ProPhoneHandRenderer {

    /** 总开关：false = 完全走原版手持渲染（期 0 的一键回退）。 */
    public static boolean ENABLED = true;

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    // ==================== 可调常量（期 0 人工微调区） ====================

    /** 手机整体缩放（0~1 空间；1.0 = 原尺寸，约 0.175 宽） */
    private static final float PHONE_SCALE = 3.0F;
    /**
     * 手机在双手之间的基础垂直偏移（手机局部高度，向上为正）。
     * 机身与离屏屏幕共享这一变换；静止参考姿态也使用同一常量，最终 HUD 对齐由投影补偿完成。
     */
    private static final float PHONE_OFFSET_Y = 0.05F;
    /** 手机在双手之间的前后偏移（负 = 靠向玩家） */
    private static final float PHONE_OFFSET_Z = 0.0F;
    /** 手机局部 X 轴旋转（俯仰） */
    private static final float PHONE_ROT_X = 0.0F;
    /** 手机局部 Y 轴旋转（左右翻转）；屏幕朝后时改成 0 */
    private static final float PHONE_ROT_Y = 0.0F;
    /** 手机局部 Z 轴旋转（画面内翻滚） */
    private static final float PHONE_ROT_Z = 0.0F;

    // ==================== 相机模式可调常量（人工微调区） ====================
    //
    // 相机模式（聊天页 📷）与平时持握是两套姿态：平时手机"平行地面"（地图式持握），
    // 相机模式要把它抬到眼前、屏幕正对玩家，作为取景框。
    // 下面这组只影响相机模式，改错了也伤不到平时的手感。
    //
    // ⚠ 这里**没有**缩放常量，且刻意不设：缩放由 renderPhoneBody 统一施加 PHONE_SCALE。
    //    曾经在这组里加过一个 CAM_SCALE，结果与 PHONE_SCALE 相乘（3.0 × 3.0 = 9 倍），
    //    实机表现就是"相机模式的手机莫名其妙变大"。
    //    若确实想让相机模式另用一套大小，请**把系数乘进 PHONE_SCALE**，不要在这里再 scale 一次。

    /**
     * 相机模式下加在俯仰角上的偏移（度），用来把 {@code tilt} 的判定点挪走。
     *
     * <h2>它解决的是什么</h2>
     * {@code tilt = 1 − xRot/45 + 0.1}（再 clamp 到 0..1）的原始语义是：
     * 平视 {@code tilt=1} → 手机转 −85°（几乎平躺）；低头 90° 时 {@code tilt=0} → 竖直正对视线。
     * 这个"从平行地面滑到垂直视线"的过程<b>就是</b>手机随俯仰打转的唯一来源。
     * <p>
     * 关键：{@code tilt} 对 {@code xRot} 是<b>非线性</b>的，它引入的不是常数旋转量，
     * <b>事后补任何常数角度都补不干净</b>（前两版就是死在这）。
     * 正解是让它<b>不要产生</b>：把判定点挪到"抬头看天"，于是正常俯仰范围内 {@code tilt} 恒为 0，
     * 手机先天垂直于视线。
     *
     * <h2>为什么是 +90 而不是 −90</h2>
     * {@code xRot} 的约定是<b>低头为正</b>。代入要算给 {@link #calculateMapTilt} 的实参：
     * <ul>
     *   <li>平视 {@code xRot=0} → 实参 {@code +90} → {@code 1 − 90/45 + 0.1 = −0.9} → clamp <b>0</b> ✓</li>
     *   <li>低头 {@code xRot=+90} → 实参 {@code +180} → 更负 → clamp <b>0</b> ✓</li>
     *   <li>抬头 {@code xRot=−90} → 实参 {@code 0} → {@code 1.1} → clamp 1（此时才趋近平行，正是"判定点在看天"）</li>
     * </ul>
     * 反过来取 −90 会让平视和低头都算成 1，等于没挪。<b>改这个值前务必按上述三点验算一遍。</b>
     */
    private static final float CAM_TILT_REF_PITCH = 90.0F;

    /** 相机模式左右偏移（视线坐标系：+ = 屏幕右方） */
    private static final float CAM_OFFSET_X = 0.0F;
    /**
     * 相机模式上下偏移（视线坐标系：+ = 上）。
     * <p>
     * 取 {@code 0.04} = <b>与常态势握完全一致</b>。
     * 常态势握的基准位移是 {@code translate(0, 0.04 + equippedProgress*-1.2 + tilt*-0.5, -0.72)}，
     * 相机模式把 {@code tilt} 项去掉之后，只要 X/Y/Z 取 {@code 0 / 0.04 / -0.72}，
     * 手机的<b>视距与高度</b>就和平时持握在同一处 —— 不会忽大忽小、也不会忽远忽近。
     * <p>
     * ⚠ 曾经写成 {@code -0.06 / -0.62}（比常态近 0.1 单位），实机表现就是
     * "相机模式手机更靠近玩家、看起来更大" —— 那不是缩放问题，是<b>距离</b>问题（近大远小）。
     */
    private static final float CAM_OFFSET_Y = 0.04F;
    /** 相机模式前后偏移（视线坐标系：− = 远离玩家。−0.72 = 常态势握的基准视距） */
    private static final float CAM_OFFSET_Z = -0.72F;
    /** 相机模式俯仰（+ 让屏幕略微仰起/俯下，用于把取景框摆正对视线） */
    private static final float CAM_ROT_X = 0.0F;
    /**
     * 相机模式水平旋转。<b>默认 0</b>。
     * <p>
     * ⚠ 别想当然地在这里补 180°。默认地图式持握（低头看手机）时，
     * 模型 <b>+Z 面本来就正对玩家</b> —— 这也正是"擦掉 +Z 面的纹理，玩家侧那块就没了"能成立的前提。
     * 所以屏幕侧不需要任何水平翻转；再加 180° 会把手机<b>整个转背过去</b>，
     * 玩家看到的就变成背面那块镜头组（实机踩到）。
     */
    private static final float CAM_ROT_Y = 0.0F;
    /** 相机模式翻滚（画面左右镜像/颠倒时先动这个） */
    private static final float CAM_ROT_Z = 0.0F;

    /**
     * 相机模式诊断探针（默认关）。
     * <p>
     * 置 {@code true} 后，只要处于相机模式就每 2 秒往 {@code latest.log} 打一行
     * {@code [HuhuTalk/CamPose]}，内容是实时的 {@code xRot / tilt / 各相机常量}。
     * <p>
     * <b>它能一眼区分两类失败</b>，不再靠猜：
     * <ul>
     *   <li><b>日志根本没出现</b> → {@link PhoneOverlay#isCameraMode()} 没返回 true，
     *       相机分支压根没执行（那旋转就来自别处，或页面状态没设上）；</li>
     *   <li><b>日志出现且 {@code tilt≈0}</b> → 姿态计算是对的，
     *       旋转来自别的地方（臂部动画、{@code swingProgress}、或 FBO 屏幕四边形）。</li>
     * </ul>
     * 与 {@code PhoneScreenTarget.DIAG} 同一个套路：<b>只按时间节流，不按内容</b>
     * （内容每帧都变，按内容判重等于没节流）。
     */
    private static final boolean CAM_DIAG = false;
    private static long camDiagLastAt = 0L;

    // ==================== 入口 ====================

    private ProPhoneHandRenderer() {
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!shouldRender(mc, player, stack)) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffer = event.getMultiBufferSource();
        int packedLight = event.getPackedLight();

        poseStack.pushPose();
        renderTwoHandedPhone(mc, player, poseStack, buffer, packedLight,
                event.getInterpolatedPitch(), event.getEquipProgress(), event.getSwingProgress(), stack);
        poseStack.popPose();

        // 让原版 renderArmWithItem 整段让位（否则会再画一遍单手物品）
        event.setCanceled(true);
    }

    // ==================== 姿势（照抄 ItemInHandRenderer.renderTwoHandedMap） ====================

    /**
     * 原版 {@code ItemInHandRenderer.calculateMapTilt}：把手持倾角映射为地图前倾量。
     * 保持逐字一致，这样手机的手感与地图完全相同。
     */
    private static float calculateMapTilt(float pitch) {
        float f = 1.0F - pitch / 45.0F + 0.1F;
        f = Mth.clamp(f, 0.0F, 1.0F);
        return -Mth.cos(f * (float) Math.PI) * 0.5F + 0.5F;
    }

    /**
     * 双手持机姿势：左右两条手臂 + 手机本体。
     * <p>
     * 前 8 行是原版 {@code renderTwoHandedMap} 的常量（挥动/装备过渡/俯仰跟随），
     * 中间 {@code renderTwoHandedArms} 是原版 {@code renderMapHand} 的两条手臂，
     * 末段把手绘地图换成我们的 3D 手机本体。
     */
    private static void renderTwoHandedPhone(Minecraft mc, AbstractClientPlayer player, PoseStack poseStack,
                                             MultiBufferSource buffer, int packedLight, float pitch,
                                             float equippedProgress, float swingProgress, ItemStack stack) {
        // 静止参考矩阵：复刻 base 位移 / tilt / 相机姿态，但不参与 equip/swing 动画。
        // 投影对齐只用它求局部 Y 补偿，实际绘制仍保留原动画。
        PoseStack referencePose = new PoseStack();
        referencePose.last().pose().set(poseStack.last().pose());

        boolean camera = PhoneOverlay.isCameraMode();
        // 前摄取景：右手单手持机（屏朝玩家）。模型右臂在自拍 pass 里由核心摆好，
        // 这里只管第一人称"自己手里那台"的持握，不复用原版 ItemRenderer（那会让屏幕熄灭）。
        boolean selfie = PhoneOverlay.isSelfieMode();
        // 「常规地图式双手持握」：非相机态本就如此；前摄的确认页也走它，把手机放下看照片。
        boolean mapPose = !camera || PhoneOverlay.cameraUseDefaultHoldPose();

        float f = Mth.sqrt(swingProgress);
        float f1 = -0.2F * Mth.sin(swingProgress * (float) Math.PI);
        float f2 = -0.4F * Mth.sin(f * (float) Math.PI);
        poseStack.translate(0.0F, -f1 / 2.0F, f2);

        // 相机模式：位移改用视线坐标系里的常量（此时 PoseStack 还没被 tilt 旋转，是唯一干净的时机）；
        // 但 tilt 旋转**照旧保留** —— 手臂是在这一帧里画的，去掉它手就会和手机分家。
        // 手机本体稍后由 applyCameraPose 精确抵消这一项即可。
        //
        // ⚠ 相机模式的 tilt 用**另一个参考俯仰**（见 CAM_TILT_REF_PITCH）：
        // 取 +90 时，正常俯仰范围内 tilt 恒为 0 = 手机始终垂直于视线，先天就没有"随俯仰打转"。
        float tilt = (camera && !mapPose)
                ? calculateMapTilt(pitch + CAM_TILT_REF_PITCH)
                : calculateMapTilt(pitch);
        if (camera && !mapPose) {
            poseStack.translate(CAM_OFFSET_X, CAM_OFFSET_Y, CAM_OFFSET_Z);
            referencePose.translate(CAM_OFFSET_X, CAM_OFFSET_Y, CAM_OFFSET_Z);
        } else {
            poseStack.translate(0.0F, 0.04F + equippedProgress * -1.2F + tilt * -0.5F, -0.72F);
            referencePose.translate(0.0F, 0.04F + tilt * -0.5F, -0.72F);
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt * -85.0F));
        referencePose.mulPose(Axis.XP.rotationDegrees(tilt * -85.0F));

        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            // 前摄只画右臂：复用原版 renderRightHand 的手臂模型与朝向，砍掉左臂就是"单手持机"。
            // 其余任何情况仍画双臂（原版地图式 / 后摄取景姿态）。
            renderPhoneHand(mc, player, poseStack, buffer, packedLight, HumanoidArm.RIGHT);
            if (!selfie) {
                renderPhoneHand(mc, player, poseStack, buffer, packedLight, HumanoidArm.LEFT);
            }
            poseStack.popPose();
        }

        float f4 = Mth.sin(f * (float) Math.PI);
        poseStack.mulPose(Axis.XP.rotationDegrees(f4 * 20.0F));

        if (camera && !mapPose) {
            applyCameraPose(poseStack, tilt);
            applyCameraPose(referencePose, tilt);
            camDiag(player.getXRot(), pitch, tilt);
        }

        // 相机取景姿态需要完整对齐；地图姿态从 30° 到 40° 平滑启用，
        // 40° 及更深全部生效，30° 及更浅保持原放平姿态，避免阈值突跳。
        float alignWeight = (camera && !mapPose)
                ? 1.0F
                : Mth.clamp((pitch - 30.0F) / 10.0F, 0.0F, 1.0F);
        renderPhoneBody(mc, player, poseStack, referencePose, buffer, packedLight, stack, alignWeight);
    }

    /**
     * 相机模式姿态：只做一件事 —— 抵消地图式 {@code tilt} 旋转，让手机相对视线恒定。     *
     * <h2>为什么这里<b>不该</b>再补一个 {@code -xRot}（曾经的错误修法）</h2>
     * 抵消掉 {@code tilt} 之后，手机在视线坐标系里的朝向已经只剩"视角自身的旋转"。
     * 如果再乘一个 {@code RotY(-xRot)}：
     * <ul>
     *   <li>它绕的是<b>竖直轴</b>，而屏幕法线（正对玩家的方向）本来就落在这个轴的附近 ——
     *       于是这个"水平旋转"对外表现成<b>画面内打转</b>，也就是玩家描述的"绕 Z 轴转"；</li>
     *   <li>{@code RotX(-xRot)} 与 {@code RotY(-xRot)} <b>不对易</b>，
     *       两者叠在一起永远不会互相抵消，只会合成一个随俯仰连续变化的歪斜 ——
     *       这正是"怎么调都还在转"的原因。</li>
     * </ul>
     * 结论：手部那一路已经包含了正确的手感曲线，相机模式需要的是"把 tilt 变成 0"，
     * <b>而不是在它之上再叠一层反向旋转</b>。
     *
     * <h2>尺寸：这里绝不能 scale</h2>
     * 缩放由 {@code renderPhoneBody} 统一施加 {@link #PHONE_SCALE}。
     * 本方法曾在这里再乘一次缩放常量 —— 两个 3.0 相乘 = 实机 9 倍，
     * 表现就是"相机模式下的手机明显变大"（实机踩到）。
     * <b>任何"相机模式专属缩放"都必须乘进 {@link #PHONE_SCALE}，不能在这里另乘一次。</b>
     */
    private static void applyCameraPose(PoseStack poseStack, float tilt) {
        // 唯一要做的事：把地图式 tilt 旋转抵消掉（CAM_TILT_REF_PITCH 已让它≈0，这里兜底）
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt * 85.0F));
        // 相机模式自己的微调（默认全 0；实机现场调）
        poseStack.mulPose(Axis.XP.rotationDegrees(CAM_ROT_X));
        poseStack.mulPose(Axis.YP.rotationDegrees(CAM_ROT_Y));
        poseStack.mulPose(Axis.ZP.rotationDegrees(CAM_ROT_Z));
    }

    /** 见 {@link #CAM_DIAG}。只按时间节流。 */
    private static void camDiag(float xRot, float interpPitch, float tilt) {
        if (!CAM_DIAG) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - camDiagLastAt < 2000L) {
            return;
        }
        camDiagLastAt = now;
        LOGGER.info("[HuhuTalk/CamPose] xRot={} interpPitch={} tilt={} refPitch={} offsets=({},{},{}) rots=({},{},{})",
                String.format(java.util.Locale.ROOT, "%.2f", xRot),
                String.format(java.util.Locale.ROOT, "%.2f", interpPitch),
                String.format(java.util.Locale.ROOT, "%.4f", tilt),
                CAM_TILT_REF_PITCH, CAM_OFFSET_X, CAM_OFFSET_Y, CAM_OFFSET_Z,
                CAM_ROT_X, CAM_ROT_Y, CAM_ROT_Z);
    }

    /**
     * 单手（用于双手持握的两条手臂）：逐字照抄原版 {@code ItemInHandRenderer.renderMapHand}。
     * 直接复用 {@link PlayerRenderer} 的手部模型，因此皮肤/袖子/手臂动画全部原版一致。
     */
    private static void renderPhoneHand(Minecraft mc, AbstractClientPlayer player, PoseStack poseStack,
                                        MultiBufferSource buffer, int packedLight, HumanoidArm side) {
        PlayerRenderer playerRenderer = (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
        poseStack.pushPose();
        float f = side == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(92.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f * -41.0F));
        poseStack.translate(f * 0.3F, -1.1F, 0.45F);
        if (side == HumanoidArm.RIGHT) {
            playerRenderer.renderRightHand(poseStack, buffer, packedLight, player);
        } else {
            playerRenderer.renderLeftHand(poseStack, buffer, packedLight, player);
        }
        poseStack.popPose();
    }

    /**
     * 手机本体：绕过 {@code display} 变换，用本类常量自己摆位，
     * 其余（render pass / render type / 附魔光效）完全照抄原版 {@code ItemRenderer.render} 的遍历方式。
     */
    private static void renderPhoneBody(Minecraft mc, AbstractClientPlayer player, PoseStack poseStack,
                                        PoseStack referencePose, MultiBufferSource buffer, int packedLight,
                                        ItemStack stack, float alignWeight) {
        poseStack.pushPose();
        applyPhoneBodyTransform(poseStack);

        // 参考姿态施加同一 body transform，但不含实际 equip/swing 运动：
        // 用它求屏幕底边中心对齐到原版生命/饱食度首行上沿所需的局部 Y 位移。
        applyPhoneBodyTransform(referencePose);
        float alignDelta = PhoneScreenTarget.localYCompensation(referencePose);
        // 补偿加在机身与 renderScreen 共同绘制之前，两者共享同一位移。
        poseStack.translate(0.0F, alignDelta * alignWeight, 0.0F);

        ItemRenderer itemRenderer = mc.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stackForMode(mc, stack), mc.level, player, 0);
        boolean foil = stack.hasFoil();
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            for (RenderType renderType : pass.getRenderTypes(stack, true)) {
                VertexConsumer consumer = ItemRenderer.getFoilBufferDirect(buffer, renderType, true, foil);
                itemRenderer.renderModelLists(pass, stack, packedLight, OverlayTexture.NO_OVERLAY, poseStack, consumer);
            }
        }

        // ⚠ 顺序关键：必须先把机身刷掉，再排入屏幕四边形。
        // MultiBufferSource.endBatch() 的刷新顺序是「先共享缓冲（RenderType.text 走这里）、
        // 后固定缓冲（机身走 Sheets.solid 的固定缓冲）」。若不在此处主动 endBatch，
        // 后刷的机身会盖住先刷的屏幕。主动 flush 之后，屏幕四边形作为新的共享批次单独排队，
        // 在 renderHandsWithItems 结尾统一刷出，顺序才正确。
        if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch();
        }
        PhoneScreenTarget.renderScreen(poseStack, buffer, packedLight);

        poseStack.popPose();
    }

    /**
     * 机身与离屏屏幕共享的最终局部变换（基础 offset、出厂旋转、缩放、居中）。
     * 正常绘制与静止参考姿态必须调用本方法，避免两套变换漂移。
     */
    private static void applyPhoneBodyTransform(PoseStack poseStack) {
        poseStack.translate(0.0F, PHONE_OFFSET_Y, PHONE_OFFSET_Z);
        poseStack.mulPose(Axis.XP.rotationDegrees(PHONE_ROT_X));
        poseStack.mulPose(Axis.YP.rotationDegrees(PHONE_ROT_Y));
        poseStack.mulPose(Axis.ZP.rotationDegrees(PHONE_ROT_Z));
        poseStack.scale(PHONE_SCALE, PHONE_SCALE, PHONE_SCALE);
        // 0~1 空间 → 以模型中心为原点（与 ItemRenderer.render 的 "-0.5" 同理）
        poseStack.translate(-0.5F, -0.5F, -0.5F);
    }

    /**
     * 本帧该用哪张手机模型。
     *
     * <h2>为什么相机模式要换模型，而不是给模型掏窟窿</h2>
     * 相机模式需要屏幕区域能<b>看到真实游戏世界</b>。掏窟窿（把实体机壳换成四条边框）
     * 当然能做到，但要多维护一套几何。更省的做法是<b>只换纹理</b>：
     * {@code phone_camera_2} 把"玩家侧可见面"擦成全透明，机壳在那些面上直接透出后面的场景。
     * <p>
     * 几何完全不动，因此<b>不需要第二套 cube 数据</b>——所谓"新增一个模型"实际只是
     * 换一行纹理引用的同一份几何（{@code models/item/phone_camera.json}）。
     * 这也是为什么 {@link HuhuTalkItems#PHONE_CAMERA} 只是个"模型名片"物品。
     * <p>
     * <b>为什么用临时 ItemStack 而不是直接捞 BakedModel</b>：
     * {@code ItemRenderer.getModel(stack, ...)} 会自动处理注册名 → 模型 JSON（含 parent 继承、
     * 缓存），而手搓 {@code ModelResourceLocation} 去 {@code ModelManager} 里取，
     * 既依赖版本相关的构造器签名，又绕过原版缓存。多一个隐藏物品是更小的代价。
     */
    private static ItemStack stackForMode(Minecraft mc, ItemStack held) {
        // 只有「透明取景」时才换擦除版纹理。相机的「已拍成页」仍是相机（姿态不变），
        // 但那时屏幕上是一张全屏照片，机壳要是还被擦掉就会从照片背后透出世界。
        if (PhoneOverlay.isCameraScreenTransparent()) {
            return new ItemStack(HuhuTalkItems.PHONE_CAMERA.get());
        }
        return held;
    }

    /**
     * 本帧是否由本渲染器接管玩家第一人称手持。
     * <p>
     * 与 {@link #onRenderHand} 共用同一个判据（{@link #shouldRender}），因此离屏出图
     * （{@link PhoneScreenTarget#update}）与真正的接管**永远同步**：
     * 判据为真就一定画得出来，为假就完全不跑离屏渲染（关屏零开销）。
     */
    public static boolean isActive() {
        if (!ENABLED) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        return shouldRender(mc, player, mc.player.getMainHandItem());
    }

    /**
     * 接管判据（{@code onRenderHand} 与 {@link #isActive} 的<b>唯一</b>真相来源）。
     *
     * <h2>为什么不再排除「有 Screen 打开」</h2>
     * 早期这里有一句 {@code if (mc.screen != null) return;}，理由写的是"避免和 Screen 打架"。
     * 那是个误判：第一人称手持渲染走的是 {@code GameRenderer.renderLevel → renderItemInHand}，
     * 而 {@code renderItemInHand} 里判断的是 {@code isFirstPerson && !hideGui}，
     * <b>跟有没有 Screen 毫无关系</b>（见原版 GameRenderer L951-954）。
     * <p>
     * 于是那段早退的真实后果是：手机输入屏（{@link com.huhutalk.phone.PhoneInputScreen}，
     * 一个完全透明的 {@code Screen}，只用来接键盘）一弹出来，我们就主动让位给原版
     * {@code renderArmWithItem} —— 手机瞬间从"双手持握 + 模型屏"塌回普通手持物品，
     * 玩家点一下输入框，手机就"没了"。而 Screen 那边其实什么都没抢。
     * <p>
     * 更关键的是：{@code PhoneInputScreen.render} 是空的、且 {@code isPauseScreen()==false}，
     * 世界与手持照常渲染，所以现在"透明输入屏 + 模型屏"是可以共存的 —— 这正是想要的观感：
     * 打字时手机仍在手里，屏幕上的输入光标由 ChatPage 照常自己画。
     * <p>
     * 唯一的实际影响是<b>那只独立光标会冻结</b>：{@code MouseHandler.handleAccumulatedMovement}
     * 只在 {@code isMouseGrabbed()} 时才调 {@code turnPlayer}，而 {@code setScreen} 会释放鼠标。
     * 这也是对的 —— 输入屏期间玩家的鼠标属于真实 GUI 光标，手机上那只应当停住。
     */
    private static boolean shouldRender(Minecraft mc, AbstractClientPlayer player, ItemStack stack) {
        // ⚠ 两个调用点必须严格同步：这里若比 onRenderHand 更宽松，就会出现
        // 「离屏靶每帧都在出图，却没有任何模型去采样它」的纯浪费。
        if (player != mc.player) {
            return false;
        }
        // 只做第一人称：第三人称由原版手持层负责（模型本体照常显示）
        if (!mc.options.getCameraType().isFirstPerson()) {
            return false;
        }
        return stack.is(HuhuTalkItems.PHONE_SMART.get());
    }
}
