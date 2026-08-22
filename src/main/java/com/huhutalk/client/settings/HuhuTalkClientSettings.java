package com.huhutalk.client.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * HuhuTalk 客户端全局设置（本地 JSON 持久化，按世界无关，全局共享）。
 * <p>
 * 统一存放手机端所有全局配置：配色方案、手机壳方块、消息免打扰、消息动画开关、
 * 断句延时参数、以及女仆手动分组标记。与 {@code ChatHistoryStorage} 一样使用 Gson + 磁盘 JSON。
 */
public final class HuhuTalkClientSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ==================== 内存态（默认值） ====================
    private static String themeId = "pearl_pink";
    /** 手机壳方块 id（如 "minecraft:iron_block"），空串表示默认无自定义壳 */
    private static String phoneCaseBlock = "";
    /** 手机屏幕比例（宽:高），可选 "20:9" / "9:16" / "4:3" */
    private static String phoneAspect = "20:9";
    /** 手机屏幕高度占实际屏幕高度的比例（0.5 ~ 1.0） */
    private static float phoneSize = 0.6f;
    /** 消息免打扰（DND）：开启后女仆消息不再弹未读红点/通知 */
    private static boolean dndEnabled = false;
    /** 消息断句动画开关 */
    private static boolean messageAnimation = true;
    /** 断句每字耗时（毫秒） */
    private static long charTypingMs = 500;
    /** 断句单句耗时上限（毫秒） */
    private static long maxTypingMs = 2400;
    /** 女仆手动分组：stableId -> 分组名 */
    private static final Map<String, String> maidGroups = new HashMap<>();

    private HuhuTalkClientSettings() {
    }

    // ==================== 读 ====================

    public static String getThemeId() { return themeId; }
    public static String getPhoneCaseBlock() { return phoneCaseBlock; }
    public static String getPhoneAspect() { return phoneAspect; }
    public static float getPhoneSize() { return phoneSize; }
    public static boolean isDndEnabled() { return dndEnabled; }
    public static boolean isMessageAnimationEnabled() { return messageAnimation; }
    public static long getCharTypingMs() { return charTypingMs; }
    public static long getMaxTypingMs() { return maxTypingMs; }

    /** 读取某女仆手动分组；未手动设置返回 null */
    public static String getMaidGroup(String stableId) {
        return maidGroups.get(stableId);
    }

    /** 全部手动分组映射（不可变副本） */
    public static Map<String, String> getMaidGroups() {
        return Map.copyOf(maidGroups);
    }

    // ==================== 写 ====================

    public static void setThemeId(String id) {
        themeId = id == null || id.isBlank() ? "pearl_pink" : id;
        save();
    }

    public static void setPhoneCaseBlock(String blockId) {
        phoneCaseBlock = blockId == null ? "" : blockId;
        save();
    }

    /** 设置屏幕比例（非法值回退 "20:9"） */
    public static void setPhoneAspect(String aspect) {
        if (aspect == null || aspect.isBlank()) {
            phoneAspect = "20:9";
        } else {
            phoneAspect = aspect;
        }
        save();
    }

    /** 设置屏幕高度比例（钳制到 0.5~1.0） */
    public static void setPhoneSize(float size) {
        phoneSize = Math.max(0.5f, Math.min(1.0f, size));
        save();
    }

    /** 拖动滑块过程中的高频更新：只改内存，不落盘（松开时由调用方 save） */
    public static void setPhoneSizeRaw(float size) {
        phoneSize = Math.max(0.5f, Math.min(1.0f, size));
    }

    public static void setDndEnabled(boolean enabled) {
        dndEnabled = enabled;
        save();
    }

    public static void setMessageAnimation(boolean enabled) {
        messageAnimation = enabled;
        save();
    }

    public static void setCharTypingMs(long ms) {
        charTypingMs = Math.max(50, ms);
        save();
    }

    public static void setMaxTypingMs(long ms) {
        maxTypingMs = Math.max(100, ms);
        save();
    }

    /** 设置某女仆手动分组；分组名为空则移除手动标记（回到默认按状态分组） */
    public static void setMaidGroup(String stableId, String group) {
        if (group == null || group.isBlank()) {
            maidGroups.remove(stableId);
        } else {
            maidGroups.put(stableId, group);
        }
        save();
    }

    // ==================== 持久化 ====================

    /** 设置文件：.minecraft/huhutalk/settings.json */
    private static Path settingsFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("huhutalk").resolve("settings.json");
    }

    /** 加载设置（游戏启动/进世界时调用） */
    public static void load() {
        Path file = settingsFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            SettingsData data = GSON.fromJson(Files.readString(file), SettingsData.class);
            if (data == null) {
                return;
            }
            themeId = data.themeId != null ? data.themeId : "pearl_pink";
            phoneCaseBlock = data.phoneCaseBlock != null ? data.phoneCaseBlock : "";
            if (data.phoneAspect != null && !data.phoneAspect.isBlank()) phoneAspect = data.phoneAspect;
            if (data.phoneSize > 0f) phoneSize = Math.max(0.5f, Math.min(1.0f, data.phoneSize));
            dndEnabled = data.dndEnabled;
            messageAnimation = data.messageAnimation;
            if (data.charTypingMs > 0) charTypingMs = data.charTypingMs;
            if (data.maxTypingMs > 0) maxTypingMs = data.maxTypingMs;
            maidGroups.clear();
            if (data.maidGroups != null) {
                maidGroups.putAll(data.maidGroups);
            }
        } catch (IOException e) {
            LOGGER.error("[HuhuTalk] 加载全局设置失败", e);
        }
    }

    /** 落盘当前设置 */
    public static void save() {
        try {
            SettingsData data = new SettingsData();
            data.themeId = themeId;
            data.phoneCaseBlock = phoneCaseBlock;
            data.phoneAspect = phoneAspect;
            data.phoneSize = phoneSize;
            data.dndEnabled = dndEnabled;
            data.messageAnimation = messageAnimation;
            data.charTypingMs = charTypingMs;
            data.maxTypingMs = maxTypingMs;
            data.maidGroups = maidGroups;
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            LOGGER.error("[HuhuTalk] 保存全局设置失败", e);
        }
    }

    /** JSON 结构 */
    private static class SettingsData {
        String themeId;
        String phoneCaseBlock;
        String phoneAspect;
        float phoneSize;
        boolean dndEnabled;
        boolean messageAnimation;
        long charTypingMs;
        long maxTypingMs;
        Map<String, String> maidGroups;
    }
}
