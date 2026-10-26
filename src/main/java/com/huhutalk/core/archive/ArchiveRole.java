package com.huhutalk.core.archive;

/** 归档记录在会话中的角色。首步只写入 PLAYER 与 MAID。 */
public enum ArchiveRole {
    PLAYER,
    MAID,
    TOOL,
    SYSTEM,
    OBSERVATION;

    public static ArchiveRole fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "role must not be null");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "unknown archive role: " + value, exception);
        }
    }
}
