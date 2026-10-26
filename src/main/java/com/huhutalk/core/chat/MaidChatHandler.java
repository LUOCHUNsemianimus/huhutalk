package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.common.SystemText;
import com.huhutalk.common.vision.VisionAppearance;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveService;
import com.huhutalk.core.archive.ArchiveTurnContext;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import com.huhutalk.core.vision.VisionService;
import com.huhutalk.core.vision.VisionSettings;
import com.huhutalk.network.C2SMaidImageChatPacket;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MaidChatHandler {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("huhutalk.maid_chat");

    /** 单条聊天消息的最大字符数（业务层，防烧 LLM 额度 / 撑大离线存档） */
    private static final int MAX_MESSAGE_LEN = 2000;

    public static void handleChat(ServerPlayer player, String stableId, String message,
                                  boolean bubbleMute, boolean chatBannerMute) {
        handleChat(player, stableId, message, bubbleMute, chatBannerMute, null);
    }

    /**
     * 带可空归档 turn 的兼容入口：sendFromPlayer 已成功建立物品 turn 时直接复用，
     * 不再重复 recordPlayerText；null 时保持原纯文本归档逻辑。
     */
    public static void handleChat(ServerPlayer player, String stableId, String message,
                                  boolean bubbleMute, boolean chatBannerMute,
                                  @Nullable ArchiveTurnContext archiveTurn) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) {
            handleOfflineMessage(player, stableId, message);
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.not_your_maid")));
            return;
        }
        // 女仆已加载但未佩戴手机 → 已关机，无法远程联系
        if (!PhoneHelper.hasPhone(maid)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatInfoPacket(stableId, SystemText.of("message.huhutalk.chat.no_phone"), false));
            return;
        }

        // 业务层长度上限：codec 上限是 32767 字符，避免异常长消息烧 LLM 额度/撑大离线存档
        if (message == null || message.isBlank()) {
            return;
        }
        if (message.length() > MAX_MESSAGE_LEN) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.message_too_long", MAX_MESSAGE_LEN)));
            return;
        }

        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.ai_unavailable")));
            return;
        }

        // 只有通过现有归属/手机/长度/AI 校验的在线纯文本才建立归档 turn；
        // 来自物品发送的 turn 已包含原话与物品事件，不能重复写玩家消息。
        ArchiveTurnContext turn = archiveTurn;
        if (turn == null) {
            try {
                turn = ArchiveService.recordPlayerText(player.getServer(), player, maid, stableId, message);
            } catch (ArchiveException exception) {
                LOGGER.error("[HuhuTalk] 在线手机纯文本原话归档失败，已继续聊天但不归档本轮回复 stableId={}",
                        stableId, exception);
            }
        }
        final ArchiveTurnContext archiveTurnIn = turn;

        manager.addUserHistory(message);

        // 手机来源窗口：本条消息来自手机 → 通知客户端播放「使用手机」附加动画
        PhoneChatSourceController.open(maid);

        Runnable chatAction = () -> {
            // 女仆悄悄话：抑制该女仆头顶气泡（全局生效）
            if (bubbleMute) {
                ImmersiveBubbleController.suppress(maid);
            }
            // 沉浸式聊天：本次回复不进公屏聊天栏
            if (chatBannerMute) {
                ChatBannerMuteController.suppress(maid);
            }
            List<LLMMessage> messages = buildMessages(manager, message);
            // 消息来源上下文：告知女仆这是手机消息 + 当前与主人的距离（SYSTEM，不写历史）
            prependSourceContext(messages, player, maid);
            // 正常聊天允许消费待注入系统消息（如果有积压，一并带上）
            String stableIdIn = stableId;
            EntityMaid maidIn = maid;
            if (maidIn instanceof StableIdProvider provider) {
                List<String> pending = PendingSystemMessageBus.drain(stableIdIn);
                if (!pending.isEmpty()) {
                    messages.add(0, LLMMessage.systemChat(maidIn, String.join("\n", pending)));
                }
            }
            HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableIdIn,
                    bubbleMute, archiveTurnIn);
            site.client().chat(callback);
        };

        if (manager.getHistorySummaryManager().tryCompressBeforeChat(chatAction)) {
            return;
        }
        chatAction.run();
    }

    /**
     * 主动播报离线消息。不写 TLM 用户历史。
     * <p>
     * 消息结构：
     * [SYSTEM 离线汇总] [SYSTEM 设定] [SYSTEM 摘要(可选)] [历史] [USER 引导回应]
     */
    public static void handleSystemInjection(ServerPlayer player, String stableId, String systemText) {
        handleSystemInjection(player, stableId, systemText, null, false, null);
    }

    /** 无文字物品发送触发的系统注入：复用已建立的物品 turn，让回复与物品事件同轮归档。 */
    public static void handleSystemInjection(ServerPlayer player, String stableId, String systemText,
                                             @Nullable ArchiveTurnContext archiveTurn) {
        handleSystemInjection(player, stableId, systemText, null, false, archiveTurn);
    }

    /**
     * 主动播报系统事件（可定制排布与收尾语）。
     *
     * @param closingUserText 收尾的 USER 消息；{@code null} = 沿用默认那句"主人就站在你面前…"
     * @param systemTextLast  {@code true} = 把 {@code systemText} 放到人设卡与历史<b>之后</b>。
     *                        模型对越靠后的 system 越敏感；"刚刚发生的事"这类即时事件若排在 0 号位
     *                        （人设卡之前）容易被当成背景设定读掉。同时收尾 USER 消息也必须能改 ——
     *                        默认那句写死了"主人就站在你面前"，对"隔着手机远程救援"这种场景是错的。
     *                        {@code false} = 沿用旧排法（适配 P→M 物品提示那种补充说明性质的内容）。
     */
    public static void handleSystemInjection(ServerPlayer player, String stableId, String systemText,
                                             @Nullable String closingUserText, boolean systemTextLast) {
        handleSystemInjection(player, stableId, systemText, closingUserText, systemTextLast, null);
    }

    public static void handleSystemInjection(ServerPlayer player, String stableId, String systemText,
                                             @Nullable String closingUserText, boolean systemTextLast,
                                             @Nullable ArchiveTurnContext archiveTurn) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) return;
        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) return;

        List<LLMMessage> messages = new ArrayList<>();
        if (!systemTextLast) {
            messages.add(LLMMessage.systemChat(maid, systemText));
        }
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            String settingText = setting.getSetting(maid, language);
            messages.add(LLMMessage.systemChat(maid, settingText));
        });
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        if (systemTextLast) {
            messages.add(LLMMessage.systemChat(maid, systemText));
        }
        messages.add(LLMMessage.userChat(maid, closingUserText != null ? closingUserText
                : "主人就站在你面前，正期待着你的反应。请综合以上所有信息，给他一个自然的、发自内心的回应。"));

        HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableId,
                false, archiveTurn);
        site.client().chat(callback);
    }

    /**
     * 处理带一张图片的聊天（C2SMaidImageChatPacket）。
     * <p>
     * 图片字节已由客户端降采样后<b>内联进包</b>传输（不再依赖共享 {@code vision_tmp} 目录）。
     * 流程：{@link VisionService#describe} 识图得到描述 → 把描述作为<b>本次调用的临时系统消息</b>
     * （不写历史、不显示），连同用户文字一起发给聊天 LLM，让女仆对图片做出反应。
     *
     * <h2>人设（外貌描述）怎么进来</h2>
     * 「你的样貌」是<b>每玩家的全局识图偏好</b>，随 {@code C2SVisionSelectionPacket} 存在
     * {@link VisionSettings} 的会话缓存里；「女仆的样貌」是<b>每女仆</b>的，按发图窗口随包带来
     * （见 {@link C2SMaidImageChatPacket#maidAppearance()}）。
     * 两者都为空时提示词逐字走 {@link VisionService#DEFAULT_PROMPT}，老玩家行为零变化。
     *
     * @param maidAppearance 发图窗口所属女仆的外貌描述；null/空白 = 未填写
     */
    public static void handleImageChat(ServerPlayer player, String stableId, String userText, byte[] imageData,
                                       boolean bubbleMute, boolean chatBannerMute, String maidAppearance) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) {
            LOGGER.error("[HuhuTalk] 图片聊天失败：找不到女仆 stableId={}", stableId);
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.maid_offline")));
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.not_your_maid")));
            return;
        }
        if (!PhoneHelper.hasPhone(maid)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatInfoPacket(stableId, SystemText.of("message.huhutalk.chat.no_phone"), false));
            return;
        }
        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            LOGGER.error("[HuhuTalk] 图片聊天失败：女仆 AI 不可用 stableId={}", stableId);
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.ai_unavailable")));
            return;
        }

        // 图片字节已在客户端降采样并内联进包：这里只做空值/长度防御，不再依赖共享临时目录
        if (imageData == null || imageData.length == 0) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.image_empty")));
            return;
        }
        if (imageData.length > C2SMaidImageChatPacket.MAX_IMAGE_BYTES) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.image_too_large")));
            return;
        }
        LOGGER.info("[HuhuTalk] 收到图片聊天: stableId={}, imgBytes={}", stableId, imageData.length);

        // 用户文字不在此提前写历史：本次调用由 buildImageMessages 末尾的 userChat 承载一次，
        // 识图成功后再写入历史，避免本条文字在消息列表里出现两次（历史+末尾各一条）。
        final String stableIdIn = stableId;
        final String userTextIn = userText == null ? "" : userText;
        final EntityMaid maidIn = maid;
        final ServerPlayer playerIn = player;
        final MinecraftServer serverIn = player.getServer();

        // 识图平台由玩家在手机设置里选（「识图模型」页）；服务端只在会话内按 UUID 缓存这份选择。
        // 从未同步过时 VisionSettings 会给出默认值（deepseek），于是老玩家行为与改造前一致。
        VisionSettings.Selection visionSel = VisionSettings.get(player.getUUID());
        final String visionSiteId = visionSel.siteId();
        final String visionModel = visionSel.resolveModel();
        // 人设：主人样貌来自会话缓存（客户端推），女仆样貌来自本包（按窗口取）。
        // 两端都再清洗一次 —— 客户端可以被改包，服务端不能假设它守规矩。
        final String playerLook = VisionAppearance.clean(visionSel.playerAppearance());
        final String maidLook = VisionAppearance.clean(maidAppearance);

        // 图片 turn 在既有校验全部通过后、识图调用前落档；caption 可空。
        // 归档失败只限捕获 ArchiveException 并记录日志，不阻断正常识图/聊天。
        ArchiveTurnContext archiveTurn = null;
        try {
            archiveTurn = ArchiveService.recordPlayerImage(serverIn, player, maid,
                    stableId, imageData, userText);
        } catch (ArchiveException exception) {
            LOGGER.error("[HuhuTalk] 图片原始消息归档失败，继续识图但不归档本轮 stableId={}",
                    stableId, exception);
        }
        final ArchiveTurnContext archiveTurnIn = archiveTurn;
        final String visionEventMessageId = UUID.randomUUID().toString();

        VisionService.describe(imageData, VisionService.buildPrompt(playerLook, maidLook),
                        visionSiteId, visionModel)
                .whenComplete((desc, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("[HuhuTalk] 识图失败 stableId={}", stableIdIn, throwable);
                        // 归档失败事件与原错误通知一起回服务端线程；不额外调用任何模型。
                        serverIn.submit(() -> {
                            if (archiveTurnIn != null) {
                                try {
                                    ArchiveService.recordVisionFailure(serverIn, maidIn,
                                            archiveTurnIn, visionEventMessageId,
                                            "vision_request_failed", visionSiteId, visionModel);
                                } catch (ArchiveException exception) {
                                    LOGGER.error("[HuhuTalk] 识图失败事件归档失败 turnId={}",
                                            archiveTurnIn.turnId(), exception);
                                }
                            }
                            PacketDistributor.sendToPlayer(playerIn,
                                    new S2CMaidChatErrorPacket(stableIdIn,
                                            SystemText.of("message.huhutalk.chat.vision_failed",
                                                    SystemText.errorDetail(throwable))));
                        });
                        return;
                    }
                    if (!maidIn.isAlive()) {
                        return;
                    }
                    LOGGER.info("[HuhuTalk] 识图成功 stableId={}, descLen={}", stableIdIn, desc == null ? 0 : desc.length());
                    // 回到服务端线程再做 LLM 调用（TLM 强制主线程）
                    serverIn.submit(() -> {
                        if (archiveTurnIn != null) {
                            try {
                                if (desc != null && !desc.isBlank()) {
                                    // 描述原文完整入档，不截断；角色是 OBSERVATION，不是主人原话。
                                    ArchiveService.recordVisionObservation(serverIn,
                                            maidIn, archiveTurnIn, visionEventMessageId, desc,
                                            visionSiteId, visionModel);
                                } else {
                                    ArchiveService.recordVisionFailure(serverIn,
                                            maidIn, archiveTurnIn, visionEventMessageId,
                                            "vision returned an empty description",
                                            visionSiteId, visionModel);
                                }
                            } catch (ArchiveException exception) {
                                LOGGER.error("[HuhuTalk] 识图观察/失败归档失败 turnId={}",
                                        archiveTurnIn.turnId(), exception);
                            }
                        }
                        // 女仆悄悄话：抑制该女仆头顶气泡（全局生效）
                        if (bubbleMute) {
                            ImmersiveBubbleController.suppress(maidIn);
                        }
                        // 沉浸式聊天：本次回复不进公屏聊天栏
                        if (chatBannerMute) {
                            ChatBannerMuteController.suppress(maidIn);
                        }
                        // 先构建消息（此时历史尚未写入本条文字，末尾 userChat 即本条唯一一次），
                        // 发起调用后再把用户文字写入历史，供后续对话记住本条；图片描述本身不写历史。
                        List<LLMMessage> messages = buildImageMessages(manager, playerIn, desc, userTextIn,
                                identityNote(playerLook, maidLook));
                        HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages,
                                playerIn, stableIdIn, bubbleMute, archiveTurnIn);
                        // 手机来源窗口：图片消息同样来自手机
                        PhoneChatSourceController.open(maidIn);
                        site.client().chat(callback);
                        if (userTextIn != null && !userTextIn.isBlank()) {
                            manager.addUserHistory(userTextIn);
                        }
                    });
                });
    }

    /**
     * 身份参考段：让女仆在<b>聊天里</b>也知道"画面里那个人是主人 / 那个女仆是你自己"。
     * <p>
     * 为什么不能只靠识图描述：描述是视觉模型写的，措辞不受控；这里主动把已知的人设
     * 交给她，她回应时就不会把主人当成陌生人。
     * <p>
     * ⚠ 两段都为空时必须返回<b>空串</b>（而不是一段空壳说明），
     * 这样下面拼出来的系统消息与改造前逐字一致。
     */
    private static String identityNote(String playerLook, String maidLook) {
        boolean hasP = playerLook != null && !playerLook.isBlank();
        boolean hasM = maidLook != null && !maidLook.isBlank();
        if (!hasP && !hasM) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n（身份参考，供你理解画面中的人是谁：");
        if (hasP) {
            sb.append("\n- 主人（玩家）：").append(playerLook);
        }
        if (hasM) {
            sb.append("\n- 你自己（女仆）：").append(maidLook);
        }
        sb.append("）");
        return sb.toString();
    }

    /** 构建带图片描述系统消息 + 用户文字的 LLM 消息列表。 */
    private static List<LLMMessage> buildImageMessages(MaidAIChatManager manager, ServerPlayer player, String desc,
                                                       String userText, String identityNote) {
        EntityMaid maid = manager.getMaid();
        List<LLMMessage> messages = new ArrayList<>();
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            messages.add(LLMMessage.systemChat(maid, setting.getSetting(maid, language)));
        });
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        // 消息来源上下文：告知女仆这是手机消息 + 当前与主人的距离（SYSTEM，不写历史）。
        // 放在图片描述之前，让图片描述紧贴用户文字，避免来源上下文遮挡图片信息。
        addSourceContext(messages, player, maid);
        // 图片描述作为本次调用的临时系统消息（不写历史、不显示在聊天）。
        // 措辞要强：明确告诉女仆这是「最新一张」图片、必须以此为准，忽略历史里看过/聊过的其他任何图片。
        messages.add(LLMMessage.systemChat(maid,
                "这是主人最新发给你的一张新图片，也是当前唯一需要你关注的图片（不是让你猜手里有什么）。"
                        + "请忽略你之前看过的、或对话里提到过的任何其他图片或角色——它们已经作废，一律以这张为准。"
                        + "你现在能清楚地看到这张最新图片，画面内容如下：\n"
                        + desc
                        + identityNote
                        + "\n请严格依据上面这张最新图片的内容回应主人：先准确说出你在图片里看到了什么，"
                        + "再对图中的场景、物品或动作给出自然、符合你性格的反应。"
                        + "不要联想或沿用之前讨论过的内容，也不要说自己看不到图片、看不清、或手里空空之类的话。"));
        if (userText != null && !userText.isBlank()) {
            messages.add(LLMMessage.userChat(maid, userText));
        } else {
            messages.add(LLMMessage.userChat(maid, "我发了一张图片给你，你看一下并回应我。"));
        }
        return messages;
    }

    private static void handleOfflineMessage(ServerPlayer player, String stableId, String message) {
        MaidContactBook book = MaidContactBook.get(player.getServer());
        MaidContactEntry entry = book.getEntry(stableId);

        if (entry == null || !entry.getOwnerUuid().equals(player.getUUID().toString())) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, SystemText.of("message.huhutalk.chat.maid_not_found")));
            return;
        }

        OfflineMessageQueue queue = OfflineMessageQueue.get(player.getServer());
        queue.enqueue(stableId, player.getName().getString(), player.getUUID().toString(),
                message, player.serverLevel().getGameTime());

        String infoText = entry.getStatus() == MaidStatus.DEAD
                ? SystemText.of("message.huhutalk.chat.offline_dead")
                : SystemText.of("message.huhutalk.chat.offline_saved");
        PacketDistributor.sendToPlayer(player, new S2CMaidChatInfoPacket(stableId, infoText, true));
    }

    private static List<LLMMessage> buildMessages(MaidAIChatManager manager, String userMessage) {
        EntityMaid maid = manager.getMaid();
        List<LLMMessage> messages = new ArrayList<>();
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            String settingText = setting.getSetting(maid, language);
            messages.add(LLMMessage.systemChat(maid, settingText));
        });
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        messages.add(LLMMessage.userChat(maid, userMessage));
        return messages;
    }

    /**
     * 手机聊天用的上下文（SYSTEM）。
     * <p>= 消息来源（手机 + 距离） + {@link MaidPhoneContexts} 的"手机 / 远程支援"常识。
     * <p>后者必须在这里显式拼上：TLM 自带的聊天路径（{@code MaidAIChatManager.normalChat}）会调
     * {@code UserPromptContexts.addContext} 自动带上知识库里的常驻上下文，而本模组的手机聊天是
     * 自建消息列表、<b>绕过了</b>那条路径。
     */
    private static String chatContext(ServerPlayer player, EntityMaid maid) {
        String source = MessageSourceContext.describe(player, maid);
        String support = MaidPhoneContexts.render(maid);
        if (support.isBlank()) {
            return source;
        }
        if (source.isBlank()) {
            return support;
        }
        return source + "\n" + support;
    }

    /**
     * 把消息来源上下文（手机 + 距离）作为 SYSTEM 消息插到消息列表最后一个元素之前
     * （即用户消息之前），避免排到用户消息后面。上下文为空时跳过。
     * <p>用于纯文本聊天（buildMessages 末尾即为用户消息）。
     */
    private static void prependSourceContext(List<LLMMessage> messages, ServerPlayer player, EntityMaid maid) {
        String ctx = chatContext(player, maid);
        if (ctx == null || ctx.isBlank()) {
            return;
        }
        messages.add(messages.size() - 1, LLMMessage.systemChat(maid, ctx));
    }

    /**
     * 把消息来源上下文（手机 + 距离）作为 SYSTEM 消息追加到列表末尾。
     * 上下文为空时跳过。
     * <p>用于图片聊天（调用时图片描述与用户消息尚未追加，追加后紧随其后的便是图片描述）。
     */
    private static void addSourceContext(List<LLMMessage> messages, ServerPlayer player, EntityMaid maid) {
        String ctx = chatContext(player, maid);
        if (ctx == null || ctx.isBlank()) {
            return;
        }
        messages.add(LLMMessage.systemChat(maid, ctx));
    }

    private static EntityMaid findMaidByStableId(MinecraftServer server, String stableId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid
                        && maid instanceof StableIdProvider provider
                        && stableId.equals(provider.getStableId())
                        && maid.isAlive()) {
                    return maid;
                }
            }
        }
        return null;
    }
}