package com.huhutalk.core.archive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端 → 客户端的档案分页响应（纯 Java）。
 *
 * <p>响应携带 requestId、女仆 stableId、当前服务器 {@code originWorldId}、状态、
 * 固定上界与本页记录。记录按 sequence 升序；最多 20 条。JSON 编码受
 * {@link ArchiveHistoryProtocol#MAX_JSON_CHARS} 与 UTF-8 字节预算约束。</p>
 *
 * <p>不做任何文本/时间去重，也不把本机聊天与档案合并；分页只按 sequence。</p>
 */
public record ArchiveHistoryResponse(
        String requestId,
        String maidStableId,
        String serverWorldId,
        ArchiveHistoryStatus status,
        long upperSequence,
        long nextBeforeSequence,
        boolean hasMore,
        boolean coveragePartial,
        List<String> coverageScopes,
        List<ArchiveHistoryRecord> records
) {
    public static final int MAX_RECORDS = ArchiveHistoryProtocol.MAX_PAGE_SIZE;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public ArchiveHistoryResponse {
        requestId = ArchiveHistoryProtocol.requireRequestId(requestId);
        maidStableId = ArchiveHistoryProtocol.requireStableId(maidStableId);
        serverWorldId = ArchiveHistoryProtocol.requireOptionalWorldId(serverWorldId);
        if (status == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history status must not be null");
        }
        if (upperSequence < ArchiveHistoryRequest.LATEST) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "upperSequence must be -1 or non-negative");
        }
        if (nextBeforeSequence < ArchiveHistoryRequest.LATEST) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "nextBeforeSequence must be -1 or non-negative");
        }
        if (records == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "records must not be null");
        }
        if (records.size() > MAX_RECORDS) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "too many archive history records: " + records.size());
        }
        List<String> scopes;
        if (coverageScopes == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "coverageScopes must not be null");
        }
        scopes = new ArrayList<>(coverageScopes.size());
        for (String scope : coverageScopes) {
            scopes.add(ArchiveIds.requireToken(scope, "coverageScope"));
        }
        coverageScopes = List.copyOf(scopes);

        List<ArchiveHistoryRecord> copy = new ArrayList<>(records.size());
        Set<String> messageIds = new HashSet<>();
        long previousSequence = Long.MIN_VALUE;
        for (ArchiveHistoryRecord record : records) {
            if (record == null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "records must not contain null");
            }
            if (!messageIds.add(record.messageId())) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "duplicate messageId in archive history page: " + record.messageId());
            }
            if (record.sequence() <= previousSequence) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "archive history records must be strictly ordered by sequence");
            }
            previousSequence = record.sequence();
            copy.add(record);
        }
        records = List.copyOf(copy);

        if (!status.isSuccess()) {
            if (!records.isEmpty()) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "error archive history response must not carry records");
            }
            if (hasMore) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "error archive history response must not claim hasMore");
            }
        }
        if (status.isSuccess() && !records.isEmpty()) {
            if (nextBeforeSequence != records.get(0).sequence()) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "nextBeforeSequence must equal the earliest record sequence");
            }
            if (upperSequence >= 0L
                    && records.get(records.size() - 1).sequence() > upperSequence) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "record sequence exceeds the pinned upperSequence");
            }
        }
        if (status.isSuccess() && records.isEmpty() && hasMore) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "empty page must not claim hasMore");
        }
    }

    /** 通用错误响应：不携带任何归档正文。 */
    public static ArchiveHistoryResponse error(String requestId, String maidStableId,
                                               String serverWorldId,
                                               ArchiveHistoryStatus status) {
        if (status == null || status.isSuccess()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "error response requires a non-success status");
        }
        return new ArchiveHistoryResponse(requestId, maidStableId, serverWorldId, status,
                ArchiveHistoryRequest.LATEST, ArchiveHistoryRequest.LATEST, false,
                true, List.of(), List.of());
    }

    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", ArchiveHistoryProtocol.PROTOCOL_SCHEMA_VERSION);
        object.addProperty("requestId", requestId);
        object.addProperty("maidStableId", maidStableId);
        object.addProperty("serverWorldId", serverWorldId);
        object.addProperty("status", status.name());
        object.addProperty("upperSequence", upperSequence);
        object.addProperty("nextBeforeSequence", nextBeforeSequence);
        object.addProperty("hasMore", hasMore);
        object.addProperty("coveragePartial", coveragePartial);
        JsonArray scopes = new JsonArray();
        for (String scope : coverageScopes) {
            scopes.add(scope);
        }
        object.add("coverageScopes", scopes);
        JsonArray recordArray = new JsonArray();
        for (ArchiveHistoryRecord record : records) {
            JsonObject entry = new JsonObject();
            entry.addProperty("messageId", record.messageId());
            entry.addProperty("role", record.role());
            entry.addProperty("text", record.text());
            entry.addProperty("sequence", record.sequence());
            recordArray.add(entry);
        }
        object.add("records", recordArray);
        return GSON.toJson(object);
    }

    public static ArchiveHistoryResponse fromJson(String json) {
        if (json == null) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive history JSON must not be null");
        }
        if (json.length() > ArchiveHistoryProtocol.MAX_JSON_CHARS
                || json.getBytes(StandardCharsets.UTF_8).length
                > ArchiveHistoryProtocol.MAX_UTF8_BYTES) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive history JSON exceeds the transfer budget");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException | IllegalStateException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive history JSON is not valid JSON", exception);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive history JSON root must be an object");
        }
        JsonObject object = parsed.getAsJsonObject();
        int schemaVersion = requireInt(object, "schemaVersion");
        if (schemaVersion != ArchiveHistoryProtocol.PROTOCOL_SCHEMA_VERSION) {
            throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_SCHEMA,
                    "unsupported archive history schemaVersion: " + schemaVersion);
        }
        String requestId = requireString(object, "requestId");
        String maidStableId = requireString(object, "maidStableId");
        String serverWorldId = requireString(object, "serverWorldId");
        ArchiveHistoryStatus status = ArchiveHistoryStatus.fromJson(requireString(object, "status"));
        long upperSequence = requireLong(object, "upperSequence");
        long nextBeforeSequence = requireLong(object, "nextBeforeSequence");
        boolean hasMore = requireBoolean(object, "hasMore");
        boolean coveragePartial = requireBoolean(object, "coveragePartial");
        JsonElement scopesElement = object.get("coverageScopes");
        if (scopesElement == null || !scopesElement.isJsonArray()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "coverageScopes must be an array");
        }
        List<String> scopes = new ArrayList<>();
        for (JsonElement scope : scopesElement.getAsJsonArray()) {
            if (!scope.isJsonPrimitive() || !scope.getAsJsonPrimitive().isString()) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "coverageScopes entries must be strings");
            }
            scopes.add(scope.getAsString());
        }
        JsonElement recordsElement = object.get("records");
        if (recordsElement == null || !recordsElement.isJsonArray()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "records must be an array");
        }
        List<ArchiveHistoryRecord> records = new ArrayList<>();
        for (JsonElement recordElement : recordsElement.getAsJsonArray()) {
            if (!recordElement.isJsonObject()) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "records entries must be objects");
            }
            records.add(recordFromJson(recordElement.getAsJsonObject()));
        }
        return new ArchiveHistoryResponse(requestId, maidStableId, serverWorldId, status,
                upperSequence, nextBeforeSequence, hasMore, coveragePartial, scopes, records);
    }

    private static ArchiveHistoryRecord recordFromJson(JsonObject object) {
        return new ArchiveHistoryRecord(
                requireString(object, "messageId"),
                requireString(object, "role"),
                requireString(object, "text"),
                requireLong(object, "sequence"));
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a string");
        }
        return element.getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int requireInt(JsonObject object, String field) {
        try {
            return requireDecimal(object, field).intValueExact();
        } catch (ArithmeticException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a 32-bit integer", exception);
        }
    }

    private static long requireLong(JsonObject object, String field) {
        try {
            return requireDecimal(object, field).longValueExact();
        } catch (ArithmeticException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a 64-bit integer", exception);
        }
    }

    private static BigDecimal requireDecimal(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a number");
        }
        try {
            return element.getAsBigDecimal();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a number", exception);
        }
    }
}
