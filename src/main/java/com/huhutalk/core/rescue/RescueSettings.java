package com.huhutalk.core.rescue;

import com.huhutalk.core.chat.MaidNarrativeBus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「药水复活叙事」玩家偏好的<b>服务端会话缓存</b>。
 *
 * <p><b>这是叙事偏好，不是功能开关</b>：即使这里返回 {@code false}，死亡登记、5 秒对账、
 * 消耗药水、死亡点复活与物品气泡仍会照常执行；关闭的只是救援相关的 LLM 播报/记忆注入。
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
 * 默认<b>开启</b>，让救援叙事开箱即用；不想烧 token 的玩家可以关掉叙事，复活本身不受影响。
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

    /** 某玩家是否开启了救援叙事；没有记录时返回默认值。实际复活不读这个值。 */
    public static boolean isEnabled(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return ENABLED.getOrDefault(playerId, DEFAULT_ENABLED);
    }

    /**
     * 统一判断某条叙事事件是否允许播报。
     *
     * <p>只有救援链路的三种事件受玩家偏好控制：
     * {@link MaidNarrativeBus.Kind#RESCUE}、{@link MaidNarrativeBus.Kind#RESCUE_LOW_POTION}、
     * {@link MaidNarrativeBus.Kind#RESCUE_STREAK_SUMMARY}。其余种类（远程补给、模式切换等）
     * 始终返回 {@code true}，避免把补给开关或救援开关串到别的链路。
     *
     * <p>返回 {@code false} 仅表示“不要为这条事件调用 LLM / 写叙事记忆”，
     * <b>不会</b>停止实际救援、消耗药水或统计记账。
     */
    public static boolean shouldNarrate(UUID playerId, MaidNarrativeBus.Kind kind) {
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case RESCUE, RESCUE_LOW_POTION, RESCUE_STREAK_SUMMARY -> isEnabled(playerId);
            default -> true;
        };
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
