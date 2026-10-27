package com.huhutalk.network;

import com.huhutalk.common.StableIdProvider;
import com.huhutalk.core.chat.PhoneChatSourceController;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：「本次回复的逐条打字显示已全部播完」回执。
 * <p>
 * 服务端在收到手机聊天请求时开启「手机来源窗口」并广播给客户端驱动动画；
 * 但"逐条显示"是纯客户端行为（节拍由玩家的「每字耗时 / 单句上限」设置决定），
 * 服务端无从得知何时播完。因此由客户端在最后一条短句（以及尾随表情）落屏后回执本包，
 * 服务端据此关闭窗口 → 动画进入放下段。
 * <p>
 * 这样窗口时长 = <b>真实打字显示时长</b>，而不是服务端拍脑袋的固定值；
 * 服务端仍保留超时兜底，防止客户端异常退出导致窗口悬挂。
 */
public record C2SPhoneTypingDonePacket(String stableId) implements CustomPacketPayload {
    public static final Type<C2SPhoneTypingDonePacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "phone_typing_done"));

    public static final StreamCodec<FriendlyByteBuf, C2SPhoneTypingDonePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SPhoneTypingDonePacket::stableId,
                    C2SPhoneTypingDonePacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SPhoneTypingDonePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
                return;
            }
            MinecraftServer server = serverPlayer.getServer();
            if (server == null) {
                return;
            }
            String stableId = packet.stableId();
            if (stableId == null || stableId.isEmpty()) {
                return;
            }
            // 诊断：客户端"打字播完"回执。窗口时长由它决定，故每次都必须可见。
            com.huhutalk.HuhuTalk.LOGGER.info("[HuhuTalk] 收到打字播完回执: stableId={}", stableId);
            for (ServerLevel level : server.getAllLevels()) {
                for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                    if (entity instanceof EntityMaid maid
                            && maid instanceof StableIdProvider provider
                            && stableId.equals(provider.getStableId())) {
                        PhoneChatSourceController.close(maid);
                        return;
                    }
                }
            }
            com.huhutalk.HuhuTalk.LOGGER.warn("[HuhuTalk] 打字播完回执找不到女仆: stableId={}", stableId);
        });
    }
}
