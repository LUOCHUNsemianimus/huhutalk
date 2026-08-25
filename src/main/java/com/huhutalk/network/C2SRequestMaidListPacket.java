package com.huhutalk.network;

import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import com.huhutalk.core.contact.SosBindData;
import com.huhutalk.common.StableIdProvider;
import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record C2SRequestMaidListPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SRequestMaidListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("huhutalk", "request_maid_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestMaidListPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {}, buf -> new C2SRequestMaidListPacket());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                MaidContactBook book = MaidContactBook.get(serverPlayer.getServer());
                List<S2CMaidListPacket.MaidDisplayInfo> displayInfos = new ArrayList<>();

                // 一次性扫描已加载女仆，建立 stableId -> (modelId, 好感度等级) 映射（仅在线女仆有实体）
                Map<String, MaidData> maidDataMap = buildMaidDataMap(serverPlayer.getServer());

                // 从全局联系人簿中取出属于该玩家的所有条目（包括离线/死亡）
                for (MaidContactEntry entry : book.getAllEntries().values()) {
                    if (entry.getOwnerUuid().equals(serverPlayer.getUUID().toString())) {
                        MaidData data = maidDataMap.get(entry.getStableId());
                        displayInfos.add(new S2CMaidListPacket.MaidDisplayInfo(
                                entry.getStableId(),
                                entry.getName(),
                                entry.getStatus(),
                                entry.getDeathCount(),
                                entry.getDimension(),
                                data != null ? data.modelId : "",
                                data != null ? data.favorLevel : 0
                        ));
                    }
                }

                context.reply(new S2CMaidListPacket(displayInfos));
                // 一并下发该玩家的 SOS 绑定列表（含工作模式），供客户端显示徽标与模式选择
                context.reply(new S2CSosBindPacket(
                        SosBindData.get(serverPlayer.getServer()).toEntries(serverPlayer.getUUID().toString())));
            }
        });
    }

    /** 扫描所有已加载女仆，返回 stableId -> (modelId, 好感度等级) 的映射 */
    private static Map<String, MaidData> buildMaidDataMap(MinecraftServer server) {
        Map<String, MaidData> map = new HashMap<>();
        if (server == null) {
            return map;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid
                        && maid instanceof StableIdProvider sp) {
                    String stableId = sp.getStableId();
                    if (stableId != null && !stableId.isEmpty()) {
                        String modelId = (maid instanceof IMaid im) ? im.getModelId() : "";
                        int favorLevel = maid.getFavorabilityManager().getLevel();
                        map.put(stableId, new MaidData(modelId, favorLevel));
                    }
                }
            }
        }
        return map;
    }

    /** 已加载女仆的服务端显示数据 */
    private record MaidData(String modelId, int favorLevel) {
    }
}