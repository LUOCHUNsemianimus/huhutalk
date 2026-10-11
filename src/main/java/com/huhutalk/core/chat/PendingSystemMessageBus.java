package com.huhutalk.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待注入系统消息总线（内存）。
 *
 * <h2>两类条目，语义完全不同（别混用）</h2>
 * <ul>
 *   <li>{@link #queue(String, String) <b>可播报</b>}：需要在女仆"重新上线 / 复活"时
 *       <b>主动开口</b>说给主人听的事实。{@code OfflineMessageDeliverer} 会把它 drain 出来，
 *       拼一句"你刚刚苏醒…"的提示词，**发起一次 LLM 调用**。</li>
 *   <li>{@link #queueSilent(String, String) <b>静默事实</b>}：只作为上下文，
 *       在<b>下一次自然聊天</b>时随系统提示一起带上，<b>不会</b>触发任何 LLM 调用。</li>
 * </ul>
 *
 * <h2>为什么必须区分（踩过的坑）</h2>
 * 救援 / 补给这类链路是"<b>先即时注入一次，再把同一条事实留进记忆</b>"。
 * 如果那条记忆是可播报的，女仆复活时就会把它当成新事件再主动说一遍 ——
 * 同一件事两次 LLM 对话、两种腔调（即时那条是轻量化文案，播报那条是"苏醒"情绪化提示词）。
 * 所以：**凡是我们已经即时开口说过的事实，一律用 {@link #queueSilent}**。
 *
 * <p>不做磁盘持久化：如果服务器在注入前崩溃，系统消息丢失可接受。
 * <p>使用 {@link ConcurrentHashMap}，防御未来从 TLM 异步 AI 回调线程调用导致 CME。
 */
public class PendingSystemMessageBus {

    /**
     * 一条待注入条目。
     *
     * @param text   文本
     * @param silent {@code true} = 只在下一次自然聊天时作为上下文带上，不触发离线播报
     */
    public record Entry(String text, boolean silent) {
    }

    private static final Map<String, List<Entry>> PENDING = new ConcurrentHashMap<>();

    /** 给某女仆排队一条<b>需要她开口</b>的系统消息（离线播报会因此发起一次 LLM 调用）。 */
    public static void queue(String stableId, String message) {
        queue(stableId, message, false);
    }

    /**
     * 给某女仆排队一条<b>静默事实</b>：只在下次自然聊天时作为上下文带上，不触发任何 LLM 调用。
     * <p>用于"我们已经即时跟她说过了"的事，避免她被复活/上线时又主动说一遍。
     */
    public static void queueSilent(String stableId, String message) {
        queue(stableId, message, true);
    }

    private static void queue(String stableId, String message, boolean silent) {
        if (stableId == null || stableId.isBlank() || message == null || message.isBlank()) {
            return;
        }
        PENDING.computeIfAbsent(stableId, k -> new ArrayList<>()).add(new Entry(message, silent));
    }

    /**
     * 取走某女仆的<b>全部</b>待注入条目（含静默条目）并清空 —— 供"自然聊天"使用。
     * <p>自然聊天本来就要调 LLM，所以静默事实顺路带上，成本为零。
     */
    public static List<String> drain(String stableId) {
        List<Entry> removed = PENDING.remove(stableId);
        if (removed == null || removed.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(removed.size());
        for (Entry entry : removed) {
            out.add(entry.text());
        }
        return out;
    }

    /**
     * 只取走<b>可播报</b>的条目；静默条目原地保留（原子分割，避免与并发写入互相覆盖）。
     * <p>供 {@link OfflineMessageDeliverer} 使用 —— 它每取一条就要发起一次 LLM 调用，
     * 所以绝不能把"已经即时说过"的静默事实也拿走。
     */
    public static List<String> drainBroadcastable(String stableId) {
        if (stableId == null) {
            return new ArrayList<>();
        }
        List<String> broadcast = new ArrayList<>();
        PENDING.computeIfPresent(stableId, (k, list) -> {
            List<Entry> kept = new ArrayList<>();
            for (Entry entry : list) {
                if (entry.silent()) {
                    kept.add(entry);
                } else {
                    broadcast.add(entry.text());
                }
            }
            return kept.isEmpty() ? null : kept;
        });
        return broadcast;
    }

    /** 清空全部（服务器关闭/世界退出时可调用，防御内存泄漏） */
    public static void clearAll() {
        PENDING.clear();
    }
}
