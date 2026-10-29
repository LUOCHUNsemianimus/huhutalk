package com.huhutalk.core.archive;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonParser;
import com.huhutalk.core.chat.HuhuTalkChatCallback;
import com.huhutalk.core.chat.PhoneMessageQuote;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * 按消息 ID 读取当前手机会话归档原文，可选把一条有资格的真实记录挂成本轮引用。
 * 读取复用 {@link PhoneMemoryProtocol#messageResult}；引用资格/作者由
 * {@link PhoneMessageQuote#fromRecord} 统一判定，不在这里另写角色白名单。
 */
public class GetPhoneMessageTool implements ITool<GetPhoneMessageTool.Result> {
    public static final String TOOL_ID = "huhutalk_get_chat_message";

    private static final String MESSAGE_ID = "message_id";
    private static final String TEXT_OFFSET = "text_offset";
    private static final String MAX_CHARS = "max_chars";
    private static final String QUOTE = "quote";
    private static final int DEFAULT_MAX_CHARS = 2000;

    private static final String TOOL_DESC = """
            Read one archived phone-chat message by its exact message_id.
            Use text_offset to continue a long message; the response gives nextTextOffset and adjacent message ids.
            Set quote=true only after you verified the message with a normal read. A successful quote is attached to your current reply.
            Quotable records are player/maid text or image messages, and real successful item-send events
            (including transfer records archived as tool/system); their author follows the actual transfer direction.
            Claimed/dropped/failed or world-directed item events cannot be quoted as a send.
            Ordinary observation/system/tool text can be read but cannot be presented as the master's or maid's words.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(MESSAGE_ID).forGetter(Result::messageId),
            Codec.INT.optionalFieldOf(TEXT_OFFSET, 0).forGetter(Result::textOffset),
            Codec.INT.optionalFieldOf(MAX_CHARS, DEFAULT_MAX_CHARS).forGetter(Result::maxChars),
            Codec.BOOL.optionalFieldOf(QUOTE, false).forGetter(Result::quote)
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
        StringParameter messageId = StringParameter.create()
                .setDescription("Exact archived message_id from search results. Required.")
                .setRange(1, 128);
        IntegerParameter textOffset = IntegerParameter.create()
                .setDescription("UTF-16 text offset to start reading. Default 0.")
                .setMinimum(0);
        IntegerParameter maxChars = IntegerParameter.create()
                .setDescription("Maximum characters to read, 64..4000. Default 2000.")
                .setMinimum(PhoneMemoryProtocol.MIN_MESSAGE_CHARS)
                .setMaximum(PhoneMemoryProtocol.MAX_MESSAGE_CHARS);
        BoolParameter quote = BoolParameter.create();
        quote.setDescription("Attach this verified quotable message or real successful item-send event to your reply. Default false.");

        root.addProperties(MESSAGE_ID, messageId, true);
        root.addProperties(TEXT_OFFSET, textOffset, false);
        root.addProperties(MAX_CHARS, maxChars, false);
        root.addProperties(QUOTE, quote, false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        // 每次显式 quote 尝试先清掉旧引用；只有通过统一工厂验证的记录才会重新挂上。
        HuhuTalkChatCallback phoneCallback =
                callback instanceof HuhuTalkChatCallback hc ? hc : null;
        if (result.quote() && phoneCallback != null) {
            phoneCallback.clearQuote();
        }

        try {
            // 先校验参数，失败时不触碰磁盘。
            ArchiveIds.requireToken(result.messageId(), "messageId");
            if (result.textOffset() < 0) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "textOffset must be >= 0");
            }
            if (result.maxChars() < PhoneMemoryProtocol.MIN_MESSAGE_CHARS
                    || result.maxChars() > PhoneMemoryProtocol.MAX_MESSAGE_CHARS) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "maxChars must be between " + PhoneMemoryProtocol.MIN_MESSAGE_CHARS
                                + " and " + PhoneMemoryProtocol.MAX_MESSAGE_CHARS);
            }

            PhoneMemoryToolSupport.Scope scope = PhoneMemoryToolSupport.requireScope(callback);
            Optional<ArchiveSnapshot> snapshot = ArchiveService.snapshot(scope.server(),
                    scope.sender().getUUID(), scope.stableId());
            String json = PhoneMemoryProtocol.messageResult(snapshot, result.messageId(),
                    result.textOffset(), result.maxChars());

            if (result.quote() && isFound(json) && phoneCallback != null) {
                ArchiveRecord record = findRecord(snapshot, result.messageId());
                if (record == null) {
                    return callback.addToolResult(
                            PhoneMemoryProtocol.error("invalid_argument"), toolCallId);
                }
                try {
                    // 唯一资格/作者派生入口；拒绝领取/失败/发往世界与普通工具文字。
                    phoneCallback.attachQuote(PhoneMessageQuote.fromRecord(record));
                } catch (ArchiveException exception) {
                    return callback.addToolResult(
                            PhoneMemoryProtocol.error("invalid_argument"), toolCallId);
                }
            }
            return callback.addToolResult(json, toolCallId);
        } catch (PhoneMemoryToolSupport.ToolFailure failure) {
            return callback.addToolResult(PhoneMemoryProtocol.error(failure.status()), toolCallId);
        } catch (ArchiveException exception) {
            return callback.addToolResult(
                    PhoneMemoryProtocol.error(PhoneMemoryToolSupport.mapArchiveStatus(exception)),
                    toolCallId);
        }
    }

    private static boolean isFound(String json) {
        return "found".equals(JsonParser.parseString(json).getAsJsonObject()
                .get("status").getAsString());
    }

    private static ArchiveRecord findRecord(Optional<ArchiveSnapshot> snapshot, String messageId) {
        if (snapshot.isEmpty()) {
            return null;
        }
        for (ArchiveRecord record : snapshot.get().records()) {
            if (record.messageId().equals(messageId)) {
                return record;
            }
        }
        return null;
    }

    public record Result(String messageId, int textOffset, int maxChars, boolean quote) {
    }
}
