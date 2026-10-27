package com.huhutalk.network.transport;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * 本项目共享的基础 codec。只覆盖现有 36 个包实际使用的类型。
 *
 * <p>字符串和集合都按“先读长度、验证 0..上限、再分配/读取”的顺序处理；
 * 写方向同样拒绝超限值。{@code INT} 保持 fixed32，{@code BOOL} 保持单字节布尔，
 * 与 1.20.1 {@link FriendlyByteBuf} 的原生表示一致。</p>
 */
public final class ByteBufCodecs {
    /** 与 1.20.1 原生 {@code FriendlyByteBuf.readUtf()}/{@code writeUtf(String)} 的默认上限一致。 */
    public static final int MAX_STRING_CHARS = 32767;
    /** 没有显式上限的集合使用这个有界默认值，避免把网络长度直接当容量。 */
    public static final int DEFAULT_MAX_COLLECTION = 65536;

    public static final StreamCodec<FriendlyByteBuf, Boolean> BOOL =
            StreamCodec.of(FriendlyByteBuf::writeBoolean, FriendlyByteBuf::readBoolean);
    public static final StreamCodec<FriendlyByteBuf, Integer> INT =
            StreamCodec.of(FriendlyByteBuf::writeInt, FriendlyByteBuf::readInt);
    public static final StreamCodec<FriendlyByteBuf, String> STRING_UTF8 = stringUtf8(MAX_STRING_CHARS);

    private ByteBufCodecs() {
    }

    public static StreamCodec<FriendlyByteBuf, String> stringUtf8(int maxChars) {
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must be >= 0");
        }
        final long maxBytes = (long) maxChars * 3L;
        return StreamCodec.of(
                (buf, value) -> {
                    if (value == null) {
                        throw new EncoderException("string must not be null");
                    }
                    if (value.length() > maxChars) {
                        throw new EncoderException("string too long: " + value.length() + " > " + maxChars);
                    }
                    buf.writeUtf(value, maxChars);
                },
                buf -> {
                    int start = buf.readerIndex();
                    int byteLength = buf.readVarInt();
                    if (byteLength < 0 || byteLength > maxBytes) {
                        throw new DecoderException("string byte length out of bounds: " + byteLength
                                + " (max " + maxBytes + ")");
                    }
                    buf.readerIndex(start);
                    return buf.readUtf(maxChars);
                });
    }

    public static StreamCodec<FriendlyByteBuf, byte[]> byteArray(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be >= 0");
        }
        return StreamCodec.of(
                (buf, value) -> {
                    if (value == null) {
                        throw new EncoderException("byte array must not be null");
                    }
                    if (value.length > maxBytes) {
                        throw new EncoderException("byte array too long: " + value.length + " > " + maxBytes);
                    }
                    buf.writeVarInt(value.length);
                    buf.writeBytes(value);
                },
                buf -> {
                    int length = buf.readVarInt();
                    if (length < 0 || length > maxBytes) {
                        throw new DecoderException("byte array length out of bounds: " + length
                                + " (max " + maxBytes + ")");
                    }
                    if (length > buf.readableBytes()) {
                        throw new DecoderException("byte array length exceeds readable bytes: " + length
                                + " > " + buf.readableBytes());
                    }
                    byte[] value = new byte[length];
                    buf.readBytes(value);
                    return value;
                });
    }

    /**
     * 集合 codec。{@code factory} 只会收到已经验证过的非负 count。
     * 现有调用没有显式上限时使用 {@link #DEFAULT_MAX_COLLECTION}。
     */
    public static <B extends FriendlyByteBuf, E, C extends Collection<E>> StreamCodec<B, C> collection(
            IntFunction<C> factory, StreamCodec<B, E> elementCodec) {
        return collection(factory, elementCodec, DEFAULT_MAX_COLLECTION);
    }

    public static <B extends FriendlyByteBuf, E, C extends Collection<E>> StreamCodec<B, C> collection(
            IntFunction<C> factory, StreamCodec<B, E> elementCodec, int maxSize) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(elementCodec, "elementCodec");
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0");
        }
        return StreamCodec.of(
                (buf, value) -> {
                    if (value == null) {
                        throw new EncoderException("collection must not be null");
                    }
                    if (value.size() > maxSize) {
                        throw new EncoderException("collection too large: " + value.size() + " > " + maxSize);
                    }
                    buf.writeVarInt(value.size());
                    for (E element : value) {
                        elementCodec.encode(buf, element);
                    }
                },
                buf -> {
                    int size = buf.readVarInt();
                    if (size < 0 || size > maxSize) {
                        throw new DecoderException("collection size out of bounds: " + size
                                + " (max " + maxSize + ")");
                    }
                    C value = factory.apply(size);
                    if (value == null) {
                        throw new DecoderException("collection factory returned null");
                    }
                    for (int i = 0; i < size; i++) {
                        value.add(elementCodec.decode(buf));
                    }
                    return value;
                });
    }

    /**
     * 供 {@code elementCodec.apply(ByteBufCodecs.list(max))} 使用的集合操作。
     */
    public static <V> StreamCodec.CodecOperation<FriendlyByteBuf, V, List<V>> list(int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0");
        }
        return elementCodec -> collection(ArrayList::new, elementCodec, maxSize);
    }
}
