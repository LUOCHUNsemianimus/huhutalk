package com.huhutalk.network;

import com.huhutalk.common.MaidStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record S2CMaidListPacket(List<MaidDisplayInfo> maids) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "maid_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CMaidListPacket> STREAM_CODEC =
            StreamCodec.composite(
                    MaidDisplayInfo.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    S2CMaidListPacket::maids,
                    S2CMaidListPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 显示用条目，全部使用 stableId 作为唯一键 */
    public record MaidDisplayInfo(String stableId, String name, MaidStatus status,
                                  int deathCount, String dimension) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MaidDisplayInfo> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        MaidDisplayInfo::stableId,
                        ByteBufCodecs.STRING_UTF8,
                        MaidDisplayInfo::name,
                        ByteBufCodecs.STRING_UTF8.map(MaidStatus::valueOf, MaidStatus::name),
                        MaidDisplayInfo::status,
                        ByteBufCodecs.INT,
                        MaidDisplayInfo::deathCount,
                        ByteBufCodecs.STRING_UTF8,
                        MaidDisplayInfo::dimension,
                        MaidDisplayInfo::new
                );
    }
}