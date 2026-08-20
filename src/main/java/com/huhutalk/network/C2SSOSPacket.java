package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.move.MaidMoveData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 客户端 → 服务端：SOS 紧急呼叫。
 * <p>复刻"come"逻辑：把玩家名下所有<b>已佩戴手机且已加载</b>的女仆立刻传送到身边，
 * 并切到战斗模式守家保护（近走远传的远距离直达 + {@link MaidMoveData#orderSOS}）。
 * 用于手机紧急呼叫（战斗保护）。
 */
public record C2SSOSPacket() implements CustomPacketPayload {
    public static final Type<C2SSOSPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "sos"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSOSPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {}, buf -> new C2SSOSPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SSOSPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            UUID ownerId = serverPlayer.getUUID();
            int summoned = 0;
            for (ServerLevel level : serverPlayer.getServer().getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof EntityMaid maid
                            && ownerId.equals(maid.getOwnerUUID())
                            && PhoneHelper.hasPhone(maid)
                            && maid.isAlive()) {
                        MaidMoveData.orderSOS(maid);
                        summoned++;
                    }
                }
            }
            serverPlayer.sendSystemMessage(
                    Component.literal("[HuhuTalk] 🚨 紧急呼叫：" + summoned + " 位女仆已赶来保护你"));
        });
    }
}
