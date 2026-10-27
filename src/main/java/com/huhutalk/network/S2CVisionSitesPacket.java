package com.huhutalk.network;

import com.huhutalk.common.vision.VisionProviders;
import com.huhutalk.core.vision.VisionSites;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：识图平台列表的<b>非敏感快照</b>。
 *
 * <h2>刻意不下发密钥</h2>
 * 车万女仆自己的编辑器（{@code SyncAISitesPacket}）会把整个站点对象（含 {@code secret_key}）
 * 发给客户端，因为它的界面要把密钥回填进输入框。本模组不需要这个：
 * 密钥只需要"已配置 / 未配置"两个状态就够画界面，玩家要改就重新输一遍。
 * 于是密钥<b>从不离开服务端</b>——比"掩码显示"更彻底。
 *
 * @param sites   站点条目（已按"预设在前、自建在后"排序）
 * @param canEdit 当前玩家是否有权修改服务器的 AI 站点配置
 *                （沿用主模组同一个 {@code GameModeUtil.canEditSite} 判定）
 */
public record S2CVisionSitesPacket(List<SiteEntry> sites, boolean canEdit) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CVisionSitesPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "vision_sites"));

    public static final StreamCodec<FriendlyByteBuf, S2CVisionSitesPacket> STREAM_CODEC =
            StreamCodec.composite(
                    SiteEntry.STREAM_CODEC.apply(ByteBufCodecs.list(256)),
                    S2CVisionSitesPacket::sites,
                    ByteBufCodecs.BOOL,
                    S2CVisionSitesPacket::canEdit,
                    S2CVisionSitesPacket::new
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 把服务端的站点表转成快照（不含密钥）。 */
    public static S2CVisionSitesPacket of(List<VisionSites.Entry> entries, boolean canEdit) {
        List<SiteEntry> out = new ArrayList<>(entries.size());
        for (VisionSites.Entry e : entries) {
            out.add(new SiteEntry(e.siteId(), e.url(), e.hasKey()));
        }
        return new S2CVisionSitesPacket(out, canEdit);
    }

    /**
     * 一条站点。显示名 / 说明 / 预设模型由客户端查 {@link VisionProviders} 得到，
     * 服务端只负责"服务器上到底有哪几个站点、地址是什么、配没配密钥"。
     *
     * @param url 接口地址；空串表示"还没填"（例如刚补建的自定义站点）
     */
    public record SiteEntry(String siteId, String url, boolean hasKey) {

        public static final StreamCodec<FriendlyByteBuf, SiteEntry> STREAM_CODEC =
                StreamCodec.of(SiteEntry::write, SiteEntry::read);

        private static void write(FriendlyByteBuf buf, SiteEntry e) {
            buf.writeUtf(e.siteId);
            buf.writeUtf(e.url == null ? "" : e.url);
            buf.writeBoolean(e.hasKey);
        }

        private static SiteEntry read(FriendlyByteBuf buf) {
            return new SiteEntry(buf.readUtf(), buf.readUtf(), buf.readBoolean());
        }
    }
}
