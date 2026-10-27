package com.huhutalk.core.supply;

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
 * 「远程补给」工作模式。
 *
 * <h2>她做什么</h2>
 * 主人血量或饱食度偏低时，她（们）从自己的背包 / 隙间绑箱里挑一件<b>刚好够补</b>的食物或药水，
 * 直接用在主人身上，并在手机聊天页留一条不可领取的「已使用」气泡 —— <b>整个过程不调用 LLM</b>。
 * 只有"补给用完""主人在补给期间没被救回"以及"模式被切走做结算"这几种情况才会让她开口
 * （受设置里的「远程补给触发女仆回复」开关管）。
 *
 * <h2>为什么这个任务没有行为（brain task）</h2>
 * 补给判定是<b>服务端全局</b>的：同一玩家名下可能有多只补给女仆，而需求 2.2 要求
 * "随机挑一只女仆背包里的物品"，这个"挑"必须在玩家维度做，不能各 tick 各的。
 * 所以逻辑走 TLM 的 {@code MaidTickEvent}（每 maid）+ {@code ServerTickEvent.Post}（每玩家），
 * 见 {@link RemoteSupplyHook} / {@link RemoteSupplyService}。
 * <p>{@link #createBrainTasks} 因此返回空表：她保留 TLM 的核心脑行为（跟随、随机走动等），
 * 不会因为多了这个模式而变得不会走路。
 */
public class TaskRemoteSupply implements IMaidTask {

    public static final ResourceLocation UID =
            new net.minecraft.resources.ResourceLocation(HuhuTalk.MOD_ID, "remote_supply");

    /** 单例：任务本质无状态，状态都在 {@link RemoteSupplySessions} 里 */
    public static final TaskRemoteSupply INSTANCE = new TaskRemoteSupply();

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 图标：金苹果（需求 2.1 指定） */
    @Override
    public ItemStack getIcon() {
        return Items.GOLDEN_APPLE.getDefaultInstance();
    }

    @Override
    @Nullable
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回【可变】列表（TLM 建脑时会改写它）。这里刻意为空：本模式的逻辑不在 AI 行为里，
        // 而在 MaidTickEvent / ServerTickEvent 上（见类注释）。
        return Lists.newArrayList();
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return true; // 补给是"待命"型工作，允许她正常张望走动
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return true;
    }

    @Override
    public boolean enableEating(EntityMaid maid) {
        return true;
    }

    @Override
    public boolean isHidden(EntityMaid maid) {
        return false; // 玩家/LLM 都应当能选到它
    }

    /**
     * 给 LLM 看的任务摘要。
     * <p>{@code switch_work_task} 工具的 task_id 参数说明就是用这个字段生成的，
     * 所以这里必须写清"这个模式能干什么"，模型才认得出该在什么时候切过来。
     */
    @Override
    public String getMaidActionSummary() {
        return "Remote supply: while in this mode she automatically feeds or heals her owner with "
                + "food and potions from her own inventory whenever his health or hunger gets low. "
                + "No supervision needed; she records what she used and reports it later.";
    }
}
