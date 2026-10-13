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
import org.jetbrains.annotations.Nullable;

/**
 * 客户端 → 服务端：修改某个识图平台的<b>密钥</b>与/或<b>接口地址</b>。
 *
 * <h2>为什么不用车万女仆自带的 {@code SaveLLMSitePacket}</h2>
 * 那个包的配套回包 {@code SyncAISitesPacket} 在客户端会 {@code mc.setScreen(...)}
 * 强行把玩家界面换成 TLM 的 AI 设置页 —— 在手机里保存一次密钥，手机界面就没了。
 * 所以写入走本包，但：
 * <ul>
 *   <li>权限判定用主模组<b>同一个</b> {@link GameModeUtil#canEditSite}；</li>
 *   <li>落盘用主模组<b>同一个</b> {@code AvailableSites.saveSites()}。</li>
 * </ul>
 * 换句话说：存储位置、权限规则、落盘逻辑与主模组同源，只有"回包不抢屏"这一点不同。
 *
 * <h2>"不改动"用 null 表示</h2>
 * 玩家改接口地址时不该被迫重新输一遍密钥，反之亦然。因此两个字段各自可为 null
 * （= 保持原值）。协议里用「布尔 + 字符串」表达可空，而不是空串哨兵 ——
 * 因为空串是<b>合法输入</b>（"我要清空这个密钥"）。
 *
 * @param siteId 目标站点 id
 * @param apiKey 新密钥；null = 不改
 * @param url    新接口地址；null = 不改
 */
public record C2SVisionSiteEditPacket(String siteId,
                                      @Nullable String apiKey,
                                      @Nullable String url) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SVisionSiteEditPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "vision_site_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SVisionSiteEditPacket> STREAM_CODEC =
            StreamCodec.of(C2SVisionSiteEditPacket::write, C2SVisionSiteEditPacket::read);

    private static void write(RegistryFriendlyByteBuf buf, C2SVisionSiteEditPacket p) {
        buf.writeUtf(p.siteId);
        buf.writeBoolean(p.apiKey != null);
        if (p.apiKey != null) {
            buf.writeUtf(p.apiKey);
        }
        buf.writeBoolean(p.url != null);
        if (p.url != null) {
            buf.writeUtf(p.url);
        }
    }

    private static C2SVisionSiteEditPacket read(RegistryFriendlyByteBuf buf) {
        String siteId = buf.readUtf();
        String key = buf.readBoolean() ? buf.readUtf() : null;
        String url = buf.readBoolean() ? buf.readUtf() : null;
        return new C2SVisionSiteEditPacket(siteId, key, url);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SVisionSiteEditPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 权限与主模组完全一致：单人 / 局域网主机 / 专用服 op>=2。
            // 无权限时不静默失败 —— 回一份 canEdit=false 的快照，让手机页显示原因。
            boolean canEdit = GameModeUtil.canEditSite(player);
            if (canEdit) {
                VisionSites.applyEdit(packet.siteId(), packet.apiKey(), packet.url());
            }
            context.reply(S2CVisionSitesPacket.of(VisionSites.snapshot(), canEdit));
        });
    }
}
