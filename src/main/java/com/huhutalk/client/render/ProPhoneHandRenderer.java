package com.huhutalk.client.render;

import com.huhutalk.init.HuhuTalkItems;
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

    // ==================== 可调常量（期 0 人工微调区） ====================

    /** 手机整体缩放（0~1 空间；1.0 = 原尺寸，约 0.175 宽） */
    private static final float PHONE_SCALE = 3.0F;
    /** 手机在双手之间的垂直偏移 */
    private static final float PHONE_OFFSET_Y = 0.05F;
    /** 手机在双手之间的前后偏移（负 = 靠向玩家） */
    private static final float PHONE_OFFSET_Z = 0.0F;
    /** 手机局部 X 轴旋转（俯仰） */
    private static final float PHONE_ROT_X = 0.0F;
    /** 手机局部 Y 轴旋转（左右翻转）；屏幕朝后时改成 0 */
    private static final float PHONE_ROT_Y = 0.0F;
    /** 手机局部 Z 轴旋转（画面内翻滚） */
    private static final float PHONE_ROT_Z = 0.0F;

    // ==================== 入口 ====================

    private ProPhoneHandRenderer() {
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (!ENABLED) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // 只做第一人称：第三人称由原版手持层负责（模型本体照常显示）
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        // 打开界面（含文本输入屏）时不接管，避免和 Screen 的渲染/输入打架
        if (mc.screen != null) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!stack.is(HuhuTalkItems.PHONE_SMART.get())) {
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
        float f = Mth.sqrt(swingProgress);
        float f1 = -0.2F * Mth.sin(swingProgress * (float) Math.PI);
        float f2 = -0.4F * Mth.sin(f * (float) Math.PI);
        poseStack.translate(0.0F, -f1 / 2.0F, f2);
        float tilt = calculateMapTilt(pitch);
        poseStack.translate(0.0F, 0.04F + equippedProgress * -1.2F + tilt * -0.5F, -0.72F);
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt * -85.0F));

        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            renderPhoneHand(mc, player, poseStack, buffer, packedLight, HumanoidArm.RIGHT);
            renderPhoneHand(mc, player, poseStack, buffer, packedLight, HumanoidArm.LEFT);
            poseStack.popPose();
        }

        float f4 = Mth.sin(f * (float) Math.PI);
        poseStack.mulPose(Axis.XP.rotationDegrees(f4 * 20.0F));

        renderPhoneBody(mc, player, poseStack, buffer, packedLight, stack);
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
                                        MultiBufferSource buffer, int packedLight, ItemStack stack) {
        poseStack.pushPose();
        poseStack.translate(0.0F, PHONE_OFFSET_Y, PHONE_OFFSET_Z);
        poseStack.mulPose(Axis.XP.rotationDegrees(PHONE_ROT_X));
        poseStack.mulPose(Axis.YP.rotationDegrees(PHONE_ROT_Y));
        poseStack.mulPose(Axis.ZP.rotationDegrees(PHONE_ROT_Z));
        poseStack.scale(PHONE_SCALE, PHONE_SCALE, PHONE_SCALE);
        // 0~1 空间 → 以模型中心为原点（与 ItemRenderer.render 的 "-0.5" 同理）
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        ItemRenderer itemRenderer = mc.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, mc.level, player, 0);
        boolean foil = stack.hasFoil();
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            for (RenderType renderType : pass.getRenderTypes(stack, true)) {
                VertexConsumer consumer = ItemRenderer.getFoilBufferDirect(buffer, renderType, true, foil);
                itemRenderer.renderModelLists(pass, stack, packedLight, OverlayTexture.NO_OVERLAY, poseStack, consumer);
            }
        }
        poseStack.popPose();
    }
}
