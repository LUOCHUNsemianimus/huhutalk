package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * 移动任务的导航行为。
 * <p>
 * 每 tick 读取 {@link MaidMoveData} 的目标并持续重瞄（针对"来我身边"实时追踪），
 * 走完路径后根据到达动作收尾。
 * <p>
 * <b>集合点（{@link MoveArrival#MEET}）</b>：到达目标后开启守家（驻留等待），
 * 并持续检测主人是否进入目标点附近；一旦进入，关闭守家、切回移动前任务（跟随主人）。
 */
public class MaidMoveToTask extends Behavior<EntityMaid> {
    /** 移动速度倍率（正常行走档，避免"飞"过去） */
    private static final float SPEED = 0.65f;
    /** 寻路完成距离（格） */
    private static final int STOP_DISTANCE = 2;
    /** 判定"到达"的水平距离（格），与垂直无关 */
    private static final double ARRIVE_DIST = 4.0;
    /** 集合点：判定"主人到达"的水平距离（格） */
    private static final double MEET_RANGE = 8.0;
    /** 集合点驻留的活动半径（格） */
    private static final int WAIT_RADIUS = 4;

    private BlockPos lastTarget;
    /** 集合点等待中 */
    private boolean waiting = false;
    private BlockPos waitPoint;

    public MaidMoveToTask() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
        ));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return MaidMoveData.hasTarget(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidMoveData.hasTarget(maid);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        this.lastTarget = null;
        this.waiting = false;
        this.waitPoint = null;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        // 集合点等待：检测主人是否到达
        if (waiting) {
            tickWaiting(level, maid, gameTime);
            return;
        }

        BlockPos target = MaidMoveData.resolveTarget(maid);
        if (target == null) {
            // 目标失效（主人消失/跨维度）→ 恢复守家与原模式并停止
            MaidMoveData.applyArrival(maid);
            this.doStop(level, maid, gameTime);
            return;
        }

        // 到达判定（水平距离）。注意：不要先 clear，applyArrival 内部会读取标签并清理
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= ARRIVE_DIST) {
            if (MaidMoveData.getArrival(maid) == MoveArrival.MEET) {
                // 集合点：到达后驻留等待（开启守家 + 不再需要临时大范围）
                beginWaiting(maid, target);
                return;
            }
            MaidMoveData.applyArrival(maid);
            this.doStop(level, maid, gameTime);
            return;
        }

        // 持续重瞄：目标变化，或路径已走完时重新寻路
        if (lastTarget == null || !lastTarget.equals(target) || maid.getNavigation().isDone()) {
            BehaviorUtils.setWalkAndLookTargetMemories(maid, target, SPEED, STOP_DISTANCE);
            lastTarget = target;
        }
    }

    /** 集合点到达后：驻留等待玩家 */
    private void beginWaiting(EntityMaid maid, BlockPos point) {
        this.waiting = true;
        this.waitPoint = point;
        // 恢复全局范围（不再需要大范围走动）
        MaidMoveData.endMovePublic();
        // 开启守家，驻留在目标点
        maid.setHomeModeEnable(true);
        maid.restrictTo(point, WAIT_RADIUS);
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    /** 等待阶段：主人进入目标点附近 → 跟随 */
    private void tickWaiting(ServerLevel level, EntityMaid maid, long gameTime) {
        if (waitPoint == null) {
            MaidMoveData.applyArrival(maid);
            this.doStop(level, maid, gameTime);
            return;
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null || owner.isDeadOrDying() || owner.isSpectator()) {
            return; // 主人不在，继续等待
        }
        if (owner.level() != maid.level()) {
            return; // 主人不在同一维度，继续等待
        }
        double dx = waitPoint.getX() - owner.getX();
        double dz = waitPoint.getZ() - owner.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= MEET_RANGE) {
            // 主人到达 → 关闭守家 + 切回原任务（跟随主人）
            waiting = false;
            waitPoint = null;
            MaidMoveData.applyArrival(maid);
            this.doStop(level, maid, gameTime);
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        this.lastTarget = null;
        this.waiting = false;
        this.waitPoint = null;
    }
}
