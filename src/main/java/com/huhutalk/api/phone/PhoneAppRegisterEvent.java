package com.huhutalk.api.phone;

import net.minecraftforge.eventbus.api.Event;

/**
 * 请求注册手机桌面 App 的事件，由 HuhuTalk 在<b>首次需要桌面 App 列表时</b>广播一次。
 *
 * <h2>为什么用事件而不是只给一个静态方法</h2>
 * 静态注册（直接调 {@link PhoneAppRegistry#register(PhoneApp)}）要求附属模组
 * 自己猜"什么时候注册才来得及"。本事件把这个时机问题彻底消除：
 * 它在所有模组的构造器都跑完之后才触发，所以只要订阅它，<b>不需要关心加载顺序</b>。
 *
 * <h2>怎么订阅</h2>
 * 事件走 NeoForge 的游戏总线，直接注解即可（默认总线就是 GAME）：
 * <pre>{@code
 * @EventBusSubscriber(modid = "mymod", value = Dist.CLIENT)
 * public final class MyModClient {
 *     @SubscribeEvent
 *     public static void onRegisterPhoneApps(PhoneAppRegisterEvent event) {
 *         event.register(PhoneApp.builder(...).build());
 *     }
 * }
 * }</pre>
 *
 * <h2>注意</h2>
 * <ul>
 *   <li>它在<b>客户端渲染线程</b>上触发（第一次打开手机桌面时）。监听器里只做注册，
 *       不要做耗时工作、不要打开界面。</li>
 *   <li>只广播一次。之后再用 {@link PhoneAppRegistry#register(PhoneApp)} 注册也是允许的
 *       （桌面下一帧就会多出一个图标），但推荐还是走本事件。</li>
 * </ul>
 */
public class PhoneAppRegisterEvent extends Event {

    /** 仅供 HuhuTalk 内部构造。 */
    public PhoneAppRegisterEvent() {
    }

    /**
     * 注册一个手机桌面 App。等价于 {@link PhoneAppRegistry#register(PhoneApp)}，
     * 只是放在这里让监听器写起来更顺手。
     */
    public void register(PhoneApp app) {
        PhoneAppRegistry.register(app);
    }
}
