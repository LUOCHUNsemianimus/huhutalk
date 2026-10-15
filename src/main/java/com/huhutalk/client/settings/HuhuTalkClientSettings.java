package com.huhutalk.client.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.huhutalk.common.vision.VisionAppearance;
import com.huhutalk.common.vision.VisionProvider;
import com.huhutalk.common.vision.VisionProviders;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /**
     * 「药水复活」：死亡时由女仆远程送药救人（默认开）。
     * <p>每次触发都会让女仆走一次 AI 叙事，因此对在意 token 消耗的玩家提供关闭入口。
     */
    private static boolean potionRescueEnabled = true;
    /**
     * 「远程补给触发女仆回复」：远程补给这条链路要不要让女仆主动开口（默认开）。
     * <p>关掉后补给照常（物品照样消耗、手机页照样留痕），只是不再为"补给品用尽 /
     * 主人没被救回 / 手动切走模式"额外调用一次 AI；这些事实仍会写进她的记忆。
     */
    private static boolean remoteSupplyReply = true;
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
    /** 主界面 HuhuTalk 图标风格："symbol2"（高清）/ "symbol"（低清）/ "fox"（狐狸emoji） */
    private static String huhutalkIconStyle = "symbol2";
    /** 屏幕亮度（0~1，1=满亮度不调；往低调则变暗，最低叠 70% 黑色滤镜） */
    private static float screenBrightness = 1f;
    /**
     * 手机内独立光标的鼠标灵敏度（0.05~2.0）。
     * <p>
     * 与游戏「视角灵敏度」<b>无关</b>：光标吃的是鼠标原始增量 × 本系数 ÷ GUI Scale，
     * 视角走原版那条 {@code sensitivity³×8} 曲线 —— 两者同源、互不干扰，
     * 所以玩家可以一边转视角一边操作屏幕。
     */
    private static float phoneCursorSensitivity = 1.2f;
    /** 女仆手动分组：stableId -> 分组名 */
    private static final Map<String, String> maidGroups = new HashMap<>();
    /** 玩家自定义的分组名列表（持久化；联系人页会把其中的分组（含空组）也展示出来） */
    private static final List<String> customGroupNames = new ArrayList<>();

    /**
     * 「识图走哪个平台」：一个车万女仆的 LLM 站点 id（如 {@code aliyun} / {@code doubao}）。
     * <p>
     * 默认 {@code deepseek}，与改造前的硬编码链路完全一致 —— 老玩家升级后不配也能照常识图。
     * <p>
     * <b>注意这里只存"选哪个"，不存密钥</b>：密钥在主模组的服务端站点配置里
     * （{@code config/touhou_little_maid/sites/llm.json}），客户端从头到尾拿不到。
     */
    private static String visionSiteId = VisionProviders.DEFAULT_SITE_ID;
    /**
     * 各平台各自记住的模型：siteId -> model。
     * <p>
     * 玩家在阿里云选了 {@code qwen3-vl-flash}、在火山选了别的，来回切平台时互不影响。
     * 值为空串或缺失表示"用该平台的默认预设模型"。
     */
    private static final Map<String, String> visionModels = new HashMap<>();
    /**
     * 处于「自定义模型」模式的平台集合。
     * <p>
     * 为什么需要单独一个标记：玩家在下拉里选了「自定义…」之后可能<b>还没敲完</b>模型名，
     * 此时"记住的模型"是空的。如果只靠"记的模型在不在预设列表里"来判断，
     * 空值会让下拉立刻弹回预设默认——玩家刚点开的自定义输入框一秒就没了。
     * <p>
     * 有了这个标记，自定义模式就成了一个<b>显式状态</b>，与"输入框里有没有字"解耦。
     */
    private static final Set<String> visionCustomModelSites = new HashSet<>();
    /**
     * 「你的样貌」：主人（玩家）的外貌描述，识图时拼进提示词，让女仆能认出画面里的人是谁。
     * <p>
     * 空串 = 未填写（识图行为与改造前逐字一致）。长度上限见 {@link VisionAppearance#MAX_CHARS}。
     */
    private static String playerAppearance = "";
    /**
     * 「女仆的样貌」：stableId -> 该女仆的外貌描述。
     * <p>
     * 与 {@link #maidGroups} 同型同处（客户端本地持久化，不跟服务端走）。
     * 缺失或空串 = 未填写。发图时按<b>聊天窗口所属的女仆</b>取用，这是"女仆认出自己"的落点。
     */
    private static final Map<String, String> maidAppearance = new HashMap<>();

    /**
     * 桌面图标布局：<b>appKey → 槽位</b>。只记「玩家在「桌面图标管理」里动过的」那些图标。
     * <p>
     * 没记录 = 用默认位置（内置 App 用自己声明的槽位、附属模组的 App 按 {@code order} 自动落位），
     * 于是这份数据天然向后兼容：老配置文件里没有它 = 布局就是出厂状态。
     * <p>
     * <b>为什么这里存的是稀疏覆盖而不是整张表</b>：附属模组随时可能注册新 App、也可能被卸载。
     * 存整张表就必须在卸载时做一次"清理陈旧键"的迁移；存稀疏覆盖则漂移的键永远不生效、
     * 也永远不碍事。键是 {@link com.huhutalk.api.phone.PhoneApp#id()} 的字符串形式
     * （内置 App 用固定短名，见 {@code MainPage.buildApps()}）。
     */
    private static final Map<String, Integer> desktopSlots = new HashMap<>();
    /**
     * 被玩家从桌面<b>移除</b>的 appKey 集合（图标还在、只是不画在桌面上）。
     * <p>
     * 主应用 HuhuTalk 不在此列 —— 它的槽位恒为 0，{@code MainPage} 会拒绝任何针对它的增删。
     */
    private static final Set<String> desktopHidden = new HashSet<>();
    /**
     * 桌面布局版本号：每次布局变动 +1。
     * <p>
     * 与 {@code PhoneAppRegistry.generation()} 是<b>两个独立的失效源</b>：
     * 前者是"注册表变了"，后者是"玩家动了布局"。桌面把两者一起当缓存键，
     * 少一个就会出现"拖完图标要重开手机才生效"这种哑失败。
     */
    private static int desktopLayoutGeneration = 0;

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
    /** 「药水复活」开关：关闭后死亡不会触发女仆远程送药（也就不消耗 AI token） */
    public static boolean isPotionRescueEnabled() { return potionRescueEnabled; }
    /** 「远程补给触发女仆回复」开关：关闭后补给照常，但不再因此唤起女仆开口 */
    public static boolean isRemoteSupplyReplyEnabled() { return remoteSupplyReply; }
    public static boolean isManualSeen() { return manualSeen; }
    public static boolean isMessageAnimationEnabled() { return messageAnimation; }
    /** 「切屏动画」全局界面动画总开关 */
    public static boolean isUiAnimationEnabled() { return uiAnimation; }
    public static long getCharTypingMs() { return charTypingMs; }
    public static long getMaxTypingMs() { return maxTypingMs; }
    public static float getUiFontScale() { return uiFontScale; }
    public static float getScreenBrightness() { return screenBrightness; }
    public static String getHuhutalkIconStyle() { return huhutalkIconStyle; }

    /** 读取某女仆手动分组；未手动设置返回 null */
    public static String getMaidGroup(String stableId) {
        return maidGroups.get(stableId);
    }

    // ==================== 识图平台 ====================

    /** 当前选中的识图平台（TLM 站点 id）。 */
    public static String getVisionSiteId() {
        return visionSiteId;
    }

    /**
     * 设置识图平台并落盘。
     * <p>
     * 只做"非空"兜底，<b>不校验站点是否存在</b>：站点列表来自服务端，
     * 而本方法可能在还没连服务器时被调用（例如读老配置）。真正找不到时
     * 服务端识图会退回 {@code deepseek}，不会崩。
     */
    public static void setVisionSiteId(String siteId) {
        visionSiteId = (siteId == null || siteId.isBlank()) ? VisionProviders.DEFAULT_SITE_ID : siteId;
        save();
    }

    /** 某平台记住的模型；未设置返回空串（= 用平台预设默认）。 */
    public static String getVisionModel(String siteId) {
        if (siteId == null) {
            return "";
        }
        String m = visionModels.get(siteId);
        return m == null ? "" : m;
    }

    /** 记住某平台选中的模型（空串 = 回到该平台的默认预设模型）。 */
    public static void setVisionModel(String siteId, String model) {
        if (siteId == null || siteId.isBlank()) {
            return;
        }
        if (model == null || model.isBlank()) {
            visionModels.remove(siteId);
        } else {
            visionModels.put(siteId, model.trim());
        }
        save();
    }

    /** 该平台是否处于「自定义模型」模式（模型名以下拉下方的输入框为准）。 */
    public static boolean isVisionCustomModel(String siteId) {
        return siteId != null && visionCustomModelSites.contains(siteId);
    }

    /**
     * 切换某平台的「自定义模型」模式。
     * <p>退出该模式时不动已记住的模型，便于玩家来回切着对比。
     */
    public static void setVisionCustomModel(String siteId, boolean custom) {
        if (siteId == null || siteId.isBlank()) {
            return;
        }
        boolean changed = custom ? visionCustomModelSites.add(siteId)
                : visionCustomModelSites.remove(siteId);
        if (changed) {
            save();
        }
    }

    /**
     * 当前识图平台最终会用的模型名——<b>只给 UI 摘要用</b>（服务端的真实取值在
     * {@code VisionSettings.Selection#resolveModel()}，那里还会再兜一层）。
     * <p>
     * 与 {@link #getVisionModel} 的区别：这里在"自定义模式但还没填"时明确返回
     * 「自定义」，而不是显示一个其实不会被用到的预设默认值。
     */
    public static String getEffectiveVisionModel() {
        String siteId = visionSiteId;
        String stored = getVisionModel(siteId);
        if (!stored.isBlank()) {
            return stored;
        }
        if (isVisionCustomModel(siteId)) {
            return "自定义";
        }
        VisionProvider p = VisionProviders.byId(siteId);
        return p != null ? p.resolveDefaultModel() : "";
    }

    /** 全部手动分组映射（不可变副本） */
    public static Map<String, String> getMaidGroups() {
        return Map.copyOf(maidGroups);
    }

    // ==================== 外貌描述（识图人设） ====================

    /** 「你的样貌」：主人（玩家）的外貌描述；未填写返回空串。 */
    public static String getPlayerAppearance() {
        return playerAppearance;
    }

    /**
     * 设置「你的样貌」并落盘。
     * <p>
     * 传空 / 空白 = 清空（回到"不认人"的默认识图行为）。
     * 文本会经 {@link VisionAppearance#clean} 规整并截断到上限。
     */
    public static void setPlayerAppearance(String text) {
        playerAppearance = VisionAppearance.clean(text);
        save();
    }

    /** 「女仆的样貌」：某个女仆的外貌描述；未填写返回空串。 */
    public static String getMaidAppearance(String stableId) {
        if (stableId == null) {
            return "";
        }
        String v = maidAppearance.get(stableId);
        return v == null ? "" : v;
    }

    /**
     * 设置某个女仆的「女仆的样貌」并落盘。
     * <p>
     * <b>空串 = 删除该条目</b>（与 {@link #setMaidGroup} 同一语义）：
     * 否则清空描述后设置文件里会永远留着一堆空条目。
     */
    public static void setMaidAppearance(String stableId, String text) {
        if (stableId == null) {
            return;
        }
        String v = VisionAppearance.clean(text);
        if (v.isEmpty()) {
            maidAppearance.remove(stableId);
        } else {
            maidAppearance.put(stableId, v);
        }
        save();
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

    /**
     * 是否是<b>默认（状态）分组</b>名：在线 / 离线 / 已关机 / 死亡。
     * <p>
     * 这四个由女仆实时状态决定、<b>不可删除</b>，也不允许被建成同名自定义分组。
     * 分组管理 UI 用它来决定"这一行画不画 ✕"——判据只有这一处，避免渲染与命中各写一份名单。
     */
    public static boolean isDefaultGroupName(String name) {
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

    /**
     * 设置「药水复活」开关。
     * <p><b>注意</b>：判定发生在服务端（死亡瞬间），所以调用方改完这里后必须把值同步给服务端
     * （见 {@code ClientModEvents.pushPotionRescueSetting} / {@code SettingsPage}）。
     * 本方法只负责本地持久化。
     */
    public static void setPotionRescueEnabled(boolean enabled) {
        potionRescueEnabled = enabled;
        save();
    }

    /**
     * 设置「远程补给触发女仆回复」开关。
     * <p><b>注意</b>：判定发生在服务端，所以调用方改完这里后必须把值同步给服务端
     * （见 {@code ClientModEvents.pushRemoteSupplyReplySetting} / {@code SettingsPage}）。
     * 本方法只负责本地持久化。
     */
    public static void setRemoteSupplyReplyEnabled(boolean enabled) {
        remoteSupplyReply = enabled;
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

    /** 设置主界面 HuhuTalk 图标风格：symbol2 / symbol / fox（非法值回退 symbol2） */
    public static void setHuhutalkIconStyle(String style) {
        if ("symbol".equals(style) || "fox".equals(style) || "symbol2".equals(style)) {
            huhutalkIconStyle = style;
        } else {
            huhutalkIconStyle = "symbol2";
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

    /** 手机内独立光标灵敏度（钳制 0.05~2.0） */
    public static float getPhoneCursorSensitivity() {
        return phoneCursorSensitivity;
    }

    /** 设置手机光标灵敏度并落盘 */
    public static void setPhoneCursorSensitivity(float v) {
        phoneCursorSensitivity = Math.max(0.05f, Math.min(2.0f, v));
        save();
    }

    /** 拖动灵敏度滑块过程中的高频更新：只改内存，不落盘（松开时由调用方 save） */
    public static void setPhoneCursorSensitivityRaw(float v) {
        phoneCursorSensitivity = Math.max(0.05f, Math.min(2.0f, v));
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

    // ==================== 桌面图标布局 ====================

    /**
     * 某 App 被玩家指定的槽位；<b>-1 = 没指定</b>（用默认位置）。
     * <p>槽位合法性（0 ~ {@code MAX_SLOTS-1}）由桌面在应用布局时判定，
     * 这样设置层不需要认识 {@code api.phone} 的类型。</p>
     */
    public static int getDesktopSlot(String key) {
        Integer v = key == null ? null : desktopSlots.get(key);
        return v == null ? -1 : v;
    }

    /** 全部布局覆盖（不可变副本，供诊断/文档用）。 */
    public static Map<String, Integer> getDesktopSlots() {
        return Map.copyOf(desktopSlots);
    }

    /**
     * 把某 App 指定到某槽位并落盘。
     * <p><b>调用方负责交换语义</b>：若目标槽位已被别人占着，要自己再调一次把对方挪走
     * （见 {@code MainPage.moveDesktopApp}）。这里只做"写一条覆盖"。
     */
    public static void setDesktopSlot(String key, int slot) {
        if (key == null || key.isBlank() || slot < 0) {
            return;
        }
        if (desktopSlots.getOrDefault(key, -1) == slot) {
            return;
        }
        desktopSlots.put(key, slot);
        desktopLayoutGeneration++;
        save();
    }

    /** 清掉某 App 的槽位覆盖（回到默认位置）。 */
    public static void clearDesktopSlot(String key) {
        if (key == null || desktopSlots.remove(key) == null) {
            return;
        }
        desktopLayoutGeneration++;
        save();
    }

    /** 某 App 是否被玩家从桌面移除。 */
    public static boolean isDesktopHidden(String key) {
        return key != null && desktopHidden.contains(key);
    }

    /** 被移除的全部 appKey（不可变副本）。 */
    public static Set<String> getDesktopHidden() {
        return Set.copyOf(desktopHidden);
    }

    /**
     * 移除 / 放回某 App。
     * <p>
     * <b>移除时会连它的槽位覆盖一起清掉</b>：否则"移除 A"与"把 B 拖到 A 那格"会让两个 key
     * 同时指向一个槽位，谁赢取决于遍历顺序（不确定）。清掉之后，放回 = 回到默认位置，
     * 被占则按桌面的既有纪律退让到最小空槽。
     *
     * @return true = 状态确实变了（调用方据此决定要不要重绘/提示）
     */
    public static boolean setDesktopHidden(String key, boolean hidden) {
        if (key == null || key.isBlank()) {
            return false;
        }
        boolean changed = hidden ? desktopHidden.add(key) : desktopHidden.remove(key);
        if (!changed) {
            return false;
        }
        if (hidden) {
            desktopSlots.remove(key);
        }
        desktopLayoutGeneration++;
        save();
        return true;
    }

    /** 桌面布局版本号；桌面把它当合并缓存的键之一。 */
    public static int getDesktopLayoutGeneration() {
        return desktopLayoutGeneration;
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
            // 用「可空 Boolean + 缺失时按 true」而不是裸 boolean：
            // 否则老配置文件里没有该字段，Gson 会解析成 false，把默认开启的功能静默关掉
            potionRescueEnabled = data.potionRescueEnabled == null ? true : data.potionRescueEnabled;
            remoteSupplyReply = data.remoteSupplyReply == null ? true : data.remoteSupplyReply;
            manualSeen = data.manualSeen;
            messageAnimation = data.messageAnimation;
            uiAnimation = data.uiAnimation;
            if (data.charTypingMs > 0) charTypingMs = data.charTypingMs;
            if (data.maxTypingMs > 0) maxTypingMs = data.maxTypingMs;
            if (data.uiFontScale > 0f) uiFontScale = Math.max(0.5f, Math.min(2.0f, data.uiFontScale));
            screenBrightness = data.screenBrightness == null
                    ? 1f : Math.max(0f, Math.min(1f, data.screenBrightness));
            phoneCursorSensitivity = data.phoneCursorSensitivity == null
                    ? 1.2f : Math.max(0.05f, Math.min(2.0f, data.phoneCursorSensitivity));
            if (data.huhutalkIconStyle != null) setHuhutalkIconStyle(data.huhutalkIconStyle);
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
            // 识图平台：老配置没有该字段 → 保持默认（deepseek），与改造前行为一致
            visionSiteId = (data.visionSiteId == null || data.visionSiteId.isBlank())
                    ? VisionProviders.DEFAULT_SITE_ID : data.visionSiteId;
            visionModels.clear();
            if (data.visionModels != null) {
                data.visionModels.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                        visionModels.put(k, v.trim());
                    }
                });
            }
            visionCustomModelSites.clear();
            if (data.visionCustomModelSites != null) {
                for (String s : data.visionCustomModelSites) {
                    if (s != null && !s.isBlank()) {
                        visionCustomModelSites.add(s.trim());
                    }
                }
            }
            // 外貌描述：老配置没有这两个字段 → 保持空（= 未填写），识图行为与改造前一致。
            // 这里同样走一遍 clean：设置文件是玩家可以手改的，超长/带换行的文本不该流进提示词。
            playerAppearance = VisionAppearance.clean(data.playerAppearance);
            maidAppearance.clear();
            if (data.maidAppearance != null) {
                data.maidAppearance.forEach((k, v) -> {
                    String cleaned = VisionAppearance.clean(v);
                    if (k != null && !k.isBlank() && !cleaned.isEmpty()) {
                        maidAppearance.put(k, cleaned);
                    }
                });
            }
            // 桌面图标布局：老配置没有这两个字段 → 保持空 = 出厂布局（与改造前逐像素一致）。
            // 槽位范围不在这里判：设置层不认识 api.phone，越界值由桌面在应用布局时忽略并退让。
            desktopSlots.clear();
            if (data.desktopSlots != null) {
                data.desktopSlots.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && v >= 0) {
                        desktopSlots.put(k, v);
                    }
                });
            }
            desktopHidden.clear();
            if (data.desktopHidden != null) {
                for (String k : data.desktopHidden) {
                    if (k != null && !k.isBlank()) {
                        desktopHidden.add(k);
                    }
                }
            }
            // 读盘也算一次布局变动：桌面可能在读盘之前就已经建过合并缓存了
            desktopLayoutGeneration++;
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
            data.potionRescueEnabled = potionRescueEnabled;
            data.remoteSupplyReply = remoteSupplyReply;
            data.manualSeen = manualSeen;
            data.messageAnimation = messageAnimation;
            data.uiAnimation = uiAnimation;
            data.charTypingMs = charTypingMs;
            data.maxTypingMs = maxTypingMs;
            data.uiFontScale = uiFontScale;
            data.screenBrightness = screenBrightness;
            data.phoneCursorSensitivity = phoneCursorSensitivity;
            data.huhutalkIconStyle = huhutalkIconStyle;
            data.maidGroups = maidGroups;
            data.customGroupNames = customGroupNames;
            data.visionSiteId = visionSiteId;
            data.visionModels = visionModels;
            data.visionCustomModelSites = visionCustomModelSites;
            data.playerAppearance = playerAppearance;
            data.maidAppearance = maidAppearance;
            data.desktopSlots = desktopSlots;
            data.desktopHidden = desktopHidden;
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
        /** 可空：缺失时默认开启（老配置文件没有该字段） */
        Boolean potionRescueEnabled;
        /** 可空：缺失时默认开启（老配置文件没有该字段） */
        Boolean remoteSupplyReply;
        boolean manualSeen;
        boolean messageAnimation;
        boolean uiAnimation;
        long charTypingMs;
        long maxTypingMs;
        float uiFontScale;
        Float screenBrightness; // 可空：缺失时默认满亮度 1.0
        Float phoneCursorSensitivity; // 可空：缺失时默认 1.2
        String huhutalkIconStyle; // 可空：缺失时默认 symbol2
        Map<String, String> maidGroups;
        List<String> customGroupNames;
        /** 可空：缺失时默认 deepseek（老配置文件没有该字段，正好等于改造前的行为） */
        String visionSiteId;
        /** 各平台选中的模型：siteId -> model */
        Map<String, String> visionModels;
        /** 处于「自定义模型」模式的平台集合 */
        Set<String> visionCustomModelSites;
        /**
         * 「你的样貌」（可空：缺失 = 未填写）。
         * <p>与 {@code screenBrightness} 同款写法：可空类型 + 缺失时取默认，
         * 于是老设置文件天然向后兼容、不需要版本号。
         */
        String playerAppearance;
        /** 「女仆的样貌」：stableId -> 描述（可空：缺失 = 全部未填写） */
        Map<String, String> maidAppearance;
        /**
         * 桌面图标布局覆盖：appKey -> 槽位（可空：缺失 = 出厂布局，老配置天然兼容）。
         * <p>稀疏覆盖：只记玩家在「桌面图标管理」里动过的图标。
         */
        Map<String, Integer> desktopSlots;
        /** 被玩家从桌面移除的 appKey（可空：缺失 = 一个都没移除） */
        Set<String> desktopHidden;
    }
}
