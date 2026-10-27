package com.huhutalk.network.transport;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 共享 codec 行为检查，覆盖 36 个包实际用到的边界。
 *
 * <p>需要 Minecraft/Netty classpath；上游用预热后的 MDK classpath 实际运行。
 * 退出码 0 表示全部断言通过。</p>
 */
public final class PacketCodecBehaviorCheck {
    private static final StreamCodec<FriendlyByteBuf, List<Integer>> INT_LIST =
            ByteBufCodecs.INT.apply(ByteBufCodecs.list(3));
    private static final StreamCodec<FriendlyByteBuf, List<Integer>> DEFAULT_INT_LIST =
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.INT);
    private static final StreamCodec<FriendlyByteBuf, List<String>> STRING_LIST =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(4));

    private static final StreamCodec<FriendlyByteBuf, SixFields> SIX_FIELDS = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SixFields::first,
            ByteBufCodecs.STRING_UTF8, SixFields::second,
            ByteBufCodecs.INT, SixFields::number,
            ByteBufCodecs.BOOL, SixFields::flag,
            ByteBufCodecs.byteArray(16), SixFields::bytes,
            STRING_LIST, SixFields::names,
            SixFields::new);

    private static int checks;

    private PacketCodecBehaviorCheck() {
    }

    public static void main(String[] args) {
        try {
            stringRoundTripAndBounds();
            byteArrayRoundTripAndBounds();
            listRoundTripAndBounds();
            defaultCollectionBound();
            compositeRoundTripAndTrailingBytes();
            customOfCodecRoundTrip();
            System.out.println("PacketCodecBehaviorCheck OK (" + checks + " checks)");
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            System.exit(1);
        }
    }

    private static void stringRoundTripAndBounds() {
        check("hello 世界".equals(roundTrip(ByteBufCodecs.STRING_UTF8, "hello 世界")),
                "STRING_UTF8 round trip");
        check("".equals(roundTrip(ByteBufCodecs.stringUtf8(4), "")), "empty bounded string round trip");
        check("abcd".equals(roundTrip(ByteBufCodecs.stringUtf8(4), "abcd")), "bounded string round trip");

        FriendlyByteBuf encode = buffer();
        try {
            expectThrows(EncoderException.class, () -> ByteBufCodecs.stringUtf8(4).encode(encode, "abcde"));
        } finally {
            encode.release();
        }

        decodeFails(ByteBufCodecs.stringUtf8(4), buf -> buf.writeVarInt(13));
        decodeFails(ByteBufCodecs.stringUtf8(4), buf -> buf.writeVarInt(-1));
    }

    private static void byteArrayRoundTripAndBounds() {
        byte[] value = {1, 2, 3, 4};
        check(Arrays.equals(value, roundTrip(ByteBufCodecs.byteArray(4), value)),
                "byteArray round trip");
        check(roundTrip(ByteBufCodecs.byteArray(4), new byte[0]).length == 0,
                "empty byteArray round trip");

        FriendlyByteBuf encode = buffer();
        try {
            expectThrows(EncoderException.class,
                    () -> ByteBufCodecs.byteArray(4).encode(encode, new byte[5]));
        } finally {
            encode.release();
        }

        decodeFails(ByteBufCodecs.byteArray(4), buf -> buf.writeVarInt(5));
        decodeFails(ByteBufCodecs.byteArray(4), buf -> buf.writeVarInt(-1));
        decodeFails(ByteBufCodecs.byteArray(16), buf -> {
            buf.writeVarInt(8);
            buf.writeBytes(new byte[]{1, 2, 3});
        });
    }

    private static void listRoundTripAndBounds() {
        check(List.of(1, 2, 3).equals(roundTrip(INT_LIST, List.of(1, 2, 3))),
                "bounded list round trip");
        check(List.of().equals(roundTrip(INT_LIST, List.of())), "empty list round trip");

        FriendlyByteBuf encode = buffer();
        try {
            expectThrows(EncoderException.class,
                    () -> INT_LIST.encode(encode, List.of(1, 2, 3, 4)));
        } finally {
            encode.release();
        }

        decodeFails(INT_LIST, buf -> buf.writeVarInt(4));
        decodeFails(INT_LIST, buf -> buf.writeVarInt(-1));
    }

    private static void defaultCollectionBound() {
        check(List.of(7, 8).equals(roundTrip(DEFAULT_INT_LIST, List.of(7, 8))),
                "default collection round trip");
        decodeFails(DEFAULT_INT_LIST,
                buf -> buf.writeVarInt(ByteBufCodecs.DEFAULT_MAX_COLLECTION + 1));
    }

    private static void compositeRoundTripAndTrailingBytes() {
        SixFields value = new SixFields("stable", "text", 42, true,
                new byte[]{9, 8, 7}, List.of("a", "b"));
        SixFields decoded = roundTrip(SIX_FIELDS, value);
        check(decoded.equals(value), "six-field composite round trip");

        FriendlyByteBuf buffer = buffer();
        try {
            SIX_FIELDS.encode(buffer, value);
            buffer.writeByte(0x7F);
            buffer.readerIndex(0);
            SIX_FIELDS.decode(buffer);
            check(buffer.isReadable(), "trailing bytes must remain detectable");
        } finally {
            buffer.release();
        }
    }

    private static void customOfCodecRoundTrip() {
        StreamCodec<FriendlyByteBuf, Long> longCodec =
                StreamCodec.of((buf, value) -> buf.writeLong(value), FriendlyByteBuf::readLong);
        check(Long.MIN_VALUE == roundTrip(longCodec, Long.MIN_VALUE), "custom of codec round trip");
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buffer = buffer();
        try {
            codec.encode(buffer, value);
            buffer.readerIndex(0);
            T decoded = codec.decode(buffer);
            check(!buffer.isReadable(), "codec must consume exactly the encoded bytes");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void decodeFails(StreamCodec<FriendlyByteBuf, ?> codec,
                                    Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = buffer();
        try {
            writer.accept(buffer);
            buffer.readerIndex(0);
            expectThrows(DecoderException.class, () -> codec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError("expected " + type.getName() + ", got " + throwable, throwable);
        }
        throw new AssertionError("expected " + type.getName() + ", but nothing was thrown");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record SixFields(String first, String second, int number, boolean flag,
                             byte[] bytes, List<String> names) {
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SixFields six)) {
                return false;
            }
            return number == six.number
                    && flag == six.flag
                    && first.equals(six.first)
                    && second.equals(six.second)
                    && Arrays.equals(bytes, six.bytes)
                    && names.equals(six.names);
        }

        @Override
        public int hashCode() {
            return 31 * first.hashCode() + second.hashCode();
        }
    }
}
