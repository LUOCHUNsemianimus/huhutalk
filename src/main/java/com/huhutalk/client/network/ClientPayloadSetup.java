package com.huhutalk.client.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
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

@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public class ClientPayloadSetup {
    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(HuhuTalk.MOD_ID);
        registrar.playToClient(S2CMaidChatResponsePacket.TYPE, S2CMaidChatResponsePacket.STREAM_CODEC, ClientPayloadSetup::handleResponse);
        registrar.playToClient(S2CMaidChatErrorPacket.TYPE, S2CMaidChatErrorPacket.STREAM_CODEC, ClientPayloadSetup::handleError);
        registrar.playToClient(S2CMaidChatInfoPacket.TYPE, S2CMaidChatInfoPacket.STREAM_CODEC, ClientPayloadSetup::handleInfo);
        registrar.playToClient(S2CMaidListPacket.TYPE, S2CMaidListPacket.STREAM_CODEC, ClientPayloadSetup::handleMaidList);
    }

    private static void handleResponse(S2CMaidChatResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.appendMessage(packet.stableId(), packet.responseText(), false, false);
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.onReceiveMessage(packet.responseText(), false);
            } else {
                NotificationManager.addUnread(packet.stableId());
            }
        });
    }

    private static void handleError(S2CMaidChatErrorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.appendMessage(packet.stableId(), "[错误] " + packet.error(), false, true);
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.onReceiveMessage("[错误] " + packet.error(), true);
            }
            NotificationManager.addUnread(packet.stableId());
        });
    }

    private static void handleInfo(S2CMaidChatInfoPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.appendMessage(packet.stableId(), "[提示] " + packet.info(), false, true);
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.onReceiveMessage("[提示] " + packet.info(), true);
            }
            NotificationManager.addUnread(packet.stableId());
        });
    }

    private static void handleMaidList(S2CMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MaidListCache.setList(packet.maids());
        });
    }
}