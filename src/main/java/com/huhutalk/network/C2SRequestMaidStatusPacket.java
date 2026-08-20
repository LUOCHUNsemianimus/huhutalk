package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.status.MaidStatusText;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 客户端 → 服务端：按需请求女仆状态文案。
 * <p>打开聊天页 / 女仆列表时发送（携带多个 stableId），服务端立即用
 * {@link MaidStatusText#compute} 算出当前状态并逐个回推 {@link S2CMaidStatusPacket}。
 * 这样"切完工作模式立刻打开"也能马上看到状态，不再依赖低频轮询。
 */
public record C2SRequestMaidStatusPacket(List<String> stableIds) implements CustomPacketPayload {

    public static final Type<C2SRequestMaidStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_maid_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestMaidStatusPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    C2SRequestMaidStatusPacket::stableIds,
                    C2SRequestMaidStatusPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestMaidStatusPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            for (String stableId : packet.stableIds()) {
                EntityMaid maid = MaidStableIdHelper.findMaid(serverPlayer.getServer(), stableId);
                if (maid == null || !maid.isAlive()) {
                    continue;
                }
                // 只推送持有手机的女仆状态（没手机视为不可远程联系）
                if (!PhoneHelper.hasPhone(maid)) {
                    continue;
                }
                context.reply(new S2CMaidStatusPacket(stableId, MaidStatusText.compute(maid)));
            }
        });
    }
}
