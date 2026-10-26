package com.huhutalk.core.archive;

import java.util.UUID;

/**
 * 在线聊天的一轮归档上下文。玩家消息先落盘；女仆最终回复在 onSuccess 使用同一
 * turnId 并以玩家 messageId 作为 relatedMessageId。
 *
 * <p>{@code source} 记录本轮入口来源（纯文本或图片），回复归档沿用该来源，
 * 不再写死为纯文本。</p>
 */
public record ArchiveTurnContext(
        UUID ownerUuid,
        String maidStableId,
        String turnId,
        String playerMessageId,
        String originWorldId,
        ArchiveSource source
) {
    public ArchiveTurnContext {
        if (ownerUuid == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "ownerUuid must not be null");
        }
        maidStableId = ArchiveIds.requireStableId(maidStableId, "maidStableId");
        turnId = ArchiveIds.requireToken(turnId, "turnId");
        playerMessageId = ArchiveIds.requireToken(playerMessageId, "playerMessageId");
        originWorldId = ArchiveIds.requireToken(originWorldId, "originWorldId");
        if (source == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "source must not be null");
        }
    }

    /** 原 5 参数入口默认在线纯文本，保持既有调用可编译。 */
    public ArchiveTurnContext(UUID ownerUuid, String maidStableId, String turnId,
                              String playerMessageId, String originWorldId) {
        this(ownerUuid, maidStableId, turnId, playerMessageId, originWorldId,
                ArchiveSource.ONLINE_PHONE_TEXT);
    }
}
