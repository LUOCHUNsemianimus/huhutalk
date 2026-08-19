package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig;
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
 * <b>关于守家/范围（TLM 机制）</b>：
 * <ul>
 *   <li>工作/休闲/睡觉范围是<b>全局配置</b>（{@link MaidConfig}），非每只女仆独立；</li>
 *   <li>{@code SchedulePos} 每 tick 按当前活动把女仆活动半径刷回配置值；</li>
 *   <li>TLM 的 {@code MaidFollowOwnerTask} 在女仆远离主人且活动半径小时会<b>直接传送</b>到主人身边。</li>
 * </ul>
 * 因此移动期间<b>临时把三个范围调到各自上限</b>（WORK 64 / IDLE 32 / SLEEP 32），
 * 让女仆在被召唤时"走过去"而非"被传送"。到达后恢复原配置。
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

    // ============ 临时调大范围的全局状态 ============
    private static final int TMP_WORK = 64;
    private static final int TMP_IDLE = 32;
    private static final int TMP_SLEEP = 32;
    private static boolean configModified = false;
    private static int savedWork, savedIdle, savedSleep;
    /** 当前正在进行的移动数（多只女仆同时移动时，全部结束后才恢复配置） */
    private static int activeMoves = 0;

    private MaidMoveData() {
    }

    /** 移动开始：临时调大三个范围（首次才保存原值） */
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

    /** 移动结束：最后一个移动结束后恢复原范围 */
    public static void endMove() {
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

    // ============ 目标读写 ============

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
     * 构造基础标签：记录移动前任务；临时调大范围以"走过去而非传送"。
     */
    private static CompoundTag baseTag(EntityMaid maid, MoveArrival arrival) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ARRIVAL, arrival.name());

        // 记录移动前的工作模式（到达后切回）
        IMaidTask task = maid.getTask();
        if (task != null) {
            tag.putString(PREV_TASK, task.getUid().toString());
        }

        // 临时调大范围，使跟随传送阈值变大 → 女仆走过去而非被传送
        beginMove();
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

    /** 到达/中止后收尾：恢复范围、关闭 home（可跟随），再按 arrival 切回对应任务 */
    public static void applyArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return;
        }
        String arrival = tag.getString(ARRIVAL);
        String prevTask = tag.getString(PREV_TASK);

        clear(maid);
        endMove();

        // 关闭 home：被召唤后应能跟随主人
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
