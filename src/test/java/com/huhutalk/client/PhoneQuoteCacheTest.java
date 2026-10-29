package com.huhutalk.client;

import com.google.gson.Gson;
import com.huhutalk.core.archive.ArchiveRole;
import com.huhutalk.core.chat.PhoneMessageQuote;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 真实客户端缓存 DTO 的 Gson 兼容测试：不启动 Minecraft、不引入 Mockito。
 *
 * <p>覆盖旧 client JSON 的 7/8 字段（无 quote）读取，以及新 quote 字段（含未来
 * MAID + IMAGE 引用）的完整往返。</p>
 */
public class PhoneQuoteCacheTest {
    private static final Gson GSON = new Gson();
    private static final long TIME = 1_700_000_000_000L;

    @Test
    public void oldSevenFieldEntryReadsWithoutQuote() {
        String json = "{"
                + "\"text\":\"旧消息\","
                + "\"isPlayer\":true,"
                + "\"isError\":false,"
                + "\"pending\":true,"
                + "\"gameTime\":12,"
                + "\"emote\":null,"
                + "\"image\":null"
                + "}";

        ChatHistoryStorage.ChatMessageEntry entry =
                GSON.fromJson(json, ChatHistoryStorage.ChatMessageEntry.class);

        assertEquals("旧消息", entry.text());
        assertEquals(12L, entry.gameTime());
        assertNull(entry.item());
        assertNull(entry.quote());
    }

    @Test
    public void oldEightFieldEntryReadsWithoutQuote() {
        String json = "{"
                + "\"text\":\"\","
                + "\"isPlayer\":false,"
                + "\"isError\":false,"
                + "\"pending\":false,"
                + "\"gameTime\":34,"
                + "\"emote\":null,"
                + "\"image\":null,"
                + "\"item\":{\"tid\":\"transfer-1\",\"previews\":[],"
                + "\"total\":2,\"pending\":1,\"used\":false,\"effect\":\"\"}"
                + "}";

        ChatHistoryStorage.ChatMessageEntry entry =
                GSON.fromJson(json, ChatHistoryStorage.ChatMessageEntry.class);

        assertNotNull(entry.item());
        assertEquals("transfer-1", entry.item().transferId);
        assertNull(entry.quote());
    }

    @Test
    public void newQuoteIncludingFutureMaidImageRoundTrips() {
        PhoneMessageQuote quote = new PhoneMessageQuote("msg-maid-image",
                ArchiveRole.MAID, PhoneMessageQuote.Kind.IMAGE, "", TIME);
        ChatHistoryStorage.ChatMessageEntry entry = new ChatHistoryStorage.ChatMessageEntry(
                "正文", false, false, false, 56L, null, null, null, quote);

        ChatHistoryStorage.ChatMessageEntry restored =
                GSON.fromJson(GSON.toJson(entry), ChatHistoryStorage.ChatMessageEntry.class);

        assertNotNull(restored.quote());
        assertEquals("msg-maid-image", restored.quote().messageId());
        assertEquals(ArchiveRole.MAID, restored.quote().role());
        assertEquals(PhoneMessageQuote.Kind.IMAGE, restored.quote().kind());
        assertEquals("", restored.quote().preview());
        assertEquals(TIME, restored.quote().recordedAtEpochMillis());
        assertEquals("正文", restored.text());
    }
}
