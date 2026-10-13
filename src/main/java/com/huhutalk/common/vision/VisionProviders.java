package com.huhutalk.common.vision;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 识图平台的<b>预设表</b>（纯数据，客户端与服务端共用）。
 *
 * <h2>清单来源</h2>
 * 全部走 OpenAI 兼容的 {@code /chat/completions}（{@code image_url} + base64 内联），
 * 与现有 {@code VisionService} 的请求体天然一致，因此一套适配器通吃。
 *
 * <h2>关于"跟随主模组"</h2>
 * {@link #DEEPSEEK} 是改造前就在用、也是唯一验证过的链路，因此：
 * <ul>
 *   <li>它是默认值，也是所有兜底路径的终点；</li>
 *   <li>它的默认模型沿用改造前的 {@code deepseek-v4-flash-vision-exp}（该模型名会被
 *       DeepSeek 自动路由到 {@code deepseek-flash}，见官方更新日志），
 *       <b>保证升级后行为不变</b>。</li>
 * </ul>
 *
 * <h2>刻意不做的平台</h2>
 * <ul>
 *   <li><b>讯飞星火</b>：其图片理解只有 WebSocket 专有协议 + HMAC 签名
 *       （{@code wss://spark-api.cn-huabei-1.xf-yun.com/v2.1/image}），
 *       那条 OpenAI 兼容的 {@code spark-api-open} 口<b>不吃图片</b>。
 *       要接就得再写一套 WS + 签名，性价比极低。</li>
 *   <li><b>Anthropic Claude</b>：协议不同（{@code x-api-key} + {@code anthropic-version} +
 *       {@code content[].source.base64}），需要独立的适配器分支。留待后续。</li>
 *   <li><b>商汤 / 百度 v1</b>：无必要，千帆 v2 已是 OpenAI 兼容。</li>
 * </ul>
 *
 * <h2>已知坑（写在 {@link VisionProvider#note()} 里，UI 会展示）</h2>
 * <ul>
 *   <li>智谱免费的 {@code glm-4v-flash} 不吃 base64，只能用公网 URL，因此<b>不列入预设</b>。</li>
 *   <li>火山方舟的 {@code model} 除了模型 id，也可以填玩家自建的<b>推理接入点 ID</b>，
 *       所以方舟支持"自定义模型"。</li>
 * </ul>
 */
public final class VisionProviders {

    private VisionProviders() {
    }

    // ==================== 内置站点（车万女仆自带，不注入、不覆盖） ====================

    /**
     * 「跟随主模组」：改造前唯一在用的识图链路。
     * <p>
     * 它不需要玩家填任何东西 —— 密钥读的就是主模组已有的 DeepSeek 站点配置，
     * 所以升级后什么都不用配也能继续识图。
     */
    public static final VisionProvider DEEPSEEK = new VisionProvider(
            "deepseek",
            "DeepSeek（跟随主模组）",
            "沿用现有链路，无需额外配置",
            null,
            List.of("deepseek-v4-flash-vision-exp", "deepseek-flash"),
            "deepseek-v4-flash-vision-exp",
            null,
            false
    );

    public static final VisionProvider ALIYUN = new VisionProvider(
            "aliyun",
            "阿里云百炼",
            "通义千问 Qwen-VL 系列",
            null,
            // ⚠ 预设模型数不得超过 MAX_MODELS_PER_PROVIDER（下拉只渲染前 VISIBLE_ROWS 行）。
            //   原先这里还有 qwen-vl-plus，已被 qwen3-vl-flash 取代，为腾出「自定义…」的位置去掉。
            List.of("qwen3-vl-plus", "qwen3-vl-flash", "qwen-vl-max",
                    "qwen2.5-vl-72b-instruct"),
            "qwen3-vl-plus",
            null,
            false
    );

    public static final VisionProvider DOUBAO = new VisionProvider(
            "doubao",
            "火山引擎方舟",
            "豆包 Doubao 视觉理解",
            "model 也可填你自建的「推理接入点 ID」",
            List.of("doubao-seed-1.6-vision", "doubao-1.5-vision-pro-32k-250115",
                    "doubao-vision-pro-32k", "doubao-vision-lite-32k"),
            "doubao-seed-1.6-vision",
            null,
            false
    );

    public static final VisionProvider ZHIPU = new VisionProvider(
            "zhipu",
            "智谱 Z.ai",
            "GLM 视觉系列",
            "免费的 glm-4v-flash 不吃 base64，故未列入",
            List.of("glm-4.6v", "glm-4v-plus", "glm-4v-plus-0111"),
            "glm-4.6v",
            null,
            false
    );

    public static final VisionProvider KIMI = new VisionProvider(
            "kimi",
            "月之暗面 Kimi",
            "Kimi 视觉理解",
            null,
            List.of("kimi-k3", "moonshot-v1-8k-vision-preview", "moonshot-v1-32k-vision-preview"),
            "kimi-k3",
            null,
            false
    );

    public static final VisionProvider MINIMAX = new VisionProvider(
            "minimax",
            "MiniMax",
            "MiniMax 多模态",
            null,
            List.of("MiniMax-M3.1-Flash-Preview", "MiniMax-M3"),
            "MiniMax-M3.1-Flash-Preview",
            null,
            false
    );

    public static final VisionProvider SILICONFLOW = new VisionProvider(
            "siliconflow",
            "硅基流动",
            "聚合平台，含免费视觉模型",
            "THUDM/GLM-4.1V-9B-Thinking 与 deepseek-ai/DeepSeek-OCR 有免费档",
            List.of("Qwen/Qwen3-VL-235B-A22B-Instruct", "Qwen/Qwen2.5-VL-72B-Instruct",
                    "THUDM/GLM-4.1V-9B-Thinking", "deepseek-ai/DeepSeek-OCR"),
            "Qwen/Qwen3-VL-235B-A22B-Instruct",
            null,
            false
    );

    public static final VisionProvider OPENAI = new VisionProvider(
            "openai",
            "OpenAI",
            "GPT 视觉系列（需自备网络环境）",
            null,
            List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1"),
            "gpt-4o-mini",
            null,
            false
    );

    public static final VisionProvider GEMINI = new VisionProvider(
            "gemini",
            "Google Gemini",
            "走官方 OpenAI 兼容端点",
            null,
            List.of("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.8-flash"),
            "gemini-2.5-flash",
            null,
            false
    );

    public static final VisionProvider GROK = new VisionProvider(
            "grok",
            "xAI Grok",
            "Grok 视觉理解",
            null,
            List.of("grok-4", "grok-2-vision"),
            "grok-4",
            null,
            false
    );

    public static final VisionProvider OPENROUTER = new VisionProvider(
            "openrouter",
            "OpenRouter",
            "一个 Key 打通多家模型",
            null,
            List.of("google/gemini-2.5-flash", "openai/gpt-4o-mini",
                    "qwen/qwen2.5-vl-72b-instruct"),
            "google/gemini-2.5-flash",
            null,
            false
    );

    // ==================== 需要本模组注入的站点（服务器上没有才建） ====================

    public static final VisionProvider QIANFAN = new VisionProvider(
            "huhutalk_qianfan",
            "百度千帆",
            "文心 ERNIE 视觉系列",
            "千帆 v2 接口；API Key 形如 bce-v3/ALTAK-...",
            List.of("ernie-4.5-turbo-vl", "ernie-4.5-vl-28b-a3b", "qwen2.5-vl-7b-instruct"),
            "ernie-4.5-turbo-vl",
            "https://qianfan.baidubce.com/v2/chat/completions",
            false
    );

    public static final VisionProvider HUNYUAN = new VisionProvider(
            "huhutalk_hunyuan",
            "腾讯混元",
            "混元视觉理解",
            "腾讯正把混元迁往 TokenHub，若报错请改用 TokenHub 的地址",
            List.of("hunyuan-vision"),
            "hunyuan-vision",
            "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
            false
    );

    public static final VisionProvider STEPFUN = new VisionProvider(
            "huhutalk_stepfun",
            "阶跃星辰",
            "StepFun 多模态",
            null,
            List.of("step-3.7-flash", "step-5-preview"),
            "step-3.7-flash",
            "https://api.stepfun.com/v1/chat/completions",
            false
    );

    /**
     * 兜底：任意 OpenAI 兼容服务。
     * <p>
     * 接口地址与模型名都由玩家自己填。地址留空表示"还没配"，此时该平台不可选。
     */
    public static final VisionProvider CUSTOM = new VisionProvider(
            "huhutalk_custom",
            "自定义（OpenAI 兼容）",
            "自己填接口地址与模型名",
            "只支持 OpenAI 兼容的 /chat/completions 接口",
            List.of(),
            null,
            "",
            true
    );

    // ==================== 表 ====================

    /**
     * 展示顺序 = 这个列表的顺序。
     * <p>
     * 「跟随主模组」放第一（保证默认与兜底是它），随后是国内主力，再是海外，
     * 最后是"要自己填东西"的两个。
     */
    private static final List<VisionProvider> ALL = List.of(
            DEEPSEEK,
            ALIYUN,
            DOUBAO,
            ZHIPU,
            KIMI,
            MINIMAX,
            SILICONFLOW,
            QIANFAN,
            HUNYUAN,
            STEPFUN,
            OPENAI,
            GEMINI,
            GROK,
            OPENROUTER,
            CUSTOM
    );

    /**
     * 默认选中的平台：与改造前完全一致（DeepSeek）。
     * <p>
     * 因此<b>老玩家升级后一行配置都不用动，识图照旧走原来的链路</b>。
     */
    public static final String DEFAULT_SITE_ID = DEEPSEEK.siteId();

    private static final Map<String, VisionProvider> BY_ID = buildIndex();

    private static Map<String, VisionProvider> buildIndex() {
        Map<String, VisionProvider> map = new LinkedHashMap<>();
        for (VisionProvider p : ALL) {
            map.put(p.siteId(), p);
        }
        return Map.copyOf(map);
    }

    /**
     * 全部预设，按展示顺序。
     * <p>
     * ⚠ 各条的 {@link VisionProvider#models()} 长度有上限，见 {@link #MAX_MODELS_PER_PROVIDER}。
     */
    public static List<VisionProvider> all() {
        return ALL;
    }

    /**
     * 单个平台允许的<b>预设模型数上限</b>。
     *
     * <h2>这个数字是怎么来的</h2>
     * 手机上的模型选择用的是 {@code PhoneDropdown}，它<b>只渲染前 {@code VISIBLE_ROWS}(=5) 行</b>
     * （没有滚动窗口，滚轮只改选中项、不改可见范围）。而下拉里还要额外放一项「自定义…」，
     * 所以预设模型最多只能有 {@code VISIBLE_ROWS - 1 = 4} 个 ——
     * 再多，末尾的选项就渲染不出来、也就永远点不到。
     * <p>
     * 手机没有滚轮（滚页面靠拖拽），所以"靠滚轮够到第 6 项"这条路是不存在的。
     * <p>
     * {@code VisionModelSettingsPage} 会在加载时校验这张表并在超限时打 WARN，
     * 免得以后有人往预设里加第 5 个模型时，只是"悄无声息地点不到"。
     */
    public static final int MAX_MODELS_PER_PROVIDER = 4;

    /** 需要本模组注入的站点预设（{@code injectUrl != null} 的那些）。 */
    public static List<VisionProvider> injectable() {
        return ALL.stream().filter(VisionProvider::injectable).toList();
    }

    /** 按站点 id 查预设；没有预设（玩家自建的站点）返回 null。 */
    @Nullable
    public static VisionProvider byId(@Nullable String siteId) {
        return siteId == null ? null : BY_ID.get(siteId);
    }

    /** 站点显示名：有预设用预设，否则退回站点 id。 */
    public static String displayNameOf(String siteId) {
        VisionProvider p = byId(siteId);
        return p != null ? p.displayName() : siteId;
    }

    /**
     * 展示排序权重：预设按表内顺序，玩家自建的站点排在最后。
     * 用于把服务器返回的站点列表排成"预设在前、其余在后"。
     */
    public static int orderOf(@Nullable String siteId) {
        if (siteId == null) {
            return Integer.MAX_VALUE;
        }
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).siteId().equals(siteId)) {
                return i;
            }
        }
        return ALL.size() + 1;
    }
}
