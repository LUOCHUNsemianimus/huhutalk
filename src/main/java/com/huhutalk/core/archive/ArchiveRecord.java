package com.huhutalk.core.archive;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条不可变归档记录。消息原文完整保存，不做摘要或断句替换。
 *
 * @param recordedAtEpochMillis 旧档允许未知时使用 0；新记录使用当前时间。
 * @param sequence              会话内排序键，由存储层分配。
 * @param event                 可选结构化事件；旧档或纯文本消息为 null。
 */
public record ArchiveRecord(
        String messageId,
        String turnId,
        String relatedMessageId,
        ArchiveSource source,
        ArchiveRole role,
        String text,
        long recordedAtEpochMillis,
        String originWorldId,
        long originGameTime,
        long sequence,
        List<String> attachmentRefs,
        String relatedEventId,
        ArchiveEvent event
) {
    public ArchiveRecord {
        messageId = ArchiveIds.requireToken(messageId, "messageId");
        turnId = ArchiveIds.requireToken(turnId, "turnId");
        if (relatedMessageId != null) {
            relatedMessageId = ArchiveIds.requireToken(relatedMessageId, "relatedMessageId");
        }
        if (source == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "source must not be null");
        }
        if (role == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "role must not be null");
        }
        if (text == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "text must not be null");
        }
        if (recordedAtEpochMillis < 0L) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "recordedAtEpochMillis must not be negative");
        }
        originWorldId = ArchiveIds.requireToken(originWorldId, "originWorldId");
        if (sequence < 0L) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "sequence must not be negative");
        }
        if (attachmentRefs == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION, "attachmentRefs must not be null");
        }
        List<String> refs = new ArrayList<>(attachmentRefs.size());
        for (String ref : attachmentRefs) {
            if (ref == null || ref.isBlank() || ref.length() > 512 || ref.contains("..")
                    || ref.indexOf('\\') >= 0 || ref.indexOf('/') >= 0) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "invalid attachment reference: " + ref);
            }
            refs.add(ref);
        }
        attachmentRefs = List.copyOf(refs);
        if (relatedEventId != null) {
            relatedEventId = ArchiveIds.requireToken(relatedEventId, "relatedEventId");
        }
    }

    /** 旧 12 参数入口；结构化事件为 null，保持既有调用可编译。 */
    public ArchiveRecord(String messageId, String turnId, String relatedMessageId,
                         ArchiveSource source, ArchiveRole role, String text,
                         long recordedAtEpochMillis, String originWorldId, long originGameTime,
                         long sequence, List<String> attachmentRefs, String relatedEventId) {
        this(messageId, turnId, relatedMessageId, source, role, text,
                recordedAtEpochMillis, originWorldId, originGameTime, sequence,
                attachmentRefs, relatedEventId, null);
    }

    public ArchiveRecord withSequence(long newSequence) {
        return new ArchiveRecord(messageId, turnId, relatedMessageId, source, role, text,
                recordedAtEpochMillis, originWorldId, originGameTime, newSequence,
                attachmentRefs, relatedEventId, event);
    }
}
