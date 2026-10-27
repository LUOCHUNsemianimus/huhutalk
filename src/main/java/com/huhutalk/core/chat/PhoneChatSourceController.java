package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.StableIdProvider;
import com.huhutalk.network.S2CPhoneChatSourcePacket;
import net.minecraft.server.MinecraftServer;
import com.huhutalk.network.transport.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 「手机聊天来源」窗口控制器（服务端）。
 * <p>
 * 手机是本模组唯一的聊天通道——所有来自手机的聊天都经
 * {@link MaidChatHandler#handleChat} / {@link MaidChatHandler#handleImageChat} 发起。
 * 本类在这些入口为该女仆开启一个「来源窗口」，并广播 {@link S2CPhoneChatSourcePacket} 给所有客户端，
 * 客户端据此播放「使用手机」附加动画。
 * <p>
 * 与 {@link ImmersiveBubbleController} / {@link ChatBannerMuteController} 同一套
 * "窗口标记 + 每 tick 对账"模式：窗口有固定时长作为兜底，请求成功/失败时提前关闭。
 * <p>
 * <b>为什么必须由服务端判定</b>：动画 predicate 运行在客户端渲染期，而"这条消息是否来自手机"
 * 只有服务端知道。女仆 AI 也可能被其他通道（如对话引导、离线消息播报）触发，
 * 那些不应唤起手机动画。
 */
public final class PhoneChatSourceController {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.phone_chat_source");

    /** 窗口兜底时长（tick）：默认 30 秒。覆盖 LLM 思考 + 回复渲染；正常路径会提前关闭。 */
    private static final int WINDOW_TICKS = 30 * 20;

    /** 女仆 UUID -> 应关闭窗口的 tick */
    private static final Map<UUID, Long> OPEN = new HashMap<>();

    private PhoneChatSourceController() {
    }

    /**
     * 开启（或刷新）某女仆的手机来源窗口，并通知所有客户端开始播放手机动画。
     * 服务端主线程调用；重复调用只刷新到期时间，不重复广播。
     */
    public static void open(EntityMaid maid) {
        if (maid == null || maid.level().getServer() == null) {
            return;
        }
        String stableId = stableIdOf(maid);
        if (stableId == null) {
            LOGGER.warn("[PhoneChatSource] open 失败：女仆 {} 尚未生成 stableId", maid.getUUID());
            return;
        }
        long expire = maid.level().getServer().getTickCount() + WINDOW_TICKS;
        boolean wasOpen = OPEN.put(maid.getUUID(), expire) != null;
        // 诊断：每一次 open 调用都打印（含"已在窗口内仅刷新"的情况）。
        // 手机聊天每来一条消息都会调这里；若这里频繁出现 wasOpen=true，说明消息在重叠到达。
        LOGGER.info("[PhoneChatSource] open 调用: stableId={} wasOpen={} (true=只刷新不重播动画)",
                stableId, wasOpen);
        if (!wasOpen) {
            broadcast(maid.level().getServer(), stableId, true);
        }
    }

    /**
     * 关闭某女仆的手机来源窗口，通知所有客户端收起手机动画（播放放下段）。
     * 幂等：窗口本未开启时不广播。
     */
    public static void close(EntityMaid maid) {
        if (maid == null || maid.level().getServer() == null) {
            return;
        }
        if (OPEN.remove(maid.getUUID()) == null) {
            return;
        }
        String stableId = stableIdOf(maid);
        if (stableId != null) {
            LOGGER.info("[PhoneChatSource] 关闭来源窗口: stableId={}", stableId);
            broadcast(maid.level().getServer(), stableId, false);
        }
    }

    /** 服务端每 tick：窗口超时兜底关闭（LLM 长时间无响应时的保护）。 */
    public static void tick(MinecraftServer server) {
        if (OPEN.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, Long>> it = OPEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() > now) {
                continue;
            }
            it.remove();
            EntityMaid maid = findMaid(server, e.getKey());
            String stableId = maid != null ? stableIdOf(maid) : null;
            if (stableId != null) {
                broadcast(server, stableId, false);
            } else {
                LOGGER.debug("[PhoneChatSource] 窗口超时但找不到女仆 {}，仅移除登记", e.getKey());
            }
        }
    }

    /** 世界停止 / 玩家登出防御：清空所有窗口（客户端随重连自会重置缓存）。 */
    public static void clearAll() {
        OPEN.clear();
    }

    /** 该女仆当前是否处于手机来源窗口内（供服务端内部判断用）。 */
    public static boolean isOpen(UUID maidUuid) {
        return maidUuid != null && OPEN.containsKey(maidUuid);
    }

    /** 广播状态到所有玩家。 */
    private static void broadcast(MinecraftServer server, String stableId, boolean active) {
        PacketDistributor.sendToAllPlayers(new S2CPhoneChatSourcePacket(stableId, active));
    }

    /** 取女仆稳定 ID；未实现 {@link StableIdProvider} 或尚未生成时返回 null。 */
    private static String stableIdOf(EntityMaid maid) {
        if (maid instanceof StableIdProvider provider) {
            return provider.getStableId();
        }
        return null;
    }

    /** 在服务端所有已加载维度里按 UUID 查找女仆实体。 */
    private static EntityMaid findMaid(MinecraftServer server, UUID uuid) {
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }
}
