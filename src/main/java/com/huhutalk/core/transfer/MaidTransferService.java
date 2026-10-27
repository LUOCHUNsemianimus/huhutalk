package com.huhutalk.core.transfer;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.github.tartaricacid.touhoulittlemaid.item.ItemWirelessIO;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
import com.huhutalk.common.SystemText;
import com.huhutalk.core.archive.ArchiveAccess;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveTransferFacts;
import com.huhutalk.core.archive.ArchiveTurnContext;
import com.huhutalk.core.chat.MaidChatHandler;
import com.huhutalk.core.chat.PendingSystemMessageBus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import com.huhutalk.network.transport.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 「传文件」服务端核心逻辑（两个方向均须在服务端线程调用）。
 * <ul>
 *   <li>M→P：女仆经 {@link MaidTransferData} 收件箱把物品发给玩家，玩家领取/整批丢地。</li>
 *   <li>P→M：玩家把自身背包物品发送给女仆，只有女仆可用背包放得下整批才成功。</li>
 * </ul>
 */
public final class MaidTransferService {
    private MaidTransferService() {
    }

    // ==================== 通用：女仆解析 ====================

    /**
     * 按 stableId 在服务端查找一只活着的女仆。找不到 / 非本人所有返回 null。
     */
    @Nullable
    public static EntityMaid findOwnedAliveMaid(MinecraftServer server, String stableId, ServerPlayer owner) {
        if (stableId == null || stableId.isBlank()) {
            return null;
        }
        EntityMaid maid = MaidStableIdHelper.findMaid(server, stableId);
        if (maid == null || !maid.isAlive()) {
            return null;
        }
        if (!maid.isOwnedBy(owner)) {
            return null;
        }
        return maid;
    }

    // ==================== M→P 领取 / 丢地 ====================

    /** 领取结果（M→P）。 */
    public static final class ClaimResult {
        /** 本次实际放入玩家背包的文件数 */
        public final int inserted;
        /** 该批剩余待领取文件数（0 = 已全部领完/已清空） */
        public final int remaining;
        /** 本批是否已完全结束（领取完 或 无空位整批丢地） */
        public final boolean finished;
        /** 是否因玩家背包无空位而把本批剩余整批丢到地上 */
        public final boolean dropped;

        public ClaimResult(int inserted, int remaining, boolean finished, boolean dropped) {
            this.inserted = inserted;
            this.remaining = remaining;
            this.finished = finished;
            this.dropped = dropped;
        }
    }

    /**
     * 玩家点击某个物品气泡领取该批文件：
     * 尽量把批内文件放入玩家背包（逐栈 {@code add}）；放不下的留下继续待领。
     * 若某次点击一个都放不下（背包满），则把本批剩余整批丢到玩家脚下并结束，防止反复点/逻辑卡死。
     */
    public static ClaimResult claim(ServerPlayer player, String stableId, String transferId) {
        if (player == null) {
            return new ClaimResult(0, 0, false, false);
        }
        boolean mayClaim;
        try {
            mayClaim = ArchiveAccess.mayRead(player.server, player, stableId);
        } catch (ArchiveException exception) {
            mayClaim = false;
        }
        if (!mayClaim) {
            // 未授权不读取/修改收件箱，也不生成成功档案。
            return new ClaimResult(0, 0, false, false);
        }
        MaidTransferData data = MaidTransferData.get(player.server);
        List<ItemStack> batch = data.getBatch(stableId, transferId);
        if (batch.isEmpty()) {
            // 重复领取空批：不写任何领取事件。
            return new ClaimResult(0, 0, true, false);
        }

        List<ItemStack> remaining = new ArrayList<>();
        List<ItemStack> claimed = new ArrayList<>();
        int inserted = 0;
        boolean anyInserted = false;
        for (ItemStack file : batch) {
            // 以玩家整体物品栏(主背包，经实体 ItemHandler capability)为容器做可堆叠插入
            IItemHandler playerInv = player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
            ItemStack original = file.copy();
            int beforeCount = original.getCount();
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(playerInv, original, false);
            ArchiveTransferFacts facts = ArchiveTransferFacts.of(beforeCount, leftover.getCount());
            if (leftover.isEmpty()) {
                // 整份放进了背包
                inserted++;
                anyInserted = true;
            } else if (leftover.getCount() != beforeCount) {
                // 部分放入（同物品补齐剩余容量）
                inserted++;
                anyInserted = true;
                remaining.add(leftover);
            } else {
                // 一个都放不进去
                remaining.add(original);
            }
            if (facts.received() > 0) {
                ItemStack received = original.copy();
                received.setCount(facts.received());
                claimed.add(received);
            }
        }

        // 一个都没能放进去 → 背包无空位：整批丢到玩家脚下并结束本批
        if (!anyInserted) {
            List<ItemStack> dropped = new ArrayList<>();
            List<ItemStack> dropFailed = new ArrayList<>();
            for (ItemStack drop : batch) {
                ItemStack snapshot = drop.copy();
                if (dropAndReport(player, drop)) {
                    dropped.add(snapshot);
                } else {
                    dropFailed.add(snapshot);
                }
            }
            data.removeBatch(stableId, transferId);
            TransferArchiveRecorder.recordDropped(player, stableId, transferId, dropped, dropFailed);
            return new ClaimResult(0, 0, true, true);
        }

        // 部分/全部放入：更新收件箱剩余
        data.setBatchRemaining(stableId, transferId, remaining);
        boolean finished = remaining.isEmpty();
        TransferArchiveRecorder.recordClaimed(player, stableId, transferId, claimed, finished);
        return new ClaimResult(inserted, remaining.size(), finished, false);
    }

    /** 在玩家脚下生成一个物品实体（丢到地上）。须服务端线程。 */
    public static void dropAtPlayerFeet(ServerPlayer player, ItemStack stack) {
        dropAndReport(player, stack);
    }

    /**
     * 真正生成掉落实体并返回是否成功；档案只能使用这个结果记录“实际生成”，
     * 不能仅因为调用了丢地就声称成功。原生成位置/拾取延迟保持不变。
     */
    private static boolean dropAndReport(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        ItemEntity entity = new ItemEntity(level,
                player.getX(), player.getY() + 0.3, player.getZ(), stack.copy());
        entity.setPickUpDelay(0);
        return level.addFreshEntity(entity);
    }

    /** 玩家删除某会话聊天记录时：把该会话收件箱里全部待领文件整批丢到玩家脚下并清空收件箱。 */
    public static void dropConversationAtFeet(ServerPlayer player, String stableId) {
        if (stableId == null) {
            return;
        }
        if (player == null) {
            return;
        }
        try {
            if (!ArchiveAccess.mayRead(player.server, player, stableId)) {
                return;
            }
        } catch (ArchiveException exception) {
            return;
        }
        MaidTransferData data = MaidTransferData.get(player.server);
        List<ItemStack> files = data.drainConversation(stableId);
        List<ItemStack> dropped = new ArrayList<>();
        List<ItemStack> dropFailed = new ArrayList<>();
        for (ItemStack f : files) {
            ItemStack snapshot = f.copy();
            if (dropAndReport(player, f)) {
                dropped.add(snapshot);
            } else {
                dropFailed.add(snapshot);
            }
        }
        TransferArchiveRecorder.recordDropped(player, stableId, null, dropped, dropFailed);
    }

    // ==================== M→P 物品来源采集 ====================

    /**
     * 尝试从女仆身上或隙间(wireless_io)绑箱取出"一份目标物品"，返回取到的栈（未取到返回 EMPTY）。
     * 顺序：女仆自身可用背包 → 手上物品 → 隙间绑箱。
     * amount <=0 表示整份（把该槽位整个栈拿走）。
     */
    public static ItemStack takeFromMaid(EntityMaid maid, ItemStack want, int amount) {
        ItemStack wantCopy = want.copy();
        if (wantCopy.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int take = amount > 0 ? amount : wantCopy.getCount();
        // 1) 女仆可用背包
        IItemHandler backpack = toHandlerOrNull(maid.getAvailableBackpackInv());
        ItemStack got = extractFromHandler(backpack, wantCopy, take);
        if (!got.isEmpty()) {
            return got;
        }
        // 2) 手中
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
            ItemStack hand = maid.getItemBySlot(slot);
            if (hand.is(wantCopy.getItem()) && !hand.isEmpty()) {
                int n = Math.min(take, hand.getCount());
                ItemStack out = hand.copy();
                out.setCount(n);
                hand.shrink(n);
                return out;
            }
        }
        // 3) 隙间绑箱
        ItemStack fromGap = takeFromGap(maid, wantCopy, take);
        if (!fromGap.isEmpty()) {
            return fromGap;
        }
        return ItemStack.EMPTY;
    }

    /** 从任意 IItemHandler 中取一份匹配目标物品的栈（优先完整匹配，其次取任意格）。 */
    private static ItemStack extractFromHandler(@Nullable IItemHandler handler, ItemStack want, int amount) {
        if (handler == null || want.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack in = handler.getStackInSlot(slot);
            if (!in.isEmpty() && in.is(want.getItem())) {
                int n = Math.min(amount, in.getCount());
                ItemStack out = handler.extractItem(slot, n, false);
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 从女仆身上的隙间(wireless_io)饰品所绑定的箱子里取一份目标物品。 */
    private static ItemStack takeFromGap(EntityMaid maid, ItemStack want, int amount) {
        net.minecraftforge.items.IItemHandler baubles = maid.getMaidBauble();
        for (int slot = 0; slot < baubles.getSlots(); slot++) {
            ItemStack bauble = baubles.getStackInSlot(slot);
            if (bauble.isEmpty() || !bauble.is(InitItems.WIRELESS_IO.get())) {
                continue;
            }
            BlockPos binding = ItemWirelessIO.getBindingPos(bauble);
            if (binding == null) {
                continue;
            }
            BlockEntity be = maid.level().getBlockEntity(binding);
            if (be == null) {
                continue;
            }
            IItemHandler chest = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
            ItemStack got = extractFromHandler(chest, want, amount);
            if (!got.isEmpty()) {
                return got;
            }
        }
        return ItemStack.EMPTY;
    }

    // ==================== P→M 玩家发件给女仆 ====================

    /** P→M 发送结果。 */
    public static final class SendResult {
        public static final int OK = 0;
        /** 女仆不在线/非本人所有 */
        public static final int NO_MAID = 1;
        /** 女仆可用背包空间不足（整批失败，可重试） */
        public static final int NO_SPACE = 2;
        /** 玩家背包里找不到对应物品（数据异常/重复发送） */
        public static final int ITEM_MISSING = 3;

        public final int code;
        public final String message;
        /** 成功转移后用于关联本轮回复的归档 turn；归档失败或旧入口为 null。 */
        public final ArchiveTurnContext archiveTurn;

        public SendResult(int code, String message) {
            this(code, message, null);
        }

        public SendResult(int code, String message, ArchiveTurnContext archiveTurn) {
            this.code = code;
            this.message = message;
            this.archiveTurn = archiveTurn;
        }
    }

    /**
     * 把玩家指定槽位的物品批量发送给女仆。
     *
     * @param entries 每项 = 玩家主背包槽位 + 目标物品 + 数量。服务端以槽位为准扣取，避免客户端伪造重复。
     * @return 结果码。仅当 OK 时才会真正把物品从玩家移到女仆可用背包。
     */
    public static SendResult sendFromPlayer(ServerPlayer player, String stableId,
                                            List<PlayerSendEntry> entries) {
        return sendFromPlayer(player, stableId, entries, null);
    }

    /**
     * 带随信文字的兼容重载：实际插入后用 leftover 计算真实接收数量并写归档。
     * 原返回码与库存算法保持不变；部分/全部实际失败只在档案中如实标记。
     */
    public static SendResult sendFromPlayer(ServerPlayer player, String stableId,
                                            List<PlayerSendEntry> entries, String caption) {
        EntityMaid maid = findOwnedAliveMaid(player.server, stableId, player);
        if (maid == null) {
            return new SendResult(SendResult.NO_MAID, SystemText.of("message.huhutalk.transfer.maid_offline"));
        }
        if (entries == null || entries.isEmpty()) {
            return new SendResult(SendResult.ITEM_MISSING, SystemText.of("message.huhutalk.transfer.nothing_to_send"));
        }

        // 1) 校验玩家背包里确实还有这些物品，并组装要转移的栈
        List<ItemStack> toSend = new ArrayList<>();
        List<int[]> removals = new ArrayList<>(); // {slot, count}
        for (PlayerSendEntry e : entries) {
            int slot = e.playerSlot();
            if (slot < 0 || slot >= player.getInventory().items.size()) {
                return new SendResult(SendResult.ITEM_MISSING, SystemText.of("message.huhutalk.transfer.invalid_slot"));
            }
            ItemStack in = player.getInventory().getItem(slot);
            if (in.isEmpty() || !in.is(e.item()) || in.getCount() < e.count()) {
                return new SendResult(SendResult.ITEM_MISSING, SystemText.of("message.huhutalk.transfer.item_mismatch"));
            }
            ItemStack part = in.copy();
            part.setCount(e.count());
            toSend.add(part);
            removals.add(new int[]{slot, e.count()});
        }

        // 2) 模拟整批放入女仆可用背包：只有整批都能放得下才成功
        IItemHandler maidInv = toHandlerOrNull(maid.getAvailableBackpackInv());
        if (maidInv == null) {
            return new SendResult(SendResult.NO_SPACE, SystemText.of("message.huhutalk.transfer.no_backpack"));
        }
        for (ItemStack stack : toSend) {
            ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, stack.copy(), true);
            if (!left.isEmpty()) {
                return new SendResult(SendResult.NO_SPACE, SystemText.of("message.huhutalk.transfer.no_space"));
            }
        }

        // 3) 提交：从玩家扣取并真正放入女仆；逐栈用 leftover 计算真实接收量
        for (int i = 0; i < removals.size(); i++) {
            int[] rm = removals.get(i);
            player.getInventory().getItem(rm[0]).shrink(rm[1]);
        }
        List<ItemStack> received = new ArrayList<>();
        List<ItemStack> failed = new ArrayList<>();
        for (ItemStack stack : toSend) {
            ItemStack snapshot = stack.copy();
            int beforeCount = snapshot.getCount();
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(maidInv, stack, false);
            ArchiveTransferFacts facts = ArchiveTransferFacts.of(beforeCount, leftover.getCount());
            if (facts.received() > 0) {
                ItemStack got = snapshot.copy();
                got.setCount(facts.received());
                received.add(got);
            }
            if (facts.remaining() > 0) {
                ItemStack miss = snapshot.copy();
                miss.setCount(facts.remaining());
                failed.add(miss);
            }
        }
        ArchiveTurnContext archiveTurn = TransferArchiveRecorder.recordPlayerSend(player, maid,
                stableId, caption, received, failed);
        return new SendResult(SendResult.OK, "", archiveTurn);
    }

    /** P→M 一条：玩家主背包槽位 + 目标物品 + 数量。 */
    public record PlayerSendEntry(int playerSlot, net.minecraft.world.item.Item item, int count) {
    }

    // ==================== M→P：AI 工具取件 + 推送给玩家 ====================

    /**
     * 返回女仆全部可用于"找物品/取物品"的来源：可用背包 + 每个隙间(wireless_io)绑箱。
     */
    private static List<IItemHandler> allSourceHandlers(EntityMaid maid) {
        List<IItemHandler> list = new ArrayList<>();
        IItemHandler backpack = toHandlerOrNull(maid.getAvailableBackpackInv());
        if (backpack != null) {
            list.add(backpack);
        }
        net.minecraftforge.items.IItemHandler baubles = maid.getMaidBauble();
        for (int slot = 0; slot < baubles.getSlots(); slot++) {
            ItemStack bauble = baubles.getStackInSlot(slot);
            if (bauble.isEmpty() || !bauble.is(InitItems.WIRELESS_IO.get())) {
                continue;
            }
            BlockPos binding = ItemWirelessIO.getBindingPos(bauble);
            if (binding == null) {
                continue;
            }
            BlockEntity be = maid.level().getBlockEntity(binding);
            if (be == null) {
                continue;
            }
            IItemHandler chest = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
            if (chest != null) {
                list.add(chest);
            }
        }
        return list;
    }

    /**
     * 按 LLM 给的文字/registry id 从女仆各处解析出"要发的物品类型"。解析不到返回 null。
     */
    @Nullable
    public static net.minecraft.world.item.Item resolveItem(EntityMaid maid, String query) {
        if (query == null) {
            return null;
        }
        String raw = query.trim();
        String q = raw.toLowerCase();
        // 1) 直接是 registry id
        if (raw.contains(":")) {
            try {
                net.minecraft.resources.ResourceLocation rl = new net.minecraft.resources.ResourceLocation(raw);
                net.minecraft.world.item.Item it = BuiltInRegistries.ITEM.get(rl);
                if (it != null && it != net.minecraft.world.item.Items.AIR) {
                    return it;
                }
            } catch (Exception ignored) {
                // 解析失败落到模糊匹配
            }
        }
        // 2) 模糊匹配（registry 路径 / 显示名包含关系）
        Item best = null;
        int bestScore = -1;
        for (IItemHandler h : allSourceHandlers(maid)) {
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack st = h.getStackInSlot(s);
                if (st.isEmpty()) {
                    continue;
                }
                Item it = st.getItem();
                net.minecraft.resources.ResourceLocation id = BuiltInRegistries.ITEM.getKey(it);
                String path = id.getPath().toLowerCase();
                String hover = st.getHoverName().getString().toLowerCase();
                int score = -1;
                if (path.equals(q)) score = 6;
                else if (hover.equals(q)) score = 5;
                else if (("minecraft:" + path).equals(raw) || ("minecraft:" + path).equals(q)) score = 6;
                else if (q.contains(path) || path.contains(q)) score = 3;
                else if (q.contains(hover) || hover.contains(q)) score = 2;
                if (score > bestScore) {
                    bestScore = score;
                    best = it;
                }
            }
        }
        return best;
    }

    /**
     * 从女仆各处取下若干份目标物品，组成一批"文件"返回（已从源移除）。amount<=0 表示能取多少取多少。
     */
    public static List<ItemStack> collectItems(EntityMaid maid, net.minecraft.world.item.Item type, int amount) {
        List<ItemStack> files = new ArrayList<>();
        if (type == null) {
            return files;
        }
        int remaining = amount <= 0 ? Integer.MAX_VALUE : amount;
        for (IItemHandler h : allSourceHandlers(maid)) {
            for (int s = 0; s < h.getSlots(); s++) {
                if (remaining <= 0) {
                    return files;
                }
                ItemStack in = h.getStackInSlot(s);
                if (in.isEmpty() || !in.is(type)) {
                    continue;
                }
                int n = Math.min(remaining, in.getCount());
                ItemStack out = h.extractItem(s, n, false);
                if (!out.isEmpty()) {
                    files.add(out);
                    remaining -= out.getCount();
                }
            }
        }
        return files;
    }

    /**
     * 把一批"文件"放入收件箱并推送物品气泡给玩家。返回 true=已推送。
     */
    public static boolean pushBatchToPlayer(ServerPlayer player, String stableId, List<ItemStack> files) {
        return pushBatchToPlayer(player, stableId, files, null);
    }

    /**
     * 带可空归档 turn 的兼容入口：addBatch 成功后按 data.getBatch 的实际持久化列表
     * 记录 QUEUED，再走原预览/包发送。没有 turn 时仍记录独立事件，不伪造玩家文字。
     */
    public static boolean pushBatchToPlayer(ServerPlayer player, String stableId, List<ItemStack> files,
                                            ArchiveTurnContext archiveTurn) {
        if (player == null || stableId == null || files == null || files.isEmpty()) {
            return false;
        }
        MaidTransferData data = MaidTransferData.get(player.server);
        String tid = data.addBatch(stableId, files);
        if (tid == null) {
            return false;
        }
        List<ItemStack> queued = data.getBatch(stableId, tid);
        TransferArchiveRecorder.recordQueued(player, stableId, archiveTurn, tid, queued);
        java.util.List<com.huhutalk.phone.ItemTransfer.Preview> previews = new ArrayList<>();
        int shown = Math.min(3, files.size());
        for (int i = 0; i < shown; i++) {
            // Preview.of() 会带上完整组件，药水等"图标取决于组件"的物品才画得对
            previews.add(com.huhutalk.phone.ItemTransfer.Preview.of(files.get(i)));
        }
        PacketDistributor.sendToPlayer(player,
                new com.huhutalk.network.S2CMaidItemTransferPacket(
                        stableId, tid, previews, files.size(), files.size(), false, ""));
        return true;
    }

    /**
     * 随一条聊天消息把玩家的物品发进女仆可用背包（P→M），并让女仆知晓收到了什么以便回应。
     * 规则：整批放得下才发；放不下 / 女仆不在线未装载 / 没戴手机 / AI 不可用 → 返回错误文案（物品未动，客户端保留队列）。
     *
     * @param text      随信文字（可为空 = 仅发物品）。
     * @return {@link ChatWithItemsResult}：{@code ok()}=成功（物品已转移并已触发女仆回复），
     *         否则 {@code problem()} 是失败文案（整条未发）。成功时同时带回
     *         <b>实际转移走的物品预览</b>，供客户端生成"玩家侧文件气泡"。
     */
    public static ChatWithItemsResult trySendChatWithItems(ServerPlayer player, String stableId, String text,
                                                           List<Integer> slots, boolean bubbleMute, boolean immersive) {
        EntityMaid maid = findOwnedAliveMaid(player.server, stableId, player);
        if (maid == null) {
            return ChatWithItemsResult.failed(SystemText.of("message.huhutalk.transfer.maid_offline_not_sent"));
        }
        if (!PhoneHelper.hasPhone(maid)) {
            return ChatWithItemsResult.failed(SystemText.of("message.huhutalk.transfer.no_phone"));
        }
        MaidAIChatManager mgr = maid.getAiChatManager();
        LLMSite site = mgr.getLLMSite();
        if (site == null || !site.enabled()) {
            return ChatWithItemsResult.failed(SystemText.of("message.huhutalk.transfer.ai_disabled"));
        }
        if (text != null && text.length() > 2000) {
            return ChatWithItemsResult.failed(SystemText.of("message.huhutalk.transfer.message_too_long"));
        }

        // 组装要发的整格物品 + 给女仆看的收到物品摘要
        List<PlayerSendEntry> entries = new ArrayList<>();
        // 玩家侧文件气泡的预览：在真正转移之前抓（转移会把槽位清空）。
        // 与女仆侧 M→P 气泡同一口径：最多 3 个预览，"总数"按**格数**算（一格 = 一个"文件"）。
        List<com.huhutalk.phone.ItemTransfer.Preview> previews = new ArrayList<>();
        StringBuilder sum = new StringBuilder("主人通过手机发给你这些物品：");
        boolean first = true;
        for (int slot : slots) {
            if (slot < 0 || slot >= player.getInventory().items.size()) {
                continue;
            }
            ItemStack st = player.getInventory().getItem(slot);
            if (st.isEmpty()) {
                continue;
            }
            entries.add(new PlayerSendEntry(slot, st.getItem(), st.getCount()));
            if (previews.size() < 3) {
                previews.add(com.huhutalk.phone.ItemTransfer.Preview.of(st));
            }
            if (!first) {
                sum.append("、");
            }
            first = false;
            sum.append(st.getHoverName().getString()).append('x').append(st.getCount());
        }
        if (entries.isEmpty()) {
            return ChatWithItemsResult.failed(SystemText.of("message.huhutalk.transfer.nothing_to_send"));
        }

        // 只有女仆可用背包整批放得下才真正转移；否则不扣玩家物品
        SendResult r = sendFromPlayer(player, stableId, entries, text);
        if (r.code != SendResult.OK) {
            return ChatWithItemsResult.failed(
                    (r.message == null || r.message.isBlank())
                            ? SystemText.of("message.huhutalk.transfer.no_space_not_sent") : r.message);
        }

        // 已成功转移：让女仆在回复里感知并回应收到的物品
        String sys = sum + "。请自然地回应主人（比如表达收到、感谢，或简单评价这些物品）。";
        ArchiveTurnContext archiveTurn = r.archiveTurn;
        if (text != null && !text.isBlank()) {
            // 有文字：作为正常用户消息，同时把"收到物品"作为系统说明一并带给 AI
            PendingSystemMessageBus.queue(stableId, sys);
            MaidChatHandler.handleChat(player, stableId, text, bubbleMute, immersive, archiveTurn);
        } else {
            // 无文字：直接让女仆针对收到的物品主动回应
            MaidChatHandler.handleSystemInjection(player, stableId, sys, archiveTurn);
        }
        return ChatWithItemsResult.sent(previews, entries.size());
    }

    /**
     * {@link #trySendChatWithItems} 的结果。
     *
     * @param problem  失败文案；null = 成功
     * @param previews 实际转移走的物品预览（最多 3 个），供客户端画"玩家侧文件气泡"
     * @param total    实际转移走的格数（一格 = 一个"文件"），与女仆侧 M→P 气泡同一口径
     */
    public record ChatWithItemsResult(String problem,
                                      List<com.huhutalk.phone.ItemTransfer.Preview> previews,
                                      int total) {
        public boolean ok() {
            return problem == null;
        }

        static ChatWithItemsResult failed(String message) {
            return new ChatWithItemsResult(message, List.of(), 0);
        }

        static ChatWithItemsResult sent(List<com.huhutalk.phone.ItemTransfer.Preview> previews, int total) {
            return new ChatWithItemsResult(null, List.copyOf(previews), total);
        }
    }

    // ==================== 通用："按最优一份"取物（救援补给用） ====================

    /**
     * 救援补给专用来源列表：可用背包 → 隙间(wireless_io)绑箱 → 主副手。
     * <p>
     * 与 {@link #allSourceHandlers}（send_item 用）<b>刻意分开</b>，这样本方法的存在
     * 不会改变 send_item 的既有行为（现有 send_item 只搜背包与隙间）。
     * <p>
     * 主副手排在最后：从女仆手里把东西拿走是对她干扰最大的一种取物方式，
     * 只有在背包和隙间都找不到时才动。
     */
    private static List<IItemHandler> allRescueHandlers(EntityMaid maid) {
        List<IItemHandler> list = new ArrayList<>(allSourceHandlers(maid));
        IItemHandler hands = toHandlerOrNull(maid.getHandsInvWrapper());
        if (hands != null) {
            list.add(hands);
        }
        return list;
    }

    /**
     * 在女仆所有可交付来源里找出"打分最高"的匹配栈（<b>只读，不移除</b>）。
     * <p>用于"她到底有没有这瓶药水"这类预判。找不到返回 {@link ItemStack#EMPTY}。
     *
     * @param match 匹配条件（例如"是治疗药水"）
     * @param score 打分函数（例如"能回多少血"）；分数最高者胜出，相同分数取先遇到的
     */
    public static ItemStack findBestMatching(EntityMaid maid, Predicate<ItemStack> match,
                                             @Nullable ToIntFunction<ItemStack> score) {
        if (maid == null || match == null) {
            return ItemStack.EMPTY;
        }
        ItemStack best = ItemStack.EMPTY;
        int bestScore = Integer.MIN_VALUE;
        for (IItemHandler handler : allRescueHandlers(maid)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack in = handler.getStackInSlot(slot);
                if (in.isEmpty() || !match.test(in)) {
                    continue;
                }
                int value = score == null ? 0 : score.applyAsInt(in);
                if (value > bestScore) {
                    bestScore = value;
                    best = in;
                }
            }
        }
        return best;
    }

    /**
     * 统计女仆所有可交付来源里满足条件的物品<b>总个数</b>（只读，不移除）。
     * <p>用于「她那边还剩几瓶治疗药水」这类判断（见
     * {@link com.huhutalk.core.rescue.RescueStreakManager} 的濒危提醒）。
     * 与 {@link #findBestMatching} 同样走 {@link #allRescueHandlers}（含隙间绑箱与主副手），
     * 因此同样<b>不会</b>影响 {@code send_item} 的既有取物范围。
     */
    public static int countMatching(EntityMaid maid, Predicate<ItemStack> match) {
        if (maid == null || match == null) {
            return 0;
        }
        int total = 0;
        for (IItemHandler handler : allRescueHandlers(maid)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack in = handler.getStackInSlot(slot);
                if (!in.isEmpty() && match.test(in)) {
                    total += in.getCount();
                }
            }
        }
        return total;
    }

    /**
     * 同 {@link #findBestMatching}，但会把选中的那一份<b>从来源真正取走</b>并返回（每次只取 1 个）。
     * <p>救援只需要一瓶，所以这里固定取 1，不做"取完一整栈"。
     */
    public static ItemStack takeBestMatching(EntityMaid maid, Predicate<ItemStack> match,
                                             @Nullable ToIntFunction<ItemStack> score) {
        if (maid == null || match == null) {
            return ItemStack.EMPTY;
        }
        IItemHandler bestHandler = null;
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (IItemHandler handler : allRescueHandlers(maid)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack in = handler.getStackInSlot(slot);
                if (in.isEmpty() || !match.test(in)) {
                    continue;
                }
                int value = score == null ? 0 : score.applyAsInt(in);
                if (value > bestScore) {
                    bestScore = value;
                    bestHandler = handler;
                    bestSlot = slot;
                }
            }
        }
        if (bestHandler == null || bestSlot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = bestHandler.extractItem(bestSlot, 1, false);
        return taken == null ? ItemStack.EMPTY : taken;
    }

    // ==================== 小工具 ====================

    @Nullable
    private static IItemHandler toHandlerOrNull(@Nullable Object maybeHandler) {
        return maybeHandler instanceof IItemHandler h ? h : null;
    }
}
