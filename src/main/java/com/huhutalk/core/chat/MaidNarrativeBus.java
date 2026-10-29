package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.HuhuTalk;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.core.rescue.RescueSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆叙事总线 —— 任务 1（药水复活）× 任务 2（远程补给）的<b>协调层</b>。
 *
 * <h2>它解决什么</h2>
 * 两条链路各自都会"让女仆说一句话"。如果各自直接调 {@code handleSystemInjection}，就会出现：
 * <ul>
 *   <li><b>同一件事说两遍</b>：先"补给品用尽了"，几秒后又"我尽力了/补给也用完了"；</li>
 *   <li><b>同一时刻两条消息</b>：复活叙事和补给叙事撞在同一轮，玩家收到两段割裂的对话；</li>
 *   <li>语气/长度约束各自拼，合并时还会重复两遍。</li>
 * </ul>
 * 总线把"要她说的话"变成**事件**：按 (玩家, 女仆) 归拢、到点合并成**一次**注入，
 * 并允许更重的事件**吸收**较轻的同类事件（见 {@link Kind} 与 {@link #withdrawAny}）。
 *
 * <h2>三条规则</h2>
 * <ol>
 *   <li><b>合并</b>：同一 (玩家, 女仆) 在同一轮 flush 里到点的多个事件拼成一条系统文本，
 *       收尾语取最重的那个；共享的语气/长度约束只出现一次。</li>
 *   <li><b>挂起（hold）</b>：当一件更重要的事正在判定中（例如"他在补给期间死了，正在等救援结果"），
 *       可以先把轻的那条（"补给用尽"）按住不许播；等结论出来再决定吸收还是放出。
 *       有超时保护，不会永久卡住。</li>
 *   <li><b>吸收</b>：死亡叙事在发出前先 {@link #withdrawAny} 掉那条挂着的"补给用尽"，
 *       把事实并进自己的文本里 —— 于是<b>整件事只有一次 LLM 调用</b>。</li>
 * </ol>
 *
 * <h2>记忆通道</h2>
 * 事件提交时就<b>立刻</b>把纯事实写进 {@link PendingSystemMessageBus#queueSilent}（零 token），
 * 这样即使这条播报因为玩家掉线被丢弃，事实也不会丢。
 *
 * <h2>线程</h2>
 * 所有入口都在服务端主线程（提交来自事件钩子、flush 来自 {@code ServerTickEvent.Post}）。
 */
public final class MaidNarrativeBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuhuTalk.MOD_ID + ".narrative");

    /**
     * 事件种类。{@code priority} 越大越"重"：
     * 合并时采用最重那条的<b>收尾语</b>（因为它决定当下这一幕的语气）。
     */
    public enum Kind {
        /** 工作模式被玩家手动切走（任务 2.3.2） */
        SUPPLY_MODE_SWITCH(1),
        /** 补给品用尽（任务 2 附加点 1；延迟窗口，可被死亡叙事吸收） */
        SUPPLY_USED_UP(2),
        /** 治疗药水见底（任务 1.3） */
        RESCUE_LOW_POTION(2),
        /** 药水复活成功（任务 1） */
        RESCUE(3),
        /** 连续复活事件的收尾总结（任务 1.1；一段事件的"最后一句话"，最重） */
        RESCUE_STREAK_SUMMARY(4),
        /** 主人在补给期间没被救回（任务 2 附加点 2） */
        SUPPLY_DIED(4);

        private final int priority;

        Kind(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    private record Key(UUID playerId, String stableId) {
    }

    /** 一条待播报事件 */
    private static final class Event {
        final Kind kind;
        final String text;
        final String memoryFact;
        final String closing;
        final long dueTick;

        Event(Kind kind, String text, @Nullable String memoryFact, String closing, long dueTick) {
            this.kind = kind;
            this.text = text;
            this.memoryFact = memoryFact;
            this.closing = closing;
            this.dueTick = dueTick;
        }
    }

    /** (玩家, 女仆) → 待播报事件（按到期时间） */
    private static final Map<Key, List<Event>> PENDING = new HashMap<>();

    /** (玩家, 种类) → 挂起截止 tick：在此之前该类事件不许播（等一个更重要的结论） */
    private static final Map<Key2, Long> HELD = new HashMap<>();

    private record Key2(UUID playerId, Kind kind) {
    }

    private MaidNarrativeBus() {
    }

    // ==================== 提交 ====================

    /**
     * 提交一条待播报事件。
     *
     * @param playerId   收件玩家
     * @param stableId   开口的女仆（决定落在哪个聊天页）
     * @param kind       事件种类（决定轻重与合并/吸收关系）
     * @param systemText 要注入的系统文本（事实 + 该事件自己的引导；共享的语气/长度约束由总线追加）
     * @param memoryFact 纯事实，会立即以<b>静默</b>条目写进记忆通道；不需要则传 {@code null}
     * @param closing    收尾 USER 语（合并时取最重那条的）
     * @param delayTicks 延迟多少 tick 才允许播（0 = 下一轮 flush）
     */
    public static void submit(UUID playerId, String stableId, Kind kind, String systemText,
                              @Nullable String memoryFact, String closing, int delayTicks) {
        if (playerId == null || stableId == null || stableId.isBlank()
                || systemText == null || systemText.isBlank()) {
            return;
        }
        if (memoryFact != null && !memoryFact.isBlank()) {
            // 事实立刻进记忆：即使这次播报因玩家掉线被丢弃，她以后也仍然知道
            PendingSystemMessageBus.queueSilent(stableId, memoryFact);
        }
        long due = nowTick() + Math.max(0, delayTicks);
        PENDING.computeIfAbsent(new Key(playerId, stableId), k -> new ArrayList<>())
                .add(new Event(kind, systemText, null, closing, due));
    }

    /**
     * 挂起某玩家某类事件的播报，等一个更重要的结论（例如"他死了，正在等救援结果"）。
     * <p>超时自动释放，所以即使结论没来也不会把它卡死。
     */
    public static void hold(UUID playerId, Kind kind, int maxHoldTicks) {
        if (playerId == null || kind == null) {
            return;
        }
        HELD.put(new Key2(playerId, kind), nowTick() + Math.max(1, maxHoldTicks));
    }

    /**
     * 解除挂起：结论已经出来了（例如"他其实被救回来了 / 这次轮不到补给叙事"），
     * 让该类事件按原计划继续排队播报。
     */
    public static void release(UUID playerId, Kind kind) {
        if (playerId != null && kind != null) {
            HELD.remove(new Key2(playerId, kind));
        }
    }

    /**
     * 撤回（并丢弃）某玩家名下所有尚未播报的指定种类事件，返回是否撤到了。
     * <p>用于"更重的事件把它吸收掉"：调用方拿到 {@code true} 后，应把该事实并进自己的文本。
     */
    public static boolean withdrawAny(UUID playerId, Kind kind) {
        if (playerId == null) {
            return false;
        }
        HELD.remove(new Key2(playerId, kind));
        boolean removed = false;
        Iterator<Map.Entry<Key, List<Event>>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, List<Event>> entry = it.next();
            if (!entry.getKey().playerId().equals(playerId)) {
                continue;
            }
            List<Event> list = entry.getValue();
            if (list.removeIf(e -> e.kind == kind)) {
                removed = true;
            }
            if (list.isEmpty()) {
                it.remove();
            }
        }
        return removed;
    }

    // ==================== 每 tick 对账 ====================

    public static void tick(MinecraftServer server) {
        if (server == null || PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<Key, List<Event>>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, List<Event>> entry = it.next();
            Key key = entry.getKey();
            List<Event> list = entry.getValue();

            // 选 due / 合并之前，按此刻的玩家偏好丢弃不再允许播报的事件（救援叙事偏好）。
            // 以实际 tick 时的当前偏好为准，而非提交时的快照：关闭开关后，即便事件在关闭前
            // 已排队、尚未发送，也会在这里被丢弃，不会“漏网”播报。普通补给事件始终保留。
            list.removeIf(e -> !RescueSettings.shouldNarrate(key.playerId(), e.kind));
            if (list.isEmpty()) {
                it.remove();
                continue;
            }

            List<Event> due = new ArrayList<>();
            for (Event e : list) {
                if (e.dueTick <= now && !isHeld(key.playerId(), e.kind, now)) {
                    due.add(e);
                }
            }
            if (due.isEmpty()) {
                continue;
            }
            list.removeAll(due);
            if (list.isEmpty()) {
                it.remove();
            }
            flush(server, key, due);
        }
    }

    private static boolean isHeld(UUID playerId, Kind kind, long now) {
        Long until = HELD.get(new Key2(playerId, kind));
        if (until == null) {
            return false;
        }
        if (now >= until) {
            HELD.remove(new Key2(playerId, kind)); // 超时释放，避免永久卡住
            return false;
        }
        return true;
    }

    /** 把同一 (玩家, 女仆) 的一批事件合并成<b>一次</b>注入。 */
    private static void flush(MinecraftServer server, Key key, List<Event> due) {
        ServerPlayer player = server.getPlayerList().getPlayer(key.playerId());
        if (player == null || !player.isAlive()) {
            LOGGER.info("[HuhuTalk][叙事] 作废（玩家不在线）：player={} 事件={}",
                    key.playerId(), due.stream().map(e -> e.kind.name()).toList());
            return;
        }
        EntityMaid maid = MaidStableIdHelper.findMaid(server, key.stableId());
        if (maid == null || !maid.isAlive()) {
            LOGGER.info("[HuhuTalk][叙事] 作废（女仆不在线）：maid={} 事件={}",
                    key.stableId(), due.stream().map(e -> e.kind.name()).toList());
            return;
        }

        // 最重的那条决定收尾语
        Event heaviest = due.getFirst();
        for (Event e : due) {
            if (e.kind.priority() > heaviest.kind.priority()) {
                heaviest = e;
            }
        }
        String text = due.size() == 1 ? due.getFirst().text : mergeTexts(due);
        String closing = heaviest.closing;

        LOGGER.info("[HuhuTalk][叙事] 播报：player={} maid={} 事件={} 合并数={}",
                player.getName().getString(), key.stableId(),
                due.stream().map(e -> e.kind.name()).toList(), due.size());
        MaidChatHandler.handleSystemInjection(player, key.stableId(), text, closing, true);
    }

    /**
     * 合并多条事件文本：去掉各自尾部那份共享的语气/长度约束，最后统一追加一次。
     * <p>单条时<b>不</b>走这里 —— 保持与改动前逐字一致，避免无谓的措辞漂移。
     */
    private static String mergeTexts(List<Event> events) {
        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            String cleaned = stripSharedTail(e.text);
            if (cleaned.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(cleaned);
        }
        sb.append('\n').append(MaidNarrativeStyle.TONE_GUIDE);
        sb.append('\n').append(MaidNarrativeStyle.BREVITY_LINE);
        return sb.toString();
    }

    /** 去掉事件文本里我们统一追加的那两行（各链路都是原样 append 这两个常量）。 */
    private static String stripSharedTail(String text) {
        String out = text.replace("\n" + MaidNarrativeStyle.TONE_GUIDE, "")
                .replace("\n" + MaidNarrativeStyle.BREVITY_LINE, "")
                .replace(MaidNarrativeStyle.TONE_GUIDE, "")
                .replace(MaidNarrativeStyle.BREVITY_LINE, "");
        return out.stripTrailing();
    }

    // ==================== 清理 ====================

    /** 玩家登出：丢掉他的待播报事件（内存态，不补发）。 */
    public static void onPlayerGone(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PENDING.keySet().removeIf(k -> k.playerId().equals(playerId));
        HELD.keySet().removeIf(k -> k.playerId().equals(playerId));
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        PENDING.clear();
        HELD.clear();
    }

    // ==================== 内部 ====================

    private static long nowTick() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.getTickCount();
    }
}
