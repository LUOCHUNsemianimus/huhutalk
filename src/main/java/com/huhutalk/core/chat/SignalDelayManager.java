package com.huhutalk.core.chat;

import com.huhutalk.network.S2CMaidChatResponsePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 信号延迟调度器（服务端）。
 * <p>
 * 模拟"手机信号强弱"：女仆的 AI 聊天回复在<b>发送给玩家前</b>，按<b>玩家所在维度</b>
 * 决定延迟多久，从"女仆思考出结果"到"玩家手机收到"之间形成可感知的时延：
 * <ul>
 *   <li><b>主世界</b>：满格，无延迟（立即发送）。</li>
 *   <li><b>其他维度</b>（除主世界与地狱之外，如末地/其他 mod 维度）：中等，延迟 1 秒。</li>
 *   <li><b>地狱</b>：差，延迟 2 秒。</li>
 * </ul>
 * 通过服务端 tick 队列实现延迟投递，与现有 {@link com.huhutalk.core.move.MaidMoveData#tickMoveQueue}
 * 同一套"队列 + 每 tick 对账"模式。
 * <p>
 * <b>注意</b>：仅作用于 AI 聊天回复（{@link HuhuTalkChatCallback}），系统通知（移动到达等）不走此延迟。
 */
public final class SignalDelayManager {
    /** 一秒 = 20 tick */
    private static final int TICKS_PER_SECOND = 20;
    /** 中等信号延迟：1 秒 */
    private static final int MEDIUM_DELAY_TICKS = TICKS_PER_SECOND;
    /** 差信号延迟：2 秒 */
    private static final int POOR_DELAY_TICKS = 2 * TICKS_PER_SECOND;

    /** 待投递的延迟消息（玩家在线状态在投递时再校验） */
    private static final List<DelayedSend> QUEUE = new ArrayList<>();

    private SignalDelayManager() {
    }

    /**
     * 根据玩家所在维度返回延迟 tick 数。
     * <ul>
     *   <li>主世界 → 0（满格，立即）。</li>
     *   <li>地狱 → 2 秒（差信号）。</li>
     *   <li>其余维度 → 1 秒（中等信号）。</li>
     * </ul>
     */
    public static int delayTicksFor(ServerPlayer player) {
        ResourceKey<Level> dim = player.level().dimension();
        if (dim.equals(Level.OVERWORLD)) {
            return 0;
        }
        if (dim.equals(Level.NETHER)) {
            return POOR_DELAY_TICKS;
        }
        return MEDIUM_DELAY_TICKS;
    }

    /**
     * 发送一条延迟的 AI 聊天回复。
     * <p>延迟为 0 时立即发送；否则按玩家维度推入延迟队列，由服务端 tick 到点投递。
     * 调用方需保证在服务端线程执行（与现有 {@code server.submit(...)} 一致）。
     */
    public static void sendDelayedReply(ServerPlayer player, String stableId, String text) {
        int delayTicks = delayTicksFor(player);
        if (delayTicks <= 0) {
            PacketDistributor.sendToPlayer(player, new S2CMaidChatResponsePacket(stableId, text));
            return;
        }
        MinecraftServer server = player.server;
        long dispatchTick = server.getTickCount() + delayTicks;
        QUEUE.add(new DelayedSend(dispatchTick, player, new S2CMaidChatResponsePacket(stableId, text)));
    }

    /** 服务端每 tick 处理到点的延迟消息（注册于 ServerTickEvent.Post） */
    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<DelayedSend> it = QUEUE.iterator();
        while (it.hasNext()) {
            DelayedSend send = it.next();
            if (send.dispatchTick > now) {
                continue;
            }
            it.remove();
            // 玩家已离线则不发送
            if (send.player.isAlive()) {
                PacketDistributor.sendToPlayer(send.player, send.packet);
            }
        }
    }

    /** 一条待投递的延迟消息 */
    private record DelayedSend(long dispatchTick, ServerPlayer player, S2CMaidChatResponsePacket packet) {
    }
}
