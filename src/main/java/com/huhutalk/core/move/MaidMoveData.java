package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private static final String NOTIFIED = "arrivedNotified";
    /** 可选的"到点后切到的目标任务 uid"（如去农场收菜） */
    private static final String TARGET_TASK = "targetTask";

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

    /**
     * 让女仆到自己身边（近走远传）。
     * 工具调用走 {@link #queueMove}（延迟到服务端 tick 执行，脱离聊天回调上下文）。
     */
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

    /** 让女仆到指定坐标并在到点后切换到指定任务（如"去农场收菜"） */
    public static void orderCoordinateTask(EntityMaid maid, BlockPos pos, ResourceKey<Level> dim, String taskUid) {
        if (taskUid == null || taskUid.isEmpty()) {
            orderMove(maid, pos, dim, MoveArrival.HOME);
            return;
        }
        orderMove(maid, pos, dim, MoveArrival.HOME);
        // 写入目标任务字段（到点后 applyArrival 会读取并切换）
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        tag.putString(TARGET_TASK, taskUid);
        maid.getPersistentData().put(KEY, tag);
    }

    /**
     * SOS 紧急呼叫：把女仆传送到指定锚点（玩家四周的某个落点）并切战斗模式保护玩家。
     * <p>复刻"come"逻辑（近走远传），但作为紧急呼叫<b>一律直接传送</b>。
     * <b>不开启守家（home）</b>——守家会让女仆停在原地；SOS 的目的是让女仆
     * 跟在玩家身边边跟随边战斗保护。传送失败则不做任何状态改动。
     *
     * @param anchor 目标落点（由调用方按"围绕玩家四周"计算）
     */
    public static void orderSOS(EntityMaid maid, BlockPos anchor) {
        orderSOS(maid, anchor, null);
    }

    /**
     * SOS 紧急呼叫（可指定到点后的工作模式）。
     * <p>行为同 {@link #orderSOS(EntityMaid, BlockPos)}，但到点后按 {@code taskUid}
     * 切换工作模式（兼容弓兵等不同攻击方式，也支持任意工作模式）。为 null/空时默认近战 {@link #ATTACK_TASK}。
     */
    public static void orderSOS(EntityMaid maid, BlockPos anchor, String taskUid) {
        LivingEntity owner = maid.getOwner();
        if (owner == null || owner.isDeadOrDying() || owner.isSpectator()) {
            return;
        }
        // 防重入：清理上一次未完成的移动（恢复原守家 + 清 NBT + 平衡范围计数）
        cancelCurrentMove(maid);
        ResourceKey<Level> dim = owner.level().dimension();
        // 紧急呼叫直接传送，不依赖走路（内部已强制加载目标区块 + 安全落地）
        teleportMaid(maid, anchor, dim);
        // 不守家：跟随玩家 + 切工作模式保护（攻击类任务会跟随主人作战）
        maid.setHomeModeEnable(false);
        maid.clearRestriction();
        if (taskUid == null || taskUid.isEmpty()) {
            TaskManager.findTask(ATTACK_TASK).ifPresent(maid::setTask);
        } else {
            setTaskByUid(maid, taskUid);
        }
    }

    // ============ 延迟队列（供 LLM 工具使用，脱离聊天回调上下文） ============

    // CopyOnWriteArrayList：LLM 工具回调（可能非服务端线程）写入 + 服务端 tick 读取并发安全
    private static final List<PendingMove> MOVE_QUEUE = new CopyOnWriteArrayList<>();

    private record PendingMove(EntityMaid maid, BlockPos pos, ResourceKey<Level> dim,
                               MoveArrival arrival, String taskUid) {
    }

    /** 工具调用：把移动挂到队列，由服务端 tick 统一执行 */
    public static void queueMove(EntityMaid maid, BlockPos pos, ResourceKey<Level> dim, MoveArrival arrival) {
        MOVE_QUEUE.add(new PendingMove(maid, pos, dim, arrival, null));
    }

    /** 工具调用：去往某地并在到点后切到指定任务（延迟执行） */
    public static void queueMoveTask(EntityMaid maid, BlockPos pos, ResourceKey<Level> dim, String taskUid) {
        MOVE_QUEUE.add(new PendingMove(maid, pos, dim, MoveArrival.HOME, taskUid));
    }

    /** 服务端每 tick 处理延迟队列（移出聊天回调上下文后再执行，避免干扰女仆大脑重建） */
    public static void tickMoveQueue() {
        if (MOVE_QUEUE.isEmpty()) {
            return;
        }
        List<PendingMove> copy = new ArrayList<>(MOVE_QUEUE);
        MOVE_QUEUE.clear();
        for (PendingMove pm : copy) {
            if (!pm.maid().isAlive()) {
                continue;
            }
            if (pm.taskUid() != null && !pm.taskUid().isEmpty()) {
                orderCoordinateTask(pm.maid(), pm.pos(), pm.dim(), pm.taskUid());
            } else {
                orderMove(pm.maid(), pm.pos(), pm.dim(), pm.arrival());
            }
        }
    }

    /** 判定并执行：近走远传 */
    private static void orderMove(EntityMaid maid, BlockPos targetPos, ResourceKey<Level> targetDim, MoveArrival arrival) {
        if (maid == null || targetPos == null || targetDim == null) {
            return;
        }
        // 重发时继承上一次移动记录的真实原任务（否则会把"寻路"模式当作原任务，到达后切不回真正模式）
        String inheritedPrevTask = getStoredPrevTask(maid);
        // 防重入：清理上一次未完成的移动（恢复原守家 + 清 NBT + 平衡范围计数），
        // 让女仆大脑重新开始，新指令干净接管（否则等待中/进行中重发会失效或状态错乱）
        cancelCurrentMove(maid);

        boolean sameDim = maid.level().dimension().equals(targetDim);

        // 可达的走路距离：TLM 限定了工作范围最高 64、空闲/睡觉最高 32。
        // 超过可达范围的目标无法"走"（否则会被跟随传送），一律传送。
        int walkLimit = WALK_DIST;
        if (sameDim && maid.getScheduleDetail() != Activity.WORK) {
            walkLimit = 32;
        }
        boolean near = sameDim && horizontalDistSq(maid, targetPos) <= (double) walkLimit * walkLimit;

        if (near) {
            // 走路：先迁家到目标点并开启守家（停止跟随、不被旧家拉回），再写目标 NBT + 切移动任务
            if (arrival != MoveArrival.BACK) {
                relocateHomeToTarget(maid, targetPos);
            }
            setCoordinateTarget(maid, targetPos, arrival, inheritedPrevTask);
            maid.setTask(TaskMoveTo.INSTANCE);
        } else if (arrival != MoveArrival.BACK) {
            // 远距离非"过来"：先传送到目标点，再用移动任务（统一到点处理 arrival/目标任务）
            if (!teleportMaid(maid, targetPos, targetDim)) {
                // 传送失败（目标维度不存在 / 无安全落点）：不写 home、不写目标 NBT、不切任务，
                // 避免把女仆的"家"持久化到错误/不可达坐标造成永久状态错乱
                return;
            }
            relocateHomeToTarget(maid, targetPos);
            setCoordinateTarget(maid, targetPos, arrival, inheritedPrevTask);
            maid.setTask(TaskMoveTo.INSTANCE);
        } else {
            // 远距离"过来"：立即传送 + 到达收尾（不切移动任务）
            teleportAndArrive(maid, targetPos, targetDim, arrival);
        }
    }

    /** 取消当前移动：恢复原守家、清空 NBT、平衡范围计数（供防重入/新指令接管用） */
    private static void cancelCurrentMove(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return;
        }
        restoreOriginalHome(maid, tag);
        clear(maid);
        endMove();
    }

    /**
     * 把女仆的"家"迁到目标点并开启守家。
     * <p>
     * 解决"走过去"被 home/跟随拉回的问题：开启守家（停止跟随），并把 home 坐标设为目标点，
     * 这样走动途中既不会被玩家跟随拉回，也不会被旧家的守家行为拉回。
     */
    private static void relocateHomeToTarget(EntityMaid maid, BlockPos targetPos) {
        SchedulePos sp = maid.getSchedulePos();
        sp.setWorkPos(targetPos);
        sp.setIdlePos(targetPos);
        sp.setSleepPos(targetPos);
        sp.restrictTo(maid);
        maid.setHomeModeEnable(true);
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

    private static void setCoordinateTarget(EntityMaid maid, BlockPos pos, MoveArrival arrival, String inheritedPrevTask) {
        CompoundTag tag = baseTag(maid, arrival, inheritedPrevTask);
        tag.putString(MODE, MODE_COORDINATE);
        tag.putInt(X, pos.getX());
        tag.putInt(Y, pos.getY());
        tag.putInt(Z, pos.getZ());
        maid.getPersistentData().put(KEY, tag);
    }

    /** 读取当前移动 NBT 中记录的原任务（供重发时继承真实原任务） */
    private static String getStoredPrevTask(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        return tag.contains(PREV_TASK) ? tag.getString(PREV_TASK) : "";
    }

    /** 构造基础标签：记录移动前任务（重发时继承真实原任务）+ 原守家坐标；临时调大范围 */
    private static CompoundTag baseTag(EntityMaid maid, MoveArrival arrival, String inheritedPrevTask) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ARRIVAL, arrival.name());
        // 优先用继承的原任务（重发场景），否则取当前真实任务
        String prevTask = (inheritedPrevTask != null && !inheritedPrevTask.isEmpty())
                ? inheritedPrevTask
                : (maid.getTask() != null ? maid.getTask().getUid().toString() : "");
        tag.putString(PREV_TASK, prevTask);
        // 保存原守家坐标，供到达后恢复（否则守家被迁到目标点，会导致到达后跟随失效）
        saveOriginalHome(maid, tag);
        beginMove();
        return tag;
    }

    private static void saveOriginalHome(EntityMaid maid, CompoundTag tag) {
        SchedulePos sp = maid.getSchedulePos();
        savePos(tag, "ow", sp.getWorkPos());
        savePos(tag, "oi", sp.getIdlePos());
        savePos(tag, "os", sp.getSleepPos());
    }

    private static void savePos(CompoundTag tag, String prefix, BlockPos pos) {
        if (pos != null) {
            tag.putInt(prefix + "x", pos.getX());
            tag.putInt(prefix + "y", pos.getY());
            tag.putInt(prefix + "z", pos.getZ());
        }
    }

    /** 恢复原守家坐标并重设活动范围 */
    private static void restoreOriginalHome(EntityMaid maid, CompoundTag tag) {
        SchedulePos sp = maid.getSchedulePos();
        if (tag.contains("owx")) {
            sp.setWorkPos(new BlockPos(tag.getInt("owx"), tag.getInt("owy"), tag.getInt("owz")));
        }
        if (tag.contains("oix")) {
            sp.setIdlePos(new BlockPos(tag.getInt("oix"), tag.getInt("oiy"), tag.getInt("oiz")));
        }
        if (tag.contains("osx")) {
            sp.setSleepPos(new BlockPos(tag.getInt("osx"), tag.getInt("osy"), tag.getInt("osz")));
        }
        sp.restrictTo(maid);
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

    /** 到达后是否已发过"我到了"通知（避免重复刷屏） */
    public static boolean wasArrivedNotified(EntityMaid maid) {
        return maid.getPersistentData().getCompound(KEY).getBoolean(NOTIFIED);
    }

    public static void markArrivedNotified(EntityMaid maid) {
        maid.getPersistentData().getCompound(KEY).putBoolean(NOTIFIED, true);
    }

    private static MoveArrival parseArrival(String name) {
        try {
            return MoveArrival.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return MoveArrival.BACK;
        }
    }

    /** 走路到达/中止后收尾（读 NBT → 恢复守家(视动作) → 清理 → 恢复范围 → 切任务） */
    public static void applyArrival(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) {
            return;
        }
        String arrival = tag.getString(ARRIVAL);
        String prevTask = tag.getString(PREV_TASK);
        String targetTask = tag.getString(TARGET_TASK);
        MoveArrival action = parseArrival(arrival);
        // 有目标任务 / HOME / PROTECT：保持 home 在新地点（到点干活/守家）；MEET/BACK 恢复原守家（结束于跟随）
        boolean keepHomeAtLocation = !targetTask.isEmpty()
                || action == MoveArrival.HOME || action == MoveArrival.PROTECT;
        if (!keepHomeAtLocation) {
            restoreOriginalHome(maid, tag);
            maid.clearRestriction();
        }
        clear(maid);
        endMove();
        finishArrival(maid, arrival, prevTask, targetTask);
    }

    // ============ 传送路径 ============

    private static void teleportAndArrive(EntityMaid maid, BlockPos targetPos, ResourceKey<Level> targetDim, MoveArrival arrival) {
        IMaidTask prevTask = maid.getTask();
        // 传送失败（维度不存在/无安全落点）：不做任何收尾状态改动，避免任务被错误切换
        if (!teleportMaid(maid, targetPos, targetDim)) {
            return;
        }
        String arrivalName = arrival == null ? MoveArrival.BACK.name() : arrival.name();
        finishArrival(maid, arrivalName, prevTask == null ? "" : prevTask.getUid().toString(), "");
    }

    /**
     * 同维度/跨维度传送到目标点附近一个安全落点，成功返回 true。
     * <p>修复"come 远距离召不回"：旧实现把女仆传到主人<b>精确坐标</b>，一旦该处不可站立
     * （被占、在方块内、贴墙/贴边），{@code Entity#teleportTo(x,y,z)} 这个 void 变体会
     * <b>静默失败</b>，come 看似"没反应"，随后由 TLM 的 {@code teleportToOwner}
     * 用随机偏移兜底才姗姗来迟。
     * <p>这里改为：先<b>强制加载目标区块</b>，再从目标点及若干小偏移中依次挑一个
     * "有安全落地高度"的位置，用返回 boolean 的 {@code teleportTo(ServerLevel,...)} 变体
     * 执行，任一成功即返回。
     */
    private static boolean teleportMaid(EntityMaid maid, BlockPos targetPos, ResourceKey<Level> targetDim) {
        if (!(maid.level() instanceof ServerLevel maidLevel)) {
            return false;
        }
        ServerLevel targetLevel = maidLevel.getServer().getLevel(targetDim);
        if (targetLevel == null) {
            return false;
        }
        // 强制加载目标区块：getChunk 同步加载/生成，避免远距离传送到未加载区块失败
        targetLevel.getChunk(targetPos.getX() >> 4, targetPos.getZ() >> 4);

        // 目标点 + 若干小偏移，依次尝试（覆盖"目标点被占/在方块内/贴墙"的情况）
        int[][] offsets = {
                {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
        };
        for (int[] off : offsets) {
            BlockPos cand = new BlockPos(targetPos.getX() + off[0], targetPos.getY(), targetPos.getZ() + off[1]);
            BlockPos landing = findSafeLanding(targetLevel, cand);
            double x = landing.getX() + 0.5;
            double z = landing.getZ() + 0.5;
            double y = landing.getY();
            // ServerLevel 变体返回 boolean，可判断成功；同维/跨维通用
            if (maid.teleportTo(targetLevel, x, y, z, Set.of(), maid.getYRot(), maid.getXRot())) {
                return true;
            }
        }
        return false;
    }

    /** 在目标点上方找第一个可安全站立的落地高度（目标 Y 向上抬 8 格，再向下扫到实心方块上方）。
     *  <p>避免把女仆传进实心方块/压进地下；找不到则退回原目标点（调用方继续兜底）。 */
    private static BlockPos findSafeLanding(ServerLevel level, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        // 用 long 计算并钳制到世界高度范围，防止极端 Y（如 LLM 提供的极大值）在 +8 时 int 溢出为负
        long clamped = Math.min(level.getMaxBuildHeight() - 1L, (long) pos.getY() + 8);
        int top = (int) Math.max(clamped, level.getMinBuildHeight());
        for (int scan = top; scan > level.getMinBuildHeight(); scan--) {
            // 脚下是实心方块 → 站立在 (scan) 处
            if (!level.isEmptyBlock(new BlockPos(x, scan - 1, z))) {
                return new BlockPos(x, scan, z);
            }
        }
        return pos;
    }

    /** 统一的到达收尾：按到达动作处理 home 与任务切换 */
    private static void finishArrival(EntityMaid maid, String arrivalName, String prevTaskUid, String targetTaskUid) {
        // 指定了目标任务 → 到点切任务（home 开，守家干活），如"去农场收菜"
        if (targetTaskUid != null && !targetTaskUid.isEmpty()) {
            maid.setHomeModeEnable(true);
            setTaskByUid(maid, targetTaskUid);
            return;
        }
        MoveArrival action = parseArrival(arrivalName);
        switch (action) {
            case BACK, MEET -> {
                // 跟随主人
                maid.setHomeModeEnable(false);
                // 关键修复：恢复"非守家"范围，否则残留/清零的守家范围会让 MaidFollowOwnerTask
                // 用 getRestrictRadius()-2 作为触发距离——残留大半径→已到跟前也不跟随；
                // 归零→minTeleportDistance=2 导致近距离频繁随机传送而闪烁。恢复默认值则正常。
                restoreFollowRestriction(maid);
                restoreTask(maid, prevTaskUid);
            }
            case HOME -> {
                // 呆着/守家：守在新地点，不跟随
                maid.setHomeModeEnable(true);
                restoreTask(maid, prevTaskUid);
            }
            case PROTECT -> {
                // 守家防御：守在新地点 + 攻击
                maid.setHomeModeEnable(true);
                TaskManager.findTask(ATTACK_TASK).ifPresent(maid::setTask);
            }
            case WAIT_IDLE -> {
                maid.setHomeModeEnable(false);
                restoreFollowRestriction(maid);
                maid.setTask(TaskManager.getIdleTask());
            }
            default -> {
                // NONE：关闭 home，保持当前任务
                maid.setHomeModeEnable(false);
                restoreFollowRestriction(maid);
            }
        }
    }

    /**
     * 恢复"非守家（跟随）"的守家范围。
     * <p>把中心设为零点、半径设为 {@link MaidConfig#MAID_NON_HOME_RANGE}（默认 8），
     * 与 TLM 原版关闭守家时的做法一致（见 {@code SwitchFollowStateTool} / {@code MaidConfigPackage}）。
     * <p>这保证 {@code MaidFollowOwnerTask} 的触发距离 {@code getRestrictRadius()-2 = 6}、
     * {@code minTeleportDistance = 10}：女仆只在真正拉远（&gt;10 格）时随机传送，
     * 近距离正常走过去，不会因半径过小（如 0）而在 2 格边界频繁随机传送而闪烁。
     */
    private static void restoreFollowRestriction(EntityMaid maid) {
        maid.restrictTo(BlockPos.ZERO, MaidConfig.MAID_NON_HOME_RANGE.get());
    }

    /** 按任务 uid 切换工作模式（找不到则回空闲） */
    private static void setTaskByUid(EntityMaid maid, String uid) {
        if (uid == null || uid.isEmpty()) {
            maid.setTask(TaskManager.getIdleTask());
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(uid);
        if (rl == null) {
            maid.setTask(TaskManager.getIdleTask());
            return;
        }
        TaskManager.findTask(rl).ifPresentOrElse(
                maid::setTask,
                () -> maid.setTask(TaskManager.getIdleTask())
        );
    }

    private static void restoreTask(EntityMaid maid, String prevTaskUid) {
        setTaskByUid(maid, prevTaskUid);
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
