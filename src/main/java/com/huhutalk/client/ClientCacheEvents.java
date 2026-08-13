package com.huhutalk.client;

import com.huhutalk.HuhuTalk;
import com.huhutalk.phone.PhoneOverlay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端世界切换处理。
 * 切换世界时：
 * - 切换聊天历史/草稿/未读的数据源（旧世界数据保留）
 * - 清空女仆列表缓存（重新请求）
 * - 关闭手机并清空页面栈
 */
@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public class ClientCacheEvents {

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // 1. 计算当前世界 key
        String worldKey = WorldKeyHelper.getCurrentWorldKey(event.getPlayer());
        // 2. 切换聊天历史/草稿数据源
        ChatHistoryStorage.onWorldChanged(worldKey);
        // 3. 切换未读红点数据源
        NotificationManager.onWorldChanged(worldKey);
        // 4. 清空女仆列表缓存（重新请求）
        MaidListCache.clear();
        // 5. 关闭手机并清空页面栈（防止显示上个存档的聊天界面）
        PhoneOverlay.resetForNewWorld();
    }
}