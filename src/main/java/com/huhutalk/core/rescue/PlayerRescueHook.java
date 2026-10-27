package com.huhutalk.core.rescue;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * 救援链路的服务端事件入口。
 * <p>
 * 全部为 {@code MinecraftForge.EVENT_BUS}（游戏总线）事件，且只在<b>逻辑服务端</b>触发 ——
 * 专用服务器上本类被加载时不会碰任何客户端类，多人环境安全。
 *
 * <h2>三个钩子各管什么</h2>
 * <ul>
 *   <li>{@link LivingDeathEvent}：死亡瞬间（原版 {@code Player#die} 里的
 *       {@code CommonHooks.onLivingDeath} 早于 {@code dropAllDeathLoot}）→ 做环境预判并登记待救援。
 *       注意我们<b>不取消</b>这个事件：需求是"真死一次，再被救回来"。</li>
 *   <li>{@code PlayerList.respawn} 的 Mixin Redirect：把<b>我们主动发起的那一次重生</b>
 *       重定向到死亡点。用它而不是"重生完再传送"，可以避免玩家先在重生点落地、
 *       也不会弹"床已被阻挡"之类的原生提示（Forge 1.20.1 没有对应的官方重生事件）。</li>
 *   <li>{@link TickEvent.ServerTickEvent}（END）：5 秒后对账执行。</li>
 * </ul>
 */
public class PlayerRescueHook {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        MaidRescueService.trySchedule(server, player, event.getSource());
        // 「连续复活事件」计时：<b>任何</b>死亡（含最终没救成的）都刷新 3 分钟静默计时，
        // 事件不在跑时这里是空操作（事件由"第一次成功救援"开启，不是由死亡开启）。
        RescueStreakManager.onDeath(player, server.getTickCount());
    }

    /** 玩家登出：清掉他的待救援、连续复活事件与偏好缓存，避免长开服内存累积。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PendingRescueManager.onPlayerGone(event.getEntity().getUUID());
        RescueStreakManager.onPlayerGone(event.getEntity().getUUID());
        RescueSettings.clear(event.getEntity().getUUID());
    }

    /** 服务器停止：内存态全清。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PendingRescueManager.clearAll();
        RescueStreakManager.clearAll();
        RescueSettings.clearAll();
    }

    /** 每 tick 对账待救援窗口，以及"连续 3 分钟没再死"的连续复活事件结算。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        PendingRescueManager.tick(event.getServer());
        RescueStreakManager.tick(event.getServer());
    }
}
