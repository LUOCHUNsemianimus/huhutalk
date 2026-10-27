package com.huhutalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.core.contact.SosBindData;
import com.huhutalk.core.move.MaidMoveData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import com.huhutalk.network.transport.StreamCodec;
import net.minecraft.network.chat.Component;
import com.huhutalk.network.transport.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.huhutalk.network.transport.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端 → 服务端：SOS 紧急呼叫。
 * <p>只把玩家<b>已绑定</b>（{@link SosBindData}，最多 4 只）且已佩戴手机、已加载的女仆
 * 传到玩家四周前后左右，并切战斗模式跟随保护（{@link MaidMoveData#orderSOS}）。
 * 不误召大量非战斗女仆。
 */
public record C2SSOSPacket() implements CustomPacketPayload {
    public static final Type<C2SSOSPacket> TYPE =
            new Type<>(new net.minecraft.resources.ResourceLocation("huhutalk", "sos"));

    /** 每次 SOS 之间的最短间隔（毫秒），防止客户端高频刷包（网络抖动/连点滥用） */
    private static final long SOS_COOLDOWN_MS = 3000L;

    /** 玩家 UUID -> 上次 SOS 时间戳 */
    private static final Map<UUID, Long> LAST_CALL = new HashMap<>();

    /** 围绕玩家的落点（前后左右各一），按绑定顺序依次分配 */
    private static final BlockPos[] SURROUND_OFFSETS = {
            new BlockPos(0, 0, 3), new BlockPos(0, 0, -3),
            new BlockPos(3, 0, 0), new BlockPos(-3, 0, 0)
    };

    public static final StreamCodec<FriendlyByteBuf, C2SSOSPacket> STREAM_CODEC =
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
            long now = System.currentTimeMillis();
            Long last = LAST_CALL.get(ownerId);
            if (last != null && now - last < SOS_COOLDOWN_MS) {
                serverPlayer.sendSystemMessage(
                        Component.translatable("message.huhutalk.sos.too_frequent"));
                return;
            }
            LAST_CALL.put(ownerId, now);
            List<String> bound = SosBindData.get(serverPlayer.getServer()).getBound(ownerId.toString());
            BlockPos base = serverPlayer.blockPosition();

            int summoned = 0;
            int count = Math.min(bound.size(), SURROUND_OFFSETS.length);
            SosBindData sos = SosBindData.get(serverPlayer.getServer());
            for (int i = 0; i < count; i++) {
                String stableId = bound.get(i);
                EntityMaid maid = MaidStableIdHelper.findMaid(serverPlayer.getServer(), stableId);
                // 只召已加载、存活、有手机、且仍归该玩家所有的女仆
                if (maid == null || !maid.isAlive() || !PhoneHelper.hasPhone(maid)) {
                    continue;
                }
                if (maid.getOwnerUUID() != null && !ownerId.equals(maid.getOwnerUUID())) {
                    continue;
                }
                // 分配到玩家四周的不同落点
                BlockPos anchor = base.offset(SURROUND_OFFSETS[i]);
                // 按该女仆绑定的工作模式切换（默认近战，兼容弓兵等新增攻击模式）
                String taskUid = sos.getTaskUid(ownerId.toString(), stableId);
                MaidMoveData.orderSOS(maid, anchor, taskUid);
                summoned++;
            }
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.huhutalk.sos.summoned", summoned));
        });
    }
}
