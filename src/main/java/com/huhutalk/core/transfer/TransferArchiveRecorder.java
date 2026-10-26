package com.huhutalk.core.transfer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.core.archive.ArchiveEvent;
import com.huhutalk.core.archive.ArchiveEventKind;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveItemSummary;
import com.huhutalk.core.archive.ArchiveRole;
import com.huhutalk.core.archive.ArchiveService;
import com.huhutalk.core.archive.ArchiveSource;
import com.huhutalk.core.archive.ArchiveTransferDirection;
import com.huhutalk.core.archive.ArchiveTransferOutcome;
import com.huhutalk.core.archive.ArchiveTurnContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 物品动作与档案服务之间的小适配器。
 *
 * <p>只把真实动作完成后的 ItemStack 快照转换成不可变摘要，并调用现有
 * {@link ArchiveService}。任何归档失败都只记录日志，不回滚物品、不改变原 LLM
 * 提示或原消息通知；本类不持有收件箱，也不提供领取/重放能力。</p>
 */
public final class TransferArchiveRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.archive");

    private TransferArchiveRecorder() {
    }

    /**
     * P→M 实际转移完成后写玩家消息与物品事件。返回归档 turn 供后续系统注入回复关联；
     * 归档失败返回 null，业务照常。
     */
    public static ArchiveTurnContext recordPlayerSend(ServerPlayer player, EntityMaid maid,
                                                      String stableId, String caption,
                                                      List<ItemStack> received,
                                                      List<ItemStack> failed) {
        if (player == null || maid == null) {
            return null;
        }
        try {
            List<ArchiveItemSummary> receivedItems = summaries(received);
            List<ArchiveItemSummary> failedItems = summaries(failed);
            if (receivedItems.isEmpty() && failedItems.isEmpty()) {
                return null;
            }
            return ArchiveService.recordPlayerTransfer(player.server, player, maid, stableId,
                    caption, receivedItems, failedItems, null);
        } catch (ArchiveException exception) {
            LOGGER.error("[HuhuTalk] P→M 物品转移归档失败 stableId={}", stableId, exception);
            return null;
        }
    }

    /** M→P 已实际放入收件箱：按 data.getBatch 的实际持久化列表记录 QUEUED。 */
    public static void recordQueued(ServerPlayer player, String stableId, ArchiveTurnContext turn,
                                    String transferId, List<ItemStack> queued) {
        if (player == null || transferId == null) {
            return;
        }
        try {
            List<ArchiveItemSummary> items = summaries(queued);
            if (items.isEmpty()) {
                return;
            }
            recordChunks(player, stableId, turn, transferId,
                    ArchiveEventKind.ITEMS_SENT, ArchiveTransferDirection.MAID_TO_PLAYER,
                    ArchiveTransferOutcome.QUEUED, items,
                    turn == null ? null : turn.playerMessageId());
        } catch (ArchiveException exception) {
            LOGGER.error("[HuhuTalk] M→P 待领取事件归档失败 stableId={}, transferId={}",
                    stableId, transferId, exception);
        }
    }

    /** 领取动作完成后按逐栈真实插入数量记录 CLAIMED；剩余非空即 PARTIAL。 */
    public static void recordClaimed(ServerPlayer player, String stableId, String transferId,
                                     List<ItemStack> claimed, boolean finished) {
        if (player == null || transferId == null) {
            return;
        }
        try {
            List<ArchiveItemSummary> items = summaries(claimed);
            if (items.isEmpty()) {
                return;
            }
            recordChunks(player, stableId, null, transferId,
                    ArchiveEventKind.ITEMS_CLAIMED, ArchiveTransferDirection.MAID_TO_PLAYER,
                    finished ? ArchiveTransferOutcome.SUCCESS : ArchiveTransferOutcome.PARTIAL,
                    items, null);
        } catch (ArchiveException exception) {
            LOGGER.error("[HuhuTalk] 领取事件归档失败 stableId={}, transferId={}",
                    stableId, transferId, exception);
        }
    }

    /**
     * 物品真正丢到地上后记录 DROPPED。成功生成的栈记 SUCCESS/PARTIAL，
     * 生成失败的栈另记 FAILED，绝不把失败数量写成已生成。
     */
    public static void recordDropped(ServerPlayer player, String stableId, String transferId,
                                     List<ItemStack> dropped, List<ItemStack> failed) {
        if (player == null) {
            return;
        }
        String groupId = transferId == null ? UUID.randomUUID().toString() : transferId;
        try {
            List<ArchiveItemSummary> droppedItems = summaries(dropped);
            List<ArchiveItemSummary> failedItems = summaries(failed);
            if (!droppedItems.isEmpty()) {
                recordChunks(player, stableId, null, groupId,
                        ArchiveEventKind.ITEMS_DROPPED, ArchiveTransferDirection.MAID_TO_WORLD,
                        failedItems.isEmpty() ? ArchiveTransferOutcome.SUCCESS
                                : ArchiveTransferOutcome.PARTIAL,
                        droppedItems, null);
            }
            if (!failedItems.isEmpty()) {
                recordChunks(player, stableId, null, groupId,
                        ArchiveEventKind.ITEMS_DROPPED, ArchiveTransferDirection.MAID_TO_WORLD,
                        ArchiveTransferOutcome.FAILED, failedItems, null);
            }
        } catch (ArchiveException exception) {
            LOGGER.error("[HuhuTalk] 丢地事件归档失败 stableId={}, groupId={}",
                    stableId, groupId, exception);
        }
    }

    private static void recordChunks(ServerPlayer player, String stableId, ArchiveTurnContext turn,
                                     String transferId, ArchiveEventKind kind,
                                     ArchiveTransferDirection direction,
                                     ArchiveTransferOutcome outcome,
                                     List<ArchiveItemSummary> items, String relatedMessageId) {
        for (int start = 0; start < items.size(); start += ArchiveEvent.MAX_ITEMS) {
            int end = Math.min(start + ArchiveEvent.MAX_ITEMS, items.size());
            List<ArchiveItemSummary> chunk = List.copyOf(items.subList(start, end));
            ArchiveEvent event = ArchiveEvent.itemEvent(kind, direction, outcome, chunk, transferId);
            ArchiveService.recordTransferEvent(player.server, player, stableId, turn,
                    ArchiveRole.TOOL, ArchiveSource.ONLINE_PHONE_ITEMS, "",
                    relatedMessageId, event);
        }
    }

    private static List<ArchiveItemSummary> summaries(List<ItemStack> stacks) {
        if (stacks == null) {
            return List.of();
        }
        List<ArchiveItemSummary> items = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            items.add(new ArchiveItemSummary(id.toString(), stack.getHoverName().getString(),
                    stack.getCount(), null));
        }
        return List.copyOf(items);
    }
}
