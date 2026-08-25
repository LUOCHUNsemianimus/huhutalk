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

    /** 显示用条目，全部使用 stableId 作为唯一键；modelId 为 TLM 女仆模型 id（离线/未知时为空串）；
     *  favorabilityLevel 为好感度等级（0~3，离线/未知为 0） */
    public record MaidDisplayInfo(String stableId, String name, MaidStatus status,
                                  int deathCount, String dimension, String modelId, int favorabilityLevel) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MaidDisplayInfo> STREAM_CODEC =
                StreamCodec.of(MaidDisplayInfo::write, MaidDisplayInfo::read);

        private static void write(RegistryFriendlyByteBuf buf, MaidDisplayInfo m) {
            buf.writeUtf(m.stableId);
            buf.writeUtf(m.name);
            buf.writeUtf(m.status.name());
            buf.writeInt(m.deathCount);
            buf.writeUtf(m.dimension);
            buf.writeUtf(m.modelId);
            buf.writeInt(m.favorabilityLevel);
        }

        private static MaidDisplayInfo read(RegistryFriendlyByteBuf buf) {
            String stableId = buf.readUtf();
            String name = buf.readUtf();
            MaidStatus status = MaidStatus.valueOf(buf.readUtf());
            int deathCount = buf.readInt();
            String dimension = buf.readUtf();
            String modelId = buf.readUtf();
            int favorabilityLevel = buf.readInt();
            return new MaidDisplayInfo(stableId, name, status, deathCount, dimension, modelId, favorabilityLevel);
        }
    }
}