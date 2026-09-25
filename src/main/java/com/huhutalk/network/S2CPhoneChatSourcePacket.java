package com.huhutalk.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端 → 客户端：「该女仆正因手机消息处于忙碌窗口」状态同步。
 * <p>
 * 手机是本模组唯一的聊天通道，{@code MaidChatHandler} 在收到玩家的手机聊天请求时，
 * 为该女仆开启一个来源窗口，并广播本包；客户端据此驱动「使用手机」附加动画
 * （抬手 → 持机打字 → 放下）。
 * <p>
 * 之所以要显式同步而不是让客户端自行判断：动画 predicate 运行在客户端渲染期，
 * 而「这条消息是否来自手机」只有服务端知道（所有聊天都经 {@code MaidChatHandler}）。
 *
 * @param stableId 女仆稳定 ID（客户端按此定位实体）
 * @param active   true = 窗口开启（播放手机动画）；false = 窗口关闭（收起动画）
 */
public record S2CPhoneChatSourcePacket(String stableId, boolean active) implements CustomPacketPayload {
    public static final Type<S2CPhoneChatSourcePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "phone_chat_source"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CPhoneChatSourcePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    S2CPhoneChatSourcePacket::stableId,
                    ByteBufCodecs.BOOL,
                    S2CPhoneChatSourcePacket::active,
                    S2CPhoneChatSourcePacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
