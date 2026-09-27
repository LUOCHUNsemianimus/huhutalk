package com.huhutalk.client.render;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoLayerRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoEntityRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.ILocationModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.huhutalk.client.PhoneChatSourceCache;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.init.MaidPhoneTier;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 把手机模型挂到女仆右手上的渲染层（「使用手机」动画期间显示）。
 *
 * <h2>挂点：模型自带的 {@code RightHandLocator} 骨骼</h2>
 * TLM 的 gecko 模型里每个女仆模型都自带一根 {@code RightHandLocator} 骨骼
 * （父级链 {@code RightHand → RightForeArm → RightArm → Arm → UpperBody …}）。
 * {@link RenderUtils#prepMatrixForLocator} 会沿这条父级链把每一根骨骼的
 * rotation / position / scale 依次应用到 PoseStack 上，因此：
 * <ul>
 *   <li>手机**自动继承整套手臂动画**（包括我们在 {@code huhutalk_phone_*} 里写的
 *       {@code RightArm} / {@code RightForeArm} 关键帧），不需要额外计算；</li>
 *   <li>如果想让手机相对手再偏一点/转一点，**直接在动画 JSON 里加 {@code RightHandLocator} 的
 *       position/rotation 关键帧**即可，逐帧可控 —— TLM 自己的 {@code use_mainhand:spyglass}
 *       就是这么把望远镜对准眼睛的：
 *       <pre>"RightHandLocator": { "rotation": [0.99, 0.014, -180.2], "position": [0, -2, 1.5] }</pre></li>
 *   <li>该骨骼的 scale 为 0 时（{@code prepMatrixForLocator} 返回 true）本层不渲染，可当作显示开关。</li>
 * </ul>
 *
 * <h2>可调的三层数值（按"由粗到细"排列）</h2>
 * <ol>
 *   <li><b>手机本体相对手的出厂朝向</b>：物品模型 JSON 的 {@code display.thirdperson_righthand}
 *       （rotation / translation / scale）。属于美术资源，改它不用改 Java。</li>
 *   <li><b>本层的基准变换</b>：{@link #BASE_TRANSLATE_Y} / {@link #BASE_TRANSLATE_Z} /
 *       {@link #BASE_ROTATE_X} / {@link #SCALE} —— 与 TLM {@code GeckoLayerMaidHeld} 保持一致，
 *       因为物品渲染器期望"手部局部坐标系"。改这几个常量即可整体挪动手机。</li>
 *   <li><b>逐帧微调</b>：动画 JSON 里 {@code RightHandLocator} 的关键帧（级别最高，覆盖上面两者）。</li>
 * </ol>
 *
 * <h2>与 TLM 自带手持层的分工</h2>
 * {@code GeckoLayerMaidHeld} 已经在 {@code RightHandLocator}/{@code LeftHandLocator} 上渲染
 * **女仆真正手持的物品**。为避免同一个挂点叠两层，本层在"主手非空"时直接跳过。
 */
public class PhoneInHandLayer<T extends Mob, R extends IGeoEntityRenderer<T>> extends GeoLayerRenderer<T, R> {

    /** 与 TLM {@code GeckoLayerMaidHeld} 相同的基准变换（物品渲染器需要"手部局部系"） */
    private static final float BASE_TRANSLATE_Y = -0.0625F;
    private static final float BASE_TRANSLATE_Z = -0.1F;
    private static final float BASE_ROTATE_X = -90.0F;
    /** 手机整体缩放（1.0 = 与手持物品同比例；真正的尺寸旋钮是模型自带的 display.scale） */
    private static final float SCALE = 1.0F;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static String lastDiag = "";
    private static long lastDiagAt = 0L;

    /** 诊断日志：同一条消息最多每 5 秒打一次，避免刷屏 */
    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (msg.equals(lastDiag) && now - lastDiagAt < 5000L) {
            return;
        }
        lastDiag = msg;
        lastDiagAt = now;
        LOGGER.info("[HuhuTalk/Hand] {}", msg);
    }

    private final ItemInHandRenderer itemInHandRenderer;

    public PhoneInHandLayer(R entityRendererIn, ItemInHandRenderer itemInHandRenderer) {
        super(entityRendererIn);
        this.itemInHandRenderer = itemInHandRenderer;
    }

    @Override
    public GeoLayerRenderer<T, R> copy(R entityRendererIn) {
        return new PhoneInHandLayer<>(entityRendererIn, this.itemInHandRenderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!(entity instanceof EntityMaid maid)) {
            diag("skip: entity is not EntityMaid");
            return;
        }
        // 只在「手机来源窗口」内渲染 —— 与 huhutalk_phone_* 动画用的是同一个客户端窗口缓存，
        // 因此手机的出现/消失天然与 raise/hold/lower 同步，不需要额外同步协议。
        String stableId = (maid instanceof StableIdProvider provider) ? provider.getStableId() : null;
        if (!PhoneChatSourceCache.isActive(stableId)) {
            diag("skip: chat window inactive, stableId=" + stableId);
            return;
        }
        // 主手有东西时不画，避免与 TLM 自带的 GeckoLayerMaidHeld 在同一挂点叠两层
        if (!maid.getMainHandItem().isEmpty()) {
            diag("skip: main hand not empty = " + maid.getMainHandItem());
            return;
        }
        ItemStack phone = phoneOf(maid);
        if (phone.isEmpty()) {
            // 客户端看不到手机 = 服务端没把配饰同步下来。dump 出客户端这份配饰栏的内容，
            // 一眼就能区分「客户端配饰栏是空的」和「同步了但物品不对」。
            diag("skip: maid has no phone tier (PhoneHelper.getPhoneTier), clientBaubles=" + baubleDump(maid));
            return;
        }
        ILocationModel geoModel = getLocationModel(entity);
        if (geoModel == null) {
            diag("skip: getLocationModel returned null");
            return;
        }
        if (geoModel.rightHandBones().isEmpty()) {
            diag("skip: rightHandBones() empty (model has no RightHandLocator)");
            return;
        }

        poseStack.pushPose();
        boolean scaleIsZero = RenderUtils.prepMatrixForLocator(poseStack, geoModel.rightHandBones());
        if (!scaleIsZero) {
            poseStack.translate(0, BASE_TRANSLATE_Y, BASE_TRANSLATE_Z);
            poseStack.mulPose(Axis.XP.rotationDegrees(BASE_ROTATE_X));
            poseStack.scale(SCALE, SCALE, SCALE);
            this.itemInHandRenderer.renderItem(maid, phone, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    false, poseStack, bufferSource, packedLight);
            diag("RENDERED " + phone.getItem() + " bones=" + geoModel.rightHandBones().size() + " SCALE=" + SCALE);
        } else {
            diag("skip: locator scale is zero");
        }
        poseStack.popPose();
    }

    /** 女仆实际佩戴的手机物品（Pro 优先）；没戴手机则不渲染。 */
    private static ItemStack phoneOf(EntityMaid maid) {
        MaidPhoneTier tier = PhoneHelper.getPhoneTier(maid);
        if (tier == MaidPhoneTier.SMART) {
            return new ItemStack(HuhuTalkItems.PHONE_SMART.get());
        }
        if (tier == MaidPhoneTier.WATCH) {
            return new ItemStack(HuhuTalkItems.PHONE_WATCH.get());
        }
        return ItemStack.EMPTY;
    }

    /** 诊断用：把客户端这份配饰栏里非空的格子列出来 */
    private static String baubleDump(EntityMaid maid) {
        var handler = maid.getMaidBauble();
        if (handler == null) {
            return "handler=null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(i).append('=').append(stack.getItem());
            }
        }
        return sb.append(']').toString();
    }
}
