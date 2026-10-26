package com.huhutalk.core.archive;

/** 物品事件的方向；只描述归属流向，不保存可执行领取能力。 */
public enum ArchiveTransferDirection {
    PLAYER_TO_MAID("player_to_maid"),
    MAID_TO_PLAYER("maid_to_player"),
    MAID_TO_WORLD("maid_to_world"),
    PLAYER_TO_WORLD("player_to_world");

    private final String jsonName;

    ArchiveTransferDirection(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static ArchiveTransferDirection fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive transfer direction must not be null");
        }
        for (ArchiveTransferDirection direction : values()) {
            if (direction.jsonName.equals(value)) {
                return direction;
            }
        }
        throw new ArchiveException(ArchiveException.Code.VALIDATION,
                "unknown archive transfer direction: " + value);
    }
}
