package com.huhutalk.client.network;

import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.ArchiveHistoryClient;
import com.huhutalk.client.ArchiveAssetClient;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.MaidStatusCache;
import com.huhutalk.client.NotificationManager;
import com.huhutalk.client.PhoneChatSourceCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.SosTaskCache;
import com.huhutalk.client.vision.VisionSiteCache;
import com.huhutalk.common.SystemText;
import com.huhutalk.core.chat.PhoneMessageQuote;
import com.huhutalk.init.HuhuTalkSounds;
import com.huhutalk.network.S2CVisionSitesPacket;
import com.huhutalk.network.S2CArchiveHistoryPacket;
import com.huhutalk.network.S2CArchiveAssetPacket;
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
import com.huhutalk.core.archive.ArchiveHistoryProtocol;
import com.huhutalk.core.archive.ArchiveHistoryResponse;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT)
public class ClientPayloadSetup {
    /** 两条固定到达通知的精确编码；只有 info 与之完全相等才按到达通知处理（参数化/普通 info 不受影响）。 */
    private static final String ARRIVED_GUARD = SystemText.of("message.huhutalk.move.arrived_guard");
    private static final String ARRIVED_WAIT = SystemText.of("message.huhutalk.move.arrived_wait");

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(HuhuTalk.NETWORK_VERSION);
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
        // 服务端 → 客户端：识图平台列表快照（不含密钥）
        registrar.playToClient(S2CVisionSitesPacket.TYPE, S2CVisionSitesPacket.STREAM_CODEC, ClientPayloadSetup::handleVisionSites);
        // 服务端 → 客户端：手机聊天档案的一页只读响应
        registrar.playToClient(S2CArchiveHistoryPacket.TYPE, S2CArchiveHistoryPacket.STREAM_CODEC,
                ClientPayloadSetup::handleArchiveHistory);
        // 服务端 → 客户端：按需读取的单张档案图片（二进制，非 base64 JSON）
        registrar.playToClient(S2CArchiveAssetPacket.TYPE, S2CArchiveAssetPacket.STREAM_CODEC,
                ClientPayloadSetup::handleArchiveAsset);
    }

    /** 图片响应先在客户端主线程过状态机/预算校验，再交给图片控制器解码。 */
    private static void handleArchiveAsset(S2CArchiveAssetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                com.huhutalk.core.archive.ArchiveAssetResponse response = packet.toResponse();
                ArchiveAssetClient.onResponse(response);
            } catch (com.huhutalk.core.archive.ArchiveException exception) {
                HuhuTalk.LOGGER.warn("[HuhuTalk] ignored malformed archive asset response",
                        exception);
                // 只有身份字段严格匹配当前 pending 时，会话才会以 ERROR 结束它。
                ArchiveAssetClient.onMalformedResponse(packet.requestId(), packet.maidStableId(),
                        packet.serverWorldId(), packet.assetHash());
            } catch (RuntimeException exception) {
                HuhuTalk.LOGGER.warn("[HuhuTalk] ignored malformed archive asset response", exception);
            }
        });
    }

    /** 档案响应先在客户端主线程完成 schema/预算/状态校验，再交给独立控制器。 */
    private static void handleArchiveHistory(S2CArchiveHistoryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                ArchiveHistoryResponse response = ArchiveHistoryProtocol.decodeResponse(packet.payloadJson());
                ArchiveHistoryClient.onResponse(response);
            } catch (RuntimeException exception) {
                HuhuTalk.LOGGER.warn("[HuhuTalk] ignored malformed archive history response", exception);
            }
        });
    }

    /** 识图平台列表快照：只写客户端缓存，由「识图模型」页自己取用。 */
    private static void handleVisionSites(S2CVisionSitesPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> VisionSiteCache.set(packet.sites(), packet.canEdit()));
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
        // AI 回复原文原样投递：绝不经过 SystemText，即使正文看起来像系统文案编码
        context.enqueueWork(() -> deliverResponse(packet.stableId(), packet.responseText(), packet.emote(),
                packet.quote()));
    }

    /**
     * 「女仆消息气泡」的唯一投递实现：落盘 + 当前聊天页实时气泡 + 未读计数 + 界面外提示音。
     * <p>
     * AI 回复（{@link #handleResponse}）与固定系统到达通知（{@link #handleInfo}）共用此路径，
     * 因此两者的落盘/未读/音效行为完全一致；系统通知只在调用前把编码解析成当前语言。
     */
    private static void deliverResponse(String stableId, String text, String emote) {
        deliverResponse(stableId, text, emote, null);
    }

    /**
     * 带可选引用的女仆回复投递。只有正文那一条历史携带 quote；尾部表情是独立记录且不带引用。
     */
    private static void deliverResponse(String stableId, String text, String emote, PhoneMessageQuote quote) {
        boolean hasEmote = emote != null && !emote.isEmpty();
        // 文本与附件表情分别落盘（同一 tick 写入 → 同 gameTime → 重进后仍同组）
        ChatHistoryStorage.appendMessage(stableId, text, null, null, false, false, quote);
        if (hasEmote) {
            ChatHistoryStorage.appendMessage(stableId, "", emote, false, false);
        }
        IPhonePage current = PhoneOverlay.getCurrentPage();
        if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(stableId)) {
            chatPage.onReceiveMessage(text, false, hasEmote ? emote : null, quote);
            // 特殊：玩家在该女仆聊天页但已收起手机 → 手机不可见、不产生红点，
            // 此时补响一声界面外通知音（较响 + 限频），提示"有消息来了"。
            if (!PhoneOverlay.isVisible()) {
                HuhuTalkSounds.playMsgReceiveOuter();
            }
        } else {
            // 未读按断句分段数累加，与聊天页逐条气泡显示保持一致；有附件表情再 +1
            NotificationManager.addUnread(stableId,
                    MessageSplitter.split(text).size() + (hasEmote ? 1 : 0));
        }
    }

    private static void handleError(S2CMaidChatErrorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 系统字段用可翻译表示：PhoneText.system 解析 => 当前语言；再加已翻译的「错误」前缀。
            // 只算一次，历史落盘与页面通知共用同一行，避免重复翻译/前缀不一致。
            String shown = PhoneText.tr("gui.huhutalk.chat.error", PhoneText.system(packet.error()));
            ChatHistoryStorage.appendMessage(packet.stableId(), shown, false, true);
            IPhonePage current = PhoneOverlay.getCurrentPage();
            if (current instanceof ChatPage chatPage && chatPage.getStableId().equals(packet.stableId())) {
                // 先标记等待中的图片为识图错误（红!），再显示错误提示
                chatPage.onVisionError();
                chatPage.onReceiveMessage(shown, true);
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

            // 固定到达通知：info 精确等于两个无参数系统键之一 → 按客户端语言解析，
            // 并复用 AI 回复的女仆消息通路（正常气泡，而非「提示」气泡）。普通 info 不匹配此分支。
            if (ARRIVED_GUARD.equals(packet.info()) || ARRIVED_WAIT.equals(packet.info())) {
                deliverResponse(packet.stableId(), PhoneText.system(packet.info()), "");
                return;
            }

            // 普通提示（如未佩戴手机）：正常显示。系统字段解析 + 已翻译「提示」前缀，只算一次
            String shown = PhoneText.tr("gui.huhutalk.chat.notice", PhoneText.system(packet.info()));
            ChatHistoryStorage.appendMessage(packet.stableId(), shown, false, true);
            if (chatPage != null) {
                chatPage.onReceiveMessage(shown, true);
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
                // 失败：整条未发 → 落盘一条提示，打开页时还原文字并保留队列。
                // problem 是模组自有系统字段（固定错误 SystemText 编码），渲染 + 前缀只算一次
                String notice = (packet.message() == null || packet.message().isBlank())
                        ? PhoneText.tr("gui.huhutalk.chat.item_send_failed")
                        : PhoneText.system(packet.message());
                String shown = PhoneText.tr("gui.huhutalk.chat.notice", notice);
                ChatHistoryStorage.appendMessage(packet.stableId(), shown, false, true);
                NotificationManager.addUnread(packet.stableId());
                if (chatPage != null) {
                    // 传无前缀原因：页面内部用同一 notice 键 + 同一 notice 生成与历史相同的行，保持公开方法原约定
                    chatPage.onItemSendRejected(notice);
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