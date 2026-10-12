package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegisterEvent;
import com.huhutalk.api.phone.camera.PhoneCameraPage;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 把桌面「相机」App 注册到 HuhuTalk 自己的桌面上。
 *
 * <h2>为什么我们自己也要走公开注册通路</h2>
 * 这个 App <b>完全按附属模组的写法注册</b>——用 {@link PhoneAppRegisterEvent} +
 * {@link PhoneApp#builder}，一行都不往 {@code MainPage} 的内置表里塞。这样做有三个好处：
 * <ol>
 *   <li><b>dogfooding</b>：公开注册通路有任何毛病，我们自己第一个踩到，不用等附属模组来报；</li>
 *   <li>内置表保持"只有不可动的核心 App"（HuhuTalk / 手册 / 设置），相机以后要挪位置、
 *       要条件显示（例如"没装手机壳就不显示"）都不用再改内置代码；</li>
 *   <li>它证明了「内置 App 有的能力（启动动画、指定槽位），注册进来的 App 一样有」。</li>
 * </ol>
 *
 * <h2>关于 {@code order(-100)}</h2>
 * 槽位 1 是"第一行第二格"，也就是相机想要的位置。但<b>没指定槽位的 App 会自动落到最小空槽</b>，
 * 而槽位 1 正是一个空槽——所以只要有任何一个附属模组先被处理，它就会把槽位 1 占掉，
 * 相机再申请就只能退让。把 {@code order} 设成负数让它<b>排在最前面</b>被处理，
 * 于是它总是先拿到槽位 1（{@code List.sort} 稳定，order 相同的才按注册顺序）。
 */
@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public final class CameraAppRegistration {

    /** 相机 App 的桌面槽位：第一行第二格（HuhuTalk 恒定占 0） */
    public static final int CAMERA_SLOT = 1;

    /**
     * 图标底色：深灰。
     * <p>
     * 图标现在用的是 256×256 的狐狐像素图（{@code assets/huhutalk/textures/icon/camera.png}）。
     * 底色仍然保留 —— 它是启动动画「色块放大淡出」用的颜色，也是贴图万一没加载出来时的兜底。
     */
    private static final int ICON_COLOR = 0xFF424242;

    /** 桌面图标贴图（256×256，铺满图标方块） */
    private static final ResourceLocation ICON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "textures/icon/camera.png");
    private static final int ICON_SIZE = 256;

    private CameraAppRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPhoneApps(PhoneAppRegisterEvent event) {
        event.register(PhoneApp
                .builder(ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "camera"), "相机")
                .color(ICON_COLOR)
                .icon(ICON_TEXTURE, ICON_SIZE, ICON_SIZE)
                .slot(CAMERA_SLOT)
                .order(-100)
                .page(PhoneCameraPage::new)
                // 启动动画默认开启（与 HuhuTalk 同款「色块放大淡出」），这里显式写出来是为了表明是刻意的
                .launchAnimation(true)
                .build());
    }
}
