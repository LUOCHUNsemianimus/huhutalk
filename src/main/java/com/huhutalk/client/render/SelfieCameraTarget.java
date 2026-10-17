package com.huhutalk.client.render;

import com.huhutalk.client.render.SelfieRenderAccess.GameRendererAccess;
import com.huhutalk.client.render.SelfieRenderAccess.LevelRendererAccess;
import com.huhutalk.client.render.SelfieRenderAccess.MinecraftAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 自拍取景离屏渲染核心（R1）。
 *
 * <h2>它到底做什么</h2>
 * 把<b>真实玩家本人 + 其身后的完整世界</b>用原版整条世界渲染管线（{@code GameRenderer.renderLevel}）
 * 实时渲染进一张独立的竖屏离屏 FBO，供手机屏显示和拍照。不是玩家头像、不是天空背景、
 * 不是主视角截图贴到手机屏上。
 *
 * <h2>实现要点</h2>
 * <ol>
 *   <li><b>复刻原版</b>：无光影时把 {@code Minecraft.mainRenderTarget} 临时换成 400x600 输出靶；
 *       光影下直接使用真实主靶，不替换其身份。随后调用公开的
 *       {@code GameRenderer.renderLevel(DeltaTracker)}，这样 {@code LevelRenderer} 内部所有
 *       {@code getMainRenderTarget()} 调用都自然落到该靶上。</li>
 *   <li><b>相机</b>：<b>只在 {@code mc.getCameraEntity() == mc.player} 时出图，pass 内完全不改相机实体</b> ——
 *       {@code Minecraft.setCameraEntity} 会调 {@code GameRenderer.checkEntityPostEffect}
 *       无条件 close 现有 postEffect 并重建，且新 PostChain 以「当时的主靶」为目标；
 *       在自拍 pass 内调用就等于以自拍 FBO 为目标、并反复重建用户的着色器。故自拍只限本地玩家视角。
 *       {@code CameraSelfieMixin} 把这帧的 {@code camera.setup} 强成第三人称正面（detached + 反向），
 *       本地玩家模型可见，且自带原版方块碰撞裁剪；{@code CalculateDetachedCameraDistanceEvent} 把距离收到约 1.35 格。</li>
 *   <li><b>投影</b>：{@code GameRendererSelfieMixin} 把 {@code getProjectionMatrix} 的输出换成
 *       竖屏 aspect；{@code ViewportEvent.ComputeFov} 固定 70°。光影裁切路径再按裁切区域占全靶的
 *       比例缩放 m00/m11，使裁切并放大后的构图与原 400x600 一致。</li>
 *   <li><b>干净照片</b>：{@code renderHand=false}、{@code renderBlockOutline=false}，
 *       自拍 pass 不画手 / 不画方块描边；HUD 本来就不在 {@code renderLevel} 内。</li>
 *   <li><b>Fabulous 隔离</b>：三重保险 —— 置空 {@code transparencyChain}/{@code entityEffect}/
 *       {@code entityTarget}、置空 5 个 Fabulous 临时靶、并让
 *       {@code Minecraft.useShaderTransparency()} 在 pass 内返回 false。
 *       详见 {@link #renderSelfie} 与 {@code MinecraftSelfieAccessor}。</li>
 *   <li><b>全量恢复</b>：{@code finally} <b>先恢复主靶</b>（任何可能加载后处理的步骤都必须在其之后），
 *       再恢复全部特效链与临时靶、手持/描边开关、命中结果、模型视图栈、
 *       投影矩阵与 vertexSorting（原版配对 {@code backupProjectionMatrix/restoreProjectionMatrix}）、
 *       真实 GL viewport 与常用 GL 状态，最后恢复主相机。相机实体全程未动，无需恢复。</li>
 * </ol>
 *
 * <h2>取景与出图</h2>
 * 预览与照片共用同一张 FBO、同一套构图（不会为了清晰度另渲一份不同视角的高分辨率图）。
 * 固定 2:3（{@link #IMAGE_WIDTH}×{@link #IMAGE_HEIGHT}）竖屏取景。
 * <ul>
 *   <li>{@link #renderPreview} 把 2:3 画面填满给定矩形（调用方需保证矩形也是 2:3）；
 *       {@link #renderPreviewFit} 在任意矩形内做等比 letterbox，绝不拉伸，
 *       下一阶段无论 GUI Scale 如何变化都能用；</li>
 *   <li>{@link #requestCapture} 用 {@code Screenshot.takeScreenshot(Fbo)} 得到<b>未镜像</b>的正常方向 PNG。</li>
 * </ul>
 * <b>FBO 纹理 V 轴</b>：GL 的 V=0 在下方，而 MC 的 GUI {@code blit} 以 v0 为可视顶边，
 * 所以离屏画面直接 blit 会上下颠倒；预览用「负的源区域尺寸」把 U/V 各翻一次
 * （同时修正 V 轴与自拍镜像 U），不改几何绕序，详见 {@link #renderPreview}。
 *
 * <h2>光影路径</h2>
 * Iris 报告正在使用光影包时，<b>直接借用真实 {@code mainRenderTarget}</b>（不替换其身份、不 resize）
 * 渲染自拍，渲染完立即用 {@code glBlitFramebuffer} 把 2:3 中央区域 LINEAR 缩放到固定 400x600 输出靶：
 * <ul>
 *   <li>同一帧稍后正常主场景会重新清屏并覆盖主靶，因此本 pass 只在
 *       {@code ClientModEvents.RenderFrame.Pre} → {@link #update} 这个前置时机调用安全；
 *       不能从 HUD 等主场景已绘制之后的位置调用，否则会破坏当帧主画面；</li>
 *   <li>投影在 {@link #buildProjection} 里按裁切比例缩放，保证裁切后的构图与 70° 竖屏 FOV 不变；</li>
 *   <li>无 Iris 或关闭光影时仍走原 400x600 直接渲染路径，预览/拍照/镜像/保存流程不变。</li>
 * </ul>
 * 此实现按 Iris 1.8.14-beta.1 + Sodium 0.8.13 + Complementary Reimagined r5.9.3 HIGH 的帧序与尺寸行为
 * 做过候选实测，但仍共享 Iris 的帧内历史/缓冲，尚非完全隔离；不声称对任意 Iris / 光影包全面兼容。
 *
 * <p><b>R1 不自动启用</b>：{@link #setActive(boolean)} 默认 false，不加任何页面/手持/输入逻辑。
 * 下一阶段在 {@code ClientModEvents.RenderFrame.Pre} 里先 {@link #update} 再
 * {@code PhoneScreenTarget.update}，并跳过自拍 pass 内 {@code AFTER_LEVEL} 的普通抓图消费。
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class SelfieCameraTarget {

    // ==================== 取景尺寸 ====================

    /** 离屏靶宽（像素）。竖屏 2:3 取景 */
    public static final int IMAGE_WIDTH = 400;
    /** 离屏靶高（像素） */
    public static final int IMAGE_HEIGHT = 600;
    /** 竖屏 aspect（= 2:3），投影矩阵与预览都用它 */
    private static final float ASPECT = (float) IMAGE_WIDTH / (float) IMAGE_HEIGHT;

    /** 自拍固定 FOV（度） */
    private static final double SELFIE_FOV = 70.0D;
    /** 相机到玩家的距离（格），原版碰撞裁剪会在此基础上继续收短 */
    private static final float SELFIE_CAMERA_DISTANCE = 1.35F;
    /** 30fps 节流：两次出图的最小间隔（毫秒）。有拍照请求时跳过节流 */
    private static final long MIN_FRAME_INTERVAL_MS = 33L;

    private static final Logger LOGGER = LogUtils.getLogger();

    // ==================== 状态 ====================

    /** 固定 400x600 输出靶：预览、拍照、镜像都只读它。 */
    private static TextureTarget fbo;
    /** 当前帧是否走「借用真实主靶 + 中心裁切」的光影路径。 */
    private static boolean shaderPathActive;
    /** 光影路径的中心裁切区域（像素，相对真实主靶）。仅 {@link #shaderPathActive} 时有效。 */
    private static int shaderCropX;
    private static int shaderCropY;
    private static int shaderCropWidth;
    private static int shaderCropHeight;
    /** 投影 m00/m11 的裁切缩放比，保证裁切放大后的构图不变。 */
    private static float shaderProjectionScaleX = 1.0F;
    private static float shaderProjectionScaleY = 1.0F;
    private static ResourceLocation textureLocation;
    private static boolean failed;
    private static boolean active;
    /** 是否至少成功出过一帧。没有它 {@link #isReady()} 会把「刚建好但还没画」当成可显示 */
    private static volatile boolean hasRendered;
    /** 重入守卫：自拍 pass 期间为 true，Mixin 全部据此判断（渲染线程写/读） */
    private static volatile boolean rendering;
    private static volatile Consumer<byte[]> pendingCapture;
    private static long lastRenderAt;
    /** 上次出图所属的 level，用于检测切世界；首次为 null 时只登记、不算切世界 */
    private static Object lastLevel;

    private SelfieCameraTarget() {
    }

    // ==================== 对外 API ====================

    /** 自拍 pass 是否正在执行。所有相关 Mixin 的唯一判据。 */
    public static boolean isRendering() {
        return rendering;
    }

    /**
     * 离屏靶是否「已有可显示的有效帧」。
     * <p>
     * 必须是 {@code fbo != null && hasRendered && !failed} —— 只建好 Fbo 但还没成功渲染过时，
     * 纹理里是未定义/上一世界的内容，不能当作就绪。
     */
    public static boolean isReady() {
        return fbo != null && hasRendered && !failed;
    }

    /**
     * 前摄取景是否已<b>失败/停用</b>（离屏靶创建或渲染抛异常时置位）。
     * <p>
     * 页面用它区分「准备中（还没出第一帧）」与「不可用（已失败）」两种状态，
     * 从而在前摄取景框里给出不同的短提示，而不是让主视角画面冒充自拍。只读，不驱动任何渲染。
     */
    public static boolean isFailed() {
        return failed;
    }

    /** 是否允许自拍取景 */
    public static boolean isActive() {
        return active;
    }

    /** 取景画面宽高比（固定 2:3）。下一阶段按它安排取景框矩形或改用 {@link #renderPreviewFit} */
    public static float aspect() {
        return ASPECT;
    }

    /** 离屏靶宽（像素），等于 {@link #IMAGE_WIDTH}；就绪前也可用 */
    public static int imageWidth() {
        return IMAGE_WIDTH;
    }

    /** 离屏靶高（像素），等于 {@link #IMAGE_HEIGHT} */
    public static int imageHeight() {
        return IMAGE_HEIGHT;
    }

    /**
     * 开关自拍取景。只在<b>状态真的发生变化</b>时做清理，避免下一阶段每帧无条件调用时反复重置。
     * <ul>
     *   <li>true → false：以 null 兑现未完成的请求，并作废当前画面（不再显示）；</li>
     *   <li>false → true：清掉失败状态与过期画面，允许重新出图（不显示上一段取景的残留）。</li>
     * </ul>
     * 离屏靶本身保留，避免反复建/销毁 Fbo。
     */
    public static void setActive(boolean value) {
        boolean changed = active != value;
        active = value;
        if (!changed) {
            return;
        }
        lastRenderAt = 0L;
        if (!value) {
            failPending();
            hasRendered = false;
        } else {
            failed = false;
            hasRendered = false;
        }
    }

    /** 已注册的离屏纹理位置；未建好时为 null。预览/贴图用 */
    public static ResourceLocation texture() {
        return textureLocation;
    }

    /**
     * 每帧渲染前调用（下一阶段挂在 {@code RenderFrame.Pre}）。
     * 内部做 30fps 节流；有未兑现的拍照请求时会立刻出一帧。
     * <p>
     * <b>只在 {@code mc.getCameraEntity() == mc.player}（本地玩家视角）时出图</b>，
     * 出图过程完全不修改相机实体（见 {@link #renderSelfie} 的说明）；条件不满足时以 null 兑现请求。
     */
    public static void update(DeltaTracker deltaTracker) {
        if (rendering) {
            return; // 重入守卫
        }
        if (!active || failed) {
            failPending();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null
                || mc.gameRenderer == null || mc.levelRenderer == null) {
            failPending();
            return;
        }
        if (mc.noRender) {
            // 资源重载 / 原版主动停渲期间不出图，避免和原版状态打架
            failPending();
            return;
        }
        if (mc.getCameraEntity() != mc.player) {
            // 自拍只限「本地玩家视角」，pass 内绝不调用 setCameraEntity：
            // setCameraEntity → GameRenderer.checkEntityPostEffect 会无条件 close 现有 postEffect 并重建，
            // 且新 PostChain 以「当时的主靶」为目标 —— 在自拍 pass 内就会以自拍 FBO 为目标。
            // 也不能放宽成 "!= null"：cameraEntity 为 null 时 renderLevel 自己会 setCameraEntity(player)，
            // 同样会踩到上面的重建路径。等下一帧 cameraEntity 已是本地玩家再出图即可。
            failPending();
            return;
        }
        Window window = mc.getWindow();
        if (window == null || window.getWidth() <= 0 || window.getHeight() <= 0) {
            failPending();
            return;
        }

        if (lastLevel == null) {
            // 首次进入世界：只是登记，绝不能把刚提交的第一次快门请求当"切世界"丢掉
            lastLevel = mc.level;
        } else if (mc.level != lastLevel) {
            lastLevel = mc.level;
            failPending();     // 旧世界的请求以 null 兑现，绝不悬着
            hasRendered = false; // 不显示上一世界的画面
            destroyTargets();    // 释放旧世界的离屏资源
            lastRenderAt = 0L;
        }

        final Consumer<byte[]> callback = pendingCapture;
        final boolean capturing = callback != null;
        long now = System.currentTimeMillis();
        if (!capturing && now - lastRenderAt < MIN_FRAME_INTERVAL_MS) {
            return;
        }
        lastRenderAt = now;

        try {
            ensureTarget(mc);
        } catch (Exception e) {
            failed = true;
            failPending();
            LOGGER.warn("[HuhuTalk/Selfie] 自拍离屏靶创建失败，自拍取景已停用", e);
            return;
        }

        byte[] png = null;
        try {
            renderSelfie(mc, deltaTracker);
            hasRendered = true;
            if (capturing) {
                png = capturePng();
            }
        } catch (Error error) {
            throw error; // 不吞致命 Error；finally 里的恢复仍会执行
        } catch (Exception e) {
            failed = true;
            LOGGER.warn("[HuhuTalk/Selfie] 自拍渲染失败，自拍取景已停用", e);
        }

        if (capturing) {
            pendingCapture = null;
            deliver(callback, png);
        }
    }

    /**
     * 请求在<b>下一有效帧</b>抓一张 PNG（未镜像、正常方向）并回调。
     * 已有未兑现请求时会被新请求覆盖（旧的以 null 回调，不堆积、不死循环）。
     * 失败 / 无世界 / 切世界 / 关闭取景时回调 {@code null}。
     */
    public static void requestCapture(Consumer<byte[]> callback) {
        Consumer<byte[]> old = pendingCapture;
        pendingCapture = callback;
        if (old != null && old != callback) {
            deliver(old, null);
        }
    }

    /**
     * 把离屏画面画到 GUI 指定矩形内。<b>要求该矩形的宽高比等于 {@link #aspect()}（2:3）</b>，
     * 否则会拉伸变形；矩形比例不确定时请用 {@link #renderPreviewFit}。
     * 只镜像 U（自拍预览镜像），同时把 V 轴修正为正常方向；不改动任何全局状态，文字请由调用方在本方法之后另画。
     *
     * <h2>为什么用「负的源区域尺寸」而不是翻转 PoseStack</h2>
     * FBO 纹理在 GL 里 V=0 在下方，而 GUI {@code blit} 以 {@code v0} 为可视顶边，所以直接画会上下颠倒；
     * 再叠加自拍预览的左右镜像，等价于把源区域沿 U/V 各翻一次。
     * {@code blit(rl,x,y,w,h,uOffset,vOffset,uWidth,vHeight,texW,texH)} 的 UV 是
     * {@code (uOffset..uOffset+uWidth)}，把 {@code uWidth/vHeight} 传成负数就能只翻 UV、不改几何绕序 ——
     * 于是完全不依赖 GUI 段此刻的背面剔除状态（{@code blit} 直连 {@code position_tex} 着色器，不经 RenderType）。
     */
    public static void renderPreview(GuiGraphics graphics, int x, int y, int w, int h) {
        if (graphics == null || fbo == null || textureLocation == null || !hasRendered || w <= 0 || h <= 0) {
            return;
        }
        int tw = fbo.width;
        int th = fbo.height;
        graphics.blit(textureLocation, x, y, w, h, (float) tw, (float) th, -tw, -th, tw, th);
    }

    /**
     * 在任意矩形内<b>等比 letterbox</b> 地画离屏画面（保持 2:3，不拉伸不变形），
     * 居中留黑边。GUI Scale 变化导致取景框比例改变时，用这个保证照片与预览构图一致。
     */
    public static void renderPreviewFit(GuiGraphics graphics, int x, int y, int w, int h) {
        if (graphics == null || w <= 0 || h <= 0) {
            return;
        }
        float rectAspect = (float) w / (float) h;
        int drawW;
        int drawH;
        if (rectAspect > ASPECT) {
            drawH = h;
            drawW = Math.max(1, Math.round(h * ASPECT));
        } else {
            drawW = w;
            drawH = Math.max(1, Math.round(w / ASPECT));
        }
        renderPreview(graphics, x + (w - drawW) / 2, y + (h - drawH) / 2, drawW, drawH);
    }

    /** 释放并清空所有状态（切世界/卸载时调用）。只销毁自己建的 Fbo 与自己的纹理注册。 */
    public static void release() {
        failPending();
        active = false;
        rendering = false;
        lastLevel = null;
        failed = false;
        hasRendered = false;
        lastRenderAt = 0L;
        destroyTargets();
    }

    /** 语义等价 {@link #release()}，供下一阶段"复位取景"调用 */
    public static void reset() {
        release();
    }

    // ==================== 事件钩子（只在自拍 pass 内生效） ====================

    /** 自拍固定 70° FOV，避免玩家设置的广角/长焦把构图拉变形 */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (rendering) {
            event.setFOV(SELFIE_FOV);
        }
    }

    /** 相机距玩家收到约 1.35 格（随后仍会经过原版碰撞裁剪） */
    @SubscribeEvent
    public static void onDetachedCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        if (rendering) {
            event.setDistance(SELFIE_CAMERA_DISTANCE);
        }
    }

    // ==================== 离屏靶 ====================

    /**
     * 建靶 + 注册纹理。输出靶固定 {@link #IMAGE_WIDTH}x{@link #IMAGE_HEIGHT}；
     * 光影生效时只按真实 {@code mainRenderTarget} 的当前宽高计算中心裁切比例，不额外分配全屏靶。
     * 窗口像素少于输出靶（宽 &lt; {@link #IMAGE_WIDTH} 或高 &lt; {@link #IMAGE_HEIGHT}）时
     * 不做裁切放大，回退既有 400x600 独立渲染路径以保证画质。
     */
    private static void ensureTarget(Minecraft mc) {
        if (fbo == null) {
            TextureTarget target = new TextureTarget(IMAGE_WIDTH, IMAGE_HEIGHT, true, Minecraft.ON_OSX);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            fbo = target;
        }
        if (textureLocation == null) {
            ResourceLocation path = ResourceLocation.fromNamespaceAndPath("huhutalk", "selfie_camera_dynamic");
            mc.getTextureManager().register(path, new FboTexture());
            textureLocation = path;
        }

        final RenderTarget main = mc.getMainRenderTarget();
        // 极小窗口保画质回退：宽或高低于照片像素数时不走裁切放大，仍用 400x600 独立渲染；
        // 这不是全场景性能保证。先做便宜的尺寸判断，再查询 Iris API。
        final boolean enoughPixels = main != null
                && main.width >= IMAGE_WIDTH && main.height >= IMAGE_HEIGHT;
        final boolean irisActive = enoughPixels && IrisShaderCompat.isShaderPackInUse();
        if (!irisActive) {
            shaderPathActive = false;
            shaderProjectionScaleX = 1.0F;
            shaderProjectionScaleY = 1.0F;
            return;
        }

        updateShaderCrop(main.width, main.height);
        shaderPathActive = true;
    }

    /**
     * 计算 2:3 中央裁切区域与投影缩放比。
     * <p>
     * 宽屏保留全高、横向取约 2:3；窄屏保留全宽、纵向取约 2:3。宽高取整后仍近似 2:3，
     * 由 {@code glBlitFramebuffer} 的 LINEAR 缩放补齐到 400x600。
     */
    private static void updateShaderCrop(int fullWidth, int fullHeight) {
        final int cropWidth;
        final int cropHeight;
        if ((long) fullWidth * IMAGE_HEIGHT >= (long) fullHeight * IMAGE_WIDTH) {
            // 宽屏 / 等比：cropH = fullH，cropW ≈ fullH * 2/3
            cropHeight = fullHeight;
            cropWidth = Math.min(fullWidth, Math.max(1, Math.round((float) fullHeight * ASPECT)));
        } else {
            // 窄屏：cropW = fullW，cropH ≈ fullW / (2/3)
            cropWidth = fullWidth;
            cropHeight = Math.min(fullHeight, Math.max(1, Math.round((float) fullWidth / ASPECT)));
        }
        shaderCropWidth = cropWidth;
        shaderCropHeight = cropHeight;
        shaderCropX = (fullWidth - cropWidth) / 2;
        shaderCropY = (fullHeight - cropHeight) / 2;
        shaderProjectionScaleX = (float) cropWidth / (float) fullWidth;
        shaderProjectionScaleY = (float) cropHeight / (float) fullHeight;
    }

    /** 销毁离屏 Fbo 与纹理注册（不动 active/failed 等逻辑状态）。GL 操作走渲染线程 */
    private static void destroyTargets() {
        TextureTarget target = fbo;
        fbo = null;
        shaderPathActive = false;
        shaderProjectionScaleX = 1.0F;
        shaderProjectionScaleY = 1.0F;
        ResourceLocation location = textureLocation;
        textureLocation = null;
        Minecraft mc = Minecraft.getInstance();
        RenderSystem.recordRenderCall(() -> {
            if (target != null) {
                target.destroyBuffers();
            }
            if (location != null && mc != null) {
                mc.getTextureManager().release(location);
            }
        });
    }

    // ==================== 自拍 pass ====================

    /**
     * 竖屏透视投影。由 {@code GameRendererSelfieMixin} 在自拍 pass 里替换原版窗口 aspect 的结果。
     * <p>
     * 光影裁切路径下，先按原 400x600 的 2:3 计算投影，再把 m00/m11 分别乘上裁切区域占全靶的比例。
     * 这样「全靶渲染 → 中央裁切 → 缩放到 400x600」后的构图与 FOV 与原直接渲染路径一致；
     * 普通路径不缩放。
     */
    public static Matrix4f buildProjection(float fovDegrees, float depthFar) {
        Matrix4f matrix = new Matrix4f().perspective(
                (float) Math.toRadians(fovDegrees), ASPECT, 0.05F, depthFar);
        if (shaderPathActive) {
            matrix.m00(matrix.m00() * shaderProjectionScaleX);
            matrix.m11(matrix.m11() * shaderProjectionScaleY);
        }
        return matrix;
    }

    private static void renderSelfie(Minecraft mc, DeltaTracker deltaTracker) {
        final GameRenderer gameRenderer = mc.gameRenderer;
        final LevelRenderer levelRenderer = mc.levelRenderer;
        final TextureTarget output = fbo;
        final RenderTarget oldMain = mc.getMainRenderTarget();
        // 光影生效时直接借用真实主靶（不替换其身份、不 resize），否则仍渲染到 400x600 输出靶
        final boolean cropped = shaderPathActive;
        final RenderTarget scene = cropped ? oldMain : output;

        final GameRendererAccess gr = (GameRendererAccess) (Object) gameRenderer;
        final MinecraftAccess mcAccess = (MinecraftAccess) (Object) mc;
        final LevelRendererAccess lr = (LevelRendererAccess) (Object) levelRenderer;

        // ---- 暂存原状态（finally 全量恢复） ----
        final HitResult oldHitResult = mc.hitResult;
        final Entity oldCrosshair = mc.crosshairPickEntity;
        final boolean oldRenderHand = gr.huhutalk$getRenderHand();
        final boolean oldBlockOutline = gr.huhutalk$getRenderBlockOutline();
        final PostChain oldTransparencyChain = lr.huhutalk$getTransparencyChain();
        final PostChain oldEntityEffect = lr.huhutalk$getEntityEffect();
        final RenderTarget oldEntityTarget = lr.huhutalk$getEntityTarget();
        final RenderTarget oldTranslucentTarget = lr.huhutalk$getTranslucentTarget();
        final RenderTarget oldItemEntityTarget = lr.huhutalk$getItemEntityTarget();
        final RenderTarget oldParticlesTarget = lr.huhutalk$getParticlesTarget();
        final RenderTarget oldWeatherTarget = lr.huhutalk$getWeatherTarget();
        final RenderTarget oldCloudsTarget = lr.huhutalk$getCloudsTarget();
        // 原版提供的配对：连带 VertexSorting 一起保存/恢复，无需猜字段名
        RenderSystem.backupProjectionMatrix();
        final Matrix4fStack modelView = RenderSystem.getModelViewStack();
        final int[] oldViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, oldViewport);
        final float restorePartialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        rendering = true;
        boolean pushedModelView = false;
        try {
            // Fabulous 三重隔离：链 / 描边链 / 5 个临时靶全部置空 + useShaderTransparency() 返回 false。
            // 这些 PostChain 在创建时就绑定了"当时的主渲染靶"，不隔离会把结果写回原主画面。
            lr.huhutalk$setTransparencyChain(null);
            lr.huhutalk$setEntityEffect(null);
            lr.huhutalk$setEntityTarget(null);
            lr.huhutalk$setTranslucentTarget(null);
            lr.huhutalk$setItemEntityTarget(null);
            lr.huhutalk$setParticlesTarget(null);
            lr.huhutalk$setWeatherTarget(null);
            lr.huhutalk$setCloudsTarget(null);

            // 光影路径直接借用真实主靶，不替换 Minecraft.mainRenderTarget 身份，也不改其尺寸；
            // 普通路径仍把主靶临时指向 400x600 输出靶。相机实体全程保持 mc.player 不动。
            if (!cropped) {
                mcAccess.huhutalk$setMainRenderTarget(output);
            }
            gameRenderer.setRenderHand(false);
            gr.huhutalk$setRenderBlockOutline(false);

            // 先清掉上一帧的颜色/深度（旧画面绝不能残留），再重新绑定并设置 viewport。
            // 光影路径使用主靶自己的现有清屏色（外部所有状态，不在此 setClearColor）；FogRenderer 随后设实际颜色。
            scene.bindWrite(true);
            scene.clear(Minecraft.ON_OSX);
            scene.bindWrite(true);
            RenderSystem.disableScissor();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            modelView.pushMatrix();
            pushedModelView = true;

            // 完整世界 pass：天空 / 地形 / 实体（含本地玩家模型）/ 方块实体 / 天气，且不含 HUD
            gameRenderer.renderLevel(deltaTracker);

            if (cropped) {
                // 完整世界渲染结束后，把真实主靶的 2:3 中央区域 LINEAR 缩放到 400x600 输出靶。
                // Iris 会重置 viewport，所以不能用「改 viewport 到 400x600」的方式裁切。
                blitCropToOutput(scene, output);
            }

            // 天空/雾可能留下 alpha=0 的有效颜色。截图会强制不透明，但 GUI 着色器会丢弃这些像素。
            // 在世界渲染结束后统一 alpha，使预览与成片一致；保留 RGB 和深度。只对输出靶做。
            makeColorOpaque(output);
        } finally {
            if (pushedModelView) {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
            rendering = false;

            // ① 先恢复主靶：任何可能触发 PostChain 加载/绑定的操作都必须发生在主靶恢复之后，
            //    否则新建的后处理会以自拍 FBO 为目标。本方法此后不再调用 setCameraEntity（见 update 守卫）。
            mcAccess.huhutalk$setMainRenderTarget(oldMain);
            oldMain.bindWrite(true);

            // ② 恢复 Fabulous 链与全部临时靶（纯字段写，不 process）
            lr.huhutalk$setTransparencyChain(oldTransparencyChain);
            lr.huhutalk$setEntityEffect(oldEntityEffect);
            lr.huhutalk$setEntityTarget(oldEntityTarget);
            lr.huhutalk$setTranslucentTarget(oldTranslucentTarget);
            lr.huhutalk$setItemEntityTarget(oldItemEntityTarget);
            lr.huhutalk$setParticlesTarget(oldParticlesTarget);
            lr.huhutalk$setWeatherTarget(oldWeatherTarget);
            lr.huhutalk$setCloudsTarget(oldCloudsTarget);

            // ③ 恢复 GameRenderer 开关
            gameRenderer.setRenderHand(oldRenderHand);
            gr.huhutalk$setRenderBlockOutline(oldBlockOutline);

            // ④ 恢复命中结果（renderLevel 内部 pick() 会改写；相机实体全程未动，无需恢复）
            mc.hitResult = oldHitResult;
            mc.crosshairPickEntity = oldCrosshair;

            // ⑤ 恢复投影矩阵与 vertexSorting（原版配对，连带 sorting 一起还原）
            RenderSystem.restoreProjectionMatrix();

            // ⑥ 恢复真实 GL viewport 与常用 GL 状态
            if (oldViewport[2] > 0 && oldViewport[3] > 0) {
                RenderSystem.viewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            }
            RenderSystem.disableScissor();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            Lighting.setupFor3DItems();

            // ⑦ 主相机恢复放最后（只写相机字段 + 派发事件，不加载 postEffect）：
            //    UI / 其他事件在同帧后面会立刻读相机，不能等到主 pass 再 setup。
            //    此时 rendering 已为 false，CameraSelfieMixin 不会再强制；实体用 mc.player（全程未改）。
            Camera camera = gameRenderer.getMainCamera();
            if (camera != null && mc.level != null) {
                var cameraType = mc.options.getCameraType();
                camera.setup(mc.level, mc.player,
                        !cameraType.isFirstPerson(), cameraType.isMirrored(), restorePartialTick);
            }
        }
    }

    /**
     * 把光影自拍（真实主靶）的 2:3 中央区域 LINEAR 缩放到固定 400x600 输出靶。
     * <p>
     * 只复制 {@code GL_COLOR_BUFFER_BIT}：深度/模板保留在主靶里，绝不从输出靶反向读，
     * 也不修改 Iris 内部管线。颜色附件默认就是 COLOR_ATTACHMENT0。
     * 绑定状态在 finally 还原；viewport 由随后的 {@link #makeColorOpaque} 绑定输出靶时设置。
     */
    private static void blitCropToOutput(RenderTarget source, TextureTarget destination) {
        final int oldReadBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int oldDrawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            RenderSystem.disableScissor();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
            GL30.glBlitFramebuffer(
                    shaderCropX, shaderCropY,
                    shaderCropX + shaderCropWidth, shaderCropY + shaderCropHeight,
                    0, 0, destination.width, destination.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadBinding);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDrawBinding);
        }
    }

    /** 只清 alpha 通道。仅设置靶的初始清屏色无效：世界渲染中的 FogRenderer 会再次覆盖清屏色。 */
    private static void makeColorOpaque(TextureTarget target) {
        final float[] oldClearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, oldClearColor);
        try {
            target.bindWrite(true);
            RenderSystem.disableScissor();
            RenderSystem.colorMask(false, false, false, true);
            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.clearColor(oldClearColor[0], oldClearColor[1], oldClearColor[2], oldClearColor[3]);
        }
    }

    /**
     * 从自拍靶读回一张未镜像、正常方向的 PNG（必须在离屏靶已解绑后调用）
     */
    private static byte[] capturePng() {
        if (fbo == null) {
            return null;
        }
        NativeImage image = null;
        Path tmp = null;
        try {
            image = Screenshot.takeScreenshot(fbo);
            tmp = Files.createTempFile("huhutalk_selfie", ".png");
            image.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } catch (Error error) {
            throw error;
        } catch (Exception e) {
            LOGGER.warn("[HuhuTalk/Selfie] 自拍抓图失败", e);
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ==================== 请求 / 失败处理 ====================

    /** 以 null 兑现未完成的请求（只兑现一次，绝不重试/死循环） */
    private static void failPending() {
        Consumer<byte[]> callback = pendingCapture;
        if (callback != null) {
            pendingCapture = null;
            deliver(callback, null);
        }
    }

    /** 回调消费方抛异常不应打断渲染帧；同样不吞致命 Error */
    private static void deliver(Consumer<byte[]> callback, byte[] value) {
        try {
            callback.accept(value);
        } catch (Error error) {
            throw error;
        } catch (Exception e) {
            LOGGER.warn("[HuhuTalk/Selfie] 自拍回调抛出异常", e);
        }
    }

    // ==================== 纹理包装 ====================

    /**
     * 把离屏靶的颜色纹理当普通纹理采样。
     * <p>
     * 只覆写 {@link #getId()}，不动父类 {@code id} 字段；{@link #setFilter} 留空，
     * 避免 {@code RenderType}/GUI 改写离屏靶自己的过滤参数。
     */
    private static final class FboTexture extends AbstractTexture {
        @Override
        public int getId() {
            TextureTarget target = fbo;
            return target != null ? target.getColorTextureId() : -1;
        }

        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            // 故意留空：过滤参数由离屏靶自管
        }

        @Override
        public void load(ResourceManager resourceManager) {
            // 内容由离屏帧缓冲提供，不从资源包加载
        }

        @Override
        public void close() {
            // 故意留空：绝不能删除离屏靶的颜色纹理（由 destroyTargets 负责销毁 Fbo）
        }
    }
}
