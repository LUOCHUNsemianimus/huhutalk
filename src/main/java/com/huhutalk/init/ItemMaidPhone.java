package com.huhutalk.init;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 女仆手机（配饰本体）。
 * <p>
 * 本身实现 {@link IMaidBauble}，因此可被放入女仆的配饰栏（BaubleItemHandler.isItemValid 要求
 * BaubleManager 已绑定该物品）。它是"可达性"的唯一判据：女仆必须持有一部手机才可能被远程联系。
 * <p>
 * 具体强制加载行为不在物品本体里做，而是由票据管理器（见后续 ticket 模块）根据
 * {@link MaidPhoneTier} 决策：SMART 恒载，WATCH 仅在 WiFi 覆盖内生效。
 */
public class ItemMaidPhone extends Item implements IMaidBauble {

    private final MaidPhoneTier tier;

    public ItemMaidPhone(MaidPhoneTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public MaidPhoneTier getTier() {
        return tier;
    }

    /** 是否拥有强制加载票据（Pro 版恒真，青春版恒假，交给票据管理器判断） */
    public boolean hasForceLoadTicket() {
        return tier == MaidPhoneTier.SMART;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable TooltipContext context,
                                @NotNull List<Component> tooltipComponents, @NotNull TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        if (tier == MaidPhoneTier.SMART) {
            tooltipComponents.add(Component.translatable("item.huhutalk.phone.tooltip.smart"));
        } else {
            tooltipComponents.add(Component.translatable("item.huhutalk.phone.tooltip.watch"));
        }
    }

    @Override
    public void onTick(EntityMaid maid, ItemStack baubleItem) {
        // 手机本体无逐 tick 行为；强制加载由票据管理器统一调度。
    }
}
