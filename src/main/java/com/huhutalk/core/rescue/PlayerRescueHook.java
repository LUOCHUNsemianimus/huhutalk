package com.huhutalk.core.rescue;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 救援链路的服务端事件入口。
 * <p>
 * 全部为 {@code NeoForge.EVENT_BUS}（游戏总线）事件，且只在<b>逻辑服务端</b>触发 ——
 * 专用服务器上本类被加载时不会碰任何客户端类，多人环境安全。
 *
 * <h2>三个钩子各管什么</h2>
 * <ul>
 *   <li>{@link LivingDeathEvent}：死亡瞬间（原版 {@code Player#die} 里的
 *       {@code CommonHooks.onLivingDeath} 早于 {@code dropAllDeathLoot}）→ 做环境预判并登记待救援。
 *       注意我们<b>不取消</b>这个事件：需求是"真死一次，再被救回来"。</li>
 *   <li>{@link PlayerRespawnPositionEvent}：{@code PlayerList.respawn} 内部触发的官方扩展点，
 *       用来把<b>我们主动发起的那一次重生</b>重定向到死亡点。用它而不是"重生完再传送"，
 *       可以避免玩家先在重生点落地、也不会弹"床已被阻挡"之类的原生提示。</li>
 *   <li>{@link ServerTickEvent.Post}：5 秒后对账执行。</li>
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
    }

    /**
     * 把我们发起的那一次重生落到死亡点。
     * <p>没有重定向登记的普通重生（玩家自己点按钮）在这里原样放行，不受任何影响。
     */
    @SubscribeEvent
    public void onRespawnPosition(PlayerRespawnPositionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PendingRescue rescue = PendingRescueManager.consumeRespawnRedirect(player.getUUID());
        if (rescue == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel target = server.getLevel(rescue.dimension());
        if (target == null) {
            return;
        }
        event.setDimensionTransition(new DimensionTransition(
                target,
                new Vec3(rescue.x(), rescue.y(), rescue.z()),
                Vec3.ZERO,
                rescue.yRot(),
                rescue.xRot(),
                DimensionTransition.DO_NOTHING));
        // 保留玩家原本的床位/出生点登记，不要因为我们这次定向重生而把它抹掉
        event.setCopyOriginalSpawnPosition(true);
    }

    /** 玩家登出：清掉他的待救援与统计，避免长开服内存累积。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PendingRescueManager.onPlayerGone(event.getEntity().getUUID());
    }

    /** 服务器停止：内存态全清。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PendingRescueManager.clearAll();
    }

    /** 每 tick 对账待救援窗口。 */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        PendingRescueManager.tick(event.getServer());
    }
}
