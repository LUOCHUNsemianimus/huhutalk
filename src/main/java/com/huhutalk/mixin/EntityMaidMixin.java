package com.huhutalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.StableIdProvider;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 EntityMaid 注入稳定 ID 的 NBT 读写能力。
 * <p>
 * 关键链路：
 * <ul>
 *   <li>死亡 → DropEquipment → ItemFilm.maidToFilm → saveWithoutId → [本 Mixin 写标签] → 胶片携带</li>
 *   <li>复活 → filmToMaid → new EntityMaid → ToMaid 事件写入 pending → readAdditionalSaveData → [本 Mixin 读标签]</li>
 * </ul>
 * 若胶片 NBT 中没有标签（老胶片），ToMaid 事件会兜底把 ID 写入 pending 字段，
 * 而本 Mixin 仅在 NBT 存在时覆盖，因此不会丢失 pending 值。
 */
@Mixin(EntityMaid.class)
@Implements(@Interface(iface = StableIdProvider.class, prefix = "huhutalk$"))
public abstract class EntityMaidMixin {

    @Unique
    private String huhutalk$stableId;

    /** 获取当前女仆的稳定 ID；从未生成时返回 null */
    public String huhutalk$getStableId() {
        return this.huhutalk$stableId;
    }

    /** 设置当前女仆的稳定 ID */
    public void huhutalk$setStableId(String id) {
        this.huhutalk$stableId = id;
    }

    // 注意：这两个 @Inject 处理器方法名不能带 $，
    // 否则 Mixin 注解处理器会把它们当作 @Implements 接口的软实现来校验
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void huhutalkOnSave(CompoundTag compound, CallbackInfo ci) {
        String id = huhutalk$getStableId();
        if (id == null) {
            id = MaidStableIdHelper.generate();
            huhutalk$setStableId(id);
        }
        compound.putString(MaidStableIdHelper.STABLE_ID_TAG, id);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void huhutalkOnRead(CompoundTag compound, CallbackInfo ci) {
        // 只有 NBT 里真的有标签才覆盖；否则保留 ToMaid 事件写入的 pending 值
        if (compound.contains(MaidStableIdHelper.STABLE_ID_TAG)) {
            huhutalk$setStableId(compound.getString(MaidStableIdHelper.STABLE_ID_TAG));
        }
    }
}