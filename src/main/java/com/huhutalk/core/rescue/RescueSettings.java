package com.huhutalk.core.rescue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「药水复活」玩家偏好的<b>服务端会话缓存</b>。
 *
 * <h2>为什么是服务端缓存，而不是 SavedData</h2>
 * 判定发生在服务端（玩家死亡的那一瞬间），所以服务端必须知道这个偏好；
 * 但它是纯粹的客户端玩法偏好，没必要落一份到服务端存档：
 * <ul>
 *   <li><b>跨死亡</b>：本缓存按 UUID 存，玩家重生不会清（只有登出才清）。</li>
 *   <li><b>跨重连 / 跨重启</b>：客户端每次登录与每次打开设置页都会重新推一次
 *       （{@code ClientModEvents.pushPotionRescueSetting}），因此服务端不需要持久化。</li>
 * </ul>
 *
 * <h2>线程约定</h2>
 * {@code C2S} 包经 {@code IPayloadContext#enqueueWork} 回到服务端主线程，
 * 读写在死亡判定（同为服务端主线程）里发生，所以内部用 {@link HashMap} 即可。
 *
 * <h2>默认值</h2>
 * 客户端还没来得及同步时（例如刚进服就摔死）按 {@link #DEFAULT_ENABLED} 处理 ——
 * 默认<b>开启</b>，让功能开箱即用；不想烧 token 的玩家手动关掉。
 */
public final class RescueSettings {

    /** 尚未收到客户端同步时的默认值 */
    private static final boolean DEFAULT_ENABLED = true;

    private static final Map<UUID, Boolean> ENABLED = new HashMap<>();

    private RescueSettings() {
    }

    /** 记录某玩家的偏好（客户端同步过来时调用）。 */
    public static void set(UUID playerId, boolean enabled) {
        if (playerId != null) {
            ENABLED.put(playerId, enabled);
        }
    }

    /** 某玩家是否开启了药水复活；没有记录时返回默认值。 */
    public static boolean isEnabled(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return ENABLED.getOrDefault(playerId, DEFAULT_ENABLED);
    }

    /** 玩家登出：清掉他的记录，避免长开服上累积。 */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            ENABLED.remove(playerId);
        }
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        ENABLED.clear();
    }
}
