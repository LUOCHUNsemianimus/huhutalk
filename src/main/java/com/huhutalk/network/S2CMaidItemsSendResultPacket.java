package com.huhutalk.network;

import com.huhutalk.phone.ItemTransfer;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.ByteBufCodecs;
import com.huhutalk.network.transport.StreamCodec;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：一条"带物品消息"(P→M)的发送结果。
 * ok=true：文字+物品整条已发给女仆（客户端补文字气泡 + <b>玩家侧物品气泡</b>、清空物品队列）。
 * ok=false：整条未发送（女仆放不下/不在线等）；客户端保留物品队列、还原文字，并按 message 提示。
 * <p>
 * {@code previews}/{@code total} = 服务端<b>真正转移走的那几格物品</b>的渲染预览（最多 3 个 + 总数）。
 * <p>
 * <b>为什么由服务端回传</b>：玩家侧原来只有一个文字气泡，"我发出去的文件"在聊天里没有任何痕迹，
 * 与女仆侧的 M→P 文件气泡不对称。补上这个气泡就要让它像其它消息一样<b>落盘持久化</b>，
 * 而落盘发生在网络接收端 —— 那一刻聊天页未必开着，客户端手里没有任何物品信息
 * （发送用的槽位早被服务端清空/改动了）。服务端回传是唯一既权威（数量以实际移动的栈为准）、
 * 又与页面生命周期无关的做法。
 */
public record S2CMaidItemsSendResultPacket(String stableId, boolean ok, String text, String message,
                                           List<ItemTransfer.Preview> previews,
                                           int total) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CMaidItemsSendResultPacket> TYPE =
            new CustomPacketPayload.Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "maid_items_send_result"));

    /** 旧签名（无预览）：保留以兼容既有调用点。 */
    public S2CMaidItemsSendResultPacket(String stableId, boolean ok, String text, String message) {
        this(stableId, ok, text, message, new ArrayList<>(), 0);
    }

    /** 与 {@link S2CMaidItemTransferPacket} 同一份预览编解码（含完整组件 SNBT）。 */
    private static final StreamCodec<FriendlyByteBuf, ItemTransfer.Preview> PREVIEW_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, p -> p.itemId,
                    ByteBufCodecs.INT, p -> p.count,
                    ByteBufCodecs.STRING_UTF8, p -> p.stackNbt,
                    ItemTransfer.Preview::new
            );

    private static final StreamCodec<FriendlyByteBuf, List<ItemTransfer.Preview>> PREVIEW_LIST_CODEC =
            ByteBufCodecs.collection(count -> new ArrayList<ItemTransfer.Preview>(count), PREVIEW_CODEC);

    /** 手写编解码：字段 6 个，超过 composite 舒服的范围，且要保证顺序与旧包一致。 */
    public static final StreamCodec<FriendlyByteBuf, S2CMaidItemsSendResultPacket> STREAM_CODEC =
            StreamCodec.<FriendlyByteBuf, S2CMaidItemsSendResultPacket>of(
                    (FriendlyByteBuf buf, S2CMaidItemsSendResultPacket packet) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.stableId());
                        ByteBufCodecs.BOOL.encode(buf, packet.ok());
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.text());
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.message());
                        PREVIEW_LIST_CODEC.encode(buf, packet.previews());
                        ByteBufCodecs.INT.encode(buf, packet.total());
                    },
                    (FriendlyByteBuf buf) -> new S2CMaidItemsSendResultPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.BOOL.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            PREVIEW_LIST_CODEC.decode(buf),
                            ByteBufCodecs.INT.decode(buf))
            );

    /**
     * 还原成客户端展示负载：<b>玩家侧</b>文件气泡。
     * <p>
     * transferId 为空串、pending 为 0：这是"我发出去的"，没有可领取的实体
     * （{@code claimable()} 为 false，点击不会发领取包）。渲染走
     * {@code ChatPage.drawItemBubble} 的玩家侧分支，不读这两个字段。
     */
    public ItemTransfer.Payload toPayload() {
        List<ItemTransfer.Preview> copy = previews == null ? new ArrayList<>() : new ArrayList<>(previews);
        return new ItemTransfer.Payload("", copy, total, 0, false, "");
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
