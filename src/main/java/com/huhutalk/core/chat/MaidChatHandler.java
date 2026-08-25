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
import com.huhutalk.network.S2CMaidChatErrorPacket;
import com.huhutalk.network.S2CMaidChatInfoPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MaidChatHandler {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("huhutalk.maid_chat");

    public static void handleChat(ServerPlayer player, String stableId, String message) {
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

        MaidAIChatManager manager = maid.getAiChatManager();
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled()) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "女仆未配置 AI 或 AI 不可用"));
            return;
        }

        manager.addUserHistory(message);

        Runnable chatAction = () -> {
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
            HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, player, stableIdIn);
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
     * 图片由客户端写入共享的 {@code vision_tmp} 目录，这里只凭 {@code imageToken} 读取。
     * 流程：读图 → {@link VisionService} 识图得到描述 → 把描述作为<b>本次调用的临时系统消息</b>
     * （不写历史、不显示），连同用户文字一起发给聊天 LLM，让女仆对图片做出反应。
     */
    public static void handleImageChat(ServerPlayer player, String stableId, String userText, String imageToken) {
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

        String safeToken = sanitizeToken(imageToken);
        Path imgPath = VisionService.visionTmpDir().resolve(safeToken);
        boolean exists = Files.exists(imgPath);
        LOGGER.info("[HuhuTalk] 收到图片聊天: stableId={}, token={}, imgPath={}, exists={}", stableId, safeToken, imgPath, exists);
        if (!exists) {
            PacketDistributor.sendToPlayer(player,
                    new S2CMaidChatErrorPacket(stableId, "图片数据缺失，请重试"));
            return;
        }
        // 用户若带了文字，先记入历史（图片描述本身不写历史）
        if (userText != null && !userText.isBlank()) {
            manager.addUserHistory(userText);
        }
        final String stableIdIn = stableId;
        final String userTextIn = userText == null ? "" : userText;
        final EntityMaid maidIn = maid;
        final ServerPlayer playerIn = player;

        VisionService.describeFile(imgPath, "这是主人刚发给你的一张我的世界图片。请仔细观察画面，尽量具体地描述其中发生了什么、有哪些东西。但是不要猜测其中人物的身份，仅描述外貌、特征")
                .whenComplete((desc, throwable) -> {
                    // 用完即删临时图
                    try {
                        Files.deleteIfExists(imgPath);
                    } catch (IOException ignored) {
                    }
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
                        List<LLMMessage> messages = buildImageMessages(manager, playerIn, desc, userTextIn);
                        HuhuTalkChatCallback callback = new HuhuTalkChatCallback(manager, messages, playerIn, stableIdIn);
                        site.client().chat(callback);
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
        // 措辞要强：明确告诉女仆她收到了图片、能看到、必须根据图片回应，避免她沿用旧的"看不到"判断。
        messages.add(LLMMessage.systemChat(maid,
                "主人刚刚给你发来了一张图片，这张图片就是主人要你看的东西（不是让你猜手里有什么）。"
                        + "你现在能清楚地看到这张图片，画面内容如下：\n"
                        + desc
                        + "\n请直接根据图片内容回应主人：先说你看到了什么，再对图中的场景、物品或动作给出自然、符合你性格的反应。"
                        + "不要说自己看不到图片、看不清、或手里空空之类的话。"));
        if (userText != null && !userText.isBlank()) {
            messages.add(LLMMessage.userChat(maid, userText));
        } else {
            messages.add(LLMMessage.userChat(maid, "我发了一张图片给你，你看一下并回应我。"));
        }
        return messages;
    }

    /** 把 token 规整为安全的文件名（防止路径穿越）。 */
    private static String sanitizeToken(String token) {
        if (token == null) {
            return "";
        }
        String clean = token.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        return clean;
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