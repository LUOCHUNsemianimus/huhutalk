package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveHistoryProtocol;
import com.huhutalk.core.archive.ArchiveHistoryRequest;
import com.huhutalk.core.archive.ArchiveHistoryResponse;
import com.huhutalk.core.archive.ArchiveHistoryStatus;
import com.huhutalk.core.archive.ArchiveService;
import com.huhutalk.core.archive.ArchiveSnapshot;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端 → 服务端：按需读取某位女仆的长期聊天档案。
 *
 * <p>身份只取 {@code context.player.getUUID()}，本包没有 ownerUuid。处理顺序：
 * 请求字段校验 → 每玩家 5 tick 速率限制 → 当前 serverWorldId 校验 → 归属校验 →
 * 最后才读取归档快照。非法请求或无权限都不会读取归档内容，也不会把磁盘路径、
 * 异常消息或堆栈发给客户端。</p>
 */
public record C2SRequestArchiveHistoryPacket(
        String requestId,
        String maidStableId,
        String expectedServerWorldId,
        long beforeSequence,
        long upperSequence,
        int limit
) implements CustomPacketPayload {

    public static final Type<C2SRequestArchiveHistoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_archive_history"));

    /** 1.21.1 的 ByteBufCodecs 没有 LONG 常量；手写固定 8 字节编解码。 */
    private static final StreamCodec<RegistryFriendlyByteBuf, Long> LONG_CODEC =
            StreamCodec.of((buf, value) -> buf.writeLong(value), RegistryFriendlyByteBuf::readLong);

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestArchiveHistoryPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(ArchiveHistoryRequest.MAX_REQUEST_ID_CHARS),
                    C2SRequestArchiveHistoryPacket::requestId,
                    ByteBufCodecs.stringUtf8(ArchiveHistoryRequest.MAX_STABLE_ID_CHARS),
                    C2SRequestArchiveHistoryPacket::maidStableId,
                    ByteBufCodecs.stringUtf8(ArchiveHistoryRequest.MAX_WORLD_ID_CHARS),
                    C2SRequestArchiveHistoryPacket::expectedServerWorldId,
                    LONG_CODEC,
                    C2SRequestArchiveHistoryPacket::beforeSequence,
                    LONG_CODEC,
                    C2SRequestArchiveHistoryPacket::upperSequence,
                    ByteBufCodecs.INT,
                    C2SRequestArchiveHistoryPacket::limit,
                    C2SRequestArchiveHistoryPacket::new
            );

    /** 每玩家最近一次被接受的读取 tick；仅在服务端线程访问。 */
    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();
    private static final long MIN_TICKS_BETWEEN_REQUESTS = 5L;

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 停服清理速率限制内存；不持有 MinecraftServer 强引用。 */
    public static void clearRateLimits() {
        LAST_REQUEST_TICK.clear();
    }

    public static void handle(C2SRequestArchiveHistoryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }

            ArchiveHistoryRequest request;
            try {
                request = new ArchiveHistoryRequest(packet.requestId(), packet.maidStableId(),
                        packet.expectedServerWorldId(), packet.beforeSequence(),
                        packet.upperSequence(), packet.limit());
            } catch (ArchiveException exception) {
                HuhuTalk.LOGGER.warn("[HuhuTalk] rejected archive history request from {}: {}",
                        player.getUUID(), exception.getMessage());
                replyInvalid(packet, context);
                return;
            }

            long now = server.getTickCount();
            if (!tryAcquire(player.getUUID(), now)) {
                reply(context, ArchiveHistoryResponse.error(request.requestId(),
                        request.maidStableId(), request.expectedServerWorldId(),
                        ArchiveHistoryStatus.RATE_LIMITED));
                return;
            }

            // 先做归属校验；无权限时不读 originWorldId、不读归档文件。
            if (!mayReadArchive(server, player, request.maidStableId())) {
                reply(context, ArchiveHistoryResponse.error(request.requestId(),
                        request.maidStableId(), request.expectedServerWorldId(),
                        ArchiveHistoryStatus.NO_PERMISSION));
                return;
            }

            String serverWorldId;
            try {
                // originWorldId 是当前服务器的连接身份；不等于归档初建的 originWorldId。
                serverWorldId = ArchiveService.originWorldId(server);
            } catch (RuntimeException exception) {
                HuhuTalk.LOGGER.error("[HuhuTalk] cannot resolve archive originWorldId", exception);
                reply(context, ArchiveHistoryResponse.error(request.requestId(),
                        request.maidStableId(), "", ArchiveHistoryStatus.ERROR));
                return;
            }

            if (!request.expectedServerWorldId().isEmpty()
                    && !request.expectedServerWorldId().equals(serverWorldId)) {
                reply(context, ArchiveHistoryResponse.error(request.requestId(),
                        request.maidStableId(), serverWorldId,
                        ArchiveHistoryStatus.STALE_WORLD));
                return;
            }

            try {
                ArchiveSnapshot snapshot = ArchiveService.snapshot(server, player.getUUID(),
                        request.maidStableId()).orElse(null);
                ArchiveHistoryResponse response = ArchiveHistoryProtocol.buildPage(
                        request.requestId(), request.maidStableId(), serverWorldId,
                        snapshot, request);
                reply(context, response);
            } catch (ArchiveException exception) {
                HuhuTalk.LOGGER.error("[HuhuTalk] archive history read failed for {} / {}: {}",
                        player.getUUID(), request.maidStableId(), exception.getMessage(), exception);
                reply(context, ArchiveHistoryResponse.error(request.requestId(),
                        request.maidStableId(), serverWorldId, ArchiveHistoryStatus.ERROR));
            } catch (RuntimeException exception) {
                HuhuTalk.LOGGER.error("[HuhuTalk] unexpected archive history failure for {} / {}",
                        player.getUUID(), request.maidStableId(), exception);
                reply(context, ArchiveHistoryResponse.error(request.requestId(),
                        request.maidStableId(), serverWorldId, ArchiveHistoryStatus.ERROR));
            }
        });
    }

    private static boolean tryAcquire(UUID playerId, long now) {
        Long previous = LAST_REQUEST_TICK.get(playerId);
        if (previous != null && now - previous < MIN_TICKS_BETWEEN_REQUESTS) {
            return false;
        }
        LAST_REQUEST_TICK.put(playerId, now);
        return true;
    }

    /**
     * 已加载女仆必须当前归属该玩家；只有女仆未加载时才允许联系人簿归属兜底。
     * 已加载但归属不符时不能再用旧联系人绕过。
     */
    private static boolean mayReadArchive(MinecraftServer server, ServerPlayer player,
                                          String maidStableId) {
        EntityMaid maid = MaidStableIdHelper.findMaid(server, maidStableId);
        if (maid != null) {
            return player.getUUID().equals(maid.getOwnerUUID());
        }
        MaidContactEntry entry = MaidContactBook.get(server).getEntry(maidStableId);
        return entry != null && player.getUUID().toString().equals(entry.getOwnerUuid());
    }

    private static void replyInvalid(C2SRequestArchiveHistoryPacket packet, IPayloadContext context) {
        // 只有 ID 本身仍可安全构造响应时才回包，避免坏 ID 让回复构造再次失败。
        if (!ArchiveHistoryProtocol.isValidRequestId(packet.requestId())
                || !ArchiveHistoryProtocol.isValidStableId(packet.maidStableId())) {
            return;
        }
        reply(context, ArchiveHistoryResponse.error(packet.requestId(), packet.maidStableId(),
                "", ArchiveHistoryStatus.INVALID_REQUEST));
    }

    private static void reply(IPayloadContext context, ArchiveHistoryResponse response) {
        context.reply(new S2CArchiveHistoryPacket(response.toJson()));
    }
}
