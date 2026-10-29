package com.huhutalk.network;

import com.huhutalk.core.chat.PhoneMessageQuote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：一条女仆聊天回复。
 *
 * <p>第 4 项 {@code quote} 以有界 JSON 承载（空串 = 无引用）；复用
 * {@link PhoneMessageQuote} 的严格解析，不在网络层另造一套格式。</p>
 */
public record S2CMaidChatResponsePacket(String stableId, String responseText, String emote,
                                        PhoneMessageQuote quote) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidChatResponsePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_chat_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidChatResponsePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::stableId,
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::responseText,
            ByteBufCodecs.STRING_UTF8,
            S2CMaidChatResponsePacket::emote,
            ByteBufCodecs.stringUtf8(PhoneMessageQuote.MAX_JSON_CHARS),
            S2CMaidChatResponsePacket::quoteJson,
            S2CMaidChatResponsePacket::fromWire
    );

    /** 旧 Java 3 参构造：兼容未带引用的旧调用点。 */
    public S2CMaidChatResponsePacket(String stableId, String responseText, String emote) {
        this(stableId, responseText, emote, null);
    }

    /** 无引用时写空串，有引用时复用 DTO 自带的有界 JSON。 */
    private String quoteJson() {
        return quote == null ? "" : quote.toJson();
    }

    private static S2CMaidChatResponsePacket fromWire(String stableId, String responseText,
                                                      String emote, String quoteJson) {
        return new S2CMaidChatResponsePacket(stableId, responseText, emote,
                PhoneMessageQuote.fromJson(quoteJson));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}