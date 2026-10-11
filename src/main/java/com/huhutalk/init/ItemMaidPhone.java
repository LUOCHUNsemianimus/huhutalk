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

    /**
     * 把手机同步到追踪该女仆的客户端。
     * <p>
     * <b>这是「手机挂手上」渲染能工作的前提。</b> {@link IMaidBauble#syncClient} 默认返回
     * {@code false}，而女仆的配饰栏（{@code BaubleItemHandler}）只在服务端权威，客户端那份是空的；
     * 不覆写此方法的话：
     * <ul>
     *   <li>服务端 {@code PhoneHelper.hasPhone} 为真（能开聊天窗口、发消息），</li>
     *   <li>但客户端 {@code PhoneHelper.getPhoneTier} 恒为 null，</li>
     *   <li>于是 {@code PhoneInHandLayer} 每次都早退：
     *       {@code skip: maid has no phone tier (PhoneHelper.getPhoneTier)}。</li>
     * </ul>
     * 覆写后 TLM 会走 {@code SyncBaublePackage}：
     * 玩家开始追踪女仆时全量同步（{@code MaidTrackEvent}），
     * 戴上/取下时增量同步（{@code BaubleContainer.BaubleSlot}）。
     */
    @Override
    public boolean syncClient(EntityMaid maid, ItemStack baubleItem) {
        return true;
    }

    /**
     * 附魔光效（"附魔纹理"）恒开。
     * <p>
     * {@code ItemStack.hasFoil()} 的判定链是
     * {@code get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) != null ? 它 : item.isFoil(stack)}，
     * 我们覆写的是后半个分支，因此**不往物品栈上写任何组件**（存档里已有的旧栈也同样发光），
     * 也不会让 {@code getRarity()} 升级（那是 {@code isEnchanted()}，看的是真实的 ENCHANTMENTS 组件）。
     * <p>
     * 覆盖到三条渲染通路，全部读同一个 {@code hasFoil()}：
     * <ul>
     *   <li>女仆手上的手机 —— {@code PhoneInHandLayer} → 原版 {@code ItemInHandRenderer}；</li>
     *   <li>玩家第一人称的离屏屏幕 —— {@code ProPhoneHandRenderer#renderPhoneBody}
     *       自己拼 {@code ItemRenderer.getFoilBufferDirect(..., foil)}；</li>
     *   <li>地面掉落物 / GUI / 展示框 —— 原版 {@code ItemRenderer}。</li>
     * </ul>
     * 想临时关掉只改这一行（返回 {@code false}）即可。
     */
    @Override
    public boolean isFoil(@NotNull ItemStack stack) {
        return true;
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
