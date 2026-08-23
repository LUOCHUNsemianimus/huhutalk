package com.huhutalk.phone.voice;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTApiType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import net.minecraft.client.Minecraft;

import java.net.http.HttpRequest;
import java.util.function.Consumer;

/**
 * 复用车万女仆(TLM)的语音转文字(STT)能力。
 * <p>
 * 按住说话 = 录音 → 上传 TLM 已配置的 STT 站点 → 返回识别出的汉字 → 在主线程回调给调用方发送。
 * 依赖玩家在 TLM 的 AI 设置里配置并启用了某个 STT 站点（在线语音识别服务 + API key）。
 */
public final class VoiceInput {
    private VoiceInput() {
    }

    /** 是否正在录音（全局同一时刻至多一个） */
    private static volatile boolean recording = false;
    /** 本次会话是否要丢弃结果（用于右键取消） */
    private static volatile boolean cancelled = false;
    /** 会话号：每次 start 自增，用于丢弃已过期的异步回调 */
    private static long session = 0;
    private static STTSite site;
    private static ResponseCallback<String> activeCallback;

    public static boolean isRecording() {
        return recording;
    }

    /** TLM 的 STT 是否已配置并启用 */
    public static boolean isConfigured() {
        try {
            if (!AIConfig.STT_ENABLED.get()) return false;
            STTApiType type = AIConfig.STT_TYPE.get();
            if (type == null) return false;
            STTSite s = AvailableSites.getSTTSite(type.getName());
            return s != null && s.enabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 开始录音。
     *
     * @param onText 识别出文字后于主线程回调
     * @return true 已开始录音；false 表示未配置 STT 或已在录音中
     */
    public static boolean start(Consumer<String> onText) {
        if (recording) return false;
        STTSite s = resolveSite();
        if (s == null) return false;

        site = s;
        cancelled = false;
        final long mySession = ++session;
        final STTClient client = s.client();
        activeCallback = new ResponseCallback<>() {
            @Override
            public void onSuccess(String text) {
                recording = false;
                if (mySession != session || cancelled || text == null || text.isBlank()) return;
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> onText.accept(text));
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int code) {
                recording = false;
            }
        };

        recording = true;
        client.startRecord(new STTConfig(), activeCallback);
        return true;
    }

    /**
     * 停止录音并结束本次识别。
     *
     * @param cancel true 丢弃结果（右键取消）；false 正常发送识别文字
     */
    public static void stop(boolean cancel) {
        if (!recording) return;
        cancelled = cancel;
        recording = false;
        if (site != null) {
            try {
                site.client().stopRecord(new STTConfig(), activeCallback);
            } catch (Throwable t) {
                recording = false;
            }
        }
    }

    private static STTSite resolveSite() {
        try {
            if (!AIConfig.STT_ENABLED.get()) return null;
            STTApiType type = AIConfig.STT_TYPE.get();
            if (type == null) return null;
            STTSite s = AvailableSites.getSTTSite(type.getName());
            return (s != null && s.enabled()) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
