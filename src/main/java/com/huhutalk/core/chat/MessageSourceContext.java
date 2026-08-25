package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;

/**
 * 消息来源上下文。
 * <p>
 * 手机是狐狐当前唯一的聊天通道，无需区分通道；但女仆应当知道主人发来的这条消息
 * 是「通过手机」发来的，并且此刻两人之间的距离远近。本类根据玩家与女仆的实际位置，
 * 生成一句自然的、供 LLM 理解的 SYSTEM 上下文文本（不写历史、不污染用户消息）。
 * <p>
 * 距离档位（水平距离，格）：
 * <ul>
 *   <li>≤ {@link #NEAR_THRESHOLD}   → 面前</li>
 *   <li>≤ {@link #FAR_THRESHOLD}    → 附近</li>
 *   <li>&gt; {@link #FAR_THRESHOLD}  → 远处</li>
 * </ul>
 * 跨维度单独处理。阈值目前为常量，后续可视反馈改成服务端可配置。
 */
public final class MessageSourceContext {

    /** 「面前」档阈值（水平距离，格） */
    public static final int NEAR_THRESHOLD = 8;

    /** 「远处」档阈值（水平距离，格）；≤ 该值为「附近」，> 该值为「远处」 */
    public static final int FAR_THRESHOLD = 64;

    private MessageSourceContext() {
    }

    /**
     * 生成消息来源上下文文本；若玩家与女仆位置不可判定，返回空串（调用方应跳过插入）。
     */
    public static String describe(ServerPlayer player, EntityMaid maid) {
        if (player == null || maid == null) {
            return "";
        }
        // 跨维度：无法用直线距离描述
        if (!sameDimension(player, maid)) {
            return "主人是通过手机给你发的这条消息，而且主人此刻和你不在同一个维度，相隔很远。";
        }
        int dist = horizontalDistance(player, maid);
        if (dist <= NEAR_THRESHOLD) {
            return "主人是通过手机给你发的这条消息，而且主人此刻就在你身边、近在眼前。";
        }
        if (dist <= FAR_THRESHOLD) {
            return "主人是通过手机给你发的这条消息，主人此刻就在附近（约 " + dist + " 格远）。";
        }
        return "主人是通过手机给你发的这条消息，主人此刻离你很远（约 " + dist + " 格远），像是从远方的心血来潮。";
    }

    /** 是否同一维度 */
    private static boolean sameDimension(ServerPlayer player, EntityMaid maid) {
        return player.level().dimension().equals(maid.level().dimension());
    }

    /** 玩家与女仆的水平距离（忽略高度差），四舍五入为整数格 */
    private static int horizontalDistance(ServerPlayer player, EntityMaid maid) {
        double dx = player.getX() - maid.getX();
        double dz = player.getZ() - maid.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }
}
