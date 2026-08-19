package com.huhutalk.core.move;

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
 * 目标存储在女仆 persistent NBT 中，作为"移动任务"与"move_to 工具/命令"之间的共享契约：
 * <ul>
 *   <li>执行层（{@code TaskMoveTo} / {@code MaidMoveToTask}）每 tick 读它、导航到目标。</li>
 *   <li>决策层（工具/命令）写它，并切到移动任务。</li>
 * </ul>
 * <p>
 * 目标模式：
 * <ul>
 *   <li>{@code player}：目标是主人（实时解析主人当前坐标，持续追踪）。</li>
 *   <li>{@code coordinate}：目标是固定坐标。</li>
 * </ul>
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

    private static final ResourceLocation ATTACK_TASK =
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "attack");

    private MaidMoveData() {
    }

    public static boolean hasTarget(EntityMaid maid) {
        return maid.getPersistentData().contains(KEY);
    }

    /** 设置"移动到固定坐标"目标，并切到移动任务前的调用顺序由调用方保证 */
    public static void setCoordinateTarget(EntityMaid maid, BlockPos pos, MoveArrival arrival) {
        CompoundTag tag = new CompoundTag();
        tag.putString(MODE, MODE_COORDINATE);
        tag.putInt(X, pos.getX());
        tag.putInt(Y, pos.getY());
        tag.putInt(Z, pos.getZ());
        tag.putString(ARRIVAL, arrival.name());
        maid.getPersistentData().put(KEY, tag);
    }

    /** 设置"移动到主人身边"目标（实时追踪） */
    public static void setPlayerTarget(EntityMaid maid, MoveArrival arrival) {
        CompoundTag tag = new CompoundTag();
        tag.putString(MODE, MODE_PLAYER);
        tag.putString(ARRIVAL, arrival.name());
        maid.getPersistentData().put(KEY, tag);
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

    /** 到达后执行动作：先清理目标，再按 arrival 切换任务 */
    public static void applyArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        String arrival = tag.getString(ARRIVAL);
        clear(maid);
        MoveArrival action;
        try {
            action = MoveArrival.valueOf(arrival);
        } catch (IllegalArgumentException | NullPointerException e) {
            action = MoveArrival.NONE;
        }
        switch (action) {
            case WAIT_IDLE -> maid.setTask(TaskManager.getIdleTask());
            case PROTECT -> TaskManager.findTask(ATTACK_TASK).ifPresent(maid::setTask);
            default -> {
                // NONE：保持"移动"模式停在原地
            }
        }
    }
}
