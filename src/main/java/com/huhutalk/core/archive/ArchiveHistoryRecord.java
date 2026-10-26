package com.huhutalk.core.archive;

import java.util.ArrayList;
import java.util.List;

/**
 * 下发给手机档案页的一条只读记录。
 *
 * <p>原文完整传输，不做摘要、断句或省略号替换。角色覆盖玩家/女仆原话，以及带
 * 结构化事件的观察/系统/工具记录；结构化事件必须是只读副本（不含传输审计 ID）。
 * 是否超预算由 {@link ArchiveHistoryProtocol} 判断，单条放不下时明确返回
 * {@link ArchiveHistoryStatus#TOO_LARGE}。</p>
 */
public record ArchiveHistoryRecord(
        String messageId,
        String role,
        String text,
        long sequence,
        ArchiveSource source,
        String turnId,
        String relatedMessageId,
        List<String> attachmentRefs,
        ArchiveEvent event
) {
    public static final int MAX_ATTACHMENT_REFS = 8;

    public ArchiveHistoryRecord {
        messageId = ArchiveIds.requireToken(messageId, "messageId");
        if (!"PLAYER".equals(role) && !"MAID".equals(role) && !"OBSERVATION".equals(role)
                && !"SYSTEM".equals(role) && !"TOOL".equals(role)) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history role is not displayable: " + role);
        }
        if (text == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history text must not be null");
        }
        if (sequence < 0L) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history sequence must not be negative");
        }
        if (source == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "archive history source must not be null");
        }
        turnId = ArchiveIds.requireToken(turnId, "turnId");
        if (relatedMessageId != null) {
            relatedMessageId = ArchiveIds.requireToken(relatedMessageId, "relatedMessageId");
        }
        if (attachmentRefs == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "attachmentRefs must not be null");
        }
        if (attachmentRefs.size() > MAX_ATTACHMENT_REFS) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "too many attachment refs: " + attachmentRefs.size());
        }
        List<String> refs = new ArrayList<>(attachmentRefs.size());
        for (String ref : attachmentRefs) {
            refs.add(ArchiveAssetStore.requireHash(ref));
        }
        attachmentRefs = List.copyOf(refs);
        if (event != null) {
            // 只读 DTO 不允许携带可被误用为历史领取能力的审计关联 ID。
            event = event.readOnlyCopy();
        }
    }

    /** 旧 4 参数入口：纯文字在线消息，扩展字段取兼容缺省值。 */
    public ArchiveHistoryRecord(String messageId, String role, String text, long sequence) {
        this(messageId, role, text, sequence, ArchiveSource.ONLINE_PHONE_TEXT, messageId, null,
                List.of(), null);
    }

    public boolean isPlayer() {
        return "PLAYER".equals(role);
    }

    public boolean isMaid() {
        return "MAID".equals(role);
    }

    /** 该记录是否带可查看的图片附件。 */
    public boolean hasImage() {
        return !attachmentRefs.isEmpty();
    }
}
