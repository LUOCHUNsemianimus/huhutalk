package com.huhutalk.client;

import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.init.HuhuTalkSounds;

import java.util.HashMap;
import java.util.Map;

/**
 * 未读消息管理器（按世界隔离）。
 * 键为女仆 stableId（死亡/复活不变）。
 * 切换世界时调用 onWorldChanged，数据互不干扰。
 * <p>
 * 开启「消息免打扰」后，新增未读会被抑制（桌面红点/通知不再出现）。
 */
public class NotificationManager {
    private static String currentWorldKey = "default";
    private static final Map<String, Map<String, Integer>> UNREAD_BY_WORLD = new HashMap<>();

    /** 切换世界：保留各世界数据，只切换当前指向 */
    public static void onWorldChanged(String newKey) {
        currentWorldKey = newKey;
        UNREAD_BY_WORLD.putIfAbsent(newKey, new HashMap<>());
    }

    /** 当前世界未读表 */
    private static Map<String, Integer> current() {
        return UNREAD_BY_WORLD.computeIfAbsent(currentWorldKey, k -> new HashMap<>());
    }

    public static void addUnread(String stableId) {
        if (HuhuTalkClientSettings.isDndEnabled()) return; // 消息免打扰：不再累计未读
        current().merge(stableId, 1, Integer::sum);
        // 界面外通知音（受 DND 影响，带 1.5s 限频）：产生未读红点时播放
        HuhuTalkSounds.playMsgReceiveOuter();
    }

    /** 按指定条数累加未读（用于按断句分段计数的女仆回复） */
    public static void addUnread(String stableId, int count) {
        if (count <= 0) return;
        if (HuhuTalkClientSettings.isDndEnabled()) return; // 消息免打扰：不再累计未读
        current().merge(stableId, count, Integer::sum);
        // 界面外通知音（受 DND 影响，带 1.5s 限频）：产生未读红点时播放
        HuhuTalkSounds.playMsgReceiveOuter();
    }

    public static int getUnread(String stableId) {
        return current().getOrDefault(stableId, 0);
    }

    public static void clear(String stableId) {
        current().put(stableId, 0);
    }

    /** 当前世界所有未读总和 */
    public static int getTotalUnread() {
        return current().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 清空当前世界所有未读 */
    public static void clearAll() {
        current().clear();
    }
}