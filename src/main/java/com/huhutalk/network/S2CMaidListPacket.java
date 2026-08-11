package com.huhutalk.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public record S2CMaidListPacket(List<MaidDisplayInfo> maids) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidListPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidListPacket> STREAM_CODEC = StreamCodec.composite(
            MaidDisplayInfo.STREAM_CODEC.apply(ByteBufCodecs.list()),
            S2CMaidListPacket::maids,
            S2CMaidListPacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record MaidDisplayInfo(UUID entityId, String name, boolean online, String dimension) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MaidDisplayInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
                MaidDisplayInfo::entityId,
                ByteBufCodecs.STRING_UTF8,
                MaidDisplayInfo::name,
                ByteBufCodecs.BOOL,
                MaidDisplayInfo::online,
                ByteBufCodecs.STRING_UTF8,
                MaidDisplayInfo::dimension,
                MaidDisplayInfo::new
        );
    }
}