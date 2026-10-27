package com.huhutalk.network.transport;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Forge SimpleChannel 上唯一注册的原生消息。业务包都装进它的 byte[] 片里，
 * 因而不需要为 36 个包各建一套通道/反射编解码。
 *
 * <p>原生编码（含通道 index 之外的本体）硬上限 32767 字节。业务分片 24000 字节，
 * 加上 payloadId/UUID/长度字段仍明显低于该上限。</p>
 */
public record WirePacket(ResourceLocation payloadId, UUID messageId, int totalLength,
                         int partIndex, int partCount, byte[] data) {
    public static final int MAX_WIRE_BYTES = 32767;

    public WirePacket {
        Objects.requireNonNull(payloadId, "payloadId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(data, "data");
    }

    public void write(FriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.writeResourceLocation(payloadId);
        buffer.writeUUID(messageId);
        buffer.writeInt(totalLength);
        buffer.writeInt(partIndex);
        buffer.writeInt(partCount);
        buffer.writeVarInt(data.length);
        buffer.writeBytes(data);
        int written = buffer.writerIndex() - start;
        if (written > MAX_WIRE_BYTES) {
            throw new EncoderException("wire packet exceeds " + MAX_WIRE_BYTES + " bytes: " + written);
        }
    }

    public static WirePacket read(FriendlyByteBuf buffer) {
        ResourceLocation payloadId = buffer.readResourceLocation();
        UUID messageId = buffer.readUUID();
        int totalLength = buffer.readInt();
        int partIndex = buffer.readInt();
        int partCount = buffer.readInt();
        int length = buffer.readVarInt();
        if (length < 0 || length > PacketFragments.MAX_FRAGMENT_BYTES) {
            throw new DecoderException("wire fragment length out of bounds: " + length);
        }
        byte[] data = new byte[length];
        buffer.readBytes(data);
        return new WirePacket(payloadId, messageId, totalLength, partIndex, partCount, data);
    }
}
