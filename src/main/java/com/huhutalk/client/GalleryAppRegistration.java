package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegisterEvent;
import com.huhutalk.phone.pages.GalleryPage;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 把桌面「图库」App 注册到 HuhuTalk 自己的桌面上。
 *
 * <h2>与 {@link CameraAppRegistration} 同一套做法</h2>
 * 走公开注册通路（{@link PhoneAppRegisterEvent}），一行不往 {@code MainPage} 的内置表里塞。
 * 加一个内置性质的 App 只需要新开一个这样的类，桌面代码零改动。
 *
 * <h2>关于 {@code order(-99)}</h2>
 * 图库固定要槽位 3。但槽位 3 同时也是一个<b>空槽</b>——没指定槽位的附属模组会自动落到最小空槽，
 * 于是谁先被处理谁就先拿到。把 order 设成负数让它排在所有附属模组之前
 * （相机是 -100，在它更前面；两者都远小于附属模组的默认 0）。
 */
@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public final class GalleryAppRegistration {

    /** 图库 App 的桌面槽位：第二行第一格 */
    public static final int GALLERY_SLOT = 3;

    /** 图标底色：青绿（与桌面上其它图标拉开） */
    private static final int ICON_COLOR = 0xFF26A69A;

    private GalleryAppRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPhoneApps(PhoneAppRegisterEvent event) {
        event.register(PhoneApp
                .builder(ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "gallery"), "图库")
                .color(ICON_COLOR)
                .center("🖼️", 2.0f, 0xFFFFFFFF)
                .slot(GALLERY_SLOT)
                .order(-99)
                .page(GalleryPage::new)
                .build());
    }
}
