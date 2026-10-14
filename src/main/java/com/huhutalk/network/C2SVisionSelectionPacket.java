package com.huhutalk.network;

import com.huhutalk.core.vision.VisionSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端 → 服务端：同步「识图走哪个平台、用哪个模型、以及主人长什么样」。
 *
 * <h2>为什么这份选择要单独同步</h2>
 * 识图请求由<b>服务端</b>发起（见 {@code MaidChatHandler#handleImageChat}），
 * 而玩家是在<b>客户端</b>的手机设置里选的，所以必须告诉服务端。
 * <p>
 * 与「药水复活」开关是同一套范式：客户端登录时推一次、改动时推一次，
 * 服务端只在<b>会话内</b>按 UUID 缓存（{@link VisionSettings}），不落盘。
 *
 * <h2>为什么「你的样貌」也走这个包</h2>
 * 它是<b>每玩家一份的全局识图偏好</b>，和"用哪个平台"是同一种东西；
 * 而图片包（{@code C2SMaidImageChatPacket}）的分量已经卡在
 * {@code StreamCodec.composite} 的上限 6 个，且每张图都带一遍玩家样貌纯属浪费。
 * 本包由 2 个字段扩到 3 个，离上限还有余量。
 *
 * <h2>这份数据不敏感</h2>
 * 它只是一个站点 id、一个模型名和一段玩家自述的外貌 —— 真正敏感的 API Key 由
 * {@link C2SVisionSiteEditPacket} 写进主模组的服务端站点配置，两者互不相干。
 *
 * @param siteId           平台（TLM 站点 id）
 * @param model            模型名；空串 = 用该平台的默认预设模型
 * @param playerAppearance 「你的样貌」；空串 = 未填写（识图行为与改造前逐字一致）
 */
public record C2SVisionSelectionPacket(String siteId, String model, String playerAppearance)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SVisionSelectionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "vision_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SVisionSelectionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    C2SVisionSelectionPacket::siteId,
                    ByteBufCodecs.STRING_UTF8,
                    C2SVisionSelectionPacket::model,
                    ByteBufCodecs.STRING_UTF8,
                    C2SVisionSelectionPacket::playerAppearance,
                    C2SVisionSelectionPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SVisionSelectionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                VisionSettings.set(player.getUUID(), packet.siteId(), packet.model(),
                        packet.playerAppearance());
            }
        });
    }
}
