package com.huhutalk.core.archive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.regex.Pattern;

/**
 * 物品事件的不可变事实摘要。
 *
 * <p>只保留注册 ID、显示名、真实数量与可选审计关联 ID；绝不保存 ItemStack、NBT
 * 或任何可执行领取能力。显示名只是当时的展示快照，事实以注册 ID 为准。</p>
 */
public record ArchiveItemSummary(
        String itemId,
        String displayName,
        int count,
        String relatedTransferId
) {
    private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final int MAX_DISPLAY_NAME_LENGTH = 256;
    public static final int MAX_COUNT = 1_000_000;

    public ArchiveItemSummary {
        if (itemId == null || itemId.length() > 256 || !REGISTRY_ID.matcher(itemId).matches()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "itemId must be a bounded registry id: " + itemId);
        }
        if (displayName == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "item displayName must not be null");
        }
        displayName = displayName.trim();
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "item displayName must be 1.." + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (count <= 0 || count > MAX_COUNT) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "item count must be between 1 and " + MAX_COUNT);
        }
        if (relatedTransferId != null) {
            relatedTransferId = ArchiveIds.requireToken(relatedTransferId, "item relatedTransferId");
        }
    }

    public ArchiveItemSummary readOnlyCopy() {
        return new ArchiveItemSummary(itemId, displayName, count, null);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("itemId", itemId);
        object.addProperty("displayName", displayName);
        object.addProperty("count", count);
        object.add("relatedTransferId", relatedTransferId == null
                ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(relatedTransferId));
        return object;
    }

    public static ArchiveItemSummary fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive event item must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        String itemId = requireString(object, "itemId");
        String displayName = requireString(object, "displayName");
        int count;
        try {
            JsonElement countElement = object.get("count");
            if (countElement == null || !countElement.isJsonPrimitive()
                    || !countElement.getAsJsonPrimitive().isNumber()) {
                throw new ArchiveException(ArchiveException.Code.CORRUPT,
                        "item count must be a number");
            }
            count = countElement.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "item count must be a 32-bit integer", exception);
        }
        String relatedTransferId = optionalString(object, "relatedTransferId");
        return new ArchiveItemSummary(itemId, displayName, count, relatedTransferId);
    }

    static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a string");
        }
        return element.getAsString();
    }

    static String optionalString(JsonObject object, String field) {
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
}
