package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真实 LLMMessage + 生产工具 {@link ChatRequestContext} 的行为测试。
 *
 * <p>覆盖：延后/逆序执行的两次请求各自保留本轮物品说明；共享 pending 总线被其他
 * consumer 消费后本轮说明仍在；用户原话与消息顺序不变；无上下文时不新增消息。
 */
public class ChatRequestContextTest {

    private static final long TIME = 1000L;
    private static final String ITEM_A = "主人通过手机发给你这些物品：A物品x2。请自然地回应主人。";
    private static final String ITEM_B = "主人通过手机发给你这些物品：B物品x3。请自然地回应主人。";

    /** 模拟生产消息列表：设定、来源上下文、最后一条 USER。 */
    private static List<LLMMessage> productionLikeMessages(String userText) {
        List<LLMMessage> messages = new ArrayList<>();
        messages.add(new LLMMessage(Role.SYSTEM, "女仆设定", TIME, null, null));
        messages.add(new LLMMessage(Role.SYSTEM, "这是来自手机的消息", TIME, null, null));
        messages.add(new LLMMessage(Role.USER, userText, TIME, null, null));
        return messages;
    }

    private static List<String> texts(List<LLMMessage> messages) {
        List<String> out = new ArrayList<>(messages.size());
        for (LLMMessage message : messages) {
            out.add(message.message());
        }
        return out;
    }

    @Test
    public void deferredReverseOrderRequestsKeepTheirOwnItemContext() {
        List<LLMMessage> requestA = productionLikeMessages("A的原话");
        List<LLMMessage> requestB = productionLikeMessages("B的原话");

        // 两次 chatAction 被压缩管理器延后，并逆序执行；每轮说明由闭包参数提供。
        List<Runnable> deferred = Arrays.asList(
                () -> ChatRequestContext.appendSystemBeforeLastUser(requestA, ITEM_A, TIME),
                () -> ChatRequestContext.appendSystemBeforeLastUser(requestB, ITEM_B, TIME));
        for (int i = deferred.size() - 1; i >= 0; i--) {
            deferred.get(i).run();
        }

        assertEquals(List.of("女仆设定", "这是来自手机的消息", ITEM_A, "A的原话"), texts(requestA));
        assertEquals(List.of("女仆设定", "这是来自手机的消息", ITEM_B, "B的原话"), texts(requestB));
        assertFalse(texts(requestA).contains(ITEM_B));
        assertFalse(texts(requestB).contains(ITEM_A));
    }

    @Test
    public void sharedBusConsumerDoesNotStealRequestContext() {
        String stableId = "chat-request-context-test";
        PendingSystemMessageBus.clearAll();

        List<LLMMessage> messages = productionLikeMessages("带上物品的原话");
        ChatRequestContext.appendSystemBeforeLastUser(messages, ITEM_A, TIME);

        // 另一 consumer（上线播报/离线 drain）消费公共总线，本轮说明不经过总线，必须仍在。
        PendingSystemMessageBus.queue(stableId, "与本次物品无关的播报");
        List<String> drained = PendingSystemMessageBus.drainBroadcastable(stableId);
        PendingSystemMessageBus.clearAll();

        assertEquals(List.of("与本次物品无关的播报"), drained);
        assertTrue(texts(messages).contains(ITEM_A));
        assertEquals(4, messages.size());
    }

    @Test
    public void userTextAndOrderAreUnchanged() {
        List<LLMMessage> messages = productionLikeMessages("用户原话，一字不改");
        ChatRequestContext.appendSystemBeforeLastUser(messages, ITEM_A, TIME);

        assertEquals(Role.USER, messages.get(messages.size() - 1).role());
        assertEquals("用户原话，一字不改", messages.get(messages.size() - 1).message());
        assertEquals(Role.SYSTEM, messages.get(messages.size() - 2).role());
        assertEquals(ITEM_A, messages.get(messages.size() - 2).message());
        assertEquals(TIME, messages.get(messages.size() - 2).gameTime());
    }

    @Test
    public void pureTextWithoutContextAddsNothing() {
        List<LLMMessage> messages = productionLikeMessages("纯文字");
        ChatRequestContext.appendSystemBeforeLastUser(messages, null, TIME);
        ChatRequestContext.appendSystemBeforeLastUser(messages, "   ", TIME);

        assertEquals(3, messages.size());
        assertEquals("纯文字", messages.get(messages.size() - 1).message());
    }
}
