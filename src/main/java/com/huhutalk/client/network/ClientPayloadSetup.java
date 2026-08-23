package com.huhutalk.client.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.SosTaskCache;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
import com.huhutalk.network.S2CMaidChatResponsePacket;
import com.huhutalk.network.S2CMaidDeliveredPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.network.S2CMaidStatusPacket;
import com.huhutalk.network.S2CSosBindPacket;
import com.huhutalk.network.S2CSosTaskListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.pages.ChatPage;
import com.huhutalk.util.MessageSplitter;
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
        registrar.playToClient(S2CMaidDeliveredPacket.TYPE, S2CMaidDeliveredPacket.STREAM_CODEC, ClientPayloadSetup::handleDelivered);
        registrar.playToClient(S2CMaidListPacket.TYPE, S2CMaidListPacket.STREAM_CODEC, ClientPayloadSetup::handleMaidList);
        registrar.playToClient(S2CMaidStatusPacket.TYPE, S2CMaidStatusPacket.STREAM_CODEC, ClientPayloadSetup::handleStatus);
        registrar.playToClient(S2CSosBindPacket.TYPE, S2CSosBindPacket.STREAM_CODEC, ClientPayloadSetup::handleSosBind);
        registrar.playToClient(S2CSosTaskListPacket.TYPE, S2CSosTaskListPacket.STREAM_CODEC, ClientPayloadSetup::handleSosTaskList);
    }

    private static void handleResponse(S2CMaidChatResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            String text = packet.responseText();
            String emote = packet.emote();
            boolean hasEmote = emote != null && !emote.isEmpty();
            // 文本与附件表情分别落盘（同一 tick 写入 → 同 gameTime → 重进后仍同组）
            ChatHistoryStorage.appendMessage(packet.stableId(), text, false, false);
            if (hasEmote) {
                ChatHistoryStorage.appendMessage(packet.stableId(), "", emote, false, false);
            }
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.onReceiveMessage(text, false, hasEmote ? emote : null);
            } else {
                // 未读按断句分段数累加，与聊天页逐条气泡显示保持一致；有附件表情再 +1
                NotificationManager.addUnread(packet.stableId(),
                        MessageSplitter.split(text).size() + (hasEmote ? 1 : 0));
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
            IPhonePage current = PhoneOverlay.getCurrentPage();
            ChatPage chatPage = (current instanceof ChatPage cp && cp.getStableId().equals(packet.stableId())) ? cp : null;

            if (packet.queued()) {
                // 消息入离线队列：只标红"!"，不显示"女仆不在线"提示文字
                if (chatPage != null) {
                    chatPage.onQueued();
                }
                return;
            }

            // 普通提示（如未佩戴手机）：正常显示
            ChatHistoryStorage.appendMessage(packet.stableId(), "[提示] " + packet.info(), false, true);
            if (chatPage != null) {
                chatPage.onReceiveMessage("[提示] " + packet.info(), true);
            }
            NotificationManager.addUnread(packet.stableId());
        });
    }

    private static void handleDelivered(S2CMaidDeliveredPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 离线消息已送达女仆 → 清除该女仆所有未送达"!"标记（不等女仆回复）
            ChatHistoryStorage.clearPending(packet.stableId());
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.clearPending();
            }
        });
    }

    private static void handleMaidList(S2CMaidListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MaidListCache.setList(packet.maids());
        });
    }

    private static void handleStatus(S2CMaidStatusPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MaidStatusCache.set(packet.stableId(), packet.statusText());
        });
    }

    private static void handleSosBind(S2CSosBindPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            SosBindCache.set(packet.bindings());
        });
    }

    private static void handleSosTaskList(S2CSosTaskListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            SosTaskCache.set(packet.taskUids());
        });
    }
}