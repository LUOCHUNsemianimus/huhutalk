package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.phone.PhoneOverlay;
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
        SosBindCache.clear();
        SosTaskCache.clear();
        PhoneOverlay.resetForNewWorld();
        // 同步 JourneyMap 路径点给服务端（可选软依赖，未装时自动跳过）
        WaypointSync.sendWaypoints();
        // 进世界提示：告诉玩家如何释放鼠标操控狐狐手机
        event.getPlayer().displayClientMessage(
                Component.literal("[HuhuTalk] 按下 Alt 释放鼠标操作狐狐Talk！"), false);
    }

    /** 周期性重同步路径点（进世界后路径点才陆续加载，需补发） */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        WaypointSync.tickSync();
    }

    /** ✅ 退出世界/退出游戏前，把当前世界聊天历史落盘 */
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ChatHistoryStorage.saveCurrentWorld();
        PhoneOverlay.resetForNewWorld();
    }
}