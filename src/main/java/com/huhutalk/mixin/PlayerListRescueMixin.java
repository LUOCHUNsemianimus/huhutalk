package com.huhutalk.mixin;

import com.huhutalk.core.rescue.PendingRescue;
import com.huhutalk.core.rescue.PendingRescueManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/**
 * 把本模组「主动发起的那一次重生」精确落到死亡维度/坐标/视角，同时不影响普通手动重生。
 *
 * <h2>为什么必须注入 {@code PlayerList.respawn} 内部</h2>
 * Forge 1.20.1 没有 NeoForge 的 {@code PlayerRespawnPositionEvent}/{@code DimensionTransition}。
 * 若改成"先按床位重生、再传送"，玩家会先落在重生点、还可能收到「床已被阻挡」和重生锚耗尽提示。
 * {@code respawn} 在发第一批位置包（{@code ClientboundRespawnPacket}/{@code teleport}）之前
 * 就已经确定好位置，所以在这里改是唯一不产生中间落地的位置。
 *
 * <h2>只对有待救援重定向的玩家生效</h2>
 * 每个 handler 都先 {@link PendingRescueManager#peekRespawnRedirect} 查本次重生是否有登记；
 * 没有登记（玩家自己点重生按钮）一律原样调用原方法。这样普通重生走完整原版分支。
 * peek 是只读的，真正的清理仍由 {@code MaidRescueService} 的 {@code finally} 负责。
 *
 * <h2>目标版本（1.20.1 Forge）的实际调用点</h2>
 * 已按目标映射 jar 的 {@code javap} 字节码核实：本方法内依次为
 * {@code getRespawnPosition/getRespawnAngle/isRespawnForced/getRespawnDimension}、
 * 静态 {@code Player.findRespawnPositionAndUseSpawnBlock}、新玩家 {@code moveTo}、
 * {@code setRespawnPosition}、以及唯一一次 {@code ServerLevel.getBlockState}。
 */
@Mixin(PlayerList.class)
public class PlayerListRescueMixin {

    /** 死亡点坐标重定向：让原版取床位位置时拿到死亡点。 */
    @Redirect(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos huhutalkRescueRespawnPosition(ServerPlayer oldPlayer) {
        PendingRescue rescue = huhutalkActiveRescue(oldPlayer);
        if (rescue == null) {
            return oldPlayer.getRespawnPosition();
        }
        return BlockPos.containing(rescue.x(), rescue.y(), rescue.z());
    }

    /** 死亡维度重定向：让原版选中死亡点所在的 {@link ServerLevel}。 */
    @Redirect(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getRespawnDimension()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> huhutalkRescueRespawnDimension(ServerPlayer oldPlayer) {
        PendingRescue rescue = huhutalkActiveRescue(oldPlayer);
        if (rescue == null) {
            return oldPlayer.getRespawnDimension();
        }
        return rescue.dimension();
    }

    /**
     * 跳过床位/重生锚的落地求解，直接把死亡点作为解析结果。
     * <p>只在有救援登记时返回 {@code Optional.of(死亡点)}；其他情况仍调用原静态方法。
     */
    @Redirect(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;findRespawnPositionAndUseSpawnBlock(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;FZZ)Ljava/util/Optional;"))
    private Optional<Vec3> huhutalkRescueFindSpawnPosition(ServerLevel level, BlockPos pos, float angle,
                                                           boolean forced, boolean keepEverything,
                                                           ServerPlayer oldPlayer,
                                                           boolean keepEverythingArg) {
        PendingRescue rescue = huhutalkActiveRescue(oldPlayer);
        if (rescue == null) {
            return Player.findRespawnPositionAndUseSpawnBlock(level, pos, angle, forced, keepEverything);
        }
        return Optional.of(new Vec3(rescue.x(), rescue.y(), rescue.z()));
    }

    /**
     * 用死亡点坐标与死亡视角定位新玩家。
     * <p>不能走原参数：床/重生锚分支会把朝向改成朝床外的方向，覆盖死亡瞬间的视角。
     */
    @Redirect(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;moveTo(DDDFF)V"))
    private void huhutalkRescueMoveTo(ServerPlayer newPlayer, double x, double y, double z, float yRot, float xRot,
                                      ServerPlayer oldPlayer, boolean keepEverythingArg) {
        PendingRescue rescue = huhutalkActiveRescue(oldPlayer);
        if (rescue == null) {
            newPlayer.moveTo(x, y, z, yRot, xRot);
            return;
        }
        newPlayer.moveTo(rescue.x(), rescue.y(), rescue.z(), rescue.yRot(), rescue.xRot());
    }

    /**
     * 保留玩家原本的床位/出生点登记，而不是把"死亡点"写成新的重生点。
     * <p>数据从 {@code oldPlayer} 的实际 getter 读取；这些 getter 只被 respawn 方法内的
     * Redirect 匹配，因此 handler 内部读到的仍是原字段。{@code warn=false} 不弹设置成功提示。
     */
    @Redirect(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setRespawnPosition(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;FZZ)V"))
    private void huhutalkRescueCopySpawnPosition(ServerPlayer newPlayer, ResourceKey<Level> dimension, BlockPos pos,
                                                 float angle, boolean forced, boolean warn,
                                                 ServerPlayer oldPlayer,
                                                 boolean keepEverythingArg) {
        PendingRescue rescue = huhutalkActiveRescue(oldPlayer);
        if (rescue == null) {
            newPlayer.setRespawnPosition(dimension, pos, angle, forced, warn);
            return;
        }
        newPlayer.setRespawnPosition(oldPlayer.getRespawnDimension(), oldPlayer.getRespawnPosition(),
                oldPlayer.getRespawnAngle(), oldPlayer.isRespawnForced(), false);
    }

    /**
     * 让原版的床/重生锚判定看到空气。
     * <p>死亡点恰好落在床或重生锚上时，原版会走朝向修正并发「锚已耗尽」声；
     * 这里只影响本次 respawn 的判定结果，不改真实世界方块。
     */
    @Redirect(method = "respawn(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState huhutalkRescueRespawnBlockState(ServerLevel level, BlockPos pos,
                                                      ServerPlayer oldPlayer, boolean keepEverythingArg) {
        if (huhutalkActiveRescue(oldPlayer) != null) {
            return Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(pos);
    }

    /**
     * 本次重生是否带有有效的救援重定向：登记存在、且目标维度在当前服务端存在。
     * <p>目标维度不存在时回退到原版分支，不能把玩家送进不存在的世界。
     */
    @Unique
    private static PendingRescue huhutalkActiveRescue(ServerPlayer oldPlayer) {
        if (oldPlayer == null) {
            return null;
        }
        MinecraftServer server = oldPlayer.getServer();
        if (server == null) {
            return null;
        }
        PendingRescue rescue = PendingRescueManager.peekRespawnRedirect(oldPlayer.getUUID());
        if (rescue == null || server.getLevel(rescue.dimension()) == null) {
            return null;
        }
        return rescue;
    }
}
