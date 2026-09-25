package com.huhutalk.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.DefaultGeckoAnimationEvent;
import com.huhutalk.HuhuTalk;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 把本模组的「使用手机」动画并入 TLM 的女仆默认动画表。
 * <p>
 * TLM 通过 {@link DefaultGeckoAnimationEvent} 暴露扩展点，事件在客户端资源重载时由
 * {@code GeckoModelLoader} 发出；合并规则是"<b>同名的用模型包自己的，未定义的才补默认</b>"，
 * 因此我们只新增 {@code huhutalk_phone_*} 三个不重名的动画，不会与任何模型包冲突。
 * <p>
 * <b>动画随本模组 jar 分发</b>：文件位于 {@code assets/huhutalk/animation/huhutalk_phone.animation.json}，
 * 不修改 TLM 自身资源；装了本模组的客户端在任何主机上都会走同一条合并路径。
 * <p>
 * 注意：TLM 文档指出该事件执行很早（早于 {@code @LittleMaidExtension} 注解扫描），
 * 因此必须在模组构造期手动注册到 {@code NeoForge.EVENT_BUS}，不能用 {@code @EventBusSubscriber}。
 */
public final class PhoneAnimationRegistration {

    /** 我们的动画文件（只含 {@code huhutalk_phone_raise/hold/lower} 三个动画）。 */
    private static final ResourceLocation ANIMATION_FILE =
            ResourceLocation.fromNamespaceAndPath(HuhuTalk.MOD_ID, "animation/huhutalk_phone.animation.json");

    private PhoneAnimationRegistration() {
    }

    /** 在模组构造期调用一次（客户端才会实际用到，注册本身无副作用）。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(DefaultGeckoAnimationEvent.class, PhoneAnimationRegistration::onDefaultGeckoAnimation);
    }

    private static void onDefaultGeckoAnimation(DefaultGeckoAnimationEvent event) {
        event.addAnimation(DefaultGeckoAnimationEvent.AnimationType.MAID, ANIMATION_FILE);
    }
}
