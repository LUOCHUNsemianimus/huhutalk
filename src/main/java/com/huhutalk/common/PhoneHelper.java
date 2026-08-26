package com.huhutalk.common;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.init.ItemMaidPhone;
import com.huhutalk.init.MaidPhoneTier;
import org.jetbrains.annotations.Nullable;

/**
 * 手机等级探测工具。
 * <p>
 * 遍历女仆配饰栏，返回其当前装备手机的最高档位（同时持有两部时按 Pro 优先）。
 * 返回 {@code null} 表示女仆没有佩戴任何手机（即不可被远程联系）。
 */
public final class PhoneHelper {

    private PhoneHelper() {
    }

    /** 返回女仆当前手机档位；未佩戴任何手机时返回 {@code null} */
    @Nullable
    public static MaidPhoneTier getPhoneTier(EntityMaid maid) {
        BaubleItemHandler baubles = maid.getMaidBauble();
        if (baubles == null) {
            return null; // 防御：配饰栏缺失时视为无手机，避免 NPE
        }
        boolean hasSmart = baubles.containsItem(HuhuTalkItems.PHONE_SMART.get());
        if (hasSmart) {
            return MaidPhoneTier.SMART;
        }
        boolean hasWatch = baubles.containsItem(HuhuTalkItems.PHONE_WATCH.get());
        if (hasWatch) {
            return MaidPhoneTier.WATCH;
        }
        return null;
    }

    /** 女仆是否佩戴了任意手机 */
    public static boolean hasPhone(EntityMaid maid) {
        return getPhoneTier(maid) != null;
    }

    /** 该物品是否是本模组的手机 */
    public static boolean isPhone(ItemMaidPhone item) {
        return item == HuhuTalkItems.PHONE_WATCH.get() || item == HuhuTalkItems.PHONE_SMART.get();
    }
}
