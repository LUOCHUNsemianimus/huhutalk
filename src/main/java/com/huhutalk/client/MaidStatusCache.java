package com.huhutalk.client;

import com.huhutalk.api.phone.ui.PhoneText;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端女仆状态缓存（stableId → 可翻译状态表示，如 {@code {"ht":1,"k":"status.huhutalk.farming"}}）。
 * 由服务端低频推送更新，供聊天页/女仆列表显示"状态小字"。
 * <p>缓存里存的是与语言无关的原始表示：{@link #get} 时按当前语言解析，
 * 因此切语言立即生效，也便于服务端做状态比较与推送节流。
 */
public final class MaidStatusCache {
    private static final Map<String, String> STATUS = new HashMap<>();

    private MaidStatusCache() {
    }

    public static void set(String stableId, String status) {
        STATUS.put(stableId, status == null ? "" : status);
    }

    /** 当前语言下的状态文案；普通旧字符串原样返回。 */
    public static String get(String stableId) {
        return PhoneText.system(STATUS.getOrDefault(stableId, ""));
    }

    /** 进入新世界时清空 */
    public static void clear() {
        STATUS.clear();
    }
}
