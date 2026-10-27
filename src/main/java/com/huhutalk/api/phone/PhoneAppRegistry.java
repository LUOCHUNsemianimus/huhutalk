package com.huhutalk.api.phone;

import com.huhutalk.HuhuTalk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机桌面 App 的公开注册表——附属模组把 {@link PhoneApp} 交到这里，就会出现在 HuhuTalk 桌面上。
 *
 * <h2>推荐用法：订阅 {@link PhoneAppRegisterEvent}</h2>
 * <pre>{@code
 * @EventBusSubscriber(modid = "mymod", value = Dist.CLIENT)
 * public final class MyModClient {
 *     @SubscribeEvent
 *     public static void onRegisterPhoneApps(PhoneAppRegisterEvent event) {
 *         event.register(PhoneApp.builder(...).build());
 *     }
 * }
 * }</pre>
 * 这样不用关心加载顺序。也可以随时直接调 {@link #register(PhoneApp)}。
 *
 * <h2>注册之后会发生什么（以及不会发生什么）</h2>
 * <ul>
 *   <li>HuhuTalk 的<b>内置 App 完全不受影响</b>：它们恒定占据网格的前几个位置，
 *       外部 App 一律<b>追加在后面</b>，所以装不装附属模组都不会改变既有图标的排布。</li>
 *   <li>注册<b>不需要</b>和 HuhuTalk 的内置 App 数量、网格行数、手机长宽比配合——
 *       排布由桌面自己算；超出当前长宽比容量的图标会排在可见区之外（与内置 App 同样处理）。</li>
 *   <li>注册<b>不会</b>给你底部导航栏、未读红点、启动动画——这些目前是内置 App 专有。
 *       点开你的 App 走的是通用「从右侧滑入」转场，右键返回桌面（与内置「设置」一致）。</li>
 * </ul>
 *
 * <h2>线程与时机</h2>
 * 所有方法内部同步，可以放心在客户端初始化阶段调用。桌面每帧检查一次"注册版本号"，
 * 所以<b>任何时候注册都有效</b>（最迟下一帧出现）。
 *
 * @see PhoneApp
 * @see PhoneAppRegisterEvent
 */
public final class PhoneAppRegistry {

    /** 注册进来的外部 App（保持注册顺序，返回时按 {@link PhoneApp#order()} 稳定排序） */
    private static final List<PhoneApp> APPS = new ArrayList<>();
    /** 按 id 去重与反查 */
    private static final Map<net.minecraft.resources.ResourceLocation, PhoneApp> BY_ID = new LinkedHashMap<>();
    /** 注册版本号：每次成功注册 +1，桌面据此判断合并列表缓存是否失效 */
    private static int generation = 0;
    /** {@link PhoneAppRegisterEvent} 是否已广播过（只广播一次） */
    private static boolean eventFired = false;

    private PhoneAppRegistry() {
    }

    /**
     * 注册一个桌面 App。
     * <p>
     * 同一个 {@link PhoneApp#id()} 只会生效一次：重复注册会被忽略并打印警告，
     * <b>不会抛异常</b>——附属模组的加载失败不该拖垮玩家的游戏。
     *
     * @return true = 本次注册生效；false = 被忽略（非客户端 / 重复 id / 参数非法）
     */
    public static synchronized boolean register(PhoneApp app) {
        if (app == null) {
            HuhuTalk.LOGGER.warn("[HuhuTalk/PhoneApp] 注册了 null App，已忽略");
            return false;
        }
        // 桌面是纯客户端的东西；服务端注册没有意义，这里挡掉而不是抛异常
        if (FMLEnvironment.dist != Dist.CLIENT) {
            HuhuTalk.LOGGER.warn("[HuhuTalk/PhoneApp] 非客户端环境尝试注册 App {}，已忽略", app.id());
            return false;
        }
        if (BY_ID.containsKey(app.id())) {
            HuhuTalk.LOGGER.warn("[HuhuTalk/PhoneApp] App id 重复，已忽略后一次注册：id={} name={}",
                    app.id(), app.displayName());
            return false;
        }
        APPS.add(app);
        BY_ID.put(app.id(), app);
        generation++;
        HuhuTalk.LOGGER.info("[HuhuTalk/PhoneApp] 已注册桌面 App：{}（{}）", app.displayName(), app.id());
        return true;
    }

    /**
     * 当前全部外部 App（按 {@link PhoneApp#order()} 稳定排序的快照）。
     * <p>
     * 首次调用会先广播一次 {@link PhoneAppRegisterEvent}——所以这个方法必须在
     * "所有模组都已构造完毕"之后调用才完整；HuhuTalk 只在打开桌面时调用它，天然满足。
     *
     * @return 不可变快照，调用方随便遍历
     */
    public static synchronized List<PhoneApp> apps() {
        ensureEventFired();
        List<PhoneApp> out = new ArrayList<>(APPS);
        // List.sort 是稳定排序：order 相同的保持注册顺序
        out.sort(Comparator.comparingInt(PhoneApp::order));
        return List.copyOf(out);
    }

    /** 注册版本号；桌面用它做合并列表的缓存键。 */
    public static synchronized int generation() {
        return generation;
    }

    /** 按 id 反查已注册的 App；不存在返回 null。 */
    public static synchronized PhoneApp byId(net.minecraft.resources.ResourceLocation id) {
        ensureEventFired();
        return BY_ID.get(id);
    }

    /**
     * 广播 {@link PhoneAppRegisterEvent}，只广播一次。
     * <p>
     * 触发点选在"第一次要桌面 App 列表时"：那时所有模组的构造器都已经跑完，
     * 监听器一定已经注册好了，因此附属模组不需要文档告诉它"要早点注册"。
     * <p>
     * 本方法在持有类锁时被调用（{@code synchronized} 可重入），监听器里再调
     * {@link #register(PhoneApp)} 不会死锁。
     */
    private static void ensureEventFired() {
        if (eventFired) {
            return;
        }
        eventFired = true;
        try {
            MinecraftForge.EVENT_BUS.post(new PhoneAppRegisterEvent());
        } catch (Throwable t) {
            // 附属模组的监听器炸了不该连带把手机桌面炸没
            HuhuTalk.LOGGER.error("[HuhuTalk/PhoneApp] 广播 App 注册事件时出错（已忽略，继续启动）", t);
        }
    }
}
