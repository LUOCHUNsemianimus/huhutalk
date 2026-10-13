package com.huhutalk.core.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.huhutalk.common.vision.VisionProvider;
import com.huhutalk.common.vision.VisionProviders;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 识图平台的<b>服务端读写层</b>——本模组与车万女仆(TLM)站点体系的唯一接触面。
 *
 * <h2>设计要点：我们只是主模组站点的一个"读改器"</h2>
 * <ul>
 *   <li><b>读</b>：{@link #snapshot()} 把 {@code AvailableSites.LLM_SITES} 里每个站点的
 *       id / url / 是否已配密钥拍成快照发给客户端。<b>密钥本身绝不下发</b>——
 *       客户端只需要知道"配没配"，这正好满足"像密码一样不可读"。</li>
 *   <li><b>写</b>：{@link #applyEdit} 直接改站点的 {@code secretKey} / {@code url}，
 *       然后调 TLM 自己的 {@code AvailableSites.saveSites()} 落盘。
 *       权限由调用方（包处理器）用 TLM 同一个 {@code GameModeUtil.canEditSite} 把关。</li>
 *   <li><b>注入</b>：TLM 自带的站点（aliyun / doubao / zhipu / kimi / …）直接复用，
 *       一个字段都不覆盖；只有 TLM 没有的平台（千帆 / 混元 / 阶跃 / 自定义）
 *       才由 {@link #ensureDefaults()} 在<b>缺失时</b>补建。</li>
 * </ul>
 *
 * <h2>为什么不复用 TLM 自己的 {@code SaveLLMSitePacket}</h2>
 * 那个包的配套回包 {@code SyncAISitesPacket} 在客户端处理器里会<b>强行换屏</b>
 * （{@code mc.setScreen(AIChatSettingsHubScreen...)}）。玩家在手机里保存一次密钥，
 * 整个手机界面就会被 TLM 的 AI 设置页顶掉。所以写入必须走我们自己的包，
 * 但落盘路径与权限判定与主模组<b>逐字节一致</b>。
 *
 * <h2>线程</h2>
 * 全部在服务端主线程调用（包处理器经 {@code enqueueWork}、服务器启动事件、识图发起前）。
 */
public final class VisionSites {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VisionSites() {
    }

    // ==================== 预设注入 ====================

    /**
     * 确保本模组需要的站点存在（<b>缺失才建，绝不覆盖</b>）。
     * <p>
     * 幂等，可在任意时刻重复调用。新增的站点会顺带落盘一次，
     * 于是它们也会出现在主模组自己的 AI 设置界面里，玩家想改地址也能改。
     *
     * @return 本次是否新增了站点
     */
    public static boolean ensureDefaults() {
        List<VisionProvider> injectable = VisionProviders.injectable();
        List<VisionProvider> missing = new ArrayList<>();
        for (VisionProvider p : injectable) {
            if (!AvailableSites.LLM_SITES.containsKey(p.siteId())) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return false;
        }
        for (VisionProvider p : missing) {
            try {
                AvailableSites.LLM_SITES.put(p.siteId(), buildSite(p));
                LOGGER.info("[HuhuTalk] 已补建识图站点预设: {} ({})", p.displayName(), p.siteId());
            } catch (Throwable t) {
                LOGGER.error("[HuhuTalk] 补建识图站点预设失败: {}", p.siteId(), t);
            }
        }
        try {
            AvailableSites.saveSites();
        } catch (Throwable t) {
            // 落盘失败不影响本次会话可用（站点已在内存表里），只是重启后会重新补建
            LOGGER.error("[HuhuTalk] 识图站点预设落盘失败", t);
        }
        return true;
    }

    /** 用预设造一个 TLM 的 OpenAI 兼容站点。 */
    private static LLMSite buildSite(VisionProvider p) {
        List<LLMOpenAISite.ModelEntry> models = new ArrayList<>();
        for (String m : p.models()) {
            models.add(new LLMOpenAISite.ModelEntry(m));
        }
        String url = p.injectUrl() == null ? "" : p.injectUrl();
        return new LLMOpenAISite(
                p.siteId(),
                SerializableSite.defaultIcon(p.siteId()),
                url,
                true,
                "",
                Map.of(),
                models);
    }

    // ==================== 读 ====================

    /** 某站点当前是否已配置密钥。 */
    public static boolean hasKey(@Nullable String siteId) {
        LLMOpenAISite site = openAiSite(siteId);
        return site != null && site.secretKey() != null && !site.secretKey().isBlank();
    }

    /**
     * 全部可用站点的<b>非敏感快照</b>，已按"预设在前、自建在后"排好序。
     * 只含 id / url / 是否已配密钥，不含密钥内容。
     */
    public static List<Entry> snapshot() {
        ensureDefaults();
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, LLMSite> e : AvailableSites.LLM_SITES.entrySet()) {
            String id = e.getKey();
            LLMSite site = e.getValue();
            if (site == null) {
                continue;
            }
            out.add(new Entry(id, site.url() == null ? "" : site.url(), hasKey(id)));
        }
        out.sort(Comparator.comparingInt((Entry e) -> VisionProviders.orderOf(e.siteId()))
                .thenComparing(Entry::siteId));
        return out;
    }

    // ==================== 写 ====================

    /**
     * 修改一个站点的密钥与/或接口地址，并落盘。
     *
     * @param newApiKey 新的密钥；{@code null} = 不改动（这样玩家改地址时不必重输密钥）
     * @param newUrl    新的接口地址；{@code null} = 不改动
     * @return 是否真的改动了什么
     */
    public static boolean applyEdit(String siteId, @Nullable String newApiKey, @Nullable String newUrl) {
        LLMOpenAISite site = openAiSite(siteId);
        if (site == null) {
            LOGGER.warn("[HuhuTalk] 拒绝写入识图站点：找不到或类型不符 siteId={}", siteId);
            return false;
        }
        boolean changed = false;
        if (newApiKey != null) {
            String key = newApiKey.trim();
            site.setSecretKey(key);
            changed = true;
        }
        if (newUrl != null) {
            String url = newUrl.trim();
            if (!url.equals(site.url())) {
                site.setUrl(url);
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        try {
            AvailableSites.saveSites();
        } catch (Throwable t) {
            LOGGER.error("[HuhuTalk] 识图站点落盘失败 siteId={}", siteId, t);
        }
        LOGGER.info("[HuhuTalk] 已更新识图站点 siteId={}, keyChanged={}, urlChanged={}",
                siteId, newApiKey != null, newUrl != null);
        return true;
    }

    // ==================== 识图请求解析 ====================

    /**
     * 解析一次识图请求要用到的站点参数。
     *
     * @param siteId 选中的站点 id；null / 空 / 找不到时退回 {@link VisionProviders#DEFAULT_SITE_ID}
     * @return 解析结果；<b>调用方仍需自行判断 apiKey 是否为空</b>
     */
    public static Resolved resolve(@Nullable String siteId) {
        String wanted = (siteId == null || siteId.isBlank()) ? VisionProviders.DEFAULT_SITE_ID : siteId;
        LLMSite site = site(wanted);
        if (site == null) {
            LOGGER.warn("[HuhuTalk] 选中的识图站点不存在，回退到默认: {}", wanted);
            site = site(VisionProviders.DEFAULT_SITE_ID);
        }
        if (site == null) {
            return new Resolved(VisionProviders.DEFAULT_SITE_ID, VisionService.BASE_URL, "", Map.of());
        }
        String url = site.url() == null || site.url().isBlank() ? VisionService.BASE_URL : site.url();
        String key = (site instanceof LLMOpenAISite oai && oai.secretKey() != null) ? oai.secretKey() : "";
        Map<String, String> headers = site.headers() == null ? Map.of() : site.headers();
        return new Resolved(site.id(), url, key, headers);
    }

    // ==================== 内部 ====================

    @Nullable
    private static LLMSite site(@Nullable String siteId) {
        if (siteId == null || siteId.isBlank()) {
            return null;
        }
        return AvailableSites.LLM_SITES.get(siteId);
    }

    @Nullable
    private static LLMOpenAISite openAiSite(@Nullable String siteId) {
        LLMSite s = site(siteId);
        return s instanceof LLMOpenAISite oai ? oai : null;
    }

    /** 快照里的一条：只有非敏感字段。 */
    public record Entry(String siteId, String url, boolean hasKey) {
    }

    /** 一次识图请求解析出来的站点参数。 */
    public record Resolved(String siteId, String url, String apiKey, Map<String, String> headers) {
        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
