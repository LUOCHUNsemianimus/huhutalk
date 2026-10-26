package com.huhutalk.core.archive;

import java.util.UUID;

/**
 * 纯文本在线聊天的一轮归档上下文。玩家原话先落盘；女仆最终回复在 onSuccess
 * 使用同一 turnId 并以玩家 messageId 作为 relatedMessageId。
 */
public record ArchiveTurnContext(
        UUID ownerUuid,
        String maidStableId,
        String turnId,
        String playerMessageId,
        String originWorldId
) {
    public ArchiveTurnContext {
        if (ownerUuid == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "ownerUuid must not be null");
        }
        maidStableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        turnId = ArchiveIds.requireToken(turnId, "turnId");
        playerMessageId = ArchiveIds.requireToken(playerMessageId, "playerMessageId");
        originWorldId = ArchiveIds.requireToken(originWorldId, "originWorldId");
    }
}
