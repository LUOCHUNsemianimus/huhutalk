package com.huhutalk.core.chat;

import com.huhutalk.core.archive.ArchiveEvent;
import com.huhutalk.core.archive.ArchiveEventKind;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveItemSummary;
import com.huhutalk.core.archive.ArchiveRecord;
import com.huhutalk.core.archive.ArchiveRole;
import com.huhutalk.core.archive.ArchiveSource;
import com.huhutalk.core.archive.ArchiveTransferDirection;
import com.huhutalk.core.archive.ArchiveTransferOutcome;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 真实 ArchiveRecord/Event 上的引用 DTO 边界测试。 */
public class PhoneMessageQuoteTest {
    private static final String WORLD_ID = "33333333-3333-3333-3333-333333333333";
    private static final long TIME = 1_700_000_000_000L;
    private static final String IMAGE_HASH = "c".repeat(64);

    @Test
    public void textPreviewIsSingleLineAndUtf16Bounded() {
        String text = "第一行\n第二行\r\n" + "字".repeat(200);
        PhoneMessageQuote quote = PhoneMessageQuote.fromRecord(record("msg-text", ArchiveRole.PLAYER,
                text, TIME, List.of(), null, ArchiveSource.ONLINE_PHONE_TEXT));
        assertEquals(PhoneMessageQuote.Kind.TEXT, quote.kind());
        assertEquals(ArchiveRole.PLAYER, quote.role());
        assertEquals("msg-text", quote.messageId());
        assertEquals(TIME, quote.recordedAtEpochMillis());
        assertFalse(quote.preview().contains("\n"));
        assertFalse(quote.preview().contains("\r"));
        assertTrue(quote.preview().length() <= PhoneMessageQuote.MAX_PREVIEW_CHARS);
        assertTrue(quote.preview().startsWith("第一行 第二行"));

        String surrogateText = "a".repeat(159) + "\uD83D\uDE00" + "尾部";
        PhoneMessageQuote surrogate = PhoneMessageQuote.fromRecord(record("msg-surrogate",
                ArchiveRole.PLAYER, surrogateText, TIME, List.of(), null,
                ArchiveSource.ONLINE_PHONE_TEXT));
        assertEquals(159, surrogate.preview().length());
        assertFalse(hasLoneSurrogate(surrogate.preview()));
        assertEquals("a".repeat(159), surrogate.preview());
    }

    @Test
    public void imageIncludesFutureMaidImage() {
        PhoneMessageQuote maidImage = PhoneMessageQuote.fromRecord(record("msg-maid-image",
                ArchiveRole.MAID, "", TIME, List.of(IMAGE_HASH), ArchiveEvent.imageSent(),
                ArchiveSource.ONLINE_PHONE_IMAGE));
        assertEquals(PhoneMessageQuote.Kind.IMAGE, maidImage.kind());
        assertEquals(ArchiveRole.MAID, maidImage.role());
        assertEquals("", maidImage.preview());
        assertEquals(TIME, maidImage.recordedAtEpochMillis());

        PhoneMessageQuote playerAttachmentOnly = PhoneMessageQuote.fromRecord(record("msg-player-image",
                ArchiveRole.PLAYER, "图片说明", TIME, List.of(IMAGE_HASH), null,
                ArchiveSource.ONLINE_PHONE_IMAGE));
        assertEquals(PhoneMessageQuote.Kind.IMAGE, playerAttachmentOnly.kind());
        assertEquals("", playerAttachmentOnly.preview());
    }

    @Test
    public void itemPreviewKeepsBothDirectionsAndBoundedFacts() {
        List<ArchiveItemSummary> items = List.of(
                new ArchiveItemSummary("minecraft:diamond", "钻石", 2, null),
                new ArchiveItemSummary("minecraft:bread", "面包", 3, null),
                new ArchiveItemSummary("minecraft:apple", "苹果", 1, null),
                new ArchiveItemSummary("minecraft:stick", "木棍", 4, null));
        ArchiveEvent sent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                items, "transfer-secret-player");
        // 新契约只允许真实发送事件；M→P 的 LEGACY 入箱仍以 ITEMS_SENT 事实引用。
        ArchiveEvent maidSent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.QUEUED,
                items, "transfer-secret-maid");

        PhoneMessageQuote playerQuote = PhoneMessageQuote.fromRecord(record("msg-items-player",
                ArchiveRole.PLAYER, "给你一些东西", TIME, List.of(), sent,
                ArchiveSource.ONLINE_PHONE_ITEMS));
        PhoneMessageQuote maidQuote = PhoneMessageQuote.fromRecord(record("msg-items-maid",
                ArchiveRole.MAID, "", TIME, List.of(), maidSent,
                ArchiveSource.ONLINE_PHONE_ITEMS));

        assertEquals(PhoneMessageQuote.Kind.ITEMS, playerQuote.kind());
        assertEquals(PhoneMessageQuote.Kind.ITEMS, maidQuote.kind());
        assertEquals(ArchiveRole.PLAYER, playerQuote.role());
        assertEquals(ArchiveRole.MAID, maidQuote.role());
        assertTrue(playerQuote.preview().contains("钻石×2"));
        assertTrue(playerQuote.preview().contains("面包×3"));
        assertTrue(playerQuote.preview().contains("苹果×1"));
        assertFalse(playerQuote.preview().contains("木棍"));
        assertTrue(playerQuote.preview().endsWith("…"));
        assertTrue(playerQuote.preview().length() <= PhoneMessageQuote.MAX_PREVIEW_CHARS);
        assertFalse(playerQuote.preview().contains("transfer-secret"));
        assertFalse(playerQuote.preview().contains("minecraft:"));
    }

    @Test
    public void realTransferProducersMapAuthorByDirection() {
        List<ArchiveItemSummary> items = List.of(
                new ArchiveItemSummary("minecraft:diamond", "钻石", 2, null));
        ArchiveEvent queuedMaidToPlayer = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.MAID_TO_PLAYER, ArchiveTransferOutcome.QUEUED,
                items, "transfer-maid-queued");
        PhoneMessageQuote maidQuote = PhoneMessageQuote.fromRecord(record("msg-tool-items",
                ArchiveRole.TOOL, "", TIME, List.of(), queuedMaidToPlayer,
                ArchiveSource.ONLINE_PHONE_ITEMS));
        assertEquals(ArchiveRole.MAID, maidQuote.role());
        assertEquals(PhoneMessageQuote.Kind.ITEMS, maidQuote.kind());
        assertTrue(maidQuote.preview().contains("钻石×2"));

        ArchiveEvent successPlayerToMaid = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                items, "transfer-player-success");
        PhoneMessageQuote playerQuote = PhoneMessageQuote.fromRecord(record("msg-system-items",
                ArchiveRole.SYSTEM, "", TIME, List.of(), successPlayerToMaid,
                ArchiveSource.ONLINE_PHONE_ITEMS));
        assertEquals(ArchiveRole.PLAYER, playerQuote.role());
        assertEquals(PhoneMessageQuote.Kind.ITEMS, playerQuote.kind());
        assertTrue(playerQuote.preview().contains("钻石×2"));
    }

    @Test
    public void nonSendOrNonHumanRecordsCannotBeQuoted() {
        List<ArchiveItemSummary> items = List.of(
                new ArchiveItemSummary("minecraft:diamond", "钻石", 2, null));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(record("msg-failed",
                        ArchiveRole.TOOL, "", TIME, List.of(),
                        ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                                ArchiveTransferDirection.MAID_TO_PLAYER,
                                ArchiveTransferOutcome.FAILED, items, "t1"),
                        ArchiveSource.ONLINE_PHONE_ITEMS)));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(record("msg-claimed",
                        ArchiveRole.TOOL, "", TIME, List.of(),
                        ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_CLAIMED,
                                ArchiveTransferDirection.MAID_TO_PLAYER,
                                ArchiveTransferOutcome.SUCCESS, items, "t2"),
                        ArchiveSource.ONLINE_PHONE_ITEMS)));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(record("msg-dropped",
                        ArchiveRole.TOOL, "", TIME, List.of(),
                        ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_DROPPED,
                                ArchiveTransferDirection.MAID_TO_PLAYER,
                                ArchiveTransferOutcome.SUCCESS, items, "t3"),
                        ArchiveSource.ONLINE_PHONE_ITEMS)));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(record("msg-to-world",
                        ArchiveRole.TOOL, "", TIME, List.of(),
                        ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                                ArchiveTransferDirection.MAID_TO_WORLD,
                                ArchiveTransferOutcome.SUCCESS, items, "t4"),
                        ArchiveSource.ONLINE_PHONE_ITEMS)));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(record("msg-player-world",
                        ArchiveRole.SYSTEM, "", TIME, List.of(),
                        ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                                ArchiveTransferDirection.PLAYER_TO_WORLD,
                                ArchiveTransferOutcome.SUCCESS, items, "t5"),
                        ArchiveSource.ONLINE_PHONE_ITEMS)));

        for (ArchiveRole role : List.of(ArchiveRole.TOOL, ArchiveRole.SYSTEM,
                ArchiveRole.OBSERVATION)) {
            expectArchiveFailure(ArchiveException.Code.VALIDATION,
                    () -> PhoneMessageQuote.fromRecord(record("msg-" + role,
                            role, "普通非人类记录", TIME, List.of(), null,
                            ArchiveSource.OBSERVATION)));
        }
    }

    @Test
    public void itemDisplayNameWithNewlineIsFlattened() {
        List<ArchiveItemSummary> items = List.of(
                new ArchiveItemSummary("minecraft:diamond", "钻石\n碎片", 2, null));
        ArchiveEvent sent = ArchiveEvent.itemEvent(ArchiveEventKind.ITEMS_SENT,
                ArchiveTransferDirection.PLAYER_TO_MAID, ArchiveTransferOutcome.SUCCESS,
                items, "transfer-secret");
        PhoneMessageQuote quote = PhoneMessageQuote.fromRecord(record("msg-items-newline",
                ArchiveRole.PLAYER, "给你一些东西", TIME, List.of(), sent,
                ArchiveSource.ONLINE_PHONE_ITEMS));

        assertEquals(PhoneMessageQuote.Kind.ITEMS, quote.kind());
        assertFalse(quote.preview().contains("\n"));
        assertEquals("钻石 碎片×2", quote.preview());
    }

    @Test
    public void unknownTimeStaysZero() {
        PhoneMessageQuote quote = PhoneMessageQuote.fromRecord(record("msg-old", ArchiveRole.MAID,
                "旧记录", 0L, List.of(), null, ArchiveSource.ONLINE_PHONE_TEXT));
        assertEquals(0L, quote.recordedAtEpochMillis());
    }

    @Test
    public void invalidQuotesAreRejected() {
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote("msg", ArchiveRole.PLAYER,
                        PhoneMessageQuote.Kind.TEXT, "x".repeat(
                                PhoneMessageQuote.MAX_PREVIEW_CHARS + 1), 1L));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote("msg", ArchiveRole.PLAYER,
                        PhoneMessageQuote.Kind.TEXT, "a\nb", 1L));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote("msg", ArchiveRole.OBSERVATION,
                        PhoneMessageQuote.Kind.TEXT, "x", 1L));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote("msg", ArchiveRole.PLAYER,
                        PhoneMessageQuote.Kind.TEXT, "x", -1L));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote(null, ArchiveRole.PLAYER,
                        PhoneMessageQuote.Kind.TEXT, "x", 1L));
        expectArchiveFailure(ArchiveException.Code.INVALID_IDENTIFIER,
                () -> new PhoneMessageQuote("../bad", ArchiveRole.PLAYER,
                        PhoneMessageQuote.Kind.TEXT, "x", 1L));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote("msg", ArchiveRole.PLAYER, null, "x", 1L));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> new PhoneMessageQuote("msg", ArchiveRole.PLAYER,
                        PhoneMessageQuote.Kind.TEXT, null, 1L));

        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(record("msg-obs", ArchiveRole.OBSERVATION,
                        "观察", TIME, List.of(), null, ArchiveSource.OBSERVATION)));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromRecord(null));
    }

    private static ArchiveRecord record(String messageId, ArchiveRole role, String text,
                                        long recordedAt, List<String> attachments,
                                        ArchiveEvent event, ArchiveSource source) {
        return new ArchiveRecord(messageId, "turn-1", null, source, role, text,
                recordedAt, WORLD_ID, 42L, 0L, attachments, null, event);
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static void expectArchiveFailure(ArchiveException.Code expected, Runnable action) {
        ArchiveException exception = assertThrows(ArchiveException.class, action::run);
        assertEquals(expected, exception.code());
    }
}
