package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 单轮聊天请求自带的临时上下文。
 *
 * <p>“随物品发送”的送达说明只属于发起它的那一次 LLM 请求：调用方在派发
 * {@link MaidChatHandler#handleChat} 时直接传入文本，由本工具在最后一条 USER 消息之前
 * 插入一条 SYSTEM 消息。它<b>不进入</b> {@link PendingSystemMessageBus}，因此压缩回调延后、
 * 同女仆交错聊天或离线队列 drain 都不会把这条说明挪给另一轮。
 *
 * <p>这里集中唯一一份 SYSTEM 临时消息构造，生产代码只通过本方法插入该消息。
 */
public final class ChatRequestContext {

    private ChatRequestContext() {
    }

    /**
     * 把本轮临时系统说明插到消息列表最后一条 USER 之前。
     *
     * <p>说明为 {@code null}/空白时不改变列表（纯文字请求保持原样）。找不到 USER 时
     * 退化为追加到末尾，保证说明不会静默丢失。
     *
     * @param messages 本轮已构建好的 LLM 消息列表，按发送顺序排列
     * @param context  本轮物品送达说明；可空
     * @param gameTime 构造 SYSTEM 消息使用的游戏时间
     */
    public static void appendSystemBeforeLastUser(List<LLMMessage> messages,
                                                  @Nullable String context,
                                                  long gameTime) {
        if (messages == null || messages.isEmpty() || context == null || context.isBlank()) {
            return;
        }
        int insertAt = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                insertAt = i;
                break;
            }
        }
        messages.add(insertAt, new LLMMessage(Role.SYSTEM, context, gameTime, null, null));
    }
}
