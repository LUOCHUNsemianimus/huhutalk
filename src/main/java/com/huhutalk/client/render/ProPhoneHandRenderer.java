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

    // ==================== 相机模式可调常量（人工微调区） ====================
    //
    // 相机模式（聊天页 📷）与平时持握是两套姿态：平时手机"平行地面"（地图式持握），
    // 相机模式要把它抬到眼前、屏幕正对玩家，作为取景框。
    // 下面这组只影响相机模式，改错了也伤不到平时的手感。

    /** 相机模式手机缩放（平时是 {@link #PHONE_SCALE}；取景框通常希望比平时更贴近脸、更大） */
    private static final float CAM_SCALE = 3.0F;
    /** 相机模式左右偏移（+ = 屏幕右方） */
    private static final float CAM_OFFSET_X = 0.0F;
    /** 相机模式上下偏移（+ = 上；抬到眼前一般取负值微调） */
    private static final float CAM_OFFSET_Y = -0.12F;
    /** 相机模式前后偏移（坐标系里 <b>−Z = 视线正前方</b>，所以推出取景框要取负） */
    private static final float CAM_OFFSET_Z = -0.55F;
    /** 相机模式俯仰（+ 让屏幕略微仰起/俯下，用于把取景框摆正对视线） */
    private static final float CAM_ROT_X = 0.0F;
    /**
     * 相机模式水平旋转。<b>默认 180</b>：手机模型的屏幕在 <b>+Z 面</b>，
     * 而此处 <b>+Z 指向玩家脸</b>，所以必须转 180° 才能让屏幕正对玩家。
     * 若实机看到的是手机背面/镜头组，把这里改成 0。
     */
    private static final float CAM_ROT_Y = 180.0F;
    /** 相机模式翻滚（画面左右镜像/颠倒时先动这个） */
    private static final float CAM_ROT_Z = 0.0F;

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
        float f = Mth.sqrt(swingProgress);
        float f1 = -0.2F * Mth.sin(swingProgress * (float) Math.PI);
        float f2 = -0.4F * Mth.sin(f * (float) Math.PI);
        poseStack.translate(0.0F, -f1 / 2.0F, f2);
        float tilt = calculateMapTilt(pitch);
        poseStack.translate(0.0F, 0.04F + equippedProgress * -1.2F + tilt * -0.5F, -0.72F);
        // 地图式俯仰补偿：tilt 是俯仰角的纯函数，把手机从"平行地面"一路转到"正对视线"。
        // 相机模式必须把这一项**反算掉**，否则手机姿态会随玩家看向哪里而乱转（见 applyCameraPose）。
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

        if (PhoneOverlay.isCameraMode()) {
            applyCameraPose(poseStack, tilt);
        }

        renderPhoneBody(mc, player, poseStack, buffer, packedLight, stack);
    }

    /**
     * 相机模式姿态：把手机从地图式持握搬到<b>眼前</b>，屏幕正对玩家（或说：取景框正对视线）。
     *
     * <h2>为什么先"反算"</h2>
     * 调用时 PoseStack 上已经叠加了地图式持握的两件事：
     * <ol>
     *   <li>绕 X 转 {@code tilt * -85°} —— 这一项让手机随俯仰角从"平行地面"转到"正对视线"；</li>
     *   <li>沿 Z 平移 {@code -0.72}（外加 {@code tilt * -0.5} 的 Y 偏移）。</li>
     * </ol>
     * 不加处理，"抬头看远方"时手机就是平躺的、屏幕朝上，根本拍不了照。所以这里把这两项
     * <b>按同样的量反向施加一遍</b>，让 PoseStack 回到"手机在原点、屏幕朝上、且与视线无关"的
     * 坐标系，再从零开始摆相机姿态。这就是本方法开头那两步平移/旋转的全部意义。
     *
     * <h2>轴向约定（微调时最需要知道的一条）</h2>
     * 经玩家视线旋转后，此处坐标系的 <b>−Z = 视线正前方</b>、<b>+Y = 上</b>、
     * <b>+Z = 玩家脸的方向</b>。而手机模型的<b>屏幕在 +Z 面</b>（{@code SCREEN_DEPTH = 8.25} 那一侧）。
     * 因此要让屏幕正对玩家，必须绕 Y 转 <b>180°</b> —— 不是 0°。
     *
     * <h2>可调区</h2>
     * 四个常量都是"凭手感"的量，实机直接改。若发现手机左右镜像/上下颠倒，
     * 先动 {@link #CAM_ROT_Z}（翻滚）与 {@link #CAM_ROT_X}（俯仰），再考虑 {@link #CAM_ROT_Y}。
     */
    private static void applyCameraPose(PoseStack poseStack, float tilt) {
        // ① 反算地图式俯仰补偿（角度精确抵消）
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt * 85.0F));
        // ② 反算地图式位移，回到原点
        poseStack.translate(0.0F, 0.04F + tilt * -0.5F, -0.72F);
        // ③ 相机模式自己的位移：抬到眼前、推出到视线前方
        poseStack.translate(CAM_OFFSET_X, CAM_OFFSET_Y, CAM_OFFSET_Z);
        // ④ 相机模式自己的朝向
        poseStack.mulPose(Axis.XP.rotationDegrees(CAM_ROT_X));
        poseStack.mulPose(Axis.YP.rotationDegrees(CAM_ROT_Y));
        poseStack.mulPose(Axis.ZP.rotationDegrees(CAM_ROT_Z));
        poseStack.scale(CAM_SCALE, CAM_SCALE, CAM_SCALE);
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
     * 本帧该用哪张手机模型。
     *
     * <h2>为什么相机模式要换模型，而不是给模型掏窟窿</h2>
     * 相机模式需要屏幕区域能<b>看到真实游戏世界</b>。掏窟窿（把实体机壳换成四条边框）
     * 当然能做到，但要多维护一套几何。更省的做法是<b>只换纹理</b>：
     * {@code phone_3d_erased} 把"玩家侧可见面"擦成全透明，机壳在那些面上直接透出后面的场景。
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
        if (PhoneOverlay.isCameraMode()) {
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
