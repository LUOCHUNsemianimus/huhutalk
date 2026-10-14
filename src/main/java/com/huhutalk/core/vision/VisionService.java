package com.huhutalk.core.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.huhutalk.common.vision.VisionAppearance;
import com.huhutalk.common.vision.VisionProviders;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite.LLM_HTTP_CLIENT;

/**
 * 服务端识图服务（单轮、不存上下文）。
 *
 * <h2>支持的平台</h2>
 * 任何 <b>OpenAI 兼容</b>的 {@code /chat/completions} 接口 —— 这是全行业的共同子集，
 * 本模组预设的十余家平台（阿里云百炼 / 火山方舟 / 智谱 / Kimi / 混元 / 硅基流动 /
 * 千帆 / 阶跃 / MiniMax / OpenAI / Gemini / Grok / OpenRouter / 自定义）全都符合，
 * 因此只需要<b>一套</b>适配器。
 *
 * <h2>平台参数从哪来</h2>
 * 从车万女仆(TLM)的 LLM 站点里取：{@code url} / {@code secret_key} / {@code headers}。
 * 也就是<b>复用主模组已有的密钥存储</b>（{@code config/touhou_little_maid/sites/llm.json}），
 * 本模组不另存一份。选中哪个站点由玩家在手机设置里决定（见 {@link VisionSettings}）。
 *
 * <h2>与改造前的兼容</h2>
 * 入参里的 {@code siteId} 为 null / 空 / 找不到时，一律退回
 * {@link VisionProviders#DEFAULT_SITE_ID}（= DeepSeek），且默认模型仍是
 * {@code deepseek-v4-flash-vision-exp}。所以<b>老玩家升级后行为一字不变</b>。
 * <p>
 * 图片通过 {@code data:image/png;base64,...} 内联传入（≤32MiB）。
 */
public final class VisionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** 兜底端点：任何站点信息都取不到时用它（DeepSeek chat-completions） */
    public static final String BASE_URL = "https://api.deepseek.com/chat/completions";
    /** 默认视觉模型（DeepSeek 支持 deepseek-v4-flash-vision-exp / deepseek-flash；旧名会被自动路由） */
    public static final String DEFAULT_MODEL = "deepseek-v4-flash-vision-exp";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /**
     * 识图系统提示词（<b>无人设</b>版）：要求她描述画面但不得臆测人物身份。
     * <p>
     * ⚠ 这段文本是"老玩家行为基线"：只要两段外貌描述都没填，
     * {@link #buildPrompt} 就<b>逐字</b>返回它，一个字都不能改。
     */
    public static final String DEFAULT_PROMPT =
            "这是主人刚发给你的一张我的世界图片。请仔细观察画面，尽量具体地描述其中发生了什么、有哪些东西。"
                    + "但是不要猜测其中人物的身份，仅描述外貌、特征";

    /**
     * 组装识图提示词：<b>无人设</b>时逐字返回 {@link #DEFAULT_PROMPT}；
     * 有人在设时换成一套<b>允许并</b>要求<b>判断人物身份</b>的提示词。
     *
     * <h2>为什么必须换掉整段、而不是往后追加信息</h2>
     * {@link #DEFAULT_PROMPT} 里那句"不要猜测其中人物的身份"是<b>明确禁止</b>识别人物的。
     * 只追加外貌信息而不解除这条禁令，模型会照旧拒绝点名 —— 喂多少描述都会被它忽略。
     *
     * <h2>为什么要求模型在描述里直接点名</h2>
     * 女仆在聊天里能看到的只有这段识图描述，看不见提示词。
     * 所以必须在描述里出现"画面里是你自己""画面里是主人"，身份才对下游有意义。
     *
     * <h2>空值必须跳过对应段落</h2>
     * 只有一方填了却把另一方也列出来，会诱导模型硬套一个不存在的身份（幻觉）。
     *
     * @param playerLook 「你的样貌」（主人）；null/空白 = 未填写
     * @param maidLook   「女仆的样貌」（画面所属的那位女仆）；null/空白 = 未填写
     * @return 可直接交给 {@link #describe} 的提示词
     */
    public static String buildPrompt(@Nullable String playerLook, @Nullable String maidLook) {
        String p = VisionAppearance.clean(playerLook);
        String m = VisionAppearance.clean(maidLook);
        if (p.isEmpty() && m.isEmpty()) {
            return DEFAULT_PROMPT;
        }
        StringBuilder sb = new StringBuilder(DEFAULT_PROMPT.length() + p.length() + m.length() + 200);
        sb.append("这是主人刚发给你的一张我的世界图片。请仔细观察画面，尽量具体地描述其中发生了什么、")
                .append("有哪些东西，以及画面里的人物正在做什么（例如在看天、在放方块、在走动）。")
                .append("画面中可能出现的人物身份参考如下，请据此判断画面中的人物是谁：");
        if (!p.isEmpty()) {
            sb.append("\n- 主人（玩家）：").append(p);
        }
        if (!m.isEmpty()) {
            sb.append("\n- 你自己（女仆）：").append(m);
        }
        sb.append("\n如果画面中的人物与上述描述相符，请在描述里明确写出他是谁")
                .append("（例如「画面里是你自己」「画面里是主人」）；")
                .append("如果都不相符、或者画面里没有人，就只描述外貌与动作，不要臆测身份。");
        return sb.toString();
    }

    private VisionService() {
    }

    /** 视觉模型 id（可运行时覆盖；默认 {@link #DEFAULT_MODEL}） */
    private static String model = DEFAULT_MODEL;

    public static void setModel(String m) {
        if (m != null && !m.isBlank()) {
            model = m;
        }
    }

    /**
     * 对一张 PNG 图片做单轮识图（<b>旧签名，行为与改造前完全一致</b>）。
     * <p>
     * 等价于 {@code describe(imagePng, prompt, null, null)}：走 DeepSeek 站点 + 默认模型。
     */
    public static CompletableFuture<String> describe(byte[] imagePng, String prompt) {
        return describe(imagePng, prompt, null, null);
    }

    /**
     * 对一张 PNG 图片做单轮识图，指定平台与模型。
     *
     * @param imagePng PNG 字节
     * @param prompt   识图引导；空则用 {@link #DEFAULT_PROMPT}
     * @param siteId   平台（TLM 站点 id）；null / 空 / 不存在 → 退回 DeepSeek
     * @param modelId  模型名；null / 空 → 用站点的第一个模型，仍为空则用 {@link #DEFAULT_MODEL}
     * @return 异步返回描述文本；失败时由异常带出
     */
    public static CompletableFuture<String> describe(byte[] imagePng, String prompt,
                                                     @Nullable String siteId, @Nullable String modelId) {
        if (imagePng == null || imagePng.length == 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("图片数据为空"));
        }

        VisionSites.Resolved site = VisionSites.resolve(siteId);
        if (!site.hasKey()) {
            LOGGER.error("[HuhuTalk] 识图失败：平台未配置 api key siteId={}", site.siteId());
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "「%s」还没配置 API Key，请到手机设置 → 识图模型里填一下。"
                            .formatted(VisionProviders.displayNameOf(site.siteId()))));
        }

        String useModel = resolveModel(site.siteId(), modelId);
        LOGGER.info("[HuhuTalk] 发起识图: site={}, model={}, imgBytes={}, headers={}",
                site.siteId(), useModel, imagePng.length, site.headers().keySet());

        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(imagePng);

        JsonObject body = new JsonObject();
        body.addProperty("model", useModel);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt);
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

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(site.url()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + site.apiKey())
                .timeout(TIMEOUT);
        // 站点自定义头（例如 OpenRouter 的 HTTP-Referer / X-OpenRouter-Title）必须一并带上，
        // 否则部分平台会拒绝请求
        for (Map.Entry<String, String> h : site.headers().entrySet()) {
            if (h.getKey() != null && h.getValue() != null) {
                builder.header(h.getKey(), h.getValue());
            }
        }

        return LLM_HTTP_CLIENT.sendAsync(
                        builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(VisionService::extractContent);
    }

    /**
     * 决定这次请求用哪个模型名。优先级：
     * <ol>
     *   <li>调用方显式给的 {@code modelId}</li>
     *   <li>玩家在设置里为该平台选过的模型（{@link VisionSettings}）</li>
     *   <li>站点自身的第一个模型（本模组注入的预设会把视觉模型写进去）</li>
     *   <li>{@link #DEFAULT_MODEL}</li>
     * </ol>
     */
    private static String resolveModel(String siteId, @Nullable String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelId;
        }
        try {
            var site = AvailableSites.LLM_SITES.get(siteId);
            if (site instanceof LLMOpenAISite oai && !oai.models().isEmpty()) {
                return oai.models().keySet().iterator().next();
            }
        } catch (Throwable t) {
            LOGGER.warn("[HuhuTalk] 读取站点模型列表失败 siteId={}", siteId, t);
        }
        return model;
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
