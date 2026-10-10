package com.huhutalk.core.rescue;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 一次「待救援」登记（纯服务端内存态，<b>不持久化</b>）。
 * <p>
 * 玩家死亡时登记，{@link PendingRescueManager} 在 {@link PendingRescueManager#DELAY_TICKS} 后
 * （服务端 tick 对账）决定是否执行救援。记录的是<b>死亡瞬间</b>的位置/朝向/维度 ——
 * 玩家在死亡界面里不会再移动，但服务端强制重生时必须知道该把人送回哪里。
 *
 * <h2>为什么只存 UUID 而不存 ServerPlayer 引用</h2>
 * 玩家（自己点重生或我们强制重生）之后，服务端会走
 * {@code PlayerList.respawn(...)} <b>重建一个 ServerPlayer 实例</b>。
 * 任何按实例缓存的引用都会在那一刻变成"上一个世界里的幽灵对象"。
 * 因此本类只持有 UUID 与纯数据，执行时一律按 UUID 重新解析玩家。
 */
public final class PendingRescue {

    private final UUID playerId;
    private final String playerName;
    private final ResourceKey<Level> dimension;
    private final double x;
    private final double y;
    private final double z;
    private final float yRot;
    private final float xRot;
    /** 死亡现场是否需要额外一瓶抗火药水（岩浆/火焰系死亡） */
    private final boolean needFireResistance;
    /**
     * 服务器原始死亡提示（例如「咲夜试图在岩浆里游泳」），供女仆叙事使用。
     * <p>在 T+0 就抓下来：{@link net.minecraft.world.damagesource.DamageSource} 不属于我们持有，
     * 而死亡提示必须逐字还原"当场发生了什么"。
     */
    private final String deathMessage;
    /** 到这一 tick（服务端 {@code getTickCount()}）就执行 */
    private final long executeAtTick;

    public PendingRescue(ServerPlayer dead, boolean needFireResistance, long executeAtTick, String deathMessage) {
        this.playerId = dead.getUUID();
        this.playerName = dead.getName().getString();
        this.dimension = dead.level().dimension();
        this.x = dead.getX();
        this.y = dead.getY();
        this.z = dead.getZ();
        this.yRot = dead.getYRot();
        this.xRot = dead.getXRot();
        this.needFireResistance = needFireResistance;
        this.deathMessage = deathMessage == null ? "" : deathMessage;
        this.executeAtTick = executeAtTick;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yRot() {
        return yRot;
    }

    public float xRot() {
        return xRot;
    }

    public boolean needFireResistance() {
        return needFireResistance;
    }

    /** 服务器原始死亡提示（可能为空串 = 取不到）。 */
    public String deathMessage() {
        return deathMessage;
    }

    public long executeAtTick() {
        return executeAtTick;
    }
}
