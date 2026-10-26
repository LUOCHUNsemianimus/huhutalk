package com.huhutalk.core.archive;

/**
 * 下发给手机档案页的一条在线文字原话。
 *
 * <p>原文完整传输，不做摘要、断句或省略号替换；只允许主人/女仆两条角色，
 * 因为本页只呈现在线文字对话。是否超预算由 {@link ArchiveHistoryProtocol} 判断，
 * 单条放不下时明确返回 {@link ArchiveHistoryStatus#TOO_LARGE}。</p>
 */
public record ArchiveHistoryRecord(
        String messageId,
        String role,
        String text,
        long sequence
) {
    public ArchiveHistoryRecord {
        messageId = ArchiveIds.requireToken(messageId, "messageId");
        if (!"PLAYER".equals(role) && !"MAID".equals(role)) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history role must be PLAYER or MAID: " + role);
        }
        if (text == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history text must not be null");
        }
        if (sequence < 0L) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history sequence must not be negative");
        }
    }

    public boolean isPlayer() {
        return "PLAYER".equals(role);
    }
}
