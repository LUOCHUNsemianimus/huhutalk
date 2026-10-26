package com.huhutalk.core.archive;

/** 归档记录的来源路径。当前接通在线手机纯文本、图片与物品事件。 */
public enum ArchiveSource {
    ONLINE_PHONE_TEXT,
    ONLINE_PHONE_IMAGE,
    ONLINE_PHONE_ITEMS,
    SYSTEM_INJECTION,
    TOOL_OUTPUT,
    OBSERVATION;

    public static ArchiveSource fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "source must not be null");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "unknown archive source: " + value, exception);
        }
    }
}
