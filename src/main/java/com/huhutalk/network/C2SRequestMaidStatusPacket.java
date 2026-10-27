package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.status.MaidStatusText;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.huhutalk.network.transport.IPayloadContext;
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
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "request_maid_status"));

    public static final StreamCodec<FriendlyByteBuf, C2SRequestMaidStatusPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)),
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
            // 去重 + 限制实际查询数，防止重复 stableId 触发全实体扫描（findMaid 是 O(实体数)）
            java.util.Set<String> seen = new java.util.HashSet<>();
            int queried = 0;
            for (String stableId : packet.stableIds()) {
                if (stableId == null || stableId.isEmpty()) {
                    continue;
                }
                if (!seen.add(stableId) || ++queried > 32) {
                    continue;
                }
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
