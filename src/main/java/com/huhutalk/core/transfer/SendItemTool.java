package com.huhutalk.core.transfer;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.core.chat.HuhuTalkChatCallback;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 让女仆把自己身上的东西 / 隙间(wireless_io)绑箱里的东西「传」给主人的 LLM 工具（M→P）。
 * <p>
 * 玩家通过聊天索要某物时，模型调用本工具：工具在服务端从女仆各处取下目标物品，
 * 存入收件箱并推送一个可领取的物品气泡到主人的手机聊天页；主人点击气泡即可领取进背包。
 * <p>
 * 女仆身上找不到 / 数量不够会给出明确反馈，而不是假成功。
 */
public class SendItemTool implements ITool<SendItemTool.Result> {

    public static final String TOOL_ID = "huhutalk_send_item";
    private static final String ITEM = "item";
    private static final String AMOUNT = "amount";

    private static final String TOOL_DESC = """
            Use this when the user (your master) asks you to give / send / hand over one of your own items or something from your gap (隙间/wireless chest), for example "give me your diamond", "send me some bread", "把那箱铁给我".
            Only use items that you actually carry or can fetch from your bound gap chest. If you are not sure what to give, first respond and ask which item, then call this tool in a later turn.
            item: the item the master wants (English registry id if you know it, otherwise any recognizable name/description).
            amount (optional): how many of that item to send. Default 1. Send as many as the master asks, up to what you actually have.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(ITEM).forGetter(Result::item),
            Codec.INT.optionalFieldOf(AMOUNT, 1).forGetter(Result::amount)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter item = StringParameter.create().setDescription(
                "The item your master wants to receive. English registry id if known (e.g. minecraft:diamond), otherwise a recognizable name/description.");
        IntegerParameter amount = IntegerParameter.create().setDescription(
                "How many of that item to send (optional). Default 1.");
        root.addProperties(ITEM, item, true);
        root.addProperties(AMOUNT, amount, false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        // 该工具只在"主人给手机聊天时"可用，此时回调必为 HuhuTalkChatCallback
        if (!(callback instanceof HuhuTalkChatCallback hc)) {
            return callback.addToolResult("send_item is only usable during phone chats.", toolCallId);
        }
        String stableId = hc.getStableId();
        ServerPlayer owner = hc.getSender();
        if (stableId == null || stableId.isBlank() || owner == null) {
            return callback.addToolResult("send_item: missing chat context.", toolCallId);
        }
        EntityMaid maid = MaidTransferService.findOwnedAliveMaid(owner.server, stableId, owner);
        if (maid == null) {
            return callback.addToolResult(
                    "send_item: your maid is not loaded / not around right now, so nothing was sent. Tell the master to try again when you are nearby and online.",
                    toolCallId);
        }

        // 1) 解析目标物品
        String query = result.item() == null ? "" : result.item().trim();
        if (query.isEmpty()) {
            return callback.addToolResult(
                    "send_item: item cannot be empty. Ask the master which item to send and try again.", toolCallId);
        }
        Item type = MaidTransferService.resolveItem(maid, query);
        if (type == null) {
            return callback.addToolResult(
                    "send_item: you do not seem to have any item matching \"" + query
                            + "\" in your backpack or your gap chest, so nothing was sent. Tell the master you don't have it.",
                    toolCallId);
        }

        // 2) 取下来（已从源移除）
        int amount = Math.max(1, result.amount());
        List<ItemStack> files = MaidTransferService.collectItems(maid, type, amount);
        if (files.isEmpty()) {
            return callback.addToolResult(
                    "send_item: you ran out of that item and could not send any. Tell the master honestly.",
                    toolCallId);
        }

        // 3) 放入收件箱并推送物品气泡
        int totalCount = 0;
        for (ItemStack f : files) {
            totalCount += f.getCount();
        }
        boolean ok = MaidTransferService.pushBatchToPlayer(owner, stableId, files);
        if (!ok) {
            return callback.addToolResult(
                    "send_item: the transfer could not be completed (internal error). Nothing was removed from you.",
                    toolCallId);
        }
        return callback.addToolResult(
                "send_item: you have sent " + totalCount + " of " + query
                        + " to your master's phone as a file (several stacks if more than one inventory slot worth). "
                        + "The master can tap it in the chat to receive it. Now reply briefly in character.",
                toolCallId);
    }

    public record Result(String item, int amount) {
    }
}
