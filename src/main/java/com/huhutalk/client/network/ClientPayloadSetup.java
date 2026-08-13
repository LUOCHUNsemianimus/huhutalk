package com.huhutalk.client.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatResponsePacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.pages.ChatPage;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)   // ✅ 没有 bus 参数
public class ClientPayloadSetup {
    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(HuhuTalk.MOD_ID);
        registrar.playToClient(S2CMaidChatResponsePacket.TYPE, S2CMaidChatResponsePacket.STREAM_CODEC, ClientPayloadSetup::handleResponse);
        registrar.playToClient(S2CMaidChatErrorPacket.TYPE, S2CMaidChatErrorPacket.STREAM_CODEC, ClientPayloadSetup::handleError);
        registrar.playToClient(S2CMaidListPacket.TYPE, S2CMaidListPacket.STREAM_CODEC, ClientPayloadSetup::handleMaidList);
    }

    private static void handleResponse(S2CMaidChatResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 1. 存储历史
            ChatHistoryStorage.appendMessage(packet.maidUuid(), packet.responseText(), false, false);

            // 2. 如果当前聊天页面正打开且匹配，则刷新；否则增加未读通知
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getMaidUuid().equals(packet.maidUuid())) {
                chatPage.onReceiveMessage(packet.responseText(), false);
            } else {
                NotificationManager.addUnread(packet.maidUuid());
            }
        });
    }

    private static void handleError(S2CMaidChatErrorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.appendMessage(packet.maidUuid(), "[错误] " + packet.error(), false, true);
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getMaidUuid().equals(packet.maidUuid())) {
                chatPage.onReceiveMessage("[错误] " + packet.error(), true);
            }
            // 错误也增加未读（让玩家知道有情况）
            NotificationManager.addUnread(packet.maidUuid());
        });
    }

    private static void handleMaidList(S2CMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MaidListCache.setList(packet.maids());
            // 如果女仆列表页面正显示，刷新界面（可选）
            // 简单情况下我们通过页面 onOpen/构造时请求，这里不主动刷新
        });
    }
}