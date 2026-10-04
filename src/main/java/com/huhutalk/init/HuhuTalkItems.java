package com.huhutalk.init;

import com.huhutalk.HuhuTalk;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组物品注册。
 * <p>
 * 手机（青春版/Pro）作为女仆配饰注册，需经 {@code HuhuTalkTLMExtension#bindMaidBauble}
 * 绑定后才能真正放入配饰栏。
 */
public final class HuhuTalkItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, HuhuTalk.MOD_ID);

    /** 电话手表（青春版）：无强制加载票据，仅在 WiFi 覆盖内可达 */
    public static final DeferredHolder<Item, ItemMaidPhone> PHONE_WATCH =
            ITEMS.register("phone_watch",
                    () -> new ItemMaidPhone(MaidPhoneTier.WATCH, new Item.Properties()));

    /** 智能手机（Pro）：永远持有强制加载票据，任意位置可达 */
    public static final DeferredHolder<Item, ItemMaidPhone> PHONE_SMART =
            ITEMS.register("phone_smart",
                    () -> new ItemMaidPhone(MaidPhoneTier.SMART, new Item.Properties()));

    /**
     * 相机模式专用模型载体（<b>不是玩家物品</b>，不进创造栏、不参与女仆配饰绑定）。
     * <p>
     * 只是一个"模型名片"：渲染期在相机模式下临时构造 {@code ItemStack}，让
     * {@code ItemRenderer} 去查 {@code models/item/phone_camera.json}（擦除版纹理）。
     * 详见 {@link ItemPhoneCamera}。
     */
    public static final DeferredHolder<Item, ItemPhoneCamera> PHONE_CAMERA =
            ITEMS.register("phone_camera",
                    () -> new ItemPhoneCamera(new Item.Properties()));

    private HuhuTalkItems() {
    }
}
