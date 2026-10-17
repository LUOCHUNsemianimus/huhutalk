package com.huhutalk.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/**
 * 模组自有「系统文案」的可翻译共享表示。
 *
 * <h2>为什么需要它</h2>
 * 有一批系统文案要走 String 通道：{@code S2CMaidStatusPacket} 的状态小字、聊天错误/提示、
 * 物品使用效果，以及被 Gson 存档的 {@code ItemTransfer.effectText}。这些通道的公开类型、
 * packet codec 和存档结构都已固定，不能为了中英适配把它们全部改成 {@link net.minecraft.network.chat.Component}
 * 或另建一整套数据类。这里用一个<b>仍然只是 String</b> 的紧凑编码承载「语言键 + 参数」：
 * 服务端/存档保存的是与语言无关的稳定 key，显示时再按当前语言解析。
 *
 * <h2>格式与兜底</h2>
 * 编码形如 {@code {"ht":1,"k":"status.huhutalk.farming"}}（无参数时省略 {@code a}）。
 * {@code ht} 是固定版本标记且固定排在第一位。{@link #render} 只识别这一种合法格式：
 * <ul>
 *   <li>普通字符串（历史缓存、玩家自建内容、AI 回复）不认识标记，<b>原样返回</b>，绝不改写用户内容。</li>
 *   <li>非法 JSON、版本不符、键不在白名单内，同样原样返回。</li>
 * </ul>
 *
 * <h2>参数</h2>
 * 只编码字符串与数值，且数值原样保留（调用方传 {@code Integer 8} 就显示 {@code 8}）；
 * 字符串参数若是另一个 {@link #of} 结果，会在 {@link #render} 中递归翻译，
 * 于是「异常原因」这类嵌套参数也能本地化。未知类型参数视为调用错误，直接抛异常而非静默兜底。
 *
 * <p>本类不依赖任何客户端类；显示入口见 {@code PhoneText.system}。
 */
public final class SystemText {

    /** 合法语言键前缀：只处理模组自己拥有的系统字段。 */
    private static final String[] KEY_PREFIXES = {
            "gui.huhutalk.", "message.huhutalk.", "status.huhutalk."
    };

    private static final String FIELD_VERSION = "ht";
    private static final String FIELD_KEY = "k";
    private static final String FIELD_ARGS = "a";
    private static final int VERSION = 1;

    /** 快速识别前缀（字段顺序固定为 ht、k、a，故开头可预判）。 */
    private static final String PREFIX = "{\"" + FIELD_VERSION + "\":" + VERSION + ",";

    private SystemText() {
    }

    /**
     * 把稳定语言键与参数编码成可翻译表示。
     *
     * @param key  语言键，必须以 {@code gui.huhutalk.}/{@code message.huhutalk.}/{@code status.huhutalk.} 开头
     * @param args 字符串或数值参数；字符串参数可再传一个 {@link #of} 结果以嵌套翻译
     * @return 紧凑 JSON 表示
     * @throws IllegalArgumentException 键不在白名单或为 {@code null}，或参数不是 {@code String}/{@code Number}
     *                                  —— 这些都属于调用方错误，不静默兜底
     */
    public static String of(String key, Object... args) {
        if (key == null) {
            throw new IllegalArgumentException("SystemText 键不能为 null");
        }
        if (!isAllowedKey(key)) {
            throw new IllegalArgumentException("SystemText 键不在白名单：" + key);
        }
        JsonObject object = new JsonObject();
        object.addProperty(FIELD_VERSION, VERSION);
        object.addProperty(FIELD_KEY, key);
        if (args != null && args.length > 0) {
            JsonArray array = new JsonArray();
            for (Object arg : args) {
                array.add(toJson(arg));
            }
            object.add(FIELD_ARGS, array);
        }
        return object.toString();
    }

    /**
     * 用给定翻译器解析可翻译表示。
     *
     * @param text       存储/传输中的原始字符串；普通字符串原样返回
     * @param translator {@code (key, args) -> 当前语言文本}，由客户端提供（见 {@code PhoneText.system}）
     * @return 当前语言文本，或无法识别时的原字符串
     */
    public static String render(String text, BiFunction<String, Object[], String> translator) {
        if (text == null || text.isEmpty() || translator == null || !text.startsWith(PREFIX)) {
            return text;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return text;
        }
        if (!parsed.isJsonObject()) {
            return text;
        }
        JsonObject object = parsed.getAsJsonObject();

        JsonElement versionElement = object.get(FIELD_VERSION);
        if (versionElement == null || !versionElement.isJsonPrimitive()
                || !versionElement.getAsJsonPrimitive().isNumber()
                || versionElement.getAsInt() != VERSION) {
            return text;
        }
        JsonElement keyElement = object.get(FIELD_KEY);
        if (keyElement == null || !keyElement.isJsonPrimitive()
                || !keyElement.getAsJsonPrimitive().isString()) {
            return text;
        }
        String key = keyElement.getAsString();
        if (!isAllowedKey(key)) {
            return text;
        }

        Object[] args = new Object[0];
        JsonElement argsElement = object.get(FIELD_ARGS);
        if (argsElement != null) {
            if (!argsElement.isJsonArray()) {
                return text;
            }
            JsonArray array = argsElement.getAsJsonArray();
            args = new Object[array.size()];
            for (int i = 0; i < array.size(); i++) {
                Object decoded = decodeArg(array.get(i), translator);
                if (decoded == UNSUPPORTED) {
                    return text;
                }
                args[i] = decoded;
            }
        }
        return translator.apply(key, args);
    }

    /** 参数不是 String/Number 时的哨兵：整条表示原样返回，不吞成空串。 */
    private static final Object UNSUPPORTED = new Object();

    /**
     * 把异常转成适合展示的原因文本，供需要本地化模板的调用方作为参数使用。
     * <ul>
     *   <li>仅解开 {@link CompletionException}/{@link ExecutionException} 包装，取最内层实际原因；</li>
     *   <li>有 message 用 message；没有则退回原因类名，保留技术细节。</li>
     * </ul>
     * <p>仅用于显示，不替代日志，也不改变异常处理结果。
     */
    public static String errorDetail(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return cause.getClass().getName();
    }

    /** 递归解析字符串参数：嵌套的 {@link #of} 结果会被翻译，普通字符串原样；不支持的类型返回哨兵。 */
    private static Object decodeArg(JsonElement element, BiFunction<String, Object[], String> translator) {
        if (element == null || !element.isJsonPrimitive()) {
            return UNSUPPORTED;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            return render(primitive.getAsString(), translator);
        }
        if (primitive.isNumber()) {
            // Number 原样交给 %s，保留原字面量（Integer 8 仍是 8）
            return primitive.getAsNumber();
        }
        return UNSUPPORTED;
    }

    private static JsonElement toJson(Object arg) {
        if (arg instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (arg instanceof String text) {
            return new JsonPrimitive(text);
        }
        throw new IllegalArgumentException("SystemText 参数只支持 String/Number，收到："
                + (arg == null ? "null" : arg.getClass().getName()));
    }

    private static boolean isAllowedKey(String key) {
        for (String prefix : KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
