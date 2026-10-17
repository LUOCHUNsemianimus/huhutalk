package com.huhutalk.client.theme;

import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.settings.HuhuTalkClientSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主题管理器（客户端）。
 * <p>
 * 管理当前配色方案，提供切换接口。当前选中方案 id 持久化在 {@link HuhuTalkClientSettings}，
 * 进世界时按保存值恢复。预留扩展：后续可向 {@link #SCHEMES} 追加新的 {@link ColorScheme}，
 * 即可在设置页一键切换。
 */
public final class ThemeManager {
    /** 全部可用配色方案（扩展点：在此追加新方案） */
    public static final List<ColorScheme> SCHEMES = Collections.unmodifiableList(new ArrayList<>(List.of(
            ColorScheme.PEARL_PINK
    )));

    private static ColorScheme active = ColorScheme.PEARL_PINK;

    private ThemeManager() {
    }

    /** 当前生效的配色方案 */
    public static ColorScheme getActive() {
        return active;
    }

    /**
     * 配色方案在界面上的显示名：按<b>稳定 id</b> 解析当前语言的译文，
     * 未知主题（或该语言没有译文）保留 {@link ColorScheme#displayName()} 原名。
     * <p>
     * 只翻译显示文案，不改 {@link ColorScheme} 的原始数据，也不改存档里的主题 id。
     */
    public static String displayNameOf(ColorScheme scheme) {
        if (scheme == null) {
            return "";
        }
        return PhoneText.trOr("gui.huhutalk.theme." + scheme.id(), scheme.displayName());
    }

    /** 按 id 切换配色方案；未知 id 回退默认 */
    public static void setScheme(String id) {
        for (ColorScheme scheme : SCHEMES) {
            if (scheme.id().equals(id)) {
                active = scheme;
                break;
            }
        }
        HuhuTalkClientSettings.setThemeId(active.id());
    }

    /** 循环切到下一套配色（设置页"切换配色"按钮用） */
    public static ColorScheme cycleNext() {
        int idx = SCHEMES.indexOf(active);
        int next = (idx + 1) % SCHEMES.size();
        active = SCHEMES.get(next);
        HuhuTalkClientSettings.setThemeId(active.id());
        return active;
    }

    /** 进世界时按保存的设置恢复主题 */
    public static void loadFromSettings() {
        setScheme(HuhuTalkClientSettings.getThemeId());
    }
}
