package com.huhutalk.network.transport;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import java.util.Objects;

/**
 * 本项目使用的双向往返编解码器接口。
 *
 * <p>它不是 Minecraft/Forge 原生 {@code StreamCodec}：本工程只实现现有 36 个包实际用到的
 * {@code of/composite/apply} 以及字符串、布尔、整数、字节数组、集合等基础 codec。
 * 所有有界 codec 在读和写两个方向都会先检查长度/数量再分配或写入。</p>
 */
public interface StreamCodec<B, V> {
    V decode(B buffer);

    void encode(B buffer, V value);

    /**
     * 把当前“元素 codec”交给一个集合/包装操作，生成新的 codec。
     * 现有调用形如 {@code STRING_UTF8.apply(ByteBufCodecs.list(64))}。
     */
    default <O> StreamCodec<B, O> apply(CodecOperation<B, V, O> operation) {
        return operation.apply(this);
    }

    static <B, V> StreamCodec<B, V> of(StreamEncoder<B, V> encoder, StreamDecoder<B, V> decoder) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new StreamCodec<>() {
            @Override
            public V decode(B buffer) {
                return decoder.decode(buffer);
            }

            @Override
            public void encode(B buffer, V value) {
                encoder.encode(buffer, value);
            }
        };
    }

    static <B, T1, O> StreamCodec<B, O> composite(
            StreamCodec<B, T1> codec1, java.util.function.Function<O, T1> getter1,
            java.util.function.Function<T1, O> constructor) {
        Objects.requireNonNull(codec1, "codec1");
        Objects.requireNonNull(getter1, "getter1");
        Objects.requireNonNull(constructor, "constructor");
        return new StreamCodec<>() {
            @Override
            public O decode(B buffer) {
                T1 value1 = codec1.decode(buffer);
                return constructor.apply(value1);
            }

            @Override
            public void encode(B buffer, O value) {
                codec1.encode(buffer, getter1.apply(value));
            }
        };
    }

    static <B, T1, T2, O> StreamCodec<B, O> composite(
            StreamCodec<B, T1> codec1, java.util.function.Function<O, T1> getter1,
            StreamCodec<B, T2> codec2, java.util.function.Function<O, T2> getter2,
            java.util.function.BiFunction<T1, T2, O> constructor) {
        Objects.requireNonNull(codec1, "codec1");
        Objects.requireNonNull(codec2, "codec2");
        Objects.requireNonNull(getter1, "getter1");
        Objects.requireNonNull(getter2, "getter2");
        Objects.requireNonNull(constructor, "constructor");
        return new StreamCodec<>() {
            @Override
            public O decode(B buffer) {
                T1 value1 = codec1.decode(buffer);
                T2 value2 = codec2.decode(buffer);
                return constructor.apply(value1, value2);
            }

            @Override
            public void encode(B buffer, O value) {
                codec1.encode(buffer, getter1.apply(value));
                codec2.encode(buffer, getter2.apply(value));
            }
        };
    }

    static <B, T1, T2, T3, O> StreamCodec<B, O> composite(
            StreamCodec<B, T1> codec1, java.util.function.Function<O, T1> getter1,
            StreamCodec<B, T2> codec2, java.util.function.Function<O, T2> getter2,
            StreamCodec<B, T3> codec3, java.util.function.Function<O, T3> getter3,
            Function3<T1, T2, T3, O> constructor) {
        Objects.requireNonNull(codec1, "codec1");
        Objects.requireNonNull(codec2, "codec2");
        Objects.requireNonNull(codec3, "codec3");
        Objects.requireNonNull(getter1, "getter1");
        Objects.requireNonNull(getter2, "getter2");
        Objects.requireNonNull(getter3, "getter3");
        Objects.requireNonNull(constructor, "constructor");
        return new StreamCodec<>() {
            @Override
            public O decode(B buffer) {
                T1 value1 = codec1.decode(buffer);
                T2 value2 = codec2.decode(buffer);
                T3 value3 = codec3.decode(buffer);
                return constructor.apply(value1, value2, value3);
            }

            @Override
            public void encode(B buffer, O value) {
                codec1.encode(buffer, getter1.apply(value));
                codec2.encode(buffer, getter2.apply(value));
                codec3.encode(buffer, getter3.apply(value));
            }
        };
    }

    static <B, T1, T2, T3, T4, O> StreamCodec<B, O> composite(
            StreamCodec<B, T1> codec1, java.util.function.Function<O, T1> getter1,
            StreamCodec<B, T2> codec2, java.util.function.Function<O, T2> getter2,
            StreamCodec<B, T3> codec3, java.util.function.Function<O, T3> getter3,
            StreamCodec<B, T4> codec4, java.util.function.Function<O, T4> getter4,
            Function4<T1, T2, T3, T4, O> constructor) {
        Objects.requireNonNull(codec1, "codec1");
        Objects.requireNonNull(codec2, "codec2");
        Objects.requireNonNull(codec3, "codec3");
        Objects.requireNonNull(codec4, "codec4");
        Objects.requireNonNull(getter1, "getter1");
        Objects.requireNonNull(getter2, "getter2");
        Objects.requireNonNull(getter3, "getter3");
        Objects.requireNonNull(getter4, "getter4");
        Objects.requireNonNull(constructor, "constructor");
        return new StreamCodec<>() {
            @Override
            public O decode(B buffer) {
                T1 value1 = codec1.decode(buffer);
                T2 value2 = codec2.decode(buffer);
                T3 value3 = codec3.decode(buffer);
                T4 value4 = codec4.decode(buffer);
                return constructor.apply(value1, value2, value3, value4);
            }

            @Override
            public void encode(B buffer, O value) {
                codec1.encode(buffer, getter1.apply(value));
                codec2.encode(buffer, getter2.apply(value));
                codec3.encode(buffer, getter3.apply(value));
                codec4.encode(buffer, getter4.apply(value));
            }
        };
    }

    static <B, T1, T2, T3, T4, T5, O> StreamCodec<B, O> composite(
            StreamCodec<B, T1> codec1, java.util.function.Function<O, T1> getter1,
            StreamCodec<B, T2> codec2, java.util.function.Function<O, T2> getter2,
            StreamCodec<B, T3> codec3, java.util.function.Function<O, T3> getter3,
            StreamCodec<B, T4> codec4, java.util.function.Function<O, T4> getter4,
            StreamCodec<B, T5> codec5, java.util.function.Function<O, T5> getter5,
            Function5<T1, T2, T3, T4, T5, O> constructor) {
        Objects.requireNonNull(codec1, "codec1");
        Objects.requireNonNull(codec2, "codec2");
        Objects.requireNonNull(codec3, "codec3");
        Objects.requireNonNull(codec4, "codec4");
        Objects.requireNonNull(codec5, "codec5");
        Objects.requireNonNull(getter1, "getter1");
        Objects.requireNonNull(getter2, "getter2");
        Objects.requireNonNull(getter3, "getter3");
        Objects.requireNonNull(getter4, "getter4");
        Objects.requireNonNull(getter5, "getter5");
        Objects.requireNonNull(constructor, "constructor");
        return new StreamCodec<>() {
            @Override
            public O decode(B buffer) {
                T1 value1 = codec1.decode(buffer);
                T2 value2 = codec2.decode(buffer);
                T3 value3 = codec3.decode(buffer);
                T4 value4 = codec4.decode(buffer);
                T5 value5 = codec5.decode(buffer);
                return constructor.apply(value1, value2, value3, value4, value5);
            }

            @Override
            public void encode(B buffer, O value) {
                codec1.encode(buffer, getter1.apply(value));
                codec2.encode(buffer, getter2.apply(value));
                codec3.encode(buffer, getter3.apply(value));
                codec4.encode(buffer, getter4.apply(value));
                codec5.encode(buffer, getter5.apply(value));
            }
        };
    }

    static <B, T1, T2, T3, T4, T5, T6, O> StreamCodec<B, O> composite(
            StreamCodec<B, T1> codec1, java.util.function.Function<O, T1> getter1,
            StreamCodec<B, T2> codec2, java.util.function.Function<O, T2> getter2,
            StreamCodec<B, T3> codec3, java.util.function.Function<O, T3> getter3,
            StreamCodec<B, T4> codec4, java.util.function.Function<O, T4> getter4,
            StreamCodec<B, T5> codec5, java.util.function.Function<O, T5> getter5,
            StreamCodec<B, T6> codec6, java.util.function.Function<O, T6> getter6,
            Function6<T1, T2, T3, T4, T5, T6, O> constructor) {
        Objects.requireNonNull(codec1, "codec1");
        Objects.requireNonNull(codec2, "codec2");
        Objects.requireNonNull(codec3, "codec3");
        Objects.requireNonNull(codec4, "codec4");
        Objects.requireNonNull(codec5, "codec5");
        Objects.requireNonNull(codec6, "codec6");
        Objects.requireNonNull(getter1, "getter1");
        Objects.requireNonNull(getter2, "getter2");
        Objects.requireNonNull(getter3, "getter3");
        Objects.requireNonNull(getter4, "getter4");
        Objects.requireNonNull(getter5, "getter5");
        Objects.requireNonNull(getter6, "getter6");
        Objects.requireNonNull(constructor, "constructor");
        return new StreamCodec<>() {
            @Override
            public O decode(B buffer) {
                T1 value1 = codec1.decode(buffer);
                T2 value2 = codec2.decode(buffer);
                T3 value3 = codec3.decode(buffer);
                T4 value4 = codec4.decode(buffer);
                T5 value5 = codec5.decode(buffer);
                T6 value6 = codec6.decode(buffer);
                return constructor.apply(value1, value2, value3, value4, value5, value6);
            }

            @Override
            public void encode(B buffer, O value) {
                codec1.encode(buffer, getter1.apply(value));
                codec2.encode(buffer, getter2.apply(value));
                codec3.encode(buffer, getter3.apply(value));
                codec4.encode(buffer, getter4.apply(value));
                codec5.encode(buffer, getter5.apply(value));
                codec6.encode(buffer, getter6.apply(value));
            }
        };
    }

    @FunctionalInterface
    interface StreamEncoder<B, V> {
        void encode(B buffer, V value);
    }

    @FunctionalInterface
    interface StreamDecoder<B, V> {
        V decode(B buffer);
    }

    @FunctionalInterface
    interface CodecOperation<B, V, O> {
        StreamCodec<B, O> apply(StreamCodec<B, V> codec);
    }

    @FunctionalInterface
    interface Function3<A, B1, C, R> {
        R apply(A a, B1 b, C c);
    }

    @FunctionalInterface
    interface Function4<A, B1, C, D, R> {
        R apply(A a, B1 b, C c, D d);
    }

    @FunctionalInterface
    interface Function5<A, B1, C, D, E, R> {
        R apply(A a, B1 b, C c, D d, E e);
    }

    @FunctionalInterface
    interface Function6<A, B1, C, D, E, F, R> {
        R apply(A a, B1 b, C c, D d, E e, F f);
    }

    static DecoderException decoderError(String message) {
        return new DecoderException(message);
    }

    static EncoderException encoderError(String message) {
        return new EncoderException(message);
    }
}
