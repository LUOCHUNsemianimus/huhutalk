package com.huhutalk.core.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huhutalk.core.archive.ArchiveException;
import com.huhutalk.core.archive.ArchiveRole;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** PhoneMessageQuote 的共享 JSON 边界与非法输入测试。 */
public class PhoneQuoteJsonTest {
    private static final long TIME = 1_700_000_000_000L;

    @Test
    public void validQuoteRoundTripsWithinJsonBoundAndIgnoresUnknownFields() {
        PhoneMessageQuote max = new PhoneMessageQuote("m".repeat(128), ArchiveRole.MAID,
                PhoneMessageQuote.Kind.ITEMS, "字".repeat(
                        PhoneMessageQuote.MAX_PREVIEW_CHARS), Long.MAX_VALUE);
        String json = max.toJson();
        assertTrue(json.length() <= PhoneMessageQuote.MAX_JSON_CHARS);
        assertEquals(max, PhoneMessageQuote.fromJson(json));

        JsonObject withUnknown = JsonParser.parseString(json).getAsJsonObject();
        withUnknown.addProperty("futureField", "ignored");
        assertEquals(max, PhoneMessageQuote.fromJson(withUnknown.toString()));

        PhoneMessageQuote image = new PhoneMessageQuote("msg-image", ArchiveRole.PLAYER,
                PhoneMessageQuote.Kind.IMAGE, "", 0L);
        assertEquals(image, PhoneMessageQuote.fromJson(image.toJson()));
    }

    @Test
    public void nullOrBlankMeansNoQuote() {
        assertNull(PhoneMessageQuote.fromJson(null));
        assertNull(PhoneMessageQuote.fromJson(""));
        assertNull(PhoneMessageQuote.fromJson("   "));
    }

    @Test
    public void oversizeAndMalformedJsonAreRejected() {
        String oversize = "{\"padding\":\"" + "a".repeat(
                PhoneMessageQuote.MAX_JSON_CHARS) + "\"}";
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(oversize));

        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson("{not valid"));
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson("[]"));
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson("{\"messageId\":\"msg\"}"));

        JsonObject wrongMessage = validObject();
        wrongMessage.addProperty("messageId", 123);
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson(wrongMessage.toString()));

        JsonObject wrongTimeType = validObject();
        wrongTimeType.addProperty("recordedAtEpochMillis", "1000");
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson(wrongTimeType.toString()));

        JsonObject fractionalTime = validObject();
        fractionalTime.addProperty("recordedAtEpochMillis", 1.5);
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson(fractionalTime.toString()));

        JsonObject outOfRangeTime = validObject();
        outOfRangeTime.addProperty("recordedAtEpochMillis",
                new java.math.BigDecimal("9223372036854775808"));
        expectArchiveFailure(ArchiveException.Code.CORRUPT,
                () -> PhoneMessageQuote.fromJson(outOfRangeTime.toString()));
    }

    @Test
    public void invalidSemanticFieldsAreRejected() {
        JsonObject observationRole = validObject();
        observationRole.addProperty("role", "OBSERVATION");
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(observationRole.toString()));

        JsonObject unknownRole = validObject();
        unknownRole.addProperty("role", "HACKER");
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(unknownRole.toString()));

        JsonObject unknownKind = validObject();
        unknownKind.addProperty("kind", "VIDEO");
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(unknownKind.toString()));

        JsonObject negativeTime = validObject();
        negativeTime.addProperty("recordedAtEpochMillis", -1L);
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(negativeTime.toString()));

        JsonObject multiline = validObject();
        multiline.addProperty("preview", "a\nb");
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(multiline.toString()));

        JsonObject overlong = validObject();
        overlong.addProperty("preview", "字".repeat(
                PhoneMessageQuote.MAX_PREVIEW_CHARS + 1));
        expectArchiveFailure(ArchiveException.Code.VALIDATION,
                () -> PhoneMessageQuote.fromJson(overlong.toString()));

        JsonObject badToken = validObject();
        badToken.addProperty("messageId", "../bad");
        expectArchiveFailure(ArchiveException.Code.INVALID_IDENTIFIER,
                () -> PhoneMessageQuote.fromJson(badToken.toString()));
    }

    private static PhoneMessageQuote valid() {
        return new PhoneMessageQuote("msg-quote", ArchiveRole.PLAYER,
                PhoneMessageQuote.Kind.TEXT, "单行预览", TIME);
    }

    private static JsonObject validObject() {
        return JsonParser.parseString(valid().toJson()).getAsJsonObject();
    }

    private static void expectArchiveFailure(ArchiveException.Code expected, Runnable action) {
        ArchiveException exception = assertThrows(ArchiveException.class, action::run);
        assertEquals(expected, exception.code());
    }
}
