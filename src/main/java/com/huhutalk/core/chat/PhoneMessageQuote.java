package com.huhutalk.core.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.huhutalk.core.archive.ArchiveEvent;
import com.huhutalk.core.archive.ArchiveEventKind;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveItemSummary;
import com.huhutalk.core.archive.ArchiveRecord;
import com.huhutalk.core.archive.ArchiveRole;
import com.huhutalk.core.archive.ArchiveTransferDirection;
import com.huhutalk.core.archive.ArchiveTransferOutcome;
import com.huhutalk.core.archive.PhoneMemoryProtocol;

import java.util.List;

/**
 * 一条可挂到女仆回复上的只读引用。
 *
 * <p>只从真实归档记录派生，保存消息 ID、作者、类型、单行预览与归档时间。
 * 不包含图片字节、本地路径、领取凭据或可重放的事件能力；未知时间保留为 0，
 * 绝不用当前时钟补造。图片/物品的作者与展示文案由后续客户端 i18n 决定。</p>
 *
 * @param messageId             归档中的真实消息 ID
 * @param role                  只能是 PLAYER 或 MAID
 * @param kind                  TEXT / IMAGE / ITEMS
 * @param preview               有界单行预览；IMAGE 可为空
 * @param recordedAtEpochMillis 归档记录时间，0 表示旧档未知
 */
public record PhoneMessageQuote(
        String messageId,
        ArchiveRole role,
        Kind kind,
        String preview,
        long recordedAtEpochMillis
) {
    public enum Kind {
        TEXT,
        IMAGE,
        ITEMS
    }

    public static final int MAX_PREVIEW_CHARS = 160;
    public static final int MAX_JSON_CHARS = 2048;
    private static final int MAX_ITEMS_IN_PREVIEW = 3;
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public PhoneMessageQuote {
        messageId = PhoneMemoryProtocol.requireMessageId(messageId);
        if (role != ArchiveRole.PLAYER && role != ArchiveRole.MAID) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote role must be PLAYER or MAID");
        }
        if (kind == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote kind must not be null");
        }
        if (preview == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote preview must not be null");
        }
        if (preview.length() > MAX_PREVIEW_CHARS) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote preview must not exceed " + MAX_PREVIEW_CHARS + " characters");
        }
        if (preview.indexOf('\n') >= 0 || preview.indexOf('\r') >= 0) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote preview must be a single line");
        }
        if (recordedAtEpochMillis < 0L) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote recordedAtEpochMillis must not be negative");
        }
    }

    /**
     * 从真实归档记录派生引用，也是唯一的引用资格/作者派生入口。
     *
     * <p>真实 {@code ITEMS_SENT} 事件只要方向是 P→M 或 M→P、结果不是 FAILED，
     * 即使生产记录是 TOOL/SYSTEM（M→P 入箱 queued、P→M 完成）也可引用；作者由
     * direction 映射，不看归档 role。领取、丢地、发往世界或失败事件不能伪称“发送”。
     * 非物品事件仍只允许 PLAYER/MAID 原话；图片附件/图片事件支持未来 MAID 图片。
     * 观察、普通 TOOL/SYSTEM 文字不得冒充玩家/女仆说过的话。</p>
     */
    public static PhoneMessageQuote fromRecord(ArchiveRecord record) {
        if (record == null) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote record must not be null");
        }

        ArchiveEvent event = record.event();
        if (event != null && event.kind().isItemEvent()) {
            if (event.kind() != ArchiveEventKind.ITEMS_SENT
                    || event.outcome() == null
                    || event.outcome() == ArchiveTransferOutcome.FAILED) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "only non-failed ITEMS_SENT events can be quoted");
            }
            ArchiveRole author;
            if (event.direction() == ArchiveTransferDirection.PLAYER_TO_MAID) {
                author = ArchiveRole.PLAYER;
            } else if (event.direction() == ArchiveTransferDirection.MAID_TO_PLAYER) {
                author = ArchiveRole.MAID;
            } else {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "item quote direction must be between player and maid");
            }
            if (!isTransferRecordRole(record.role())) {
                throw new ArchiveException(ArchiveException.Code.VALIDATION,
                        "item transfer quote role must be PLAYER, MAID, SYSTEM or TOOL");
            }
            return new PhoneMessageQuote(record.messageId(), author, Kind.ITEMS,
                    itemsPreview(event.items()), record.recordedAtEpochMillis());
        }

        if (record.role() != ArchiveRole.PLAYER && record.role() != ArchiveRole.MAID) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "only PLAYER or MAID text records can be quoted");
        }

        Kind kind;
        String preview;
        if (!record.attachmentRefs().isEmpty()
                || (event != null && event.kind() == ArchiveEventKind.IMAGE_SENT)) {
            kind = Kind.IMAGE;
            preview = "";
        } else {
            kind = Kind.TEXT;
            preview = oneLine(record.text());
        }
        return new PhoneMessageQuote(record.messageId(), record.role(), kind, preview,
                record.recordedAtEpochMillis());
    }

    /** 真实物品转移事实可能由玩家/女仆原记录或 TOOL/SYSTEM 生产记录承载。 */
    private static boolean isTransferRecordRole(ArchiveRole role) {
        return role == ArchiveRole.PLAYER || role == ArchiveRole.MAID
                || role == ArchiveRole.SYSTEM || role == ArchiveRole.TOOL;
    }

    /** 固定字段顺序的合法 JSON；字段本身均有界，整体不超过 {@link #MAX_JSON_CHARS}。 */
    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("messageId", messageId);
        object.addProperty("role", role.name());
        object.addProperty("kind", kind.name());
        object.addProperty("preview", preview);
        object.addProperty("recordedAtEpochMillis", recordedAtEpochMillis);
        return GSON.toJson(object);
    }

    /**
     * 解析引用 JSON。null/空白表示本回复没有引用；过长、坏 JSON、错误类型、非法
     * role/kind/preview/时间或非法消息 ID 都显式失败，不返回伪造的默认引用。
     * 未知字段按 JSON 兼容惯例忽略。
     */
    public static PhoneMessageQuote fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        if (json.length() > MAX_JSON_CHARS) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "quote JSON must not exceed " + MAX_JSON_CHARS + " characters");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException | IllegalStateException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "quote JSON is not valid JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    "quote JSON root must be an object");
        }
        JsonObject object = parsed.getAsJsonObject();
        String messageId = requireString(object, "messageId");
        ArchiveRole role = ArchiveRole.fromJson(requireString(object, "role"));
        Kind kind;
        try {
            kind = Kind.valueOf(requireString(object, "kind"));
        } catch (IllegalArgumentException exception) {
            throw new ArchiveException(ArchiveException.Code.VALIDATION,
                    "unknown quote kind", exception);
        }
        String preview = requireString(object, "preview");
        long recordedAt = requireLong(object, "recordedAtEpochMillis");
        return new PhoneMessageQuote(messageId, role, kind, preview, recordedAt);
    }

    private static String itemsPreview(List<ArchiveItemSummary> items) {
        StringBuilder builder = new StringBuilder();
        int count = Math.min(items.size(), MAX_ITEMS_IN_PREVIEW);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('、');
            }
            ArchiveItemSummary item = items.get(i);
            // 合法 displayName 仍可能含换行；引用预览必须是单行。
            builder.append(oneLine(item.displayName())).append('×').append(item.count());
        }
        if (items.size() > count) {
            builder.append('…');
        }
        return truncate(builder.toString(), MAX_PREVIEW_CHARS);
    }

    private static String oneLine(String text) {
        String normalized = text.replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return truncate(normalized, MAX_PREVIEW_CHARS);
    }

    /** UTF-16 安全裁剪，不把代理对拆成孤立半字符。 */
    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (end > 0 && end < value.length() && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a string");
        }
        return element.getAsString();
    }

    private static long requireLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a number");
        }
        try {
            return element.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ArchiveException(ArchiveException.Code.CORRUPT,
                    field + " must be a 64-bit integer", exception);
        }
    }
}
