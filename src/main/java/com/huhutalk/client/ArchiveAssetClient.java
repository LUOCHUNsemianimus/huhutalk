package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.core.archive.ArchiveAssetRequest;
import com.huhutalk.core.archive.ArchiveAssetResponse;
import com.huhutalk.core.archive.ArchiveAssetSession;
import com.huhutalk.core.archive.ArchiveAssetStatus;
import com.huhutalk.core.archive.ArchiveAssetStore;
import com.huhutalk.network.C2SRequestArchiveAssetPacket;
import com.huhutalk.phone.PhoneOverlay;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;

/**
 * 档案图片查看的客户端控制器：单 pending、单字节数组、单 {@link DynamicTexture}。
 *
 * <p>响应先在客户端主线程通过 {@link ArchiveAssetSession} 校验 requestId/stableId/
 * 世界/hash，再验证 hash 与 PNG 预算，最后才交给 {@link NativeImage} 解码。任何失败
 * 只释放本次分配并给出可重试状态，不 crash、不自动无限重试。不写本机相册，也不
 * 持有全局增长 cache。</p>
 */
public final class ArchiveAssetClient {
    public enum State {
        IDLE,
        LOADING,
        READY,
        ERROR
    }

    private static final ArchiveAssetSession SESSION = new ArchiveAssetSession();
    private static State state = State.IDLE;
    private static ArchiveAssetStatus lastError;
    private static String requestedHash;
    private static ResourceLocation texture;
    private static int textureWidth;
    private static int textureHeight;
    private static int textureSeq;

    private ArchiveAssetClient() {
    }

    /** 打开一张图：必须已有当前档案连接的世界 ID，否则不发请求。 */
    public static void request(String stableId, String assetHash) {
        String world = ArchiveHistoryClient.serverWorldId();
        if (world == null || world.isEmpty()) {
            state = State.ERROR;
            lastError = ArchiveAssetStatus.STALE_WORLD;
            PhoneOverlay.requestRedraw();
            return;
        }
        requestedHash = assetHash;
        lastError = null;
        // 同一时刻只保留一张图：新请求先释放旧纹理，避免显示上一张的残留。
        releaseTexture();
        SESSION.open(stableId, world, assetHash).ifPresent(ArchiveAssetClient::send);
        state = State.LOADING;
        PhoneOverlay.requestRedraw();
    }

    /** 用户点击重试：只发一次，不进入自动循环。 */
    public static void retry() {
        if (requestedHash == null || requestedHash.isEmpty()) {
            return;
        }
        SESSION.retry(requestedHash).ifPresent(ArchiveAssetClient::send);
        if (SESSION.isLoading()) {
            state = State.LOADING;
            lastError = null;
        }
        PhoneOverlay.requestRedraw();
    }

    public static void close() {
        SESSION.close();
        releaseTexture();
        requestedHash = null;
        lastError = null;
        state = State.IDLE;
    }

    /** 登录/登出/换世界：作废 pending 并释放纹理。 */
    public static void onConnectionReset() {
        SESSION.onConnectionReset();
        releaseTexture();
        requestedHash = null;
        lastError = null;
        state = State.IDLE;
    }

    public static void onResponse(ArchiveAssetResponse response) {
        if (SESSION.accept(response) != ArchiveAssetSession.Apply.APPLIED) {
            // 迟到/错 request/错世界/错 hash：不解码、不建纹理。
            return;
        }
        if (!response.status().isSuccess()) {
            lastError = response.status();
            state = State.ERROR;
            PhoneOverlay.requestRedraw();
            return;
        }
        byte[] bytes = response.data();
        try {
            // 再次证明后端声明的 hash 与实际字节一致，并复用共享 PNG 预算校验。
            if (!response.assetHash().equals(ArchiveAssetStore.sha256Hex(bytes))) {
                throw new IllegalStateException("archive asset hash mismatch");
            }
            ArchiveAssetStore.validatePngBytes(bytes);
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            boolean handedOff = false;
            try {
                if (image.getWidth() <= 0 || image.getHeight() <= 0
                        || image.getWidth() > ArchiveAssetStore.MAX_IMAGE_SIDE
                        || image.getHeight() > ArchiveAssetStore.MAX_IMAGE_SIDE
                        || (long) image.getWidth() * image.getHeight()
                        > ArchiveAssetStore.MAX_IMAGE_PIXELS) {
                    throw new IllegalStateException("archive asset dimensions exceed limits");
                }
                handedOff = true;
                replaceTexture(image);
                textureWidth = image.getWidth();
                textureHeight = image.getHeight();
                lastError = null;
                state = State.READY;
            } catch (RuntimeException exception) {
                if (!handedOff) {
                    image.close();
                }
                throw exception;
            }
        } catch (Exception exception) {
            HuhuTalk.LOGGER.warn("[HuhuTalk] ignored undecodable archive asset", exception);
            releaseTexture();
            // 已接受的 OK 响应必须在会话里变为可重试的 ERROR，而不是停留在“成功但无图”。
            SESSION.markDecodeFailure(response);
            lastError = ArchiveAssetStatus.ERROR;
            state = State.ERROR;
        }
        PhoneOverlay.requestRedraw();
    }

    /**
     * 头部可解析但内容畸形（status/hash/PNG 校验失败）的回包：只有会话确认它精确匹配
     * 当前 pending 时才结束为可重试 ERROR；旧世界/旧请求/错 hash 一律忽略。
     */
    public static void onMalformedResponse(String requestId, String maidStableId,
                                           String serverWorldId, String assetHash) {
        if (SESSION.failPending(requestId, maidStableId, serverWorldId, assetHash)
                != ArchiveAssetSession.Apply.APPLIED) {
            return;
        }
        lastError = ArchiveAssetStatus.ERROR;
        state = State.ERROR;
        PhoneOverlay.requestRedraw();
    }

    public static State state() {
        return state;
    }

    public static ArchiveAssetStatus lastError() {
        return lastError;
    }

    public static boolean canRetry() {
        return state == State.ERROR && SESSION.canRetry();
    }

    public static boolean isLoading() {
        return state == State.LOADING;
    }

    public static ResourceLocation texture() {
        return state == State.READY ? texture : null;
    }

    public static int textureWidth() {
        return textureWidth;
    }

    public static int textureHeight() {
        return textureHeight;
    }

    private static void send(ArchiveAssetRequest request) {
        PacketDistributor.sendToServer(new C2SRequestArchiveAssetPacket(
                request.requestId(), request.maidStableId(), request.expectedServerWorldId(),
                request.assetHash()));
    }

    private static void replaceTexture(NativeImage image) {
        ResourceLocation old = texture;
        ResourceLocation next = ResourceLocation.fromNamespaceAndPath(
                "huhutalk", "archive_asset/" + (++textureSeq));
        DynamicTexture dynamic;
        try {
            // 构造成功即由 DynamicTexture 接管 NativeImage；构造前失败仍由调用方释放 image。
            dynamic = new DynamicTexture(image);
        } catch (RuntimeException exception) {
            image.close();
            throw exception;
        }
        boolean installed = false;
        try {
            Minecraft.getInstance().getTextureManager().register(next, dynamic);
            RenderSystem.recordRenderCall(() -> dynamic.setFilter(true, false));
            installed = true;
        } finally {
            // 注册或调度失败时本次 texture 尚未接管成功，明确释放，避免泄漏。
            if (!installed) {
                dynamic.close();
            }
        }
        texture = next;
        if (old != null) {
            // 成功接管后只按独立 ResourceLocation 释放旧纹理，不会误删新纹理。
            RenderSystem.recordRenderCall(
                    () -> Minecraft.getInstance().getTextureManager().release(old));
        }
    }

    private static void releaseTexture() {
        ResourceLocation old = texture;
        texture = null;
        textureWidth = 0;
        textureHeight = 0;
        if (old != null) {
            RenderSystem.recordRenderCall(
                    () -> Minecraft.getInstance().getTextureManager().release(old));
        }
    }
}
