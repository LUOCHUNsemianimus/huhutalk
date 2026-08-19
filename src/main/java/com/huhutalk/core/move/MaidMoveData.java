package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 女仆移动目标的读写（契约层）。
 * <p>
 * 目标存储在女仆 persistent NBT 中，作为"移动任务"与"move_to 工具/命令"之间的共享契约。
 * <p>
 * 移动时记录：目标模式、到达动作、<b>移动前的原工作模式</b>、<b>守家状态</b>。
 * 若女仆处于守家模式，移动前会临时解除守家限制以便走动，到达后恢复。
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
    private static final String HOME_MODE = "homeMode";
    private static final String HOME_X = "hx";
    private static final String HOME_Y = "hy";
    private static final String HOME_Z = "hz";
    private static final String HOME_RADIUS = "hr";

    private static final ResourceLocation ATTACK_TASK =
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "attack");

    private MaidMoveData() {
    }

    public static boolean hasTarget(EntityMaid maid) {
        return maid.getPersistentData().contains(KEY);
    }

    /** 设置"移动到固定坐标"目标。调用方需随后切换任务到移动模式。 */
    public static void setCoordinateTarget(EntityMaid maid, BlockPos pos, MoveArrival arrival) {
        CompoundTag tag = baseTag(maid, arrival);
        tag.putString(MODE, MODE_COORDINATE);
        tag.putInt(X, pos.getX());
        tag.putInt(Y, pos.getY());
        tag.putInt(Z, pos.getZ());
        maid.getPersistentData().put(KEY, tag);
    }

    /** 设置"移动到主人身边"目标（实时追踪）。调用方需随后切换任务到移动模式。 */
    public static void setPlayerTarget(EntityMaid maid, MoveArrival arrival) {
        CompoundTag tag = baseTag(maid, arrival);
        tag.putString(MODE, MODE_PLAYER);
        maid.getPersistentData().put(KEY, tag);
    }

    /**
     * 构造基础标签：记录到达动作、原工作模式、守家状态；若在守家则临时解除限制以便走动。
     */
    private static CompoundTag baseTag(EntityMaid maid, MoveArrival arrival) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ARRIVAL, arrival.name());

        // 记录原工作模式
        IMaidTask task = maid.getTask();
        if (task != null) {
            tag.putString(PREV_TASK, task.getUid().toString());
        }

        // 记录并临时解除守家限制
        boolean home = maid.isHomeModeEnable();
        tag.putBoolean(HOME_MODE, home);
        if (home && maid.hasRestriction()) {
            BlockPos center = maid.getRestrictCenter();
            tag.putInt(HOME_X, center.getX());
            tag.putInt(HOME_Y, center.getY());
            tag.putInt(HOME_Z, center.getZ());
            tag.putFloat(HOME_RADIUS, maid.getRestrictRadius());
            maid.setHomeModeEnable(false);
            maid.clearRestriction();
        }
        return tag;
    }

    /**
     * 解析当前应前往的坐标。
     *
     * @return 目标坐标；目标失效（主人消失/跨维度暂不可走/无目标）时返回 {@code null}
     */
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
            // 跨维度暂不支持"走"，传送留待后续
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

    /** 到达/中止后收尾：恢复守家状态，再按 arrival 切回对应任务 */
    public static void applyArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return;
        }
        String arrival = tag.getString(ARRIVAL);
        String prevTask = tag.getString(PREV_TASK);
        boolean home = tag.getBoolean(HOME_MODE);

        clear(maid);

        // 恢复守家
        if (home) {
            maid.setHomeModeEnable(true);
            if (tag.contains(HOME_X)) {
                maid.restrictTo(
                        new BlockPos(tag.getInt(HOME_X), tag.getInt(HOME_Y), tag.getInt(HOME_Z)),
                        Math.max(1, Math.round(tag.getFloat(HOME_RADIUS)))
                );
            }
        }

        // 切换任务
        MoveArrival action;
        try {
            action = MoveArrival.valueOf(arrival);
        } catch (IllegalArgumentException | NullPointerException e) {
            action = MoveArrival.BACK;
        }
        switch (action) {
            case WAIT_IDLE -> maid.setTask(TaskManager.getIdleTask());
            case PROTECT -> TaskManager.findTask(ATTACK_TASK).ifPresent(maid::setTask);
            case BACK -> restoreTask(maid, prevTask);
            default -> {
                // NONE：保持"移动"模式停在原地
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
}
