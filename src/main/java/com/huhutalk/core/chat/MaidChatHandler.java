package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.core.contact.MaidContactBook;
import com.huhutalk.core.contact.MaidContactEntry;
import com.huhutalk.core.vision.VisionService;
import com.huhutalk.network.C2SMaidImageChatPacket;
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class MaidChatHandler {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("huhutalk.maid_chat");

    /** 单条聊天消息的最大字符数（业务层，防烧 LLM 额度 / 撑大离线存档） */
    private static final int MAX_MESSAGE_LEN = 2000;

    public static void handleChat(ServerPlayer player, String stableId, String message,
                                  boolean bubbleMute, boolean chatBannerMute) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) {
            handleOfflineMessage(player, stableId, message);
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "这不是你的女仆"));
            return;
        }
        // 女仆已加载但未佩戴手机 → 已关机，无法远程联系
        if (!PhoneHelper.hasPhone(maid)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatInfoPacket(stableId, "该女仆没有佩戴手机，暂时无法联系。", false));
            return;
        }

        // 业务层长度上限：codec 上限是 32767 字符，避免异常长消息烧 LLM 额度/撑大离线存档
        if (message == null || message.isBlank()) {
            return;
        }
        if (message.length() > MAX_MESSAGE_LEN) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "消息过长（上限 " + MAX_MESSAGE_LEN + " 字符）"));
            return;
        }

        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "女仆未配置 AI 或 AI 不可用"));
            return;
        }

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
            HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableIdIn, bubbleMute);
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
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) return;
        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) return;

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.systemChat(maid, systemText));
        manager.getSetting().ifPresent(setting -> {
            String language = manager.getChatLanguage();
            String settingText = setting.getSetting(maid, language);
            messages.add(LLMMessage.systemChat(maid, settingText));
        });
        manager.getHistorySummaryManager().appendSummaryMessage(messages);
        manager.getHistory().getDeque().descendingIterator().forEachRemaining(messages::add);
        messages.add(LLMMessage.userChat(maid, "主人就站在你面前，正期待着你的反应。请综合以上所有信息，给他一个自然的、发自内心的回应。"));

        HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableId);
        site.client().chat(callback);
    }

    /**
     * 处理带一张图片的聊天（C2SMaidImageChatPacket）。
     * <p>
     * 图片字节已由客户端降采样后<b>内联进包</b>传输（不再依赖共享 {@code vision_tmp} 目录）。
     * 流程：{@link VisionService#describe} 识图得到描述 → 把描述作为<b>本次调用的临时系统消息</b>
     * （不写历史、不显示），连同用户文字一起发给聊天 LLM，让女仆对图片做出反应。
     */
    public static void handleImageChat(ServerPlayer player, String stableId, String userText, byte[] imageData,
                                       boolean bubbleMute, boolean chatBannerMute) {
        EntityMaid maid = findMaidByStableId(player.getServer(), stableId);
        if (maid == null) {
            LOGGER.error("[HuhuTalk] 图片聊天失败：找不到女仆 stableId={}", stableId);
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "找不到该女仆或女仆不在线"));
            return;
        }
        if (!maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "这不是你的女仆"));
            return;
        }
        if (!PhoneHelper.hasPhone(maid)) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatInfoPacket(stableId, "该女仆没有佩戴手机，暂时无法联系。", false));
            return;
        }
        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            LOGGER.error("[HuhuTalk] 图片聊天失败：女仆 AI 不可用 stableId={}", stableId);
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "女仆未配置 AI 或 AI 不可用"));
            return;
        }

        // 图片字节已在客户端降采样并内联进包：这里只做空值/长度防御，不再依赖共享临时目录
        if (imageData == null || imageData.length == 0) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "图片数据为空，请重试"));
            return;
        }
        if (imageData.length > C2SMaidImageChatPacket.MAX_IMAGE_BYTES) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "图片过大，请换小一点的图"));
            return;
        }
        LOGGER.info("[HuhuTalk] 收到图片聊天: stableId={}, imgBytes={}", stableId, imageData.length);

        // 用户文字不在此提前写历史：本次调用由 buildImageMessages 末尾的 userChat 承载一次，
        // 识图成功后再写入历史，避免本条文字在消息列表里出现两次（历史+末尾各一条）。
        final String stableIdIn = stableId;
        final String userTextIn = userText == null ? "" : userText;
        final EntityMaid maidIn = maid;
        final ServerPlayer playerIn = player;

        VisionService.describe(imageData, "这是主人刚发给你的一张我的世界图片。请仔细观察画面，尽量具体地描述其中发生了什么、有哪些东西。但是不要猜测其中人物的身份，仅描述外貌、特征")
                .whenComplete((desc, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("[HuhuTalk] 识图失败 stableId={}", stableIdIn, throwable);
                        PacketDistributor.sendToPlayer(playerIn,
                                new S2CMaidChatErrorPacket(stableIdIn, "识图失败：" + throwable.getMessage()));
                        return;
                    }
                    if (!maidIn.isAlive()) {
                        return;
                    }
                    LOGGER.info("[HuhuTalk] 识图成功 stableId={}, descLen={}", stableIdIn, desc == null ? 0 : desc.length());
                    // 回到服务端线程再做 LLM 调用（TLM 强制主线程）
                    playerIn.getServer().submit(() -> {
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
                        List<LLMMessage> messages = buildImageMessages(manager, playerIn, desc, userTextIn);
                        HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, playerIn, stableIdIn, bubbleMute);
                        // 手机来源窗口：图片消息同样来自手机
                        PhoneChatSourceController.open(maidIn);
                        site.client().chat(callback);
                        if (userTextIn != null && !userTextIn.isBlank()) {
                            manager.addUserHistory(userTextIn);
                        }
                    });
                });
    }

    /** 构建带图片描述系统消息 + 用户文字的 LLM 消息列表。 */
    private static List<LLMMessage> buildImageMessages(MaidAIChatManager manager, ServerPlayer player, String desc, String userText) {
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
                    new S2CMaidChatErrorPacket(stableId, "找不到该女仆或不是你的女仆"));
            return;
        }

        OfflineMessageQueue queue = OfflineMessageQueue.get(player.getServer());
        queue.enqueue(stableId, player.getName().getString(), player.getUUID().toString(),
                message, player.serverLevel().getGameTime());

        String infoText = entry.getStatus() == MaidStatus.DEAD
                ? "女仆已死亡，消息会等她复活后代为转达。"
                : "女仆暂不在线，消息已离线保存。";
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
     * 把消息来源上下文（手机 + 距离）作为 SYSTEM 消息插到消息列表最后一个元素之前
     * （即用户消息之前），避免排到用户消息后面。上下文为空时跳过。
     * <p>用于纯文本聊天（buildMessages 末尾即为用户消息）。
     */
    private static void prependSourceContext(List<LLMMessage> messages, ServerPlayer player, EntityMaid maid) {
        String ctx = MessageSourceContext.describe(player, maid);
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
        String ctx = MessageSourceContext.describe(player, maid);
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