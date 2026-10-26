package com.huhutalk.core.archive;

/** 物品事件的最终结果；失败事件只能记录为失败，不能改写成成功叙述。 */
public enum ArchiveTransferOutcome {
    SUCCESS("success"),
    PARTIAL("partial"),
    FAILED("failed"),
    /** 已放入收件箱，仅代表发送时待领取，不代表归档查看时仍可领取。 */
    QUEUED("queued");

    private final String jsonName;

    ArchiveTransferOutcome(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static ArchiveTransferOutcome fromJson(String value) {
        if (value == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive transfer outcome must not be null");
        }
        for (ArchiveTransferOutcome outcome : values()) {
            if (outcome.jsonName.equals(value)) {
                return outcome;
            }
        }
        throw new ArchiveException(ArchiveException.Code.VALIDATION,
                "unknown archive transfer outcome: " + value);
    }
}
