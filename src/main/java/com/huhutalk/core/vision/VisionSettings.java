package com.huhutalk.core.vision;

import com.huhutalk.common.vision.VisionAppearance;
import com.huhutalk.common.vision.VisionProviders;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「识图走哪个平台、用哪个模型」的<b>服务端会话缓存</b>（按玩家）。
 *
 * <h2>为什么是"按玩家"</h2>
 * API Key 是<b>全服共享</b>的（就存在 TLM 的站点配置里，一份），
 * 但"我想用阿里云还是火山"是每个人自己的口味，没有任何理由强制统一。
 * 因此选择按 UUID 记，密钥仍然只有服务端那一份。
 *
 * <h2>为什么是服务端缓存，而不是 SavedData</h2>
 * 识图请求由<b>服务端</b>发起，所以服务端必须知道选择；
 * 但它不是需要持久化的世界数据 —— 客户端每次登录都会重新推一次
 * （见 {@code ClientModEvents#pushVisionSelection}），重连后不会丢。
 * 这与既有的 {@code RescueSettings} 是同一套范式。
 *
 * <h2>线程</h2>
 * 写发生在 {@code C2S} 包处理（{@code enqueueWork} 回主线程），
 * 读发生在识图发起（同样是服务端主线程），所以用 {@link HashMap} 即可。
 */
public final class VisionSettings {

    /** 尚未收到客户端同步时的选择：与改造前完全一致（DeepSeek）。 */
    private static final String DEFAULT_SITE = VisionProviders.DEFAULT_SITE_ID;

    /** 玩家选择：siteId / model */
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    private VisionSettings() {
    }

    /**
     * 记录某玩家的选择与「你的样貌」（客户端同步过来时调用）。
     *
     * @param playerAppearance 主人的外貌描述；{@code null}/空白 = 未填写。
     *                         这里<b>再清洗一遍</b>：客户端可以被改包，服务端不能假设它守规矩。
     */
    public static void set(UUID playerId, @Nullable String siteId, @Nullable String model,
                           @Nullable String playerAppearance) {
        if (playerId == null) {
            return;
        }
        SELECTIONS.put(playerId, new Selection(
                siteId == null || siteId.isBlank() ? DEFAULT_SITE : siteId,
                model == null ? "" : model,
                VisionAppearance.clean(playerAppearance)));
    }

    /** 某玩家的选择；从未同步过则返回默认（DeepSeek + 空模型 + 未填写样貌）。 */
    public static Selection get(@Nullable UUID playerId) {
        if (playerId == null) {
            return new Selection(DEFAULT_SITE, "", "");
        }
        return SELECTIONS.getOrDefault(playerId, new Selection(DEFAULT_SITE, "", ""));
    }

    /** 玩家登出：清掉记录，避免长开服上累积。 */
    public static void clear(@Nullable UUID playerId) {
        if (playerId != null) {
            SELECTIONS.remove(playerId);
        }
    }

    /** 服务器停止：全清。 */
    public static void clearAll() {
        SELECTIONS.clear();
    }

    /**
     * 一次选择。
     *
     * @param siteId           平台（TLM 站点 id）
     * @param model            模型名；空串表示"用该平台的默认预设模型"
     * @param playerAppearance 「你的样貌」；空串表示未填写（此时识图走原默认提示词）
     */
    public record Selection(String siteId, String model, String playerAppearance) {

        /** 最终要发给视觉模型的模型名：玩家选了就用玩家的，否则取平台预设的默认。 */
        public String resolveModel() {
            if (model != null && !model.isBlank()) {
                return model;
            }
            var provider = VisionProviders.byId(siteId);
            return provider != null ? provider.resolveDefaultModel() : "";
        }
    }
}
