package com.huhutalk.core.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import static com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite.LLM_HTTP_CLIENT;

/**
 * 服务端识图服务（DeepSeek 视觉模型，单轮、不存上下文）。
 * <p>
 * 复用车万女仆(TLM)的 DeepSeek 站点（{@code LLMSite.LLM_HTTP_CLIENT} + 已有 api key）与 Gson 基础设施，
 * 向 DeepSeek chat-completions 多模态接口发一条 user 消息，返回对图片的文字描述。
 * 与 {@code MaidAIChatManager} 完全解耦：不需要女仆、不需要历史。
 * <p>
 * 图片通过 {@code data:image/png;base64,...} 内联传入（≤32MiB）。
 */
public final class VisionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** DeepSeek chat-completions 端点 */
    public static final String BASE_URL = "https://api.deepseek.com/chat/completions";
    /** 默认视觉模型（DeepSeek 支持 deepseek-v4-pro / deepseek-v4-flash / deepseek-v4-flash-vision-exp） */
    public static final String DEFAULT_MODEL = "deepseek-v4-flash-vision-exp";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private VisionService() {
    }

    /** 视觉模型 id（可运行时覆盖；默认 {@link #DEFAULT_MODEL}） */
    private static String model = DEFAULT_MODEL;

    public static void setModel(String m) {
        if (m != null && !m.isBlank()) {
            model = m;
        }
    }

    /** 从 TLM 服务端缓存的 DeepSeek 站点读取 api key；未配置返回 null */
    public static String resolveApiKey() {
        try {
            var site = AvailableSites.LLM_SITES.get("deepseek");
            if (site instanceof LLMOpenAISite oai) {
                String key = oai.secretKey();
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[HuhuTalk] 读取 DeepSeek api key 失败", t);
        }
        return null;
    }

    /**
     * 对一张 PNG 图片做单轮识图。
     *
     * @param imagePng PNG 字节
     * @param prompt   识图引导（如 "请描述这张图片的内容"）
     * @return 异步返回描述文本；失败时由异常带出
     */
    public static CompletableFuture<String> describe(byte[] imagePng, String prompt) {
        String apiKey = resolveApiKey();
        if (apiKey == null) {
            LOGGER.error("[HuhuTalk] 识图失败：未配置 DeepSeek api key（AvailableSites.LLM_SITES['deepseek'] 无 key）");
            return CompletableFuture.failedFuture(new IllegalStateException("未配置 DeepSeek api key，请在车万女仆 AI 设置里配置 DeepSeek 站点。"));
        }
        if (imagePng == null || imagePng.length == 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("图片数据为空"));
        }
        LOGGER.info("[HuhuTalk] 发起识图: model={}, imgBytes={}, keyConfigured=true", model, imagePng.length);
        String b64 = Base64.getEncoder().encodeToString(imagePng);
        String dataUrl = "data:image/png;base64," + b64;

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", prompt == null || prompt.isBlank() ? "请详细描述这张图片的内容。" : prompt);
        JsonObject img = new JsonObject();
        img.addProperty("type", "image_url");
        JsonObject iu = new JsonObject();
        iu.addProperty("url", dataUrl);
        img.add("image_url", iu);
        content.add(text);
        content.add(img);
        msg.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(msg);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return LLM_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(VisionService::extractContent);
    }

    /** 解析响应，取 choices[0].message.content；非 2xx 抛异常 */
    private static String extractContent(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            LOGGER.error("[HuhuTalk] 识图 HTTP 错误 {}: {}", code, truncate(response.body()));
            throw new IllegalStateException("HTTP %d: %s".formatted(code, truncate(response.body())));
        }
        try {
            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                LOGGER.warn("[HuhuTalk] 识图响应无 choices: {}", truncate(response.body()));
                return "";
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                LOGGER.warn("[HuhuTalk] 识图响应无 message: {}", truncate(response.body()));
                return "";
            }
            String content = message.get("content") == null ? "" : message.get("content").getAsString();
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            LOGGER.error("[HuhuTalk] 解析识图响应失败: {}", truncate(response.body()), e);
            throw new IllegalStateException("解析识图响应失败: " + truncate(response.body()), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
