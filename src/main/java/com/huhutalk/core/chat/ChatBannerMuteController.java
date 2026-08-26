package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 「沉浸式聊天」公屏聊天栏抑制控制器（服务端）。
 * <p>
 * 与「女仆悄悄话」（气泡抑制）独立。当玩家开启「沉浸式聊天」并通过手机发起聊天时，
 * 把对应女仆标记为"本次回复不进公屏聊天栏"，持续一段窗口覆盖整个回复周期；
 * {@code ChatBubbleManagerMixin} 据此在 {@code addLLMChatText} 里跳过
 * {@code sendSystemMessage}（左下角公屏那条），而头顶气泡不受影响。
 * <p>
 * 用 {@code Set} 式窗口管理（到期清除标记），不修改女仆任何状态，退出保护同 {@link ImmersiveBubbleController}。
 */
public final class ChatBannerMuteController {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.chat_banner_mute");

    /** 标记持续时间（tick）：默认 20 秒，覆盖回复周期 */
    private static final int MUTE_DURATION_TICKS = 20 * 20;

    /** 女仆 UUID -> 应清除标记的 tick */
    private static final Map<UUID, Long> PENDING = new HashMap<>();

    private ChatBannerMuteController() {
    }

    /** 标记某女仆本次回复不进公屏聊天栏（沉浸式聊天）。重复调用刷新到期时间。服务端主线程调用。 */
    public static void suppress(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        long expire = maid.level().getServer().getTickCount() + MUTE_DURATION_TICKS;
        PENDING.put(maid.getUUID(), expire);
    }

    /** 该女仆当前是否处于"公屏聊天栏静音"状态（由 mixin 查询） */
    public static boolean isMuted(UUID maidUuid) {
        return maidUuid != null && PENDING.containsKey(maidUuid);
    }

    /** 服务端每 tick：到点清除过期标记 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, Long>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    /** 世界停止 / 玩家登出防御：清空所有标记 */
    public static void restoreAll() {
        PENDING.clear();
    }
}
