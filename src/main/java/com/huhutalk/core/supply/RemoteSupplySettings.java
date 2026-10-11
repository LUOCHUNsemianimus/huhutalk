package com.huhutalk.core.supply;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「远程补给触发女仆回复」偏好的<b>服务端会话缓存</b>（需求 2.4）。
 *
 * <h2>它管什么</h2>
 * 只管"补给这条链路要不要让女仆<b>开口</b>"（= 要不要多花一次 LLM 调用）：
 * <ul>
 *   <li><b>开</b>（默认）：补给品用尽、主人在补给期间没被救回、玩家手动把模式切走 ——
 *       这三种情况会即时唤起她一次；</li>
 *   <li><b>关</b>：一律<b>不唤起</b>，但仍然把同样的事实写进记忆
 *       （{@code PendingSystemMessageBus}，零 token），下次自然聊天时她会知道。</li>
 * </ul>
 * 注意它<b>不</b>影响补给本身：补给、气泡留痕、计数都与这个开关无关
 * （与救援那套「药水复活开关 = 直接不救」不同 —— 那个开关管的是功能本身）。
 *
 * <h2>为什么是服务端缓存</h2>
 * 与 {@link com.huhutalk.core.rescue.RescueSettings} 完全同一套模式：
 * 判定发生在服务端，偏好属于客户端玩法设置，客户端每次登录 / 打开设置页 / 拨动开关各推一次，
 * 服务端因此不需要落盘。
 */
public final class RemoteSupplySettings {

    /** 尚未收到客户端同步时的默认值：开（开箱即用的沉浸感，在意 token 的玩家自行关闭） */
    private static final boolean DEFAULT_REPLY_ENABLED = true;

    private static final Map<UUID, Boolean> REPLY_ENABLED = new HashMap<>();

    private RemoteSupplySettings() {
    }

    /** 记录某玩家的偏好（客户端同步过来时调用）。 */
    public static void setReplyEnabled(UUID playerId, boolean enabled) {
        if (playerId != null) {
            REPLY_ENABLED.put(playerId, enabled);
        }
    }

    /** 某玩家是否允许补给链路唤起女仆回复；没有记录时返回默认值。 */
    public static boolean isReplyEnabled(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return REPLY_ENABLED.getOrDefault(playerId, DEFAULT_REPLY_ENABLED);
    }

    /** 玩家登出：清掉记录。 */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            REPLY_ENABLED.remove(playerId);
        }
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        REPLY_ENABLED.clear();
    }
}
