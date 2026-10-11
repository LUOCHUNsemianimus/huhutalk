package com.huhutalk.core.transfer;

import com.huhutalk.network.S2CMaidItemTransferPacket;
import com.huhutalk.phone.ItemTransfer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * 把「女仆刚才用掉了这件东西」推到玩家聊天页 —— 一条 <b>不可领取</b>的「已使用」气泡。
 *
 * <h2>与"传文件"的区别</h2>
 * 传文件走的是 {@code used = false}：物品进收件箱，玩家点气泡领取。
 * 本类走 {@code used = true}：药水/食物已经被直接消耗在玩家身上，没有可领取的实体，
 * 气泡只作展示，点击无反应。玩家侧因此能看见"她为我花掉了什么"。
 *
 * <h2>本类不触发任何 LLM</h2>
 * 只是 S2C 包，不写聊天历史、不调 AI —— 这正是「远程补给」（需求 2.2）要的
 * "在手机页面留下发送信息，但不触发 llm"。
 *
 * <h2>为什么必须传原始 {@link ItemStack}</h2>
 * 预览要带上 {@code POTION_CONTENTS} 等组件，客户端才画得出正确的图标与液色
 * （见交接文档 §5.6：所有药水都退化成"水瓶"就是因为丢了组件）。
 */
public final class UsedItemNotifier {

    private UsedItemNotifier() {
    }

    /**
     * 推送一条「已使用」气泡。
     *
     * @param player     收件玩家
     * @param stableId   该次使用归属的女仆 stableId（决定气泡落在哪个聊天页）
     * @param stack      <b>真正被用掉的那瓶/那份</b>物品（带完整组件）
     * @param effectText 气泡上的效果文案（如「已使用 · 恢复 8 ❤」）
     */
    public static void pushUsed(ServerPlayer player, String stableId, ItemStack stack, String effectText) {
        if (player == null || stableId == null || stack == null || stack.isEmpty()) {
            return;
        }
        List<ItemTransfer.Preview> previews = List.of(ItemTransfer.Preview.of(stack));
        PacketDistributor.sendToPlayer(player, new S2CMaidItemTransferPacket(
                stableId,
                UUID.randomUUID().toString(),
                previews,
                1,
                0,
                true,
                effectText));
    }
}
