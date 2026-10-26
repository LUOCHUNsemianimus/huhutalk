package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.animation.PhoneAnimation;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.event.ClientModEvents;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.pages.ChatPage;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端世界切换处理。
 * - 进入世界：切换数据源（保存旧世界、加载新世界）、清空缓存、加载全局设置与主题、重置手机
 * - 退出世界：显式落盘聊天历史
 */
@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public class ClientCacheEvents {

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // 全局设置/主题（本地，跨世界共享）
        HuhuTalkClientSettings.load();
        ThemeManager.loadFromSettings();
        String worldKey = WorldKeyHelper.getCurrentWorldKey(event.getPlayer());
        ChatHistoryStorage.onWorldChanged(worldKey); // 内部会保存旧世界 + 加载新世界
        NotificationManager.onWorldChanged(worldKey);
        MaidListCache.clear();
        MaidStatusCache.clear();
        // 档案读取按连接代次隔离：登录即作废旧世界/旧连接的迟到响应。
        ArchiveHistoryClient.onConnectionReset();
        ArchiveAssetClient.onConnectionReset();
        SosBindCache.clear();
        SosTaskCache.clear();
        PhoneChatSourceCache.clear();
        PhoneAnimation.reset();
        PhoneOverlay.resetForNewWorld();
        // 重置路径点同步签名 + 同步 JourneyMap 路径点给服务端（可选软依赖，未装时自动跳过）
        WaypointSync.reset();
        WaypointSync.sendWaypoints();
        // 进世界提示：告诉玩家怎么用手机。
        // 这条提示改过一轮：最早写的是"按下 Alt 释放鼠标操作狐狐Talk" —— 那条通路
        // （释放系统鼠标、用真实光标点手机）早已拆除（见 MouseHandlerMixin 的类注释）：
        // 现在手机是**独立光标**，鼠标左右键完整留给游戏（攻击/放置照常），
        // 手机自己只吃玩家绑定的两个功能键。所以正确说法是：掏出手机 + 低头，然后用那两个键操作。
        // 键名**动态取当前绑定**：默认鼠标侧键 4/5，会显示成「鼠标按键4」「鼠标按键5」；
        // 玩家在「按键设置」里改过之后这条提示也依然准确（绑成 W 就显示「W」）。
        // 版式与开局礼包提示（PlayerStarterKit）对齐：前缀「【Huhutalk】」橙色、正文默认色；
        // 两个键名单独标黄，作为 translatable 参数保留自己的颜色与真实绑定。
        event.getPlayer().displayClientMessage(
                Component.empty()
                        .append(Component.literal("【Huhutalk】").withColor(0xFFA500))
                        .append(Component.translatable("message.huhutalk.enter_world",
                                keyComponent(ClientModEvents.PHONE_CLICK),
                                keyComponent(ClientModEvents.PHONE_BACK))), false);
    }

    /** 取某个键位当前绑定的显示名（玩家可能改过；取不到就退化成占位符）。 */
    private static String keyName(net.minecraft.client.KeyMapping mapping) {
        try {
            return mapping.getTranslatedKeyMessage().getString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** 键名标黄：与正文、橙色前缀区分开（Minecraft 标准黄 0xFFFF55）。 */
    private static Component keyComponent(net.minecraft.client.KeyMapping mapping) {
        return Component.literal(keyName(mapping)).withColor(0xFFFF55);
    }

    /** 周期性重同步路径点（进世界后路径点才陆续加载，需补发） */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        WaypointSync.tickSync();
        // 逐条打字显示按真实时间推进。⚠ 这里【不能】再判 `getCurrentPage() instanceof ChatPage`：
        // 玩家在等回复时切到别的页面（或收起手机），推进就会停 —— 短句不落屏、"正在输入…"不解除、
        // C2SPhoneTypingDonePacket 迟迟不发，服务端只能等 30s 兜底关窗（女仆一直举着手机），
        // 玩家则觉得"回复没出来，退出再进才刷出来"。
        // 现在改为遍历"所有持有未播完断句的聊天页"，与页面栈无关。
        ChatPage.tickAllSplitting();
    }

    /** ✅ 退出世界/退出游戏前，把当前世界聊天历史落盘 */
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ChatHistoryStorage.saveCurrentWorld();
        // 登出后旧连接的档案响应不得再落到任何页面。
        ArchiveHistoryClient.onConnectionReset();
        ArchiveAssetClient.onConnectionReset();
        PhoneChatSourceCache.clear();
        PhoneAnimation.reset();
        PhoneOverlay.resetForNewWorld();
        // 页面实例随世界作废，断句登记表一并清空
        ChatPage.clearPendingSplits();
    }
}