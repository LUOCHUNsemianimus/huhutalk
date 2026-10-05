package com.huhutalk.client.theme;

/**
 * 一套手机 UI 配色方案。
 * <p>
 * 所有颜色字段均为 ARGB（0xAARRGGBB）。方案定义一套"语义色板"，页面统一从这里取色，
 * 便于日后新增其他配色方案一键切换。
 *
 * <h2>「语义色板」是默认值，不是强制</h2>
 * 内置页面与页面骨架默认从 {@link ThemeManager#getActive()} 取色，这样换主题能整体生效。
 * 但<b>自带视觉风格的 App 不受此约束</b>——例如深色底的内容型 App，
 * 直接用自己的品牌色即可（覆盖 {@code PhonePage#colors()} 或干脆不用它）。
 * 色板负责"让默认页面跟着主题走"，不负责"规定所有 App 长什么样"。
 */
public record ColorScheme(
        String id,
        String displayName,
        // ===== 结构色 =====
        int topBarBg,        // 顶部标题栏 / 底部导航背景（高亮粉）
        int topBarText,      // 顶栏文字（白）
        int pageBg,          // 主要页面背景（桌面 / 通用页面底）
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
        int neutralText,     // 中性灰文字
        // ===== 页面结构色（页面骨架与列表页使用）=====
        int pageBgList,      // 设置 / 条目 / 列表类页面的背景（比主要背景略深一档）
        int cardBg,          // 栏目 / 卡片填充（比页面底略亮，用于分组容器）
        int textPrimary,     // 主要文字（标题、正文）
        int textSecondary,   // 次要文字（说明、状态）
        int textMuted,       // 弱化文字（占位、禁用）
        int divider,         // 分隔线
        int accent           // 主强调色（开关激活态 / 滑块把手 / stepper 箭头）
) {
    // ==================== 预设配色方案 ====================

    /**
     * 默认「珍珠粉」主题。
     * 顶栏粉底白字、页面浅珍珠粉底、深青女仆气泡/蓝玩家气泡、SOS 珊瑚红、柔粉下拉。
     * <p>
     * 页面结构色（自 {@code pageBgList} 起）的三档背景与三档文字：
     * 文字色刻意取同色系的暖深色（而非纯灰），压在粉底上不发蓝、不显脏；
     * 对 {@code pageBg} 的对比度分别约为 10.9 : 1（主要）、4.9 : 1（次要）、3.3 : 1（弱化）。
     */
    public static final ColorScheme PEARL_PINK = new ColorScheme(
            "pearl_pink",
            "珍珠粉",
            0xFFDE7E7E,   // 顶栏：品牌粉（与内置页面的硬编码顶栏同色；原为 #F06292，偏紫，已更正）
            0xFFFFFFFF,   // 顶栏文字：白
            0xFFF2E8EF,   // 主要页面背景：柔和粉
            0xFFDE7E7E,   // 底部导航：与顶栏同色
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
            0xFFFFFFFF,   // 中性灰文字
            // ===== 页面结构色 =====
            0xFFEDE1E7,   // 列表/设置类页面背景：比主要背景深一档
            0xFFFCF2F5,   // 栏目 / 卡片填充：比页面底亮一档
            0xFF3A2E33,   // 主要文字（对比度 ≈ 10.9:1）
            0xFF6F6167,   // 次要文字（≈ 4.9:1）
            0xFF8A7C82,   // 弱化文字（≈ 3.3:1）
            0xFFE3D5DB,   // 分隔线
            0xFFDE7E7E    // 主强调色（与原页面里的 #de7e7e 一致）
    );
}
