package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * 移动任务的导航行为。
 * <p>
 * 每 tick 读取 {@link MaidMoveData} 的目标并持续重瞄（针对"来我身边"实时追踪），
 * 走完路径后根据到达动作收尾。
 */
public class MaidMoveToTask extends Behavior<EntityMaid> {
    /** 移动速度倍率（正常行走档，避免"飞"过去） */
    private static final float SPEED = 0.65f;
    /** 寻路完成距离（格） */
    private static final int STOP_DISTANCE = 2;
    /** 判定"到达"的水平距离（格），与垂直无关 */
    private static final double ARRIVE_DIST = 4.0;

    private BlockPos lastTarget;

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
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        BlockPos target = MaidMoveData.resolveTarget(maid);
        if (target == null) {
            // 目标失效（主人消失/跨维度）→ 恢复守家与原模式并停止
            MaidMoveData.applyArrival(maid);
            this.doStop(level, maid, gameTime);
            return;
        }

        // 到达判定（水平距离）
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= ARRIVE_DIST) {
            MaidMoveData.clear(maid);
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

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        this.lastTarget = null;
    }
}
