package com.huhutalk.network;

import com.huhutalk.core.archive.ArchiveAssetRequest;
import com.huhutalk.core.archive.ArchiveAssetResponse;
import com.huhutalk.core.archive.ArchiveAssetStatus;
import com.huhutalk.core.archive.ArchiveAssetStore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：一张按需读取的档案 PNG。
 *
 * <p>字段长度全部有界，图片使用 {@code byteArray(MAX_ASSET_BYTES)} 编码而非无界
 * readByteArray；单图 1,000,000 字节加头部字段低于 1 MiB。非 OK 状态字节为空。</p>
 */
public record S2CArchiveAssetPacket(
        String requestId,
        String maidStableId,
        String serverWorldId,
        String status,
        String assetHash,
        byte[] data
) implements CustomPacketPayload {

    public static final Type<S2CArchiveAssetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "archive_asset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CArchiveAssetPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_REQUEST_ID_CHARS),
                    S2CArchiveAssetPacket::requestId,
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_STABLE_ID_CHARS),
                    S2CArchiveAssetPacket::maidStableId,
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_WORLD_ID_CHARS),
                    S2CArchiveAssetPacket::serverWorldId,
                    ByteBufCodecs.stringUtf8(32),
                    S2CArchiveAssetPacket::status,
                    ByteBufCodecs.stringUtf8(ArchiveAssetRequest.MAX_HASH_CHARS),
                    S2CArchiveAssetPacket::assetHash,
                    ByteBufCodecs.byteArray(ArchiveAssetStore.MAX_ASSET_BYTES),
                    S2CArchiveAssetPacket::data,
                    S2CArchiveAssetPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public ArchiveAssetResponse toResponse() {
        return new ArchiveAssetResponse(requestId, maidStableId, serverWorldId,
                ArchiveAssetStatus.fromJson(status), assetHash, data);
    }
}
