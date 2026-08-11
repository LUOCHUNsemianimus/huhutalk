package com.huhutalk.client.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.screen.ChatHistoryStorage;
import com.huhutalk.client.screen.MaidChatScreen;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatResponsePacket;
import com.huhutalk.network.S2CMaidListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)  // ✅ 移除 bus 参数
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
            ChatHistoryStorage.appendMessage(packet.maidUuid(), new MaidChatScreen.ChatMessage(packet.responseText(), false, false));

            Screen current = Minecraft.getInstance().screen;
            if (current instanceof MaidChatScreen chatScreen && chatScreen.getMaidUuid().equals(packet.maidUuid())) {
                chatScreen.onReceiveMessage(packet.maidUuid(), packet.responseText());
            } else {
                NotificationManager.addUnread(packet.maidUuid());
            }
        });
    }

    private static void handleError(S2CMaidChatErrorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.appendMessage(packet.maidUuid(), new MaidChatScreen.ChatMessage("[错误] " + packet.error(), false, true));

            Screen current = Minecraft.getInstance().screen;
            if (current instanceof MaidChatScreen chatScreen && chatScreen.getMaidUuid().equals(packet.maidUuid())) {
                chatScreen.onReceiveError(packet.maidUuid(), packet.error());
            }
        });
    }

    private static void handleMaidList(S2CMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MaidListCache.setList(packet.maids());
        });
    }
}