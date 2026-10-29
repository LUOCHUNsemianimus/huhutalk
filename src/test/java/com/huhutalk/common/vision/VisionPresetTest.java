package com.huhutalk.common.vision;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 豆包视觉预设与旧配置归一的纯行为测试。
 *
 * <p>不启动游戏、不访问网络、不需要密钥：只验证 {@link VisionProviders} 的纯数据与
 * {@link VisionProviders#normalizeLegacyPresetModel(String, String, boolean)} 的边界。
 */
public class VisionPresetTest {

    private static final String DOUBAO = "doubao";
    private static final String PRO = "doubao-seed-2-1-pro-260915";
    private static final String LITE = "doubao-seed-2-1-lite-260915";
    private static final String TURBO = "doubao-seed-2-1-turbo-260628";
    private static final String MINI = "doubao-seed-2-0-mini-260428";

    @Test
    public void doubaoHasFourCurrentPresetsWithProDefault() {
        VisionProvider doubao = VisionProviders.byId(DOUBAO);
        assertTrue(doubao != null);
        assertEquals(List.of(PRO, LITE, TURBO, MINI), doubao.models());
        assertEquals(4, doubao.models().size());
        assertTrue("预设数不得超过下拉能渲染的上限",
                doubao.models().size() <= VisionProviders.MAX_MODELS_PER_PROVIDER);
        assertEquals(PRO, doubao.resolveDefaultModel());
        assertTrue(doubao.models().contains(doubao.resolveDefaultModel()));
    }

    @Test
    public void allProvidersRespectVisibleRowLimit() {
        for (VisionProvider provider : VisionProviders.all()) {
            assertTrue("平台 " + provider.siteId() + " 的预设模型过多",
                    provider.models().size() <= VisionProviders.MAX_MODELS_PER_PROVIDER);
        }
    }

    @Test
    public void legacyLitePresetMapsToCurrentLite() {
        assertEquals(LITE, VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "doubao-vision-lite-32k", false));
    }

    @Test
    public void otherThreeLegacyPresetsMapToCurrentPro() {
        assertEquals(PRO, VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "doubao-seed-1.6-vision", false));
        assertEquals(PRO, VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "doubao-1.5-vision-pro-32k-250115", false));
        assertEquals(PRO, VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "doubao-vision-pro-32k", false));
    }

    @Test
    public void customModeKeepsLiteralEvenIfItMatchesLegacyPreset() {
        assertEquals("doubao-vision-lite-32k", VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "doubao-vision-lite-32k", true));
        assertEquals("my-own-vision-model", VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "my-own-vision-model", true));
    }

    @Test
    public void endpointIdIsNeverRewritten() {
        assertEquals("ep-20260101-abcdef", VisionProviders.normalizeLegacyPresetModel(
                DOUBAO, "ep-20260101-abcdef", false));
    }

    @Test
    public void otherSitesAndCurrentModelsAreUntouched() {
        assertEquals("doubao-vision-lite-32k", VisionProviders.normalizeLegacyPresetModel(
                "aliyun", "doubao-vision-lite-32k", false));
        assertEquals("qwen3-vl-plus", VisionProviders.normalizeLegacyPresetModel(
                "aliyun", "qwen3-vl-plus", false));
        assertEquals(PRO, VisionProviders.normalizeLegacyPresetModel(DOUBAO, PRO, false));
        assertEquals(TURBO, VisionProviders.normalizeLegacyPresetModel(DOUBAO, TURBO, false));
    }

    @Test
    public void emptySelectionFallsBackToProDefault() {
        assertNull(VisionProviders.normalizeLegacyPresetModel(DOUBAO, null, false));
        assertEquals("", VisionProviders.normalizeLegacyPresetModel(DOUBAO, "", false));
        assertEquals(PRO, VisionProviders.byId(DOUBAO).resolveDefaultModel());
    }
}
