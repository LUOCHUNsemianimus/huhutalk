package com.huhutalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.StableIdProvider;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 女仆头像管理器（客户端）。
 * <p>
 * 女仆是 3D 模型、无单一正面，故采用"离屏渲染一次头部/上半身 → 拍照存 PNG → 作为头像"。
 * 头像持久化为 .minecraft/huhutalk/avatars/&lt;stableId&gt;.png，后续直接从 PNG 加载纹理，
 * 避免每帧渲染 3D 模型。离线/死亡女仆无实体无法现拍，使用缓存；从未拍过的回退占位。
 */
public final class MaidAvatarManager {
    private static final int CAPTURE_SIZE = 96;                  // 离屏渲染分辨率
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    private static final Map<String, int[]> DIMS = new HashMap<>();
    private static final Map<String, Boolean> CAPTURING = new HashMap<>();

    private MaidAvatarManager() {
    }

    /** 头像存储目录：.minecraft/huhutalk/avatars/ */
    public static Path avatarDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("huhutalk").resolve("avatars");
    }

    private static Path avatarFile(String stableId) {
        return avatarDir().resolve(sanitize(stableId) + ".png");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
    }

    /** 是否有已缓存的头像文件 */
    public static boolean hasAvatar(String stableId) {
        return Files.exists(avatarFile(stableId));
    }

    /**
     * 获取某女仆头像的已注册纹理（ResourceLocation）；无缓存文件则返回 null。
     * 首次从 PNG 加载并注册到 TextureManager，之后复用。
     */
    public static ResourceLocation getOrLoadTexture(String stableId) {
        ResourceLocation cached = CACHE.get(stableId);
        if (cached != null) {
            return cached;
        }
        Path file = avatarFile(stableId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            NativeImage image = NativeImage.read(Files.newInputStream(file));
            DynamicTexture tex = new DynamicTexture(image);
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("huhutalk", "avatars/" + sanitize(stableId));
            Minecraft.getInstance().getTextureManager().register(rl, tex);
            CACHE.put(stableId, rl);
            DIMS.put(stableId, new int[]{image.getWidth(), image.getHeight()});
            return rl;
        } catch (IOException e) {
            return null;
        }
    }

    /** 头像纹理的像素尺寸（宽,高）；未加载返回 null */
    public static int[] getTextureSize(String stableId) {
        return DIMS.get(stableId);
    }

    /** 删除某女仆的头像文件与内存缓存（用于重新拍照） */
    public static void clear(String stableId) {
        ResourceLocation old = CACHE.remove(stableId);
        DIMS.remove(stableId);
        if (old != null) {
            Minecraft.getInstance().getTextureManager().release(old);
        }
        try {
            Files.deleteIfExists(avatarFile(stableId));
        } catch (IOException ignored) {
        }
    }

    /** 是否正在拍照（防止并发重复触发） */
    public static boolean isCapturing(String stableId) {
        return CAPTURING.getOrDefault(stableId, false);
    }

    /** 在客户端已加载实体中按 stableId 查找女仆（未找到返回 null） */
    public static EntityMaid findLoadedMaid(String stableId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof EntityMaid maid && maid instanceof StableIdProvider sp
                    && stableId.equals(sp.getStableId())) {
                return maid;
            }
        }
        return null;
    }

    /** 把 EntityMaid 适配成拍照所需接口 */
    private static EntityMaidLike toLike(EntityMaid maid) {
        if (!(maid instanceof StableIdProvider sp)) {
            return null;
        }
        String sid = sp.getStableId();
        if (sid == null || sid.isEmpty()) {
            return null;
        }
        return new EntityMaidLike() {
            public String getStableId() { return sid; }
            public float getBbHeight() { return maid.getBbHeight(); }
            public float getScale() { return maid.getScale(); }
            public float getYRot() { return maid.getYRot(); }
            public float getXRot() { return maid.getXRot(); }
            public float getYBodyRot() { return maid.yBodyRot; }
            public float getYHeadRot() { return maid.yHeadRotO; }
            public void setYRot(float y) { maid.setYRot(y); }
            public void setXRot(float x) { maid.setXRot(x); }
            public void setYBodyRot(float b) { maid.yBodyRot = b; }
            public void setYHeadRot(float h) { maid.setYHeadRot(h); }
            public LivingEntity asEntity() { return maid; }
        };
    }

    /**
     * 按 stableId 查找在场女仆并拍照。用于"更新头像"按钮。
     *
     * @return 0=未找到在场女仆；1=拍照成功；2=拍照失败
     */
    public static int captureIfLoaded(String stableId) {
        EntityMaid maid = findLoadedMaid(stableId);
        if (maid == null) {
            return 0;
        }
        clear(stableId); // 清旧缓存，重新生成
        EntityMaidLike like = toLike(maid);
        return like != null && capture(like) ? 1 : 2;
    }

    /**
     * 离屏渲染女仆头部/上半身，拍照存为 PNG。
     * 成功后会清掉内存中的旧纹理缓存，下次读取重新加载。
     *
     * @return 成功与否
     */
    public static boolean capture(EntityMaidLike maid) {
        String stableId = maid.getStableId();
        if (CAPTURING.getOrDefault(stableId, false)) {
            return false;
        }
        CAPTURING.put(stableId, true);
        int size = CAPTURE_SIZE;
        Minecraft mc = Minecraft.getInstance();
        TextureTarget fb = new TextureTarget(size, size, true, Minecraft.ON_OSX);
        float yaw = maid.getYRot();
        float bodyRot = maid.getYBodyRot();
        float headRot = maid.getYHeadRot();
        float xRot = maid.getXRot();
        boolean ok = false;
        try {
            // 归零旋转，正面朝前
            maid.setYBodyRot(180.0F);
            maid.setYRot(180.0F);
            maid.setXRot(0.0F);
            maid.setYHeadRot(180.0F);

            fb.bindWrite(true);
            fb.clear(Minecraft.ON_OSX);
            fb.bindWrite(true); // clear 结尾会解绑，需重新绑定

            // 投影：覆盖整个离屏目标（模拟 GUI 的正交投影）
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, 0.0F, size, 1000.0F, 3000.0F),
                    VertexSorting.DISTANCE_TO_ORIGIN);

            PoseStack pose = new PoseStack();
            pose.translate(size / 2.0, size / 2.0, 50.0);
            float f = size * 0.30F / maid.getScale(); // 让头部/上半身铺满
            pose.scale(f, f, -f);
            pose.translate(0.0F, -maid.getBbHeight() * 0.95F, 0.0F);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));

            Lighting.setupForEntityInInventory();
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            dispatcher.overrideCameraOrientation(new Quaternionf().rotateY((float) Math.PI));
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(maid.asEntity(), 0.0, 0.0, 0.0, 0.0F, 1.0F, pose,
                    mc.renderBuffers().bufferSource(), 15728880));
            mc.renderBuffers().bufferSource().endBatch();

            // 读取像素（fb 此时仍是当前读写帧缓冲）
            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
            RenderSystem.readPixels(0, 0, size, size, 6408 /*GL_RGBA*/, 5121 /*GL_UNSIGNED_BYTE*/, pixels);
            pixels.rewind();
            NativeImage img = new NativeImage(size, size, true);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int idx = (y * size + x) * 4;
                    int r = pixels.get(idx) & 0xFF;
                    int g = pixels.get(idx + 1) & 0xFF;
                    int b = pixels.get(idx + 2) & 0xFF;
                    int a = pixels.get(idx + 3) & 0xFF;
                    img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            img.flipY();
            Files.createDirectories(avatarDir());
            img.writeToFile(avatarFile(stableId));
            img.close();
            CACHE.remove(stableId);
            ok = true;
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            CAPTURING.put(stableId, false);
            // 恢复女仆旋转
            maid.setYBodyRot(bodyRot);
            maid.setYRot(yaw);
            maid.setXRot(xRot);
            maid.setYHeadRot(headRot);
            fb.destroyBuffers();
            Lighting.setupFor3DItems();
            mc.getEntityRenderDispatcher().setRenderShadow(true);
            // 回到主渲染目标
            mc.getMainRenderTarget().bindWrite(true);
            if (ok) {
                try {
                    Files.createDirectories(avatarDir());
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 女仆外观所需的最小接口，避免把 EntityMaid 直接耦合进来 */
    public interface EntityMaidLike {
        String getStableId();
        float getBbHeight();
        float getScale();
        float getYRot();
        float getXRot();
        float getYBodyRot();
        float getYHeadRot();
        void setYRot(float y);
        void setXRot(float x);
        void setYBodyRot(float b);
        void setYHeadRot(float h);
        LivingEntity asEntity();
    }
}
