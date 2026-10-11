package com.huhutalk.core.event;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.core.contact.MaidContactBook;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 胶片转换事件兜底：确保稳定 ID 在所有“女仆 ↔ 物品”转换路径中存活。
 * <p>
 * - ToItem：女仆已经通过 Mixin 写好了 NBT 标签，这里只做兜底（实体上还没生成 ID 时补生成）。
 * - ToMaid：unreadAdditionalSaveData 之前把胶片里的 ID 注入 pending，Mixin 读取逻辑会保留它。
 */
public class FilmTransformHandler {

    @SubscribeEvent
    public void onToItem(MaidAndItemTransformEvent.ToItem event) {
        EntityMaid maid = event.getMaid();
        CompoundTag data = event.getData();
        if (!(maid instanceof StableIdProvider provider)) {
            return;
        }
        String stableId = provider.getStableId();
        if (stableId == null) {
            stableId = MaidStableIdHelper.generate();
            provider.setStableId(stableId);
        }
        data.putString(MaidStableIdHelper.STABLE_ID_TAG, stableId);

        // 玩家把她收进魂符/胶片 → 记为 IN_CHARM。
        // 这样她从魂符里被放出来时（IN_CHARM → ONLINE）不会被当成"失联后回来"，
        // 也就不会触发"你刚刚离开了信号范围…"那套提示词 —— 玩家当然知道她在兜里（见 §5.16）。
        if (maid.level() instanceof ServerLevel serverLevel) {
            MaidContactBook.get(serverLevel.getServer()).markInCharm(
                    stableId,
                    maid.getName().getString(),
                    serverLevel.dimension().location().toString(),
                    serverLevel.getGameTime(),
                    maid.getOwnerUUID() == null ? "" : maid.getOwnerUUID().toString());
        }
    }

    @SubscribeEvent
    public void onToMaid(MaidAndItemTransformEvent.ToMaid event) {
        EntityMaid maid = event.getMaid();
        CompoundTag data = event.getData();
        if (maid instanceof StableIdProvider provider && data.contains(MaidStableIdHelper.STABLE_ID_TAG)) {
            provider.setStableId(data.getString(MaidStableIdHelper.STABLE_ID_TAG));
        }
    }
}