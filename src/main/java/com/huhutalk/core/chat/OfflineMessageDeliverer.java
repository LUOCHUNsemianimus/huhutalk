package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.network.S2CMaidDeliveredPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线消息播报器：女仆"回来"时，把攒下的消息一次讲给她听（会发起一次 LLM 调用）。
 *
 * <h2>它只在"她确实离开过"时开口（§5.16 的核心修复）</h2>
 * 由 {@code MaidScanTicker} 在<b>状态跃迁</b>时调用 {@link #onMaidScanned}：
 * <table border="1">
 *   <tr><th>旧状态 → ONLINE</th><th>性质</th><th>措辞</th></tr>
 *   <tr><td>{@code DEAD}</td><td>{@link ReturnKind#REVIVED}</td><td>"你刚刚经历了一次死亡，并成功复活…"（<b>保持不变</b>）</td></tr>
 *   <tr><td>{@code OFFLINE}</td><td>{@link ReturnKind#LEFT_RANGE}</td><td>"你刚刚离开了信号范围…"</td></tr>
 *   <tr><td>{@code SHUTDOWN}</td><td>{@link ReturnKind#GOT_PHONE}</td><td>"主人把手机给你了…"</td></tr>
 *   <tr><td>{@code ONLINE}</td><td>—</td><td><b>不开口</b>（她一直在场；我们写记忆就是这种情况）</td></tr>
 *   <tr><td>{@code IN_CHARM}</td><td>—</td><td><b>不开口</b>（玩家把她收进魂符又放出来，玩家自己知道）</td></tr>
 * </table>
 *
 * 旧实现是"每次扫描都无条件尝试播报"，于是任何写进总线的记忆都会在 ≤5 秒内被
 * 当成"她刚苏醒"再播一遍 —— 同一件事两次 LLM 对话。现在门禁接上了。
 *
 * <h2>三条细节（都踩过）</h2>
 * <ol>
 *   <li><b>先判前提、再 drain</b>：原来先 drain、再发现"没手机"就把条目塞回<b>内存</b>总线，
 *       而离线消息队列是<b>落盘</b>的 —— 服务器一重启，玩家发给她的消息就永久丢了。</li>
 *   <li><b>没积压就不说话</b>：她只是回来了、并没有人找过她，不该为一句空话烧 token。</li>
 *   <li><b>主人不在线时保留"欠一次播报"</b>：等他上线后的下一次扫描再发，既不会丢也不会刷。</li>
 * </ol>
 */
public class OfflineMessageDeliverer {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.offline_deliverer");

    /** 「她回来了」的三种性质，决定措辞 */
    private enum ReturnKind {
        /** 死亡后复活（措辞保持不变） */
        REVIVED,
        /** 离开信号范围后又连上了 */
        LEFT_RANGE,
        /** 之前没戴手机（关机），现在戴上了 */
        GOT_PHONE
    }

    /** 一次"欠着的播报" */
    private record Due(ReturnKind kind, long tick) {
    }

    /** 欠一次播报的女仆：stableId → 欠账 */
    private static final Map<String, Due> DUE = new HashMap<>();

    /** 欠账保留上限（tick）：10 分钟还没能发出去就丢弃，避免长期占内存 */
    private static final long DUE_TTL = 10L * 60L * 20L;

    /** 积压条目超过这个数时才做一次过期清理（常态下不扫） */
    private static final int PRUNE_THRESHOLD = 64;

    private OfflineMessageDeliverer() {
    }

    /**
     * 扫描周期回调（由 {@code MaidScanTicker} 在女仆<b>已加载、存活、佩戴手机</b>时调用）。
     *
     * @param previousStatus     本次扫描<b>之前</b>联系人簿里的状态（{@code null} = 本存档第一次见到她）
     * @param firstScanOfSession 本次服务器会话的第一个扫描周期
     */
    public static void onMaidScanned(EntityMaid maid, MaidStatus previousStatus, boolean firstScanOfSession) {
        if (!(maid instanceof StableIdProvider provider)) {
            return;
        }
        String stableId = provider.getStableId();
        if (stableId == null || stableId.isBlank()) {
            return;
        }

        ReturnKind kind = kindOf(previousStatus);
        if (kind != null) {
            DUE.put(stableId, new Due(kind, nowTick(maid)));
            LOGGER.info("[OfflineDeliverer] 女仆 {} 回来了：{} → ONLINE（性质={}）",
                    stableId, previousStatus, kind);
        } else if (firstScanOfSession) {
            // 重启兜底：她在簿状态是 ONLINE，但重启前可能还有排进【落盘的】离线队列的消息。
            // 这里只挂欠账；下一句的积压判断决定到底要不要真的开口。
            DUE.put(stableId, new Due(ReturnKind.LEFT_RANGE, nowTick(maid)));
        }
        pruneIfNeeded(nowTick(maid));
        tryDeliver(maid, stableId);
    }

    /** 旧状态 → 本次"回来"的性质；不构成"回来"时返回 {@code null}（= 不开口）。 */
    private static ReturnKind kindOf(MaidStatus previous) {
        if (previous == null) {
            return null; // 首次见到（多半是新女仆）：交给 firstScan 兜底逻辑处理
        }
        return switch (previous) {
            case DEAD -> ReturnKind.REVIVED;
            case OFFLINE -> ReturnKind.LEFT_RANGE;
            case SHUTDOWN -> ReturnKind.GOT_PHONE;
            // ONLINE：她一直在场 → 不开口
            // IN_CHARM：玩家把她收进魂符又放出来，玩家自己知道 → 不开口
            case ONLINE, IN_CHARM -> null;
        };
    }

    /** 尝试把"欠的那次播报"发出去；不能发就保留欠账，等下一次扫描。 */
    private static void tryDeliver(EntityMaid maid, String stableId) {
        Due due = DUE.get(stableId);
        if (due == null) {
            return;
        }
        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            return;
        }
        // ⚠ 前提判定必须在 drain 之前（见类注释第 1 条）
        if (!maid.isAlive() || !PhoneHelper.hasPhone(maid)) {
            return;
        }
        if (!(maid.getOwner() instanceof ServerPlayer master) || !master.isAlive()) {
            return; // 主人不在线：留着欠账，等他上线后的扫描再发
        }
        if (!PendingSystemMessageBus.hasBroadcastable(stableId)
                && OfflineMessageQueue.get(server).count(stableId) <= 0) {
            DUE.remove(stableId); // 没积压：她只是回来了而已
            return;
        }

        // 先取总线里"需要她开口"的条目；没有再从落盘的离线队列取（玩家真发的消息）
        List<String> pending = PendingSystemMessageBus.drainBroadcastable(stableId);
        if (pending.isEmpty()) {
            List<OfflineMessageQueue.QueuedMessage> queued = OfflineMessageQueue.get(server).drain(stableId);
            if (!queued.isEmpty()) {
                pending = new ArrayList<>();
                for (OfflineMessageQueue.QueuedMessage msg : queued) {
                    pending.add(String.format("%s: %s", msg.playerName(), msg.message()));
                }
                LOGGER.info("[OfflineDeliverer] 从离线队列取出女仆 {} 的消息 {} 条", stableId, pending.size());
            }
        }
        if (pending.isEmpty()) {
            DUE.remove(stableId);
            return;
        }

        DUE.remove(stableId);
        LOGGER.info("[OfflineDeliverer] 主人 {} 在线，向女仆 {} 播报 {} 条（{}）",
                master.getName().getString(), stableId, pending.size(), due.kind());
        // 消息已交给女仆 → 通知主人客户端清除未送达"!"（不等女仆回复）
        PacketDistributor.sendToPlayer(master, new S2CMaidDeliveredPacket(stableId));
        MaidChatHandler.handleSystemInjection(master, stableId, buildSystemText(pending, due.kind()));
    }

    /**
     * 后台设定：把这几个概念讲清楚，免得模型自己乱补设定（需求 6）。
     * <p>三种措辞共用，只出现在这一次性的播报里，不是每条消息的常驻成本。
     */
    private static final String BACKGROUND =
            "（背景设定：你和主人靠手机联系——Pro 智能手机在任何地方都有信号；"
                    + "青春版电话手表只有待在 WiFi 方块覆盖范围内才有信号。"
                    + "手机在你身上时，你所在区块会被保持加载，所以正常情况下你不会掉线；"
                    + "只有走出 WiFi 覆盖、或者跑得太快票据没跟上时，才会短暂失联。）";

    /** 按"回来的性质"拼播报文本。 */
    private static String buildSystemText(List<String> messages, ReturnKind kind) {
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case REVIVED -> sb.append("你刚刚经历了一次死亡，并成功复活。主人在你离开期间非常担心，给你发来了 ")
                    .append(messages.size()).append(" 条消息。\n\n");
            case LEFT_RANGE -> sb.append("你刚刚离开了信号范围，没能收到消息。等你重新连上时，主人已经给你发来了 ")
                    .append(messages.size()).append(" 条消息。\n\n");
            case GOT_PHONE -> sb.append("主人把手机给你了，你现在可以给他发消息了。在那之前，他给你留了 ")
                    .append(messages.size()).append(" 条消息。\n\n");
        }
        sb.append("以下是你离开期间收到的所有消息：\n");
        for (int i = 0; i < messages.size(); i++) {
            sb.append(i + 1).append(". ").append(messages.get(i)).append('\n');
        }
        switch (kind) {
            // 死亡重逢保持原来的情绪化措辞（需求方明确"死亡场景保持不变"）
            case REVIVED -> sb.append("\n请逐条阅读这些消息，体会主人当时的心情，然后给主人一个真诚、温暖的回应。");
            case LEFT_RANGE, GOT_PHONE -> sb.append("\n给他回复吧。");
        }
        sb.append('\n').append(BACKGROUND);
        return sb.toString();
    }

    // ==================== 小工具 ====================

    private static long nowTick(EntityMaid maid) {
        MinecraftServer server = maid.level().getServer();
        return server == null ? 0L : server.getTickCount();
    }

    /** 欠账太多时清掉过期项（常态下不执行，避免每次扫描都遍历）。 */
    private static void pruneIfNeeded(long nowTick) {
        if (DUE.size() <= PRUNE_THRESHOLD) {
            return;
        }
        DUE.entrySet().removeIf(e -> nowTick - e.getValue().tick() > DUE_TTL);
    }

    /** 服务器停止：清空欠账（内存态）。 */
    public static void clearAll() {
        DUE.clear();
    }
}
