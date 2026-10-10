package com.huhutalk.common;

import com.huhutalk.init.HuhuTalkItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家侧手机探测（<b>服务端权威</b>，纯判定无副作用）。
 * <p>
 * 与 {@link PhoneHelper} 相对：后者查的是<b>女仆配饰栏</b>里戴没戴手机（决定女仆能否被联络），
 * 本类查的是<b>玩家自己身上</b>有没有 Pro 手机（决定主人这条联络通道成不成立）。
 *
 * <h2>覆盖范围</h2>
 * 用 {@code Inventory#getContainerSize()} 遍历：原版该方法返回
 * {@code items(36) + armor(4) + offhand(1) = 41}，因此主背包、快捷栏、盔甲、副手全都覆盖到，
 * 不需要分别调用 {@code getMainHandItem()} / {@code getOffhandItem()}。
 * <p>
 * 只认 Pro（{@code phone_smart}）：青春版电话手表按产品设定不能承载这条远程救援链路。
 */
public final class PlayerPhoneHelper {

    private PlayerPhoneHelper() {
    }

    /** 玩家身上（任意槽位）是否带着 Pro 手机 */
    public static boolean hasSmartPhone(Player player) {
        if (player == null) {
            return false;
        }
        var inventory = player.getInventory();
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(HuhuTalkItems.PHONE_SMART.get())) {
                return true;
            }
        }
        return false;
    }
}
