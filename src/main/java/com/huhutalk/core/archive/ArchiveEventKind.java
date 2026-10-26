package com.huhutalk.core.archive;

/**
 * 结构化归档事件的稳定种类。持久化字符串固定为小写，不能随 UI 文案变化。
 */
public enum ArchiveEventKind {
    IMAGE_SENT("image_sent"),
    VISION_OBSERVATION("vision_observation"),
    VISION_FAILED("vision_failed"),
    ITEMS_SENT("items_sent"),
    ITEMS_CLAIMED("items_claimed"),
    ITEMS_DROPPED("items_dropped"),
    EMOTE("emote");

    private final String jsonName;

    ArchiveEventKind(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public boolean isItemEvent() {
        return this == ITEMS_SENT || this == ITEMS_CLAIMED || this == ITEMS_DROPPED;
    }

    public static ArchiveEventKind fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive event kind must not be null");
        }
        for (ArchiveEventKind kind : values()) {
            if (kind.jsonName.equals(value)) {
                return kind;
            }
        }
        throw new ArchiveException(ArchiveException.Code.VALIDATION,
                "unknown archive event kind: " + value);
    }
}
