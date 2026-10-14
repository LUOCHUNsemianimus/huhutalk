package com.huhutalk.common.vision;

/**
 * 「外貌描述」的公共约束与规整化 —— 客户端（控件与设置文件）与服务端（提示词组装）共用一份。
 *
 * <h2>为什么单独一个类</h2>
 * 这两段文本<b>两端都要过一遍</b>：
 * <ul>
 *   <li>客户端：输入控件的长度上限、落盘前的清洗（{@code HuhuTalkClientSettings}）；</li>
 *   <li>服务端：拼进识图提示词之前的清洗（{@code MaidChatHandler}，防范客户端被改包）。</li>
 * </ul>
 * 上限定在两处就会漂移，而漂移的后果是"客户端存得下、服务端却被截掉一半"，
 * 所以放在 {@code common} 包里，两端都引同一个常量。
 *
 * <h2>为什么要压空白</h2>
 * 描述最终是拼成一行提示词的一段，换行在里面没有语义，
 * 却可能被某些视觉模型当成新的一条指令。压成单个空格最省事也最安全。
 */
public final class VisionAppearance {

    /** 单段外貌描述的最大字符数（200 字 ≈ 400 token，是刻意的上限）。 */
    public static final int MAX_CHARS = 200;

    private VisionAppearance() {
    }

    /**
     * 规整化一段外貌描述：去掉首尾空白、把连续空白（含换行/Tab）压成单个空格、
     * 截断到 {@link #MAX_CHARS}。
     *
     * @return 规整化后的文本；入参为 {@code null} 时返回空串（<b>不返回 null</b>）
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s;
    }

    /** 规整化后是否为空（= 视为未填写）。 */
    public static boolean isBlank(String raw) {
        return clean(raw).isEmpty();
    }
}
