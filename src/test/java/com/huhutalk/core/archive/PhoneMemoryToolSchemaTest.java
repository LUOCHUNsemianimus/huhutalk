package com.huhutalk.core.archive;

import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * 真实手机工具参数 schema 与 codec 默认值的类型兼容测试。
 *
 * <p>TLM 的 {@code Parameter.defaultValue} 固定是 {@code String}，而 Gson 会把它直接写成
 * JSON 的 {@code default}。数值/布尔参数一旦设置这个 metadata，provider 收到的函数定义就会出现
 * {@code type=integer/boolean} 却带字符串 default 的不一致。这里用真实工具、真实
 * {@link ObjectParameter} 和 Gson 序列化确认这五个参数不再携带错误 default，同时用真实 codec
 * 确认省略参数时的运行默认值没有变化。</p>
 *
 * <p>不启动 Minecraft、不碰磁盘、不新增依赖或 Mock。</p>
 */
public class PhoneMemoryToolSchemaTest {
    private static final Gson GSON = new GsonBuilder().create();

    @Test
    public void numericAndBooleanParametersCarryNoStringDefaultMetadata() {
        JsonObject searchSchema = GSON.toJsonTree(new SearchPhoneHistoryTool()
                .parameters(ObjectParameter.create(), null)).getAsJsonObject();
        JsonObject searchProperties = searchSchema.getAsJsonObject("properties");
        assertTypedWithoutDefault(searchProperties, "offset", "integer");
        assertTypedWithoutDefault(searchProperties, "limit", "integer");
        JsonObject role = searchProperties.getAsJsonObject("role");
        assertEquals("string", role.get("type").getAsString());
        assertEquals("player", role.get("default").getAsString());

        JsonObject getSchema = GSON.toJsonTree(new GetPhoneMessageTool()
                .parameters(ObjectParameter.create(), null)).getAsJsonObject();
        JsonObject getProperties = getSchema.getAsJsonObject("properties");
        assertTypedWithoutDefault(getProperties, "text_offset", "integer");
        assertTypedWithoutDefault(getProperties, "max_chars", "integer");
        assertTypedWithoutDefault(getProperties, "quote", "boolean");

        assertEquals("object", searchSchema.get("type").getAsString());
        assertEquals("object", getSchema.get("type").getAsString());
    }

    @Test
    public void codecDefaultsStayPlayerZeroFiveAndZeroTwoThousandFalse() {
        JsonObject searchArguments = new JsonObject();
        searchArguments.addProperty("query", "星糖");
        SearchPhoneHistoryTool.Result search = new SearchPhoneHistoryTool().codec()
                .parse(JsonOps.INSTANCE, searchArguments).result().orElseThrow();
        assertEquals("player", search.role());
        assertEquals(0, search.offset());
        assertEquals(PhoneMemoryProtocol.MAX_SEARCH_RESULTS, search.limit());

        JsonObject getArguments = new JsonObject();
        getArguments.addProperty("message_id", "smoke-message-id");
        GetPhoneMessageTool.Result get = new GetPhoneMessageTool().codec()
                .parse(JsonOps.INSTANCE, getArguments).result().orElseThrow();
        assertEquals(0, get.textOffset());
        assertEquals(2000, get.maxChars());
        assertFalse(get.quote());
    }

    private static void assertTypedWithoutDefault(JsonObject properties, String name, String type) {
        JsonObject property = properties.getAsJsonObject(name);
        assertNotNull("missing parameter: " + name, property);
        assertEquals(name, type, property.get("type").getAsString());
        assertFalse(name + " must not serialize a string default", property.has("default"));
    }
}
