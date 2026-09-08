package com.huhutalk.core.transfer;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.github.tartaricacid.touhoulittlemaid.item.ItemWirelessIO;
import com.huhutalk.common.MaidStableIdHelper;
import com.huhutalk.common.PhoneHelper;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
        MaidTransferData data = MaidTransferData.get(player.server);
        List<ItemStack> batch = data.getBatch(stableId, transferId);
        if (batch.isEmpty()) {
            return new ClaimResult(0, 0, true, false);
        }

        List<ItemStack> remaining = new ArrayList<>();
        int inserted = 0;
        boolean anyInserted = false;
        for (ItemStack file : batch) {
            // 以玩家整体物品栏(主背包，经实体 ItemHandler capability)为容器做可堆叠插入
            IItemHandler playerInv = player.getCapability(Capabilities.ItemHandler.ENTITY);
            ItemStack original = file.copy();
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(playerInv, original, false);
            if (leftover.isEmpty()) {
                // 整份放进了背包
                inserted++;
                anyInserted = true;
            } else if (leftover.getCount() != original.getCount()) {
                // 部分放入（同物品补齐剩余容量）
                inserted++;
                anyInserted = true;
                remaining.add(leftover);
            } else {
                // 一个都放不进去
                remaining.add(original);
            }
        }

        // 一个都没能放进去 → 背包无空位：整批丢到玩家脚下并结束本批
        if (!anyInserted) {
            for (ItemStack drop : batch) {
                dropAtPlayerFeet(player, drop);
            }
            data.removeBatch(stableId, transferId);
            return new ClaimResult(0, 0, true, true);
        }

        // 部分/全部放入：更新收件箱剩余
        data.setBatchRemaining(stableId, transferId, remaining);
        boolean finished = remaining.isEmpty();
        return new ClaimResult(inserted, remaining.size(), finished, false);
    }

    /** 在玩家脚下生成一个物品实体（丢到地上）。须服务端线程。 */
    public static void dropAtPlayerFeet(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ItemEntity entity = new ItemEntity(level,
                player.getX(), player.getY() + 0.3, player.getZ(), stack.copy());
        entity.setPickUpDelay(0);
        level.addFreshEntity(entity);
    }

    /** 玩家删除某会话聊天记录时：把该会话收件箱里全部待领文件整批丢到玩家脚下并清空收件箱。 */
    public static void dropConversationAtFeet(ServerPlayer player, String stableId) {
        if (stableId == null) {
            return;
        }
        MaidTransferData data = MaidTransferData.get(player.server);
        List<ItemStack> files = data.drainConversation(stableId);
        for (ItemStack f : files) {
            dropAtPlayerFeet(player, f);
        }
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
        net.neoforged.neoforge.items.IItemHandler baubles = maid.getMaidBauble();
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
            IItemHandler chest = maid.level().getCapability(Capabilities.ItemHandler.BLOCK, binding, null);
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

        public SendResult(int code, String message) {
            this.code = code;
            this.message = message;
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
        EntityMaid maid = findOwnedAliveMaid(player.server, stableId, player);
        if (maid == null) {
            return new SendResult(SendResult.NO_MAID, "女仆不在线或不在你名下");
        }
        if (entries == null || entries.isEmpty()) {
            return new SendResult(SendResult.ITEM_MISSING, "没有要发送的物品");
        }

        // 1) 校验玩家背包里确实还有这些物品，并组装要转移的栈
        List<ItemStack> toSend = new ArrayList<>();
        List<int[]> removals = new ArrayList<>(); // {slot, count}
        for (PlayerSendEntry e : entries) {
            int slot = e.playerSlot();
            if (slot < 0 || slot >= player.getInventory().items.size()) {
                return new SendResult(SendResult.ITEM_MISSING, "背包槽位无效");
            }
            ItemStack in = player.getInventory().getItem(slot);
            if (in.isEmpty() || !in.is(e.item()) || in.getCount() < e.count()) {
                return new SendResult(SendResult.ITEM_MISSING, "背包物品与所选不符");
            }
            ItemStack part = in.copy();
            part.setCount(e.count());
            toSend.add(part);
            removals.add(new int[]{slot, e.count()});
        }

        // 2) 模拟整批放入女仆可用背包：只有整批都能放得下才成功
        IItemHandler maidInv = toHandlerOrNull(maid.getAvailableBackpackInv());
        if (maidInv == null) {
            return new SendResult(SendResult.NO_SPACE, "女仆没有可用背包");
        }
        for (ItemStack stack : toSend) {
            ItemStack left = ItemHandlerHelper.insertItemStacked(maidInv, stack.copy(), true);
            if (!left.isEmpty()) {
                return new SendResult(SendResult.NO_SPACE, "女仆背包空间不足");
            }
        }

        // 3) 提交：从玩家扣取并真正放入女仆
        for (int i = 0; i < removals.size(); i++) {
            int[] rm = removals.get(i);
            player.getInventory().getItem(rm[0]).shrink(rm[1]);
        }
        for (ItemStack stack : toSend) {
            ItemHandlerHelper.insertItemStacked(maidInv, stack, false);
        }
        return new SendResult(SendResult.OK, "");
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
        net.neoforged.neoforge.items.IItemHandler baubles = maid.getMaidBauble();
        for (int slot = 0; slot < baubles.getSlots(); slot++) {
            ItemStack bauble = baubles.getStackInSlot(slot);
            if (bauble.isEmpty() || !bauble.is(InitItems.WIRELESS_IO.get())) {
                continue;
            }
            BlockPos binding = ItemWirelessIO.getBindingPos(bauble);
            if (binding == null) {
                continue;
            }
            IItemHandler chest = maid.level().getCapability(Capabilities.ItemHandler.BLOCK, binding, null);
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
                net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.parse(raw);
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
        if (player == null || stableId == null || files == null || files.isEmpty()) {
            return false;
        }
        MaidTransferData data = MaidTransferData.get(player.server);
        String tid = data.addBatch(stableId, files);
        if (tid == null) {
            return false;
        }
        java.util.List<com.huhutalk.phone.ItemTransfer.Preview> previews = new ArrayList<>();
        int shown = Math.min(3, files.size());
        for (int i = 0; i < shown; i++) {
            ItemStack f = files.get(i);
            net.minecraft.resources.ResourceLocation id = BuiltInRegistries.ITEM.getKey(f.getItem());
            previews.add(new com.huhutalk.phone.ItemTransfer.Preview(id.toString(), f.getCount()));
        }
        PacketDistributor.sendToPlayer(player,
                new com.huhutalk.network.S2CMaidItemTransferPacket(stableId, tid, previews, files.size(), files.size()));
        return true;
    }

    /**
     * 随一条聊天消息把玩家的物品发进女仆可用背包（P→M），并让女仆知晓收到了什么以便回应。
     * 规则：整批放得下才发；放不下 / 女仆不在线未装载 / 没戴手机 / AI 不可用 → 返回错误文案（物品未动，客户端保留队列）。
     *
     * @param text      随信文字（可为空 = 仅发物品）。
     * @return null = 成功（物品已转移并已触发女仆回复）；非 null = 失败文案（整条未发）。
     */
    public static String trySendChatWithItems(ServerPlayer player, String stableId, String text,
                                              List<Integer> slots, boolean bubbleMute, boolean immersive) {
        EntityMaid maid = findOwnedAliveMaid(player.server, stableId, player);
        if (maid == null) {
            return "女仆不在线或不在你名下，本次未发送";
        }
        if (!PhoneHelper.hasPhone(maid)) {
            return "该女仆没有佩戴手机，无法接收";
        }
        MaidAIChatManager mgr = maid.getAiChatManager();
        LLMSite site = mgr.getLLMSite();
        if (site == null || !site.enabled()) {
            return "女仆未配置或未启用 AI";
        }
        if (text != null && text.length() > 2000) {
            return "消息过长";
        }

        // 组装要发的整格物品 + 给女仆看的收到物品摘要
        List<PlayerSendEntry> entries = new ArrayList<>();
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
            if (!first) {
                sum.append("、");
            }
            first = false;
            sum.append(st.getHoverName().getString()).append('x').append(st.getCount());
        }
        if (entries.isEmpty()) {
            return "没有可发送的物品";
        }

        // 只有女仆可用背包整批放得下才真正转移；否则不扣玩家物品
        SendResult r = sendFromPlayer(player, stableId, entries);
        if (r.code != SendResult.OK) {
            return (r.message == null || r.message.isBlank()) ? "女仆背包放不下，本次未发送" : r.message;
        }

        // 已成功转移：让女仆在回复里感知并回应收到的物品
        String sys = sum + "。请自然地回应主人（比如表达收到、感谢，或简单评价这些物品）。";
        if (text != null && !text.isBlank()) {
            // 有文字：作为正常用户消息，同时把"收到物品"作为系统说明一并带给 AI
            PendingSystemMessageBus.queue(stableId, sys);
            MaidChatHandler.handleChat(player, stableId, text, bubbleMute, immersive);
        } else {
            // 无文字：直接让女仆针对收到的物品主动回应
            MaidChatHandler.handleSystemInjection(player, stableId, sys);
        }
        return null;
    }

    // ==================== 小工具 ====================

    @Nullable
    private static IItemHandler toHandlerOrNull(@Nullable Object maybeHandler) {
        return maybeHandler instanceof IItemHandler h ? h : null;
    }
}
