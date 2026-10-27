package com.huhutalk.network;

import com.huhutalk.core.archive.ArchiveHistoryProtocol;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：一页档案响应（JSON 字符串）。
 *
 * <p>载荷上限与 {@link ArchiveHistoryProtocol#MAX_JSON_CHARS} 一致；服务端在构造响应时
 * 同时校验字符与 UTF-8 字节预算。客户端解码后仍会校验 schema、ID、排序与预算，
 * 不信任网络内容。</p>
 */
public record S2CArchiveHistoryPacket(String payloadJson) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CArchiveHistoryPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    new net.minecraft.resources.ResourceLocation("huhutalk", "archive_history"));

    public static final StreamCodec<FriendlyByteBuf, S2CArchiveHistoryPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(ArchiveHistoryProtocol.MAX_JSON_CHARS),
                    S2CArchiveHistoryPacket::payloadJson,
                    S2CArchiveHistoryPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
