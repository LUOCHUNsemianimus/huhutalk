package com.huhutalk.core.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一条消息附带的不可变结构化事件。
 *
 * <p>事件只保存稳定事实：种类、方向、结果、物品摘要、关联 ID 与视觉/表情资源名。
 * 它不保存自由 JSON、本地化叙述、ItemStack/NBT，也不提供任何领取操作。数量、枚举、
 * 数组大小与字符串长度都在构造和反序列化时严格校验。</p>
 */
public record ArchiveEvent(
        ArchiveEventKind kind,
        ArchiveTransferDirection direction,
        ArchiveTransferOutcome outcome,
        List<ArchiveItemSummary> items,
        String transferId,
        String relatedEventId,
        String visualSite,
        String visualModel,
        String emoteResource
) {
    public static final int MAX_ITEMS = 256;
    public static final int MAX_VISUAL_FIELD_LENGTH = 256;
    public static final int MAX_EMOTE_RESOURCE_LENGTH = 256;
    private static final Pattern RESOURCE_LOCATION =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public ArchiveEvent {
        if (kind == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive event kind must not be null");
        }
        if (items == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive event items must not be null");
        }
        if (items.size() > MAX_ITEMS) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive event items must not exceed " + MAX_ITEMS);
        }
        List<ArchiveItemSummary> copy = new ArrayList<>(items.size());
        for (ArchiveItemSummary item : items) {
            if (item == null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "archive event items must not contain null");
            }
            copy.add(item);
        }
        items = List.copyOf(copy);
        if (transferId != null) {
            transferId = ArchiveIds.requireToken(transferId, "event transferId");
        }
        if (relatedEventId != null) {
            relatedEventId = ArchiveIds.requireToken(relatedEventId, "event relatedEventId");
        }
        visualSite = optionalBounded(visualSite, "event visualSite", MAX_VISUAL_FIELD_LENGTH);
        visualModel = optionalBounded(visualModel, "event visualModel", MAX_VISUAL_FIELD_LENGTH);
        emoteResource = optionalBounded(emoteResource, "event emoteResource",
                MAX_EMOTE_RESOURCE_LENGTH);
        if (emoteResource != null && !RESOURCE_LOCATION.matcher(emoteResource).matches()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "event emoteResource must be a resource location: " + emoteResource);
        }

        if (kind.isItemEvent()) {
            if (items.isEmpty()) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "item event must contain at least one item");
            }
            if (direction == null || outcome == null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "item event must declare direction and outcome");
            }
            if (emoteResource != null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "item event must not carry an emote resource");
            }
        } else {
            if (!items.isEmpty()) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "non-item event must not carry items");
            }
            if (direction != null || outcome != null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "non-item event must not declare direction or outcome");
            }
            if (kind == ArchiveEventKind.EMOTE && emoteResource == null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "emote event must declare emoteResource");
            }
            if (kind != ArchiveEventKind.EMOTE && emoteResource != null) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "only emote event may carry emoteResource");
            }
        }
    }

    /**
     * 生成只读展示副本：删除 transferId / relatedEventId 及物品上的相关传输审计 ID。
     * 档案页只需要事实，不能把审计关联误用成“历史领取/重新执行”的入口。
     */
    public ArchiveEvent readOnlyCopy() {
        List<ArchiveItemSummary> readOnlyItems = new ArrayList<>(items.size());
        for (ArchiveItemSummary item : items) {
            readOnlyItems.add(item.readOnlyCopy());
        }
        return new ArchiveEvent(kind, direction, outcome, readOnlyItems, null, null,
                visualSite, visualModel, emoteResource);
    }

    public static ArchiveEvent imageSent() {
        return new ArchiveEvent(ArchiveEventKind.IMAGE_SENT, null, null, List.of(),
                null, null, null, null, null);
    }

    public static ArchiveEvent visionObservation(String site, String model) {
        return new ArchiveEvent(ArchiveEventKind.VISION_OBSERVATION, null, null, List.of(),
                null, null, site, model, null);
    }

    public static ArchiveEvent visionFailed(String site, String model) {
        return new ArchiveEvent(ArchiveEventKind.VISION_FAILED, null, null, List.of(),
                null, null, site, model, null);
    }

    public static ArchiveEvent itemEvent(ArchiveEventKind kind,
                                         ArchiveTransferDirection direction,
                                         ArchiveTransferOutcome outcome,
                                         List<ArchiveItemSummary> items,
                                         String transferId) {
        return new ArchiveEvent(kind, direction, outcome, items, transferId, null,
                null, null, null);
    }

    public static ArchiveEvent emote(String emoteResource) {
        return new ArchiveEvent(ArchiveEventKind.EMOTE, null, null, List.of(),
                null, null, null, null, emoteResource);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("kind", kind.jsonName());
        object.add("direction", direction == null
                ? JsonNull.INSTANCE : new JsonPrimitive(direction.jsonName()));
        object.add("outcome", outcome == null
                ? JsonNull.INSTANCE : new JsonPrimitive(outcome.jsonName()));
        JsonArray itemArray = new JsonArray();
        for (ArchiveItemSummary item : items) {
            itemArray.add(item.toJson());
        }
        object.add("items", itemArray);
        object.add("transferId", transferId == null
                ? JsonNull.INSTANCE : new JsonPrimitive(transferId));
        object.add("relatedEventId", relatedEventId == null
                ? JsonNull.INSTANCE : new JsonPrimitive(relatedEventId));
        object.add("visualSite", visualSite == null
                ? JsonNull.INSTANCE : new JsonPrimitive(visualSite));
        object.add("visualModel", visualModel == null
                ? JsonNull.INSTANCE : new JsonPrimitive(visualModel));
        object.add("emoteResource", emoteResource == null
                ? JsonNull.INSTANCE : new JsonPrimitive(emoteResource));
        return object;
    }

    public static ArchiveEvent fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive event must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        ArchiveEventKind kind = ArchiveEventKind.fromJson(
                ArchiveItemSummary.requireString(object, "kind"));
        String directionText = ArchiveItemSummary.optionalString(object, "direction");
        String outcomeText = ArchiveItemSummary.optionalString(object, "outcome");
        ArchiveTransferDirection direction = directionText == null
                ? null : ArchiveTransferDirection.fromJson(directionText);
        ArchiveTransferOutcome outcome = outcomeText == null
                ? null : ArchiveTransferOutcome.fromJson(outcomeText);
        JsonElement itemsElement = object.get("items");
        if (itemsElement == null || !itemsElement.isJsonArray()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "archive event items must be an array");
        }
        List<ArchiveItemSummary> items = new ArrayList<>();
        for (JsonElement item : itemsElement.getAsJsonArray()) {
            items.add(ArchiveItemSummary.fromJson(item));
        }
        String transferId = ArchiveItemSummary.optionalString(object, "transferId");
        String relatedEventId = ArchiveItemSummary.optionalString(object, "relatedEventId");
        String visualSite = ArchiveItemSummary.optionalString(object, "visualSite");
        String visualModel = ArchiveItemSummary.optionalString(object, "visualModel");
        String emoteResource = ArchiveItemSummary.optionalString(object, "emoteResource");
        return new ArchiveEvent(kind, direction, outcome, items, transferId, relatedEventId,
                visualSite, visualModel, emoteResource);
    }

    public boolean sameContent(ArchiveEvent other) {
        return other != null
                && kind == other.kind
                && direction == other.direction
                && outcome == other.outcome
                && items.equals(other.items)
                && Objects.equals(transferId, other.transferId)
                && Objects.equals(relatedEventId, other.relatedEventId)
                && Objects.equals(visualSite, other.visualSite)
                && Objects.equals(visualModel, other.visualModel)
                && Objects.equals(emoteResource, other.emoteResource);
    }

    private static String optionalBounded(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    field + " must not be blank");
        }
        if (trimmed.length() > maxLength) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    field + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }
}
