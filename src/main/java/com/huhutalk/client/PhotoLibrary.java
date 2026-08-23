package com.huhutalk.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 狐狐手机的照片图库 + 临时截图（客户端）。
 * <p>
 * - 图库照片持久化到 {@code gameDir/huhutalk/photos/*.png}。
 * - 相机「确认但不保存」的临时照片以 PNG 字节暂存内存（键 = captured id），不落盘。
 * - 为照片/临时图注册 {@link DynamicTexture} 用于 GUI 渲染（缩略图、气泡）。
 * - 发送时把选中的 PNG 写入共享 {@code gameDir/huhutalk/vision_tmp/<token>.png}，
 *   包内只传 token 给服务端读图识图（单机下客户端写、集成服务端读共享）。
 */
public final class PhotoLibrary {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("huhutalk.photo_library");
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final Map<String, int[]> DIMS = new HashMap<>();
    /** 临时（未保存）照片：captured id -> PNG 字节 */
    private static final Map<String, byte[]> CAPTURED = new HashMap<>();

    private PhotoLibrary() {
    }

    // ==================== 目录 ====================

    public static Path photoDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("huhutalk").resolve("photos");
    }

    /** 识图临时目录（客户端写入，单机集成服务端从同一路径读取） */
    public static Path visionTmpDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("huhutalk").resolve("vision_tmp");
    }

    /**
     * 截取主渲染目标并转为 PNG 字节（相机"拍照"用）。
     * 必须在渲染线程调用；截图会带上 HUD / 手机 overlay（demo 阶段可接受）。
     */
    public static byte[] captureMainTargetPng() {
        NativeImage img = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        try {
            Path tmp = Files.createTempFile("huhutalk_cam", ".png");
            img.writeToFile(tmp);
            byte[] bytes = Files.readAllBytes(tmp);
            Files.deleteIfExists(tmp);
            return bytes;
        } catch (IOException e) {
            return null;
        } finally {
            img.close();
        }
    }

    // ==================== 图库持久化 ====================

    /** 保存一张图到图库，返回文件名；失败返回 null */
    public static String saveToGallery(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) return null;
        try {
            Files.createDirectories(photoDir());
            String name = "photo_" + System.currentTimeMillis() + ".png";
            Files.write(photoDir().resolve(name), pngBytes);
            return name;
        } catch (IOException e) {
            return null;
        }
    }

    /** 列出图库中所有 png 文件名（按名字排序） */
    public static List<String> listGallery() {
        List<String> out = new ArrayList<>();
        try {
            if (Files.isDirectory(photoDir())) {
                try (Stream<Path> s = Files.list(photoDir())) {
                    s.filter(p -> p.getFileName().toString().endsWith(".png"))
                            .forEach(p -> out.add(p.getFileName().toString()));
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(String::compareTo);
        return out;
    }

    /** 删除图库某张照片（含缓存纹理） */
    public static void deleteGallery(String fileName) {
        release(fileName);
        try {
            Files.deleteIfExists(photoDir().resolve(sanitize(fileName)));
        } catch (IOException ignored) {
        }
    }

    // ==================== 临时截图 ====================

    /** 注册一张临时（未保存）照片：存字节 + 注册纹理，返回其 id */
    public static String registerCaptured(byte[] pngBytes) {
        String id = UUID.randomUUID().toString();
        CAPTURED.put(id, pngBytes);
        registerTexture(id, pngBytes);
        return id;
    }

    /** 用固定 key 注册一张临时照片（如相机取景框 __camshot__），并返回该 key */
    public static String registerCapturedBytes(String key, byte[] pngBytes) {
        if (key == null || pngBytes == null) return null;
        CAPTURED.put(key, pngBytes);
        registerTexture(key, pngBytes);
        return key;
    }

    // ==================== 读取 / 纹理 ====================

    /** 读某张图的 PNG 字节（key 可为图库文件名或 captured id）；失败返回 null */
    public static byte[] readBytes(String key) {
        if (key == null) return null;
        byte[] cap = CAPTURED.get(key);
        if (cap != null) return cap;
        Path p = photoDir().resolve(sanitize(key));
        try {
            if (!Files.exists(p)) {
                LOGGER.error("[HuhuTalk] 读图失败：文件不存在 key={}, path={}", key, p);
                return null;
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            LOGGER.error("[HuhuTalk] 读图失败 key={}, path={}", key, p, e);
            return null;
        }
    }

    /** 获取某张图的纹理（自动从文件/缓存加载并注册） */
    public static ResourceLocation getOrLoadTexture(String key) {
        ResourceLocation cached = TEXTURES.get(key);
        if (cached != null) return cached;
        byte[] bytes = readBytes(key);
        if (bytes == null) return null;
        return registerTexture(key, bytes);
    }

    public static int[] getTextureSize(String key) {
        int[] d = DIMS.get(key);
        return d != null ? d : new int[]{24, 24};
    }

    private static ResourceLocation registerTexture(String key, byte[] pngBytes) {
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(pngBytes));
            DynamicTexture tex = new DynamicTexture(img);
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("huhutalk", "photo/" + sanitize(key));
            Minecraft.getInstance().getTextureManager().register(rl, tex);
            TEXTURES.put(key, rl);
            DIMS.put(key, new int[]{img.getWidth(), img.getHeight()});
            return rl;
        } catch (IOException e) {
            return null;
        }
    }

    private static void release(String key) {
        ResourceLocation old = TEXTURES.remove(key);
        if (old != null) {
            RenderSystem.recordRenderCall(() -> Minecraft.getInstance().getTextureManager().release(old));
        }
        DIMS.remove(key);
    }

    // ==================== 发送临时图 ====================

    /** 把要发送的 PNG 写入共享 vision_tmp 目录，返回 token（文件名）；失败返回 null */
    public static String writeVisionTmp(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) return null;
        try {
            Files.createDirectories(visionTmpDir());
            String token = UUID.randomUUID().toString() + ".png";
            Files.write(visionTmpDir().resolve(token), pngBytes);
            return token;
        } catch (IOException e) {
            LOGGER.error("[HuhuTalk] 写 vision_tmp 失败 dir={}", visionTmpDir(), e);
            return null;
        }
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
    }
}
