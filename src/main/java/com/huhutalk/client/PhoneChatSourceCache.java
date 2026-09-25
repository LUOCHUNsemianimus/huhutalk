package com.huhutalk.client;

import java.util.HashSet;
import java.util.Set;

/**
 * 客户端「手机聊天来源」窗口缓存。
 * <p>
 * 由 {@code S2CPhoneChatSourcePacket} 写入，供 {@code PhoneAnimation} 的动画 predicate 读取：
 * 只有处于该窗口内的女仆才播放「使用手机」附加动画。
 * <p>
 * 之所以要服务端同步而不是客户端自行判断：动画 predicate 在客户端渲染期运行，
 * 而"这条消息是否来自手机"只有服务端知道（所有手机聊天都经 {@code MaidChatHandler}）。
 * 女仆 AI 也可能被其他通道触发（对话引导、离线消息播报等），那些不应唤起手机动画。
 */
public final class PhoneChatSourceCache {
    /** 当前处于手机来源窗口内的女仆 stableId 集合 */
    private static final Set<String> ACTIVE = new HashSet<>();

    private PhoneChatSourceCache() {
    }

    /** 标记某女仆进入/退出手机来源窗口（由网络包处理器调用）。 */
    public static void set(String stableId, boolean active) {
        if (stableId == null || stableId.isEmpty()) {
            return;
        }
        if (active) {
            ACTIVE.add(stableId);
        } else {
            ACTIVE.remove(stableId);
        }
    }

    /** 该女仆当前是否处于手机来源窗口内（动画 predicate 每帧查询，走 HashSet 无分配）。 */
    public static boolean isActive(String stableId) {
        return stableId != null && ACTIVE.contains(stableId);
    }

    /** 退出世界时清空，避免残留（服务端会在重连后重新同步）。 */
    public static void clear() {
        ACTIVE.clear();
    }
}
