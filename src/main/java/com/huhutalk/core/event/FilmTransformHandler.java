package com.huhutalk.core.event;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.StableIdProvider;
import net.minecraft.nbt.CompoundTag;
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
        if (maid instanceof StableIdProvider provider) {
            String stableId = provider.getStableId();
            if (stableId == null) {
                stableId = MaidStableIdHelper.generate();
                provider.setStableId(stableId);
            }
            data.putString(MaidStableIdHelper.STABLE_ID_TAG, stableId);
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