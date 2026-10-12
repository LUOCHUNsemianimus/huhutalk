package com.huhutalk.client.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.PhoneChatSourceCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.SosTaskCache;
import com.huhutalk.init.HuhuTalkSounds;
import com.huhutalk.network.S2CPhoneChatSourcePacket;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
import com.huhutalk.network.S2CMaidChatResponsePacket;
import com.huhutalk.network.S2CMaidDeliveredPacket;
import com.huhutalk.network.S2CMaidItemClaimResultPacket;
import com.huhutalk.network.S2CMaidItemTransferPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.network.S2CMaidItemsSendResultPacket;
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
        registrar.playToClient(S2CMaidItemTransferPacket.TYPE, S2CMaidItemTransferPacket.STREAM_CODEC, ClientPayloadSetup::handleItemTransfer);
        registrar.playToClient(S2CMaidItemClaimResultPacket.TYPE, S2CMaidItemClaimResultPacket.STREAM_CODEC, ClientPayloadSetup::handleItemClaimResult);
        registrar.playToClient(S2CMaidItemsSendResultPacket.TYPE, S2CMaidItemsSendResultPacket.STREAM_CODEC, ClientPayloadSetup::handleItemsSendResult);
        registrar.playToClient(S2CMaidListPacket.TYPE, S2CMaidListPacket.STREAM_CODEC, ClientPayloadSetup::handleMaidList);
        registrar.playToClient(S2CMaidStatusPacket.TYPE, S2CMaidStatusPacket.STREAM_CODEC, ClientPayloadSetup::handleStatus);
        registrar.playToClient(S2CSosBindPacket.TYPE, S2CSosBindPacket.STREAM_CODEC, ClientPayloadSetup::handleSosBind);
        registrar.playToClient(S2CSosTaskListPacket.TYPE, S2CSosTaskListPacket.STREAM_CODEC, ClientPayloadSetup::handleSosTaskList);
        // 服务端 → 客户端：手机聊天来源窗口（驱动「使用手机」附加动画）
        registrar.playToClient(S2CPhoneChatSourcePacket.TYPE, S2CPhoneChatSourcePacket.STREAM_CODEC, ClientPayloadSetup::handlePhoneChatSource);
    }

    /** 手机来源窗口同步：只有窗口内的女仆才播放手机动画。 */
    private static void handlePhoneChatSource(S2CPhoneChatSourcePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 诊断：与 PhoneAnimation 的"窗口状态"日志对时间，可判断动画重播是否由服务端开/关窗引起
            HuhuTalk.LOGGER.info("[HuhuTalk] 收到窗口包: stableId={} active={}",
                    packet.stableId(), packet.active());
            PhoneChatSourceCache.set(packet.stableId(), packet.active());
        });
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
                // 特殊：玩家在该女仆聊天页但已收起手机 → 手机不可见、不产生红点，
                // 此时补响一声界面外通知音（较响 + 限频），提示"有消息来了"。
                if (!PhoneOverlay.isVisible()) {
                    HuhuTalkSounds.playMsgReceiveOuter();
                }
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
                // 先标记等待中的图片为识图错误（红!），再显示错误提示
                chatPage.onVisionError();
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

    /** 收到女仆发来的一批文件（M→P）：落盘 + 若正打开该聊天则实时显示物品气泡。 */
    private static void handleItemTransfer(S2CMaidItemTransferPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.appendItemTransfer(packet.stableId(), packet.toPayload());
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.onReceiveItemTransfer(packet.toPayload());
                if (!PhoneOverlay.isVisible()) {
                    HuhuTalkSounds.playMsgReceiveOuter();
                }
            } else {
                NotificationManager.addUnread(packet.stableId());
            }
        });
    }

    /** 领取结果回包：更新该物品气泡的待领取数（0 = 已接收）。 */
    private static void handleItemClaimResult(S2CMaidItemClaimResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ChatHistoryStorage.updateItemTransfer(packet.stableId(), packet.transferId(), packet.pending());
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                chatPage.onItemClaimResult(packet.transferId(), packet.pending());
            }
        });
    }

    /** P→M "文字+物品"发送结果：ok=整条已发(补文字气泡+清队列)，否则保留队列并提示。 */
    private static void handleItemsSendResult(S2CMaidItemsSendResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            String text = packet.text() == null ? "" : packet.text();
            IPhonePage current = PhoneOverlay.getCurrentPage();
            ChatPage chatPage = (current instanceof ChatPage cp && cp.getStableId().equals(packet.stableId())) ? cp : null;

            if (packet.ok()) {
                // 成功：文字已发出 → 落盘（含不在当前页的情况），并在打开页时补内存气泡、清空物品队列
                if (!text.isBlank()) {
                    ChatHistoryStorage.appendMessage(packet.stableId(), text, true, false);
                }
                // 玩家侧"文件气泡"（P→M）：与女仆侧的 M→P 文件气泡对称，同样落盘。
                // 预览由服务端 ack 带回，所以页面没开着也不会丢这条记录。
                boolean hasItems = packet.total() > 0
                        || (packet.previews() != null && !packet.previews().isEmpty());
                if (hasItems) {
                    ChatHistoryStorage.appendItemTransfer(packet.stableId(), packet.toPayload(), true);
                }
                if (chatPage != null) {
                    chatPage.onItemSendAccepted(hasItems ? packet.toPayload() : null);
                }
            } else {
                // 失败：整条未发 → 落盘一条提示，打开页时还原文字并保留队列
                String msg = packet.message() == null ? "发送失败" : packet.message();
                ChatHistoryStorage.appendMessage(packet.stableId(), "[提示] " + msg, false, true);
                NotificationManager.addUnread(packet.stableId());
                if (chatPage != null) {
                    chatPage.onItemSendRejected(msg);
                }
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