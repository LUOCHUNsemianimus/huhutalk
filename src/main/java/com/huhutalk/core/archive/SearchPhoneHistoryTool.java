package com.huhutalk.core.archive;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 搜索当前手机会话已归档记录。只走真实 {@link ArchiveService#query} 与
 * {@link PhoneMemoryProtocol}，不复制检索、不新增索引、不写归档。
 */
public class SearchPhoneHistoryTool implements ITool<SearchPhoneHistoryTool.Result> {
    public static final String TOOL_ID = "huhutalk_search_chat_history";

    private static final String QUERY = "query";
    private static final String ROLE = "role";
    private static final String OFFSET = "offset";
    private static final String LIMIT = "limit";

    private static final String TOOL_DESC = """
            Search this phone chat's archived history with your master.
            Use one short keyword from the original conversation (Chinese or English, case-insensitive literal match).
            The same search also matches structured item facts (item name or item id) and media labels
            (image/photo/picture or 图片/照片); item labels include item/file/transfer or 物品/文件.
            Default role is player; to find item or media records use role=all, because real transfer events
            may be archived as tool or system records. No match only means this archive has no matching record,
            not that it never happened.
            To quote a message, first read it with huhutalk_get_chat_message and then call it again with quote=true.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(QUERY).forGetter(Result::query),
            Codec.STRING.optionalFieldOf(ROLE, "player").forGetter(Result::role),
            Codec.INT.optionalFieldOf(OFFSET, 0).forGetter(Result::offset),
            Codec.INT.optionalFieldOf(LIMIT, PhoneMemoryProtocol.MAX_SEARCH_RESULTS)
                    .forGetter(Result::limit)
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
        StringParameter query = StringParameter.create()
                .setDescription("Short keyword: original words, an item name/id, or image/item label. Required.")
                .setRange(1, PhoneMemoryProtocol.MAX_QUERY_LENGTH);
        StringParameter role = StringParameter.create()
                .setDescription("Which archived role to search. Use all for item/media records. Default player.")
                .addEnumValues("player", "maid", "observation", "system", "tool", "all")
                .setDefaultValue("player");
        IntegerParameter offset = IntegerParameter.create()
                .setDescription("Start offset in matching records. Default 0.")
                .setMinimum(0);
        IntegerParameter limit = IntegerParameter.create()
                .setDescription("Maximum records in this page, 1..5. Default 5.")
                .setMinimum(1)
                .setMaximum(PhoneMemoryProtocol.MAX_SEARCH_RESULTS);

        root.addProperties(QUERY, query, true);
        root.addProperties(ROLE, role, false);
        root.addProperties(OFFSET, offset, false);
        root.addProperties(LIMIT, limit, false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        try {
            // 先校验参数，失败时不触碰磁盘。
            PhoneMemoryProtocol.validateSearch(result.query(), result.offset(), result.limit());
            ArchiveRole role = PhoneMemoryProtocol.parseRole(result.role());
            PhoneMemoryToolSupport.Scope scope = PhoneMemoryToolSupport.requireScope(callback);
            ArchivePage page = ArchiveService.queryMemory(scope.server(), scope.sender().getUUID(),
                    scope.stableId(), result.offset(), result.limit(), role, result.query());
            String json = PhoneMemoryProtocol.searchResult(page, result.query());
            return callback.addToolResult(json, toolCallId);
        } catch (PhoneMemoryToolSupport.ToolFailure failure) {
            return callback.addToolResult(PhoneMemoryProtocol.error(failure.status()), toolCallId);
        } catch (ArchiveException exception) {
            return callback.addToolResult(
                    PhoneMemoryProtocol.error(PhoneMemoryToolSupport.mapArchiveStatus(exception)),
                    toolCallId);
        }
    }

    public record Result(String query, String role, int offset, int limit) {
    }
}
