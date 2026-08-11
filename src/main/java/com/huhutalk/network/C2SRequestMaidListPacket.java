package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record C2SRequestMaidListPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SRequestMaidListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_maid_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestMaidListPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {},
                    buf -> new C2SRequestMaidListPacket()
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                List<S2CMaidListPacket.MaidDisplayInfo> displayInfos = new ArrayList<>();

                // 遍历玩家当前所在维度的所有已加载 EntityMaid
                for (EntityMaid maid : serverPlayer.serverLevel().getEntitiesOfClass(
                        EntityMaid.class,
                        serverPlayer.getBoundingBox().inflate(200))) { // 范围200可调，或直接传全局AABB
                    if (maid.getOwnerUUID().equals(serverPlayer.getUUID())) {
                        displayInfos.add(new S2CMaidListPacket.MaidDisplayInfo(
                                maid.getUUID(),
                                maid.getDisplayName().getString(),
                                maid.isAlive(),
                                serverPlayer.level().dimension().location().toString()
                        ));
                    }
                }

                // 如果需要显示其他维度的女仆，可遍历所有 ServerLevel
                // for (ServerLevel level : serverPlayer.getServer().getAllLevels()) { ... }
                // 但注意：未加载的维度里女仆实体不存在，所以只有加载过的维度才能拿到，这符合“在线”定义。

                context.reply(new S2CMaidListPacket(displayInfos));
            }
        });
    }
}