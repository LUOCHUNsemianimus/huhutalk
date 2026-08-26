package com.huhutalk.client.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    /** 手机屏幕比例（宽:高），可选 "20:9" / "9:16" / "4:3"。默认 9:16（标准竖屏，避免默认过细长导致按键误触） */
    private static String phoneAspect = "9:16";
    /** 手机屏幕高度占实际屏幕高度的比例（0.5 ~ 1.0）。默认 75%，避免默认过小 */
    private static float phoneSize = 0.75f;
    /** 手机相对默认右下角锚点的水平/垂直拖拽偏移（GUI 缩放坐标，持久化） */
    private static float phoneOffsetX = 0f;
    private static float phoneOffsetY = 0f;
    /** 消息免打扰（DND）：开启后女仆消息不再弹未读红点/通知 */
    private static boolean dndEnabled = false;
    /** 「女仆悄悄话」：开启后通过手机唤起聊天时，女仆回复不出现在头顶气泡（默认关） */
    private static boolean maidWhisperEnabled = false;
    /** 「沉浸式聊天」：开启后通过手机唤起聊天时，女仆回复不出现在左下角公屏聊天栏（默认关） */
    private static boolean immersiveChatEnabled = false;
    /** 是否已看过手机内置手册（设置 APP）：首次未看时主界面该 APP 右上角标小红点，点击后消除 */
    private static boolean manualSeen = false;
    /** 消息断句动画开关 */
    private static boolean messageAnimation = true;
    /** 「切屏动画」全局界面动画总开关：关闭后所有界面瞬时切换，所有动画逻辑跳过 */
    private static boolean uiAnimation = true;
    /** 断句每字耗时（毫秒） */
    private static long charTypingMs = 500;
    /** 断句单句耗时上限（毫秒） */
    private static long maxTypingMs = 2400;
    /** 手机 UI 全局字体倍率（可选 0.5/0.75/1.0/1.5/2.0）。1.0 = 默认，不缩放 */
    private static float uiFontScale = 1.0f;
    /** 屏幕亮度（0~1，1=满亮度不调；往低调则变暗，最低叠 70% 黑色滤镜） */
    private static float screenBrightness = 1f;
    /** 女仆手动分组：stableId -> 分组名 */
    private static final Map<String, String> maidGroups = new HashMap<>();
    /** 玩家自定义的分组名列表（持久化；联系人页会把其中的分组（含空组）也展示出来） */
    private static final List<String> customGroupNames = new ArrayList<>();

    private HuhuTalkClientSettings() {
    }

    // ==================== 读 ====================

    public static String getThemeId() { return themeId; }
    public static String getPhoneCaseBlock() { return phoneCaseBlock; }
    public static String getPhoneAspect() { return phoneAspect; }
    public static float getPhoneSize() { return phoneSize; }
    public static float getPhoneOffsetX() { return phoneOffsetX; }
    public static float getPhoneOffsetY() { return phoneOffsetY; }
    public static boolean isDndEnabled() { return dndEnabled; }
    public static boolean isMaidWhisperEnabled() { return maidWhisperEnabled; }
    public static boolean isImmersiveChatEnabled() { return immersiveChatEnabled; }
    public static boolean isManualSeen() { return manualSeen; }
    public static boolean isMessageAnimationEnabled() { return messageAnimation; }
    /** 「切屏动画」全局界面动画总开关 */
    public static boolean isUiAnimationEnabled() { return uiAnimation; }
    public static long getCharTypingMs() { return charTypingMs; }
    public static long getMaxTypingMs() { return maxTypingMs; }
    public static float getUiFontScale() { return uiFontScale; }
    public static float getScreenBrightness() { return screenBrightness; }

    /** 读取某女仆手动分组；未手动设置返回 null */
    public static String getMaidGroup(String stableId) {
        return maidGroups.get(stableId);
    }

    /** 全部手动分组映射（不可变副本） */
    public static Map<String, String> getMaidGroups() {
        return Map.copyOf(maidGroups);
    }

    /** 玩家自定义分组名列表（不可变副本） */
    public static List<String> getCustomGroupNames() {
        return List.copyOf(customGroupNames);
    }

    /**
     * 是否已存在同名自定义分组（忽略大小写）。
     */
    public static boolean hasCustomGroup(String name) {
        if (name == null || name.isBlank()) return false;
        for (String g : customGroupNames) {
            if (g.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * 新增自定义分组名：跳过空名、与默认分组同名、以及已存在的同名分组。
     * 成功返回 true；否则返回 false（供 UI 提示）。
     */
    public static boolean addCustomGroup(String name) {
        if (name == null || name.isBlank()) return false;
        String trimmed = name.trim();
        if (isDefaultGroupName(trimmed) || hasCustomGroup(trimmed)) {
            return false;
        }
        customGroupNames.add(trimmed);
        save();
        return true;
    }

    /** 删除自定义分组名（不自动改变女仆归属）。 */
    public static void removeCustomGroup(String name) {
        if (name == null) return;
        customGroupNames.removeIf(g -> g.equalsIgnoreCase(name));
        save();
    }

    private static boolean isDefaultGroupName(String name) {
        return "在线".equals(name) || "离线".equals(name)
                || "已关机".equals(name) || "死亡".equals(name);
    }

    /** 是否含该分组名（含默认组与自定义组，忽略大小写），供去重判断 */
    public static boolean isKnownGroupName(String name) {
        if (name == null || name.isBlank()) return false;
        if (isDefaultGroupName(name)) return true;
        for (String g : customGroupNames) {
            if (g.equalsIgnoreCase(name)) return true;
        }
        return false;
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

    /** 设置屏幕比例（非法值回退默认 "9:16"） */
    public static void setPhoneAspect(String aspect) {
        if (aspect == null || aspect.isBlank()) {
            phoneAspect = "9:16";
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

    /** 拖动手机过程中的高频更新：只改内存，不落盘（松开时由调用方 save） */
    public static void setPhoneOffsetRaw(float x, float y) {
        phoneOffsetX = x;
        phoneOffsetY = y;
    }

    /** 落盘手机位置偏移 */
    public static void setPhoneOffset(float x, float y) {
        phoneOffsetX = x;
        phoneOffsetY = y;
        save();
    }

    public static void setDndEnabled(boolean enabled) {
        dndEnabled = enabled;
        save();
    }

    /** 设置「女仆悄悄话」开关 */
    public static void setMaidWhisperEnabled(boolean enabled) {
        maidWhisperEnabled = enabled;
        save();
    }

    /** 设置「沉浸式聊天」开关 */
    public static void setImmersiveChatEnabled(boolean enabled) {
        immersiveChatEnabled = enabled;
        save();
    }

    /** 标记已看过手册（消除首次红点） */
    public static void setManualSeen(boolean seen) {
        manualSeen = seen;
        save();
    }

    public static void setMessageAnimation(boolean enabled) {
        messageAnimation = enabled;
        save();
    }

    /** 切换「切屏动画」全局界面动画总开关 */
    public static void setUiAnimation(boolean enabled) {
        uiAnimation = enabled;
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

    /** 可选字体倍率档位 */
    private static final float[] UI_FONT_SCALE_STEPS = {0.5f, 0.75f, 1.0f, 1.5f, 2.0f};

    /** 设置全局字体倍率：非法值吸附到最近的可选档位（默认 1.0） */
    public static void setUiFontScale(float scale) {
        if (Float.isNaN(scale) || scale <= 0f) {
            uiFontScale = 1.0f;
        } else {
            float best = 1.0f;
            float bestDist = Float.MAX_VALUE;
            for (float step : UI_FONT_SCALE_STEPS) {
                float d = Math.abs(step - scale);
                if (d < bestDist) {
                    bestDist = d;
                    best = step;
                }
            }
            uiFontScale = best;
        }
        save();
    }

    /** 设置屏幕亮度（钳制 0~1）。0 表示最暗（叠 70% 黑色滤镜），1 表示满亮度不调。 */
    public static void setScreenBrightness(float b) {
        screenBrightness = Math.max(0f, Math.min(1f, b));
        save();
    }

    /** 拖动亮度滑块过程中的高频更新：只改内存，不落盘（松开时由调用方 save） */
    public static void setScreenBrightnessRaw(float b) {
        screenBrightness = Math.max(0f, Math.min(1f, b));
    }

    /** 设置某女仆手动分组；分组名为空则移除手动标记（回到默认按状态分组） */
    public static void setMaidGroup(String stableId, String group) {
        if (stableId == null) {
            return;
        }
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
            phoneOffsetX = data.phoneOffsetX;
            phoneOffsetY = data.phoneOffsetY;
            dndEnabled = data.dndEnabled;
            maidWhisperEnabled = data.maidWhisperEnabled;
            immersiveChatEnabled = data.immersiveChatEnabled;
            manualSeen = data.manualSeen;
            messageAnimation = data.messageAnimation;
            uiAnimation = data.uiAnimation;
            if (data.charTypingMs > 0) charTypingMs = data.charTypingMs;
            if (data.maxTypingMs > 0) maxTypingMs = data.maxTypingMs;
            if (data.uiFontScale > 0f) uiFontScale = Math.max(0.5f, Math.min(2.0f, data.uiFontScale));
            screenBrightness = data.screenBrightness == null
                    ? 1f : Math.max(0f, Math.min(1f, data.screenBrightness));
            maidGroups.clear();
            if (data.maidGroups != null) {
                maidGroups.putAll(data.maidGroups);
            }
            customGroupNames.clear();
            if (data.customGroupNames != null) {
                for (String g : data.customGroupNames) {
                    if (g != null && !g.isBlank()) {
                        customGroupNames.add(g.trim());
                    }
                }
            }
        } catch (JsonParseException e) {
            // 损坏/不合法 JSON：不崩溃，回退默认设置；原文件改名备份，避免下次继续崩
            LOGGER.error("[HuhuTalk] 设置文件损坏，已回退默认设置: {}", file, e);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
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
            data.phoneOffsetX = phoneOffsetX;
            data.phoneOffsetY = phoneOffsetY;
            data.dndEnabled = dndEnabled;
            data.maidWhisperEnabled = maidWhisperEnabled;
            data.immersiveChatEnabled = immersiveChatEnabled;
            data.manualSeen = manualSeen;
            data.messageAnimation = messageAnimation;
            data.uiAnimation = uiAnimation;
            data.charTypingMs = charTypingMs;
            data.maxTypingMs = maxTypingMs;
            data.uiFontScale = uiFontScale;
            data.screenBrightness = screenBrightness;
            data.maidGroups = maidGroups;
            data.customGroupNames = customGroupNames;
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            // 原子写入：先写临时文件再重命名，避免写一半崩溃/断电导致 JSON 损坏
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
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
        float phoneOffsetX;
        float phoneOffsetY;
        boolean dndEnabled;
        boolean maidWhisperEnabled;
        boolean immersiveChatEnabled;
        boolean manualSeen;
        boolean messageAnimation;
        boolean uiAnimation;
        long charTypingMs;
        long maxTypingMs;
        float uiFontScale;
        Float screenBrightness; // 可空：缺失时默认满亮度 1.0
        Map<String, String> maidGroups;
        List<String> customGroupNames;
    }
}
