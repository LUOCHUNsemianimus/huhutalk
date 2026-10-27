package com.huhutalk.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.core.archive.ArchiveAccess;
import com.huhutalk.core.archive.ArchiveAssetRequest;
import com.huhutalk.core.archive.ArchiveAssetStatus;
import com.huhutalk.core.archive.ArchiveAssetResponse;
import com.huhutalk.core.archive.ArchiveAssetStore;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveService;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端 → 服务端：按需读取当前档案会话引用的一张 PNG。
 *
 * <p>没有 ownerUuid、没有磁盘路径；身份只取当前连接玩家。服务端先做归属/世界/
 * 速率校验，再证明 hash 被该玩家会话引用，最后才读取并再次校验资产。</p>
 */
public record C2SRequestArchiveAssetPacket(
        String requestId,
        String maidStableId,
        String expectedServerWorldId,
        String assetHash
) implements CustomPacketPayload {

    public static final Type<C2SRequestArchiveAssetPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "request_archive_asset"));

    public static final StreamCodec<FriendlyByteBuf, C2SRequestArchiveAssetPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_REQUEST_ID_CHARS),
                    C2SRequestArchiveAssetPacket::requestId,
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_STABLE_ID_CHARS),
                    C2SRequestArchiveAssetPacket::maidStableId,
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_WORLD_ID_CHARS),
                    C2SRequestArchiveAssetPacket::expectedServerWorldId,
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_HASH_CHARS),
                    C2SRequestArchiveAssetPacket::assetHash,
                    C2SRequestArchiveAssetPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public ArchiveAssetRequest toRequest() {
        return new ArchiveAssetRequest(requestId, maidStableId, expectedServerWorldId, assetHash);
    }

    /** 每玩家最近一次被接受的图片读取 tick；仅在服务端线程访问。 */
    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();
    private static final long MIN_TICKS_BETWEEN_REQUESTS = 5L;

    public static void clearRateLimits() {
        LAST_REQUEST_TICK.clear();
    }

    public static void handle(C2SRequestArchiveAssetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            ArchiveAssetRequest request;
            try {
                request = packet.toRequest();
            } catch (ArchiveException exception) {
                // 请求本身不安全（ID/hash 非法）时不回显、不读盘。
                HuhuTalk.LOGGER.warn("[HuhuTalk] rejected archive asset request from {}: {}",
                        player.getUUID(), exception.getMessage());
                return;
            }

            long now = server.getTickCount();
            if (!tryAcquire(player.getUUID(), now)) {
                reply(context, error(request, ArchiveAssetStatus.RATE_LIMITED, null));
                return;
            }

            // 归属先于任何归档/资产读取；已加载但归属不符绝不回退旧联系人。
            if (!ArchiveAccess.mayRead(server, player, request.maidStableId())) {
                reply(context, error(request, ArchiveAssetStatus.NO_PERMISSION, null));
                return;
            }

            String serverWorldId;
            try {
                serverWorldId = ArchiveService.originWorldId(server);
            } catch (RuntimeException exception) {
                HuhuTalk.LOGGER.error("[HuhuTalk] cannot resolve archive world id for asset read",
                        exception);
                reply(context, error(request, ArchiveAssetStatus.ERROR, null));
                return;
            }
            if (!request.expectedServerWorldId().equals(serverWorldId)) {
                reply(context, error(request, ArchiveAssetStatus.STALE_WORLD, serverWorldId));
                return;
            }

            try {
                // readAsset 再次证明 hash 被当前 owner+stableId 会话引用，并重新校验资产。
                byte[] data = ArchiveService.readAsset(server, player.getUUID(),
                        request.maidStableId(), request.assetHash());
                ArchiveAssetResponse response = new ArchiveAssetResponse(request.requestId(),
                        request.maidStableId(), serverWorldId, ArchiveAssetStatus.OK,
                        request.assetHash(), data);
                reply(context, response);
            } catch (ArchiveException exception) {
                HuhuTalk.LOGGER.warn("[HuhuTalk] archive asset read failed for {} / {}: {}",
                        player.getUUID(), request.maidStableId(), exception.getMessage());
                reply(context, error(request, toStatus(exception.code()), serverWorldId));
            } catch (RuntimeException exception) {
                HuhuTalk.LOGGER.error("[HuhuTalk] unexpected archive asset failure for {} / {}",
                        player.getUUID(), request.maidStableId(), exception);
                reply(context, error(request, ArchiveAssetStatus.ERROR, serverWorldId));
            }
        });
    }

    private static ArchiveAssetStatus toStatus(ArchiveException.Code code) {
        return switch (code) {
            case NOT_FOUND -> ArchiveAssetStatus.MISSING;
            case OWNERSHIP -> ArchiveAssetStatus.NO_PERMISSION;
            case INVALID_IDENTIFIER, VALIDATION -> ArchiveAssetStatus.INVALID_REQUEST;
            default -> ArchiveAssetStatus.ERROR;
        };
    }

    private static boolean tryAcquire(UUID playerId, long now) {
        Long previous = LAST_REQUEST_TICK.get(playerId);
        if (previous != null && now - previous < MIN_TICKS_BETWEEN_REQUESTS) {
            return false;
        }
        LAST_REQUEST_TICK.put(playerId, now);
        return true;
    }

    private static ArchiveAssetResponse error(ArchiveAssetRequest request,
                                              ArchiveAssetStatus status,
                                              String serverWorldId) {
        return ArchiveAssetResponse.error(request.requestId(), request.maidStableId(),
                serverWorldId == null ? request.expectedServerWorldId() : serverWorldId,
                status, request.assetHash());
    }

    private static void reply(IPayloadContext context, ArchiveAssetResponse response) {
        context.reply(replyPacket(response));
    }

    private static S2CArchiveAssetPacket replyPacket(ArchiveAssetResponse response) {
        return new S2CArchiveAssetPacket(response.requestId(), response.maidStableId(),
                response.serverWorldId(), response.status().name(), response.assetHash(),
                response.data());
    }

}
