package com.huhutalk.client.theme;

/**
 * 一套手机 UI 配色方案。
 * <p>
 * 所有颜色字段均为 ARGB（0xAARRGGBB）。方案定义一套"语义色板"，页面统一从这里取色，
 * 便于日后新增其他配色方案一键切换。
 */
public record ColorScheme(
        String id,
        String displayName,
        // ===== 结构色 =====
        int topBarBg,        // 顶部标题栏 / 底部导航背景（高亮粉）
        int topBarText,      // 顶栏文字（白）
        int pageBg,          // 页面整体背景（浅珍珠粉）
        int navBg,           // 底部导航背景（与顶栏同款）
        int navText,         // 底部导航未选中文字
        int navTextActive,   // 底部导航选中文字（白）
        // ===== 消息气泡 =====
        int maidBubble,      // 女仆消息气泡（深青）
        int maidText,        // 女仆气泡文字（白）
        int playerBubble,    // 玩家消息气泡（蓝）
        int playerText,      // 玩家气泡文字（白）
        int errorBubble,     // 错误/提示气泡（中性浅红）
        int errorText,       // 错误气泡文字
        // ===== 操作色 =====
        int sosBg,           // SOS 紧急按钮（珊瑚红）
        int sosText,         // SOS 文字（白）
        int accentSoft,      // 中度柔粉（下拉/分组控件底）
        int accentSoftActive,// 下拉/分组选中项高亮（柔粉）
        int dangerBg,        // 危险/删除操作（暗红警示）
        int dangerText,      // 危险文字（白）
        int neutralBg,       // 中性灰（如删除聊天记录）
        int neutralText      // 中性灰文字
) {
    // ==================== 预设配色方案 ====================

    /**
     * 默认「珍珠粉」主题。
     * 顶栏粉底白字、页面浅珍珠粉底、深青女仆气泡/蓝玩家气泡、SOS 珊瑚红、柔粉下拉。
     */
    public static final ColorScheme PEARL_PINK = new ColorScheme(
            "pearl_pink",
            "珍珠粉",
            0xFFF06292,   // 顶栏：高亮粉
            0xFFFFFFFF,   // 顶栏文字：白
            0xFFFFF0F5,   // 页面底：浅珍珠粉
            0xFFF06292,   // 底部导航：同款粉
            0xFFFFE0EA,   // 底部导航未选中：浅粉
            0xFFFFFFFF,   // 底部导航选中：白
            0xFF00796B,   // 女仆气泡：低饱和低亮深青
            0xFFFFFFFF,   // 女仆文字：白
            0xFF1E88E5,   // 玩家气泡：蓝
            0xFFFFFFFF,   // 玩家文字：白
            0xFFFFE0E0,   // 错误气泡：浅红
            0xFFC62828,   // 错误文字：暗红
            0xFFFF6F61,   // SOS：珊瑚红
            0xFFFFFFFF,   // SOS 文字：白
            0xFFF8BBD0,   // 中度柔粉（下拉/分组控件）
            0xFFF48FB1,   // 选中项高亮（稍深柔粉）
            0xFFB71C1C,   // 危险/删除：暗红
            0xFFFFFFFF,   // 危险文字：白
            0xFF9E9E9E,   // 中性灰（删除聊天记录）
            0xFFFFFFFF    // 中性灰文字
    );
}
