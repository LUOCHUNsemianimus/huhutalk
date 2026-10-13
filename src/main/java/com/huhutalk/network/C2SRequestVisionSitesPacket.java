package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.util.GameModeUtil;
import com.huhutalk.core.vision.VisionSites;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：拉取识图平台列表。
 * <p>
 * 在设置里的「识图模型」页<b>每次打开</b>时发一次，另外每次改完密钥后服务端也会主动回推，
 * 所以玩家在车万女仆自己的 AI 设置里改了站点，回到手机重开本页就能看到最新状态。
 */
public record C2SRequestVisionSitesPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SRequestVisionSitesPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_vision_sites"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestVisionSitesPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
            }, buf -> new C2SRequestVisionSitesPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestVisionSitesPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                context.reply(S2CVisionSitesPacket.of(
                        VisionSites.snapshot(), GameModeUtil.canEditSite(player)));
            }
        });
    }
}
