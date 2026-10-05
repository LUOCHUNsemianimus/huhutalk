package com.huhutalk.init;

import net.minecraft.world.item.Item;

/**
 * 手机「相机模式」专用模型载体。
 *
 * <h2>它为什么存在</h2>
 * 相机模式需要一块<b>背面能看到游戏世界</b>的手机屏幕。做法不是给模型掏窟窿，而是
 * 换一张把"玩家侧可见面"擦成全透明的纹理（{@code phone_camera_2}）——
 * 于是整块机壳在那些面上直接透出后面的真实场景，几何完全不用动。
 * <p>
 * 要让 {@code ItemRenderer} 拿到那套擦除版模型，最省事、也最不容易碰坏的路径是
 * <b>给它一个可寻址的 Item</b>：{@code ItemRenderer.getModel(stack, ...)} 会照常
 * 按注册名查出 {@code models/item/phone_camera.json}。相比手搓
 * {@code ModelResourceLocation} 去 {@code ModelManager} 里捞 BakedModel，
 * 这条路不依赖任何版本相关的构造器签名，也不会绕过原版缓存。
 *
 * <h2>它不是玩家物品</h2>
 * 刻意<b>不进创造模式物品栏</b>（见 {@link HuhuTalkCreativeTabs}），
 * 也<b>不是</b> {@link ItemMaidPhone}：它没有套装等级、不参与女仆配饰绑定，
 * 只是一张"模型名片"，永远只作为 {@code ItemStack} 在渲染期被临时构造出来。
 * 用普通 {@link Item} 而不是 {@code ItemMaidPhone}，正是为了让"它不是手机"这件事在类型上就成立。
 */
public class ItemPhoneCamera extends Item {

    public ItemPhoneCamera(Properties properties) {
        super(properties);
    }
}
