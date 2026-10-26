package com.huhutalk.core.archive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个「玩家 + 女仆」会话的完整 JSON 快照。
 *
 * <p>磁盘文件与导出快照共用同一结构；schemaVersion 未知时明确拒绝，
 * 不会用空列表冒充成功，也不会覆盖原文件。</p>
 */
public record ArchiveSnapshot(
        int schemaVersion,
        String archiveId,
        String ownerUuid,
        String maidStableId,
        ArchiveCoverage coverage,
        String originWorldId,
        long createdAtEpochMillis,
        List<ArchiveRecord> records
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public ArchiveSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_SCHEMA,
                    "unsupported archive schemaVersion: " + schemaVersion);
        }
        archiveId = ArchiveIds.requireToken(archiveId, "archiveId");
        ownerUuid = ArchiveIds.requireCanonicalUuid(ownerUuid, "ownerUuid");
        maidStableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        if (coverage == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "coverage must not be null");
        }
        originWorldId = ArchiveIds.requireToken(originWorldId, "originWorldId");
        if (createdAtEpochMillis < 0L) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "createdAtEpochMillis must not be negative");
        }
        if (records == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "records must not be null");
        }
        List<ArchiveRecord> copy = new ArrayList<>(records.size());
        Set<String> messageIds = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        for (ArchiveRecord record : records) {
            if (record == null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "records must not contain null");
            }
            if (!messageIds.add(record.messageId())) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "duplicate messageId in archive: " + record.messageId());
            }
            if (!sequences.add(record.sequence())) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "duplicate sequence in archive: " + record.sequence());
            }
            copy.add(record);
        }
        records = List.copyOf(copy);
    }

    public ArchiveSnapshot withRecords(List<ArchiveRecord> newRecords) {
        return new ArchiveSnapshot(schemaVersion, archiveId, ownerUuid, maidStableId, coverage,
                originWorldId, createdAtEpochMillis, newRecords);
    }

    public String toJson() {
        return GSON.toJson(toJsonObject());
    }

    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", schemaVersion);
        object.addProperty("archiveId", archiveId);
        object.addProperty("ownerUuid", ownerUuid);
        object.addProperty("maidStableId", maidStableId);
        object.add("coverage", coverage.toJson());
        object.addProperty("originWorldId", originWorldId);
        object.addProperty("createdAtEpochMillis", createdAtEpochMillis);
        JsonArray recordArray = new JsonArray();
        for (ArchiveRecord record : records) {
            recordArray.add(recordToJson(record));
        }
        object.add("records", recordArray);
        return object;
    }

    public static ArchiveSnapshot fromJson(String json) {
        if (json == null) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT, "archive JSON must not be null");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException | IllegalStateException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive JSON is not valid JSON", exception);
        }
        return fromJson(parsed);
    }

    public static ArchiveSnapshot fromJson(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonObject()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive JSON root must be an object");
        }
        JsonObject object = parsed.getAsJsonObject();
        int schemaVersion = requireInt(object, "schemaVersion");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_SCHEMA,
                    "unsupported archive schemaVersion: " + schemaVersion);
        }
        String archiveId = requireString(object, "archiveId");
        String ownerUuid = requireString(object, "ownerUuid");
        String maidStableId = requireString(object, "maidStableId");
        ArchiveCoverage coverage = ArchiveCoverage.fromJson(object.get("coverage"));
        String originWorldId = requireString(object, "originWorldId");
        long createdAt = requireLong(object, "createdAtEpochMillis");
        JsonElement recordsElement = object.get("records");
        if (recordsElement == null || !recordsElement.isJsonArray()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT, "records must be an array");
        }
        List<ArchiveRecord> records = new ArrayList<>();
        for (JsonElement recordElement : recordsElement.getAsJsonArray()) {
            if (!recordElement.isJsonObject()) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "records entries must be objects");
            }
            records.add(recordFromJson(recordElement.getAsJsonObject()));
        }
        return new ArchiveSnapshot(schemaVersion, archiveId, ownerUuid, maidStableId, coverage,
                originWorldId, createdAt, records);
    }

    private static JsonObject recordToJson(ArchiveRecord record) {
        JsonObject object = new JsonObject();
        object.addProperty("messageId", record.messageId());
        object.addProperty("turnId", record.turnId());
        object.add("relatedMessageId", record.relatedMessageId() == null
                ? JsonNull.INSTANCE : new JsonPrimitive(record.relatedMessageId()));
        object.addProperty("source", record.source().name());
        object.addProperty("role", record.role().name());
        object.addProperty("text", record.text());
        object.addProperty("recordedAtEpochMillis", record.recordedAtEpochMillis());
        object.addProperty("originWorldId", record.originWorldId());
        object.addProperty("originGameTime", record.originGameTime());
        object.addProperty("sequence", record.sequence());
        JsonArray attachments = new JsonArray();
        for (String ref : record.attachmentRefs()) {
            attachments.add(ref);
        }
        object.add("attachmentRefs", attachments);
        object.add("relatedEventId", record.relatedEventId() == null
                ? JsonNull.INSTANCE : new JsonPrimitive(record.relatedEventId()));
        return object;
    }

    private static ArchiveRecord recordFromJson(JsonObject object) {
        String messageId = requireString(object, "messageId");
        String turnId = requireString(object, "turnId");
        String relatedMessageId = optionalString(object, "relatedMessageId");
        ArchiveSource source = ArchiveSource.fromJson(requireString(object, "source"));
        ArchiveRole role = ArchiveRole.fromJson(requireString(object, "role"));
        String text = requireString(object, "text");
        long recordedAt = requireLong(object, "recordedAtEpochMillis");
        String originWorldId = requireString(object, "originWorldId");
        long originGameTime = requireLong(object, "originGameTime");
        long sequence = requireLong(object, "sequence");
        JsonElement attachmentsElement = object.get("attachmentRefs");
        if (attachmentsElement == null || !attachmentsElement.isJsonArray()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "attachmentRefs must be an array");
        }
        List<String> attachmentRefs = new ArrayList<>();
        for (JsonElement attachment : attachmentsElement.getAsJsonArray()) {
            if (!attachment.isJsonPrimitive() || !attachment.getAsJsonPrimitive().isString()) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "attachmentRefs entries must be strings");
            }
            attachmentRefs.add(attachment.getAsString());
        }
        String relatedEventId = optionalString(object, "relatedEventId");
        return new ArchiveRecord(messageId, turnId, relatedMessageId, source, role, text,
                recordedAt, originWorldId, originGameTime, sequence, attachmentRefs, relatedEventId);
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

    private static String optionalString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a string or null");
        }
        return element.getAsString();
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
