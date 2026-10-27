package com.huhutalk.core.move;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.huhutalk.HuhuTalk;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * "移动"工作模式。
 * <p>
 * 由 move_to 工具/命令驱动（在写目标 NBT 后切到此模式）。本身在切换 UI 中隐藏，
 * 因为它是瞬态指令而非常驻工作。到达后按 {@link MoveArrival} 收尾。
 */
public class TaskMoveTo implements IMaidTask {
    public static final ResourceLocation UID =
            new net.minecraft.resources.ResourceLocation(HuhuTalk.MOD_ID, "move_to");

    /** 单例：任务本质无状态，状态在行为实例里 */
    public static final TaskMoveTo INSTANCE = new TaskMoveTo();

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return Items.COMPASS.getDefaultInstance();
    }

    @Override
    @Nullable
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 注意：必须返回【可变】列表，TLM 建脑时会改写此列表；返回不可变 List.of() 会抛 UnsupportedOperationException。
        // 优先级 4 高于 TLM 核心脑里的 MaidFollowOwnerTask(3)，从而在移动期间抢占它、抑制其"远离即传送"。
        return Lists.newArrayList(Pair.of(4, new MaidMoveToTask()));
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false; // 移动时不要随机走动/张望
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false; // 移动途中不因受伤而乱跑
    }

    @Override
    public boolean enableEating(EntityMaid maid) {
        return false; // 移动途中不进食
    }

    @Override
    public boolean isHidden(EntityMaid maid) {
        return true; // 瞬态指令，不在切换 UI 中手动选
    }

    @Override
    public String getMaidActionSummary() {
        return "Move to a designated coordinate or to the owner, then optionally wait or protect.";
    }
}
