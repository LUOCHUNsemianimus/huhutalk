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
 * <b>守家处理</b>：移动期间强制开启守家模式并清空活动半径——
 * <ul>
 *   <li>守家模式开启可<b>抑制 TLM 自带跟随传送</b>（否则女仆"嗖"地传送到主人身边，失去走路感）；</li>
 *   <li>清空活动半径则允许女仆走出守家范围、真正走到目标。</li>
 * </ul>
 * 到达后关闭守家（被召唤后应跟随主人），并切回移动前的任务（或按到达动作切换）。
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
     * 构造基础标签：记录到达动作、移动前任务；并开启守家+清空活动半径以安全移动。
     */
    private static CompoundTag baseTag(EntityMaid maid, MoveArrival arrival) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ARRIVAL, arrival.name());

        // 记录移动前的工作模式（到达后切回）
        IMaidTask task = maid.getTask();
        if (task != null) {
            tag.putString(PREV_TASK, task.getUid().toString());
        }

        // 开启守家（抑制 TLM 跟随传送）+ 清空活动半径（允许走出守家范围）
        maid.setHomeModeEnable(true);
        maid.clearRestriction();
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

    /** 到达/中止后收尾：关闭守家（跟随主人），再按 arrival 切回对应任务 */
    public static void applyArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return;
        }
        String arrival = tag.getString(ARRIVAL);
        String prevTask = tag.getString(PREV_TASK);

        clear(maid);

        // 关闭守家：被召唤后应能跟随主人
        maid.setHomeModeEnable(false);

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
