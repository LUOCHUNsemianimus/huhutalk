package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 女仆移动指令的统一入口（契约层）。
 * <p>
 * <b>近走远传</b>：同维度且水平距离 ≤ {@link #WALK_DIST} 时走路（走移动任务），
 * 否则（远距离/跨维度）<b>直接传送</b>，不再依赖 TLM 自带那套有延迟的跟随传送。
 * <ul>
 *   <li>{@link #orderCome}：让女仆到自己身边。</li>
 *   <li>{@link #orderCoordinate}：让女仆到指定坐标（可跨维度）。</li>
 * </ul>
 * <b>范围处理</b>：走路时需要临时把工作/休闲/睡觉范围调到上限（避免 TLM 跟随传送、且能走出去），
 * 到达后恢复。这些范围是全局配置，用计数管理，多只女仆移动时全部结束才恢复。
 */
public final class MaidMoveData {
    private static final String KEY = "HuhuTalkMoveTarget";
    private static final String MODE = "mode";
    private static final String MODE_PLAYER = "player";
    private static final String MODE_COORDINATE = "coordinate";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String ARRIVAL = "arrival";
    private static final String PREV_TASK = "prevTask";

    /** 同维度下"走"的最大水平距离（格），超出则传送 */
    public static final int WALK_DIST = 64;

    private static final ResourceLocation ATTACK_TASK =
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "attack");

    // ============ 临时调大范围的全局状态 ============
    private static final int TMP_WORK = 64;
    private static final int TMP_IDLE = 32;
    private static final int TMP_SLEEP = 32;
    private static boolean configModified = false;
    private static int savedWork, savedIdle, savedSleep;
    private static int activeMoves = 0;

    private MaidMoveData() {
    }

    // ============ 统一入口 ============

    /** 让女仆到自己身边（近走远传） */
    public static void orderCome(EntityMaid maid, MoveArrival arrival) {
        LivingEntity owner = maid.getOwner();
        if (owner == null || owner.isDeadOrDying() || owner.isSpectator()) {
            return;
        }
        orderMove(maid, owner.blockPosition(), owner.level().dimension(), arrival);
    }

    /** 让女仆到指定坐标（近走远传，可跨维度） */
    public static void orderCoordinate(EntityMaid maid, BlockPos pos, ResourceKey<Level> dim, MoveArrival arrival) {
        orderMove(maid, pos, dim, arrival);
    }

    /** 判定并执行：近走远传 */
    private static void orderMove(EntityMaid maid, BlockPos targetPos, ResourceKey<Level> targetDim, MoveArrival arrival) {
        if (maid == null || targetPos == null || targetDim == null) {
            return;
        }
        boolean sameDim = maid.level().dimension().equals(targetDim);

        // 可达的走路距离：TLM 限定了工作范围最高 64、空闲/睡觉最高 32。
        // 超过可达范围的目标无法"走"（否则会被跟随传送），一律传送。
        int walkLimit = WALK_DIST;
        if (sameDim && maid.getScheduleDetail() != Activity.WORK) {
            walkLimit = 32;
        }
        boolean near = sameDim && horizontalDistSq(maid, targetPos) <= (double) walkLimit * walkLimit;

        if (near) {
            // 走路：写目标 NBT + 切到移动任务
            setCoordinateTarget(maid, targetPos, arrival);
            maid.setTask(TaskMoveTo.INSTANCE);
        } else if (arrival == MoveArrival.MEET) {
            // 远距离集合点：先传送到目标点，再用移动任务进入"等待玩家"阶段
            teleportMaid(maid, targetPos, targetDim);
            setCoordinateTarget(maid, targetPos, arrival);
            maid.setTask(TaskMoveTo.INSTANCE);
        } else {
            // 传送：立即传送 + 到达收尾（不切移动任务）
            teleportAndArrive(maid, targetPos, targetDim, arrival);
        }
    }

    // ============ 走路路径（目标 NBT + 移动任务） ============

    public static boolean hasTarget(EntityMaid maid) {
        return maid.getPersistentData().contains(KEY);
    }

    /** 读取当前移动目标的到达动作（无目标时返回 BACK） */
    public static MoveArrival getArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        try {
            return MoveArrival.valueOf(tag.getString(ARRIVAL));
        } catch (IllegalArgumentException | NullPointerException e) {
            return MoveArrival.BACK;
        }
    }

    private static void setCoordinateTarget(EntityMaid maid, BlockPos pos, MoveArrival arrival) {
        CompoundTag tag = baseTag(maid, arrival);
        tag.putString(MODE, MODE_COORDINATE);
        tag.putInt(X, pos.getX());
        tag.putInt(Y, pos.getY());
        tag.putInt(Z, pos.getZ());
        maid.getPersistentData().put(KEY, tag);
    }

    /** 构造基础标签：记录移动前任务；临时调大范围 */
    private static CompoundTag baseTag(EntityMaid maid, MoveArrival arrival) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ARRIVAL, arrival.name());
        IMaidTask task = maid.getTask();
        if (task != null) {
            tag.putString(PREV_TASK, task.getUid().toString());
        }
        beginMove();
        return tag;
    }

    /** 移动任务每 tick 解析目标坐标 */
    @Nullable
    public static BlockPos resolveTarget(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return null;
        }
        String mode = tag.getString(MODE);
        if (MODE_PLAYER.equals(mode)) {
            LivingEntity owner = maid.getOwner();
            if (owner == null || owner.isDeadOrDying() || owner.isSpectator()) {
                return null;
            }
            if (owner.level() != maid.level()) {
                return null;
            }
            return owner.blockPosition();
        }
        if (!tag.contains(X) || !tag.contains(Y) || !tag.contains(Z)) {
            return null;
        }
        return new BlockPos(tag.getInt(X), tag.getInt(Y), tag.getInt(Z));
    }

    public static void clear(EntityMaid maid) {
        maid.getPersistentData().remove(KEY);
    }

    /** 走路到达/中止后收尾（读 NBT → 清理 → 恢复范围 → 切任务） */
    public static void applyArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return;
        }
        String arrival = tag.getString(ARRIVAL);
        String prevTask = tag.getString(PREV_TASK);
        clear(maid);
        endMove();
        finishArrival(maid, arrival, prevTask);
    }

    // ============ 传送路径 ============

    private static void teleportAndArrive(EntityMaid maid, BlockPos targetPos, ResourceKey<Level> targetDim, MoveArrival arrival) {
        IMaidTask prevTask = maid.getTask();
        teleportMaid(maid, targetPos, targetDim);
        String arrivalName = arrival == null ? MoveArrival.BACK.name() : arrival.name();
        finishArrival(maid, arrivalName, prevTask == null ? "" : prevTask.getUid().toString());
    }

    /** 同维度/跨维度传送到目标点，返回是否成功 */
    private static boolean teleportMaid(EntityMaid maid, BlockPos targetPos, ResourceKey<Level> targetDim) {
        if (!(maid.level() instanceof ServerLevel maidLevel)) {
            return false;
        }
        ServerLevel targetLevel = maidLevel.getServer().getLevel(targetDim);
        if (targetLevel == null) {
            return false;
        }
        double x = targetPos.getX() + 0.5;
        double z = targetPos.getZ() + 0.5;
        double y = targetPos.getY();
        if (targetLevel == maidLevel) {
            maid.teleportTo(x, y, z);
        } else {
            maid.teleportTo(targetLevel, x, y, z, Set.of(), maid.getYRot(), maid.getXRot());
        }
        return true;
    }

    /** 统一的到达收尾：关 home + 按到达动作切任务 */
    private static void finishArrival(EntityMaid maid, String arrivalName, String prevTaskUid) {
        // 关闭 home：被召唤后应能跟随主人
        maid.setHomeModeEnable(false);

        MoveArrival action;
        try {
            action = MoveArrival.valueOf(arrivalName);
        } catch (IllegalArgumentException | NullPointerException e) {
            action = MoveArrival.BACK;
        }
        switch (action) {
            case WAIT_IDLE -> maid.setTask(TaskManager.getIdleTask());
            case PROTECT -> TaskManager.findTask(ATTACK_TASK).ifPresent(maid::setTask);
            case BACK, MEET -> restoreTask(maid, prevTaskUid);
            default -> {
                // NONE：保持当前任务
            }
        }
    }

    private static void restoreTask(EntityMaid maid, String prevTaskUid) {
        if (prevTaskUid == null || prevTaskUid.isEmpty()) {
            maid.setTask(TaskManager.getIdleTask());
            return;
        }
        ResourceLocation uid = ResourceLocation.tryParse(prevTaskUid);
        if (uid == null) {
            maid.setTask(TaskManager.getIdleTask());
            return;
        }
        TaskManager.findTask(uid).ifPresentOrElse(
                maid::setTask,
                () -> maid.setTask(TaskManager.getIdleTask())
        );
    }

    private static double horizontalDistSq(EntityMaid maid, BlockPos target) {
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        return dx * dx + dz * dz;
    }

    // ============ 全局范围临时调整 ============

    private static void beginMove() {
        if (activeMoves == 0) {
            savedWork = MaidConfig.MAID_WORK_RANGE.get();
            savedIdle = MaidConfig.MAID_IDLE_RANGE.get();
            savedSleep = MaidConfig.MAID_SLEEP_RANGE.get();
            MaidConfig.MAID_WORK_RANGE.set(TMP_WORK);
            MaidConfig.MAID_IDLE_RANGE.set(TMP_IDLE);
            MaidConfig.MAID_SLEEP_RANGE.set(TMP_SLEEP);
            configModified = true;
        }
        activeMoves++;
    }

    private static void endMove() {
        if (activeMoves > 0) {
            activeMoves--;
        }
        if (activeMoves == 0 && configModified) {
            MaidConfig.MAID_WORK_RANGE.set(savedWork);
            MaidConfig.MAID_IDLE_RANGE.set(savedIdle);
            MaidConfig.MAID_SLEEP_RANGE.set(savedSleep);
            configModified = false;
        }
    }

    /** 供移动任务在"到达并驻留等待"时调用（不再需要临时大范围） */
    public static void endMovePublic() {
        endMove();
    }

    /** 服务端关闭时兜底恢复（防崩溃/异常导致配置残留） */
    public static void restoreOnServerStop() {
        if (configModified) {
            MaidConfig.MAID_WORK_RANGE.set(savedWork);
            MaidConfig.MAID_IDLE_RANGE.set(savedIdle);
            MaidConfig.MAID_SLEEP_RANGE.set(savedSleep);
            configModified = false;
            activeMoves = 0;
        }
    }
}
