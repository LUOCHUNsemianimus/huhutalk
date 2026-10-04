package com.huhutalk.client.render;

import com.huhutalk.phone.PhoneOverlay;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * 手机「模型屏」离屏渲染靶：把 {@link PhoneOverlay} 整块 UI 画进一张离屏纹理，
 * 再由 {@link ProPhoneHandRenderer} 贴到 3D 手机模型的屏幕面上。
 *
 * <h2>为什么能直接用 {@link GuiGraphics}（关键设计）</h2>
 * {@code GuiGraphics} 的绘制全部是「当前投影矩阵 + 当前帧缓冲」下的普通 GL 调用，
 * 所以只要给一个自洽的坐标系与一块自己的帧缓冲，它就能原样跑。两个约束：
 * <ol>
 *   <li><b>裁剪</b>：{@code GuiGraphics.applyScissor} 只从 {@code window.getHeight()}（帧缓冲像素高）
 *       和 {@code window.getGuiScale()} 取数。因此只要让<b>离屏靶的像素高 = 窗口帧缓冲高</b>、
 *       <b>正交换算系数 = 窗口 GUI Scale</b>，页面里满地的 {@code enableScissor} 就自动正确，
 *       <b>不需要碰任何原版方法</b>。</li>
 *   <li><b>深度</b>：原版 GUI pass 的投影是 {@code ortho(..., 1000, 21000)} 且把模型视图
 *       {@code translate(0,0,-11000)}（这样 z≈0 的内容才落在裁剪体内）。这里逐字照抄。</li>
 * </ol>
 * 于是：<b>1 GUI 单位 = GUI Scale 个像素</b>，与玩家在屏幕上看到的手机完全一致；
 * 手机贴图分辨率也自然随玩家的 GUI Scale 缩放（高分屏自动 GUI Scale 更大 → 更清晰）。
 *
 * <h2>画布尺寸：复刻原版悬浮窗「跟随 GUI Scale」的耦合</h2>
 * 原版悬浮窗的尺寸是 {@code 屏幕逻辑高 × phoneSize}，所以 GUI Scale 越大 → 逻辑屏幕越小 →
 * 同一份字体（固定 GUI 单位）占手机的比例越大，也就是玩家感知的「字变大了」。
 * <p>
 * 模型屏必须复刻这个行为：否则玩家调 GUI Scale 只能改变贴图像素、改不了版面大小，
 * 等于把一个缩放旋钮弄丢了。
 * 于是画布逻辑高取 {@code 窗口 GUI 逻辑高 × CANVAS_HEIGHT_RATIO}；
 * 画布逻辑宽由<b>屏幕面矩形的宽高比</b>导出（矩形是物理形状，不能跟着 GUI 走），
 * 这样屏幕面在任何 GUI Scale 下都不变形。
 * <p>
 * 附带的良性副作用：{@code 画布逻辑高 × GUI Scale = 常数}，
 * 即离屏靶里那块手机区域始终是同样多的像素 —— GUI Scale 只影响"字相对屏幕的大小"，
 * 与原版悬浮窗（物理尺寸固定、字相对变大）行为一致。
 */
public final class PhoneScreenTarget {

    /**
     * 画布逻辑高 = 窗口 GUI 逻辑高 × 该系数。
     * <p>
     * 0.87 来自验收实测：当时的设置是 {@code guiScale=4} / 帧缓冲高 1009px（GUI 逻辑高 252），
     * 乘出来约 219 逻辑单位，与玩家手工对齐好的观感一致。
     */
    private static final float CANVAS_HEIGHT_RATIO = 0.87F;
    /** 画布逻辑高下限（极端设置下的兜底，保证状态栏/水印栏仍放得下） */
    private static final int MIN_CANVAS_H = 120;

    // ==================== 屏幕在手机正面上的位置（模型单位 0~16） ====================
    //
    // 这四个数直接对着 phone_3d.json / Blockbench 读，改位置就是改它们：
    //   整块右移 n → SCREEN_LEFT 和 SCREEN_RIGHT 同时 +n
    //   整块上移 n → SCREEN_BOTTOM 和 SCREEN_TOP 同时 +n
    //   变窄/变宽 → 只动 SCREEN_LEFT 或 SCREEN_RIGHT
    //   变矮/变高 → 只动 SCREEN_BOTTOM 或 SCREEN_TOP
    // （1 个模型单位 = 1/16 模型空间；手机整体还乘了 PHONE_SCALE=3，所以屏幕上看起来是 3/16 格）

    /** 屏幕左边界 */
    private static final float SCREEN_LEFT = 6.8F;
    /** 屏幕右边界 */
    private static final float SCREEN_RIGHT = 9.2F;
    /** 屏幕下边界 */
    private static final float SCREEN_BOTTOM = 5.3F;
    /** 屏幕上边界 */
    private static final float SCREEN_TOP = 9.7F;
    /** 贴合面 z（机身正面 = 8.25） */
    private static final float SCREEN_DEPTH = 8.25F;
    /** 朝玩家方向外推的微量（模型单位），只为防 z-fighting */
    private static final float SCREEN_DEPTH_PUSH = 0.01F;

    // ⚠ 下面是从上面换算出来的**原始 0~1 模型空间**坐标，不要再减 0.5。
    // 原因：renderScreen 拿到的 PoseStack 里已经含了 ItemRenderer 的那次 translate(-0.5, -0.5, -0.5)
    //（renderPhoneBody 逐字照抄了它）。顶点若再按"已居中的空间"给，就会把那 0.5 用两次，
    // 整块屏幕沿手机三条轴各平移半个模型单位。
    private static final float SCREEN_X0 = SCREEN_LEFT / 16F;
    private static final float SCREEN_X1 = SCREEN_RIGHT / 16F;
    private static final float SCREEN_Y0 = SCREEN_BOTTOM / 16F;
    private static final float SCREEN_Y1 = SCREEN_TOP / 16F;
    private static final float SCREEN_Z = (SCREEN_DEPTH + SCREEN_DEPTH_PUSH) / 16F;

    /**
     * 屏幕面宽高比（由矩形导出）。
     * 画布宽度必须与它一致，否则贴上去会横向/纵向拉伸 —— 改 {@link #SCREEN_LEFT} 等边界后无需手工同步。
     */
    private static final float SCREEN_ASPECT =
            (SCREEN_RIGHT - SCREEN_LEFT) / (SCREEN_TOP - SCREEN_BOTTOM);

    // ==================== 期 1a 可调开关（人工验收用） ====================

    /** 画面左右镜像时置 true */
    private static final boolean FLIP_U = false;
    /** 画面上下颠倒时置 true */
    private static final boolean FLIP_V = false;
    /** 屏幕整块不可见（被背面剔除吃掉）时置 true —— 反转四边形绕序 */
    private static final boolean FLIP_WINDING = false;

    /**
     * 双面绘制：正反两个绕序各排一遍顶点。
     * <p>
     * 原版 {@code RenderType.text} 默认开启背面剔除，而我们这张四边形是手写顶点，
     * 绕序一旦与相机的正/背面约定不符就会整块"看不见"。双面绘制只多 4 个顶点，
     * 却能把「绕序」这个变量彻底消掉（透明像素叠加两次仍是透明，不会变脏）。
     */
    private static final boolean DOUBLE_SIDED = true;

    /**
     * 空转刷新上限（毫秒）。手机 UI 绝大多数时间只有状态栏时钟在变，
     * 真正的重绘由 {@link #markDirty()} 驱动；这里是「最坏情况保证」，
     * 让网络推送/异步状态不至于一直不刷新。
     */
    private static final long MAX_IDLE_MS = 100L;

    private static TextureTarget fbo;
    private static ResourceLocation location;
    private static RenderType renderType;
    private static int usedGuiScale = 1;
    private static int usedPixelHeight = -1;
    /** 当前这轮离屏靶使用的画布逻辑尺寸（GUI 单位），随窗口 GUI 逻辑高变化 */
    private static int usedCanvasW = 1;
    private static int usedCanvasH = 1;
    private static boolean unavailable = false;

    private static boolean dirty = true;
    private static long lastRedrawAt = 0L;

    /** 诊断日志总开关：默认关。排查模型屏问题时置 true 即可在 latest.log 看到定位信息。 */
    private static final boolean DIAG = false;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static String lastDiag = "";
    private static long lastDiagAt = 0L;

    private PhoneScreenTarget() {
    }

    /** 标记「下一帧必须重绘」 */
    public static void markDirty() {
        dirty = true;
    }

    // ==================== 每帧出图 ====================

    /**
     * 在 {@code RenderFrameEvent.Pre} 调用（GL 上下文有效、主靶还绑着、世界尚未渲染），
     * 保证本帧后面画到模型上的纹理已是最新。
     */
    public static void update(float partialTick) {
        if (unavailable) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!PhoneOverlay.hasModelScreen()) {
            // 不贴模型（手机没掏出 / 已切到 O 的悬浮窗 / 第三人称）：下次贴上一定重绘
            dirty = true;
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        try {
            if (!ensureTarget(mc)) {
                return;
            }
        } catch (Throwable t) {
            // 离屏靶建不起来（驱动/GL 异常等）：直接永久关闭该功能，绝不影响正常游戏
            unavailable = true;
            LOGGER.warn("[HuhuTalk/ModelScreen] 离屏靶创建失败，模型屏已停用", t);
            return;
        }

        diag("update: fbo=" + fbo.width + "x" + fbo.height + " gs=" + usedGuiScale
                + " canvas=" + usedCanvasW + "x" + usedCanvasH
                + " visible=" + PhoneOverlay.isVisible()
                + " active=" + ProPhoneHandRenderer.isActive());

        long now = System.currentTimeMillis();
        if (!dirty && !PhoneOverlay.hasActiveAnimation() && now - lastRedrawAt < MAX_IDLE_MS) {
            return;
        }
        dirty = false;
        lastRedrawAt = now;

        TextureTarget target = fbo;
        target.bindWrite(true);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true); // clear 结尾会解绑，需重新绑定

        RenderSystem.disableScissor();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        Lighting.setupForFlatItems();

        // 与缩放无关的正交：1 GUI 单位 = usedGuiScale 像素
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0F, usedCanvasW, (float) usedPixelHeight / usedGuiScale, 0.0F, 1000.0F, 21000.0F),
                VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.setTranslation(0.0F, 0.0F, -11000.0F);
        RenderSystem.applyModelViewMatrix();

        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        PhoneOverlay.renderExternal(graphics, partialTick, usedCanvasW, usedCanvasH);
        graphics.flush();

        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableScissor();
        mc.getMainRenderTarget().bindWrite(true);
    }

    /** 建/重建离屏靶；只做一次纹理注册 */
    private static boolean ensureTarget(Minecraft mc) {
        int guiScale = Math.max(1, (int) mc.getWindow().getGuiScale());
        int guiScaledHeight = mc.getWindow().getGuiScaledHeight();
        int pixelHeight = mc.getWindow().getHeight();
        if (guiScaledHeight <= 0 || pixelHeight <= 0) {
            return false;
        }

        // 画布逻辑高跟随窗口 GUI 逻辑高（复刻原版悬浮窗的 GUI Scale 耦合）。
        // 上限就是 guiScaledHeight：离屏靶像素高必须等于窗口帧缓冲高（scissor 正确性的前提），
        // 画布作为它的子矩形，绝不能超出。
        int canvasH = Math.min(guiScaledHeight,
                Math.max(MIN_CANVAS_H, Math.round(guiScaledHeight * CANVAS_HEIGHT_RATIO)));
        canvasH = Math.max(1, canvasH);
        int canvasW = Math.max(1, Math.round(canvasH * SCREEN_ASPECT));
        int pixelWidth = canvasW * guiScale;
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            return false;
        }

        if (fbo == null || fbo.width != pixelWidth || fbo.height != pixelHeight) {
            if (fbo != null) {
                fbo.destroyBuffers();
            }
            fbo = new TextureTarget(pixelWidth, pixelHeight, true, Minecraft.ON_OSX);
            fbo.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            usedGuiScale = guiScale;
            usedPixelHeight = pixelHeight;
        }
        usedCanvasW = canvasW;
        usedCanvasH = canvasH;

        if (location == null) {
            // 把离屏靶的颜色纹理包成 AbstractTexture（覆写 getId 指向它），注册进 TextureManager，
            // 就能用原版 RenderType.text(loc) 采样它 —— 与 MapRenderer 的动态地图纹理同一套路。
            ResourceLocation path = ResourceLocation.fromNamespaceAndPath("huhutalk", "phone_screen_dynamic");
            mc.getTextureManager().register(path, new FboTexture());
            location = path;
            renderType = RenderType.text(path);
        }
        return true;
    }

    // ==================== 贴到模型屏幕面 ====================

    /**
     * 在手机模型本体的局部空间里画屏幕四边形。
     * <p>
     * 调用者必须已经把 PoseStack 摆到与 {@code ProPhoneHandRenderer.renderPhoneBody} 里
     * {@code renderModelLists} 所处的同一坐标系（即**已含** {@code ItemRenderer} 那次
     * {@code translate(-0.5,-0.5,-0.5)}），因此本方法给出的顶点坐标是**原始 0~1 模型空间**，不再居中。
     * <p>
     * <b>深度顺序</b>：调用方必须先 {@code endBatch()} 把机身刷掉，否则
     * {@code MultiBufferSource.endBatch()} 会先刷共享缓冲（本四边形）再刷固定缓冲（机身 solid），
     * 结果机身盖住屏幕。
     */
    public static void renderScreen(PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // 手机 UI 没打开时不画屏幕（模型自带的屏幕贴图照常显示），避免残留上一帧画面；
        // 已切到 O 的悬浮窗时同样不画 —— 两种屏幕互斥，贴模型这条路整体让位
        if (!PhoneOverlay.hasModelScreen()) {
            return;
        }
        if (renderType == null || fbo == null || usedPixelHeight <= 0) {
            return;
        }
        // 画布只占离屏靶左上角一块，换算成纹理 V（GL 的 V=0 在下、=1 在上）
        float vBottom = 1.0F - (usedCanvasH * (float) usedGuiScale) / (float) usedPixelHeight;
        float u0 = FLIP_U ? 1.0F : 0.0F;
        float u1 = FLIP_U ? 0.0F : 1.0F;
        float vTop = FLIP_V ? vBottom : 1.0F;
        float vBot = FLIP_V ? 1.0F : vBottom;

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(renderType);
        // 屏幕内容不受世界光照影响
        int light = LightTexture.FULL_BRIGHT;
        emitQuad(consumer, matrix, light, u0, u1, vTop, vBot, !FLIP_WINDING);
        if (DOUBLE_SIDED) {
            // 另一面再来一遍，彻底免疫背面剔除
            emitQuad(consumer, matrix, light, u0, u1, vTop, vBot, FLIP_WINDING);
        }

        // ⚠ 立即刷出：这 4/8 个顶点必须就在「第一人称手持」这一步、以此刻的**世界透视投影**画出去。
        // 若留在共享缓冲里等后续 endBatch，一旦刷新时机落到 GUI 段（那里投影是正交的），
        // 屏幕就会脱离手机、钉死在屏幕某个角上。
        if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch(renderType);
        }

        diagPlacement(poseStack);
    }

    /** 排入一组四边形顶点（{@code frontCcw=true} = 按「从 +Z 看逆时针」的绕序） */
    private static void emitQuad(VertexConsumer consumer, Matrix4f matrix, int light,
                                 float u0, float u1, float vTop, float vBot, boolean frontCcw) {
        if (frontCcw) {
            consumer.addVertex(matrix, SCREEN_X0, SCREEN_Y0, SCREEN_Z).setColor(-1).setUv(u0, vBot).setLight(light);
            consumer.addVertex(matrix, SCREEN_X1, SCREEN_Y0, SCREEN_Z).setColor(-1).setUv(u1, vBot).setLight(light);
            consumer.addVertex(matrix, SCREEN_X1, SCREEN_Y1, SCREEN_Z).setColor(-1).setUv(u1, vTop).setLight(light);
            consumer.addVertex(matrix, SCREEN_X0, SCREEN_Y1, SCREEN_Z).setColor(-1).setUv(u0, vTop).setLight(light);
        } else {
            consumer.addVertex(matrix, SCREEN_X0, SCREEN_Y1, SCREEN_Z).setColor(-1).setUv(u0, vTop).setLight(light);
            consumer.addVertex(matrix, SCREEN_X1, SCREEN_Y1, SCREEN_Z).setColor(-1).setUv(u1, vTop).setLight(light);
            consumer.addVertex(matrix, SCREEN_X1, SCREEN_Y0, SCREEN_Z).setColor(-1).setUv(u1, vBot).setLight(light);
            consumer.addVertex(matrix, SCREEN_X0, SCREEN_Y0, SCREEN_Z).setColor(-1).setUv(u0, vBot).setLight(light);
        }
    }

    /**
     * 诊断探针：把屏幕中心用「当前投影 × 当前 pose」投到 NDC。
     * <p>
     * 这一行日志能一眼区分两类失败：
     * <ul>
     *   <li>{@code proj33≈0}（透视投影）且 NDC 落在屏幕中下 → 位置本身是对的；</li>
     *   <li>{@code proj33≈1}（正交投影）或 NDC 贴到某个角 → 说明绘制时机/矩阵不对，
     *       那种情况下屏幕会**钉在屏幕固定位置**而不跟随视角。</li>
     * </ul>
     */
    private static void diagPlacement(PoseStack poseStack) {
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Matrix4f mvp = new Matrix4f(projection).mul(poseStack.last().pose());
        Vector4f centre = mvp.transform(new Vector4f(0.0F, 0.0F, SCREEN_Z, 1.0F));
        float ndcX = centre.w == 0.0F ? Float.NaN : centre.x / centre.w;
        float ndcY = centre.w == 0.0F ? Float.NaN : centre.y / centre.w;
        diag(String.format(Locale.ROOT,
                "screen quad ndc=(%.3f,%.3f) proj33=%.3f(0=透视/1=正交) 预计屏幕像素≈(%.0f,%.0f)",
                ndcX, ndcY, projection.m33(),
                (ndcX + 1.0F) * 0.5F * Minecraft.getInstance().getWindow().getWidth(),
                (1.0F - ndcY) * 0.5F * Minecraft.getInstance().getWindow().getHeight()));
    }

    /**
     * 诊断日志。默认整段关闭（{@link #DIAG}）。
     * <p>
     * 注意只按<b>时间</b>节流、不按内容：NDC 每帧都在变，按内容判重等于没节流
     * （上一版就是这样把日志刷到近万行）。
     */
    private static void diag(String message) {
        if (!DIAG) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDiagAt < 2000L) {
            return;
        }
        lastDiagAt = now;
        lastDiag = message;
        LOGGER.info("[HuhuTalk/ModelScreen] {}", message);
    }

    /**
     * 把离屏靶的颜色纹理当普通纹理用。
     * <p>
     * 只覆写 {@link #getId()}，不动父类 {@code id} 字段（保持 -1），
     * 于是父类的 {@code close()} 不会误删离屏靶的 GL 纹理。
     * {@link #setFilter} 覆写成空实现：{@code RenderType.text} 会请求 nearest 过滤，
     * 而离屏靶的纹理参数由它自己管，不该被 RenderType 改写。
     */
    private static final class FboTexture extends AbstractTexture {
        @Override
        public int getId() {
            return fbo != null ? fbo.getColorTextureId() : -1;
        }

        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            // 故意留空，见类注释
        }

        @Override
        public void load(ResourceManager resourceManager) {
            // 纹理内容由离屏帧缓冲直接提供，不从资源包加载
        }
    }
}
