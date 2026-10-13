package com.huhutalk.common.vision;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一个「识图平台」的预设描述——<b>纯数据，客户端与服务端共用</b>。
 *
 * <h2>它和车万女仆(TLM)的站点是什么关系</h2>
 * 本模组<b>不另建一套 API Key 存储</b>：识图平台直接就是 TLM 的一个 LLM 站点
 * （{@code AvailableSites.LLM_SITES} 里的一条，明文落在
 * {@code config/touhou_little_maid/sites/llm.json}）。
 * {@link #siteId()} 就是那个站点的 id；密钥、接口地址都读写站点上的字段。
 * <p>
 * 这样做的直接好处：与主模组的存储位置、权限判定（{@code GameModeUtil.canEditSite}）、
 * 落盘与同步机制<b>完全同源</b>，本模组不需要新造一份配置体系。
 *
 * <h2>为什么模型列表是"预设"而不是读站点的 models</h2>
 * TLM 给这些站点预设的是<b>文本模型</b>（例如 aliyun 站点的 {@code qwen3-max}）。
 * 识图要的是视觉模型，所以本模组自带一份视觉模型预设；站点的 {@code models} 字段
 * 我们<b>一个都不动</b>，避免和主模组的模型选择器互相污染。
 *
 * @param siteId       TLM 站点 id（识图最终就是从这个站点取 url / secret_key / headers）
 * @param displayName  手机设置里显示的中文名
 * @param tagline      平台行下方的一句话说明
 * @param note         需要提醒玩家的注意事项（可为 null）
 * @param models       视觉模型预设（下拉选项）；空列表表示"没有预设，请自己填"
 * @param defaultModel 默认模型；必须存在于 {@link #models()} 或为 null（选第一个）
 * @param injectUrl    非 null 表示：若服务器上没有这个站点，就用这个地址建一个。
 *                     内置站点（TLM 自带的）这里是 null，绝不覆盖玩家已有的配置。
 * @param editableUrl  是否允许玩家在手机设置里改这个站点的接口地址。
 *                     「自定义 OpenAI 兼容」为 true（那是它唯一的意义），其余为 false。
 */
public record VisionProvider(
        String siteId,
        String displayName,
        String tagline,
        @Nullable String note,
        List<String> models,
        @Nullable String defaultModel,
        @Nullable String injectUrl,
        boolean editableUrl
) {

    /** 是否需要由本模组注入站点（服务器上没有时才建）。 */
    public boolean injectable() {
        return injectUrl != null;
    }

    /** 是否有需要提醒玩家的注意事项。 */
    public boolean hasNote() {
        return note != null && !note.isBlank();
    }

    /**
     * 默认模型：{@link #defaultModel()} 合法则用它，否则退回第一个预设；
     * 没有预设时返回空串（交给调用方兜底）。
     */
    public String resolveDefaultModel() {
        if (defaultModel != null && !defaultModel.isBlank()) {
            return defaultModel;
        }
        return models.isEmpty() ? "" : models.getFirst();
    }
}
