package com.huhutalk.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息断句工具（聊天页气泡分段与未读计数共用）。
 * <p>
 * 断句规则：
 * <ul>
 *   <li><b>消耗型</b>（分隔且不保留符号）：中英文逗号（, ，）、中英文句号（. 。）、回车/换行。</li>
 *   <li><b>保留型</b>（分隔且保留句尾符号）：感叹号（! ！）、问号（? ？）、波浪号（~ ～）。</li>
 *   <li><b>省略号组</b>：连续的点号（. 或 …，两个及以上）视为省略号，在<b>最后一个点</b>处断句并整组保留。
 *       LLM 无论输出几个点，都只在省略号组末尾断一次。</li>
 * </ul>
 * 去除每段首尾空白与空段。
 */
public final class MessageSplitter {
    private MessageSplitter() {}

    /** 把整段回复按断句规则拆成多条短句 */
    public static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            // 省略号组：连续的点号（. 或 …）
            if (c == '.' || c == '…') {
                int start = i;
                while (i < n && (text.charAt(i) == '.' || text.charAt(i) == '…')) {
                    i++;
                }
                String dots = text.substring(start, i);
                // 单个 '.' 视为句号（消耗）；两个及以上视为省略号组（保留）
                if (dots.length() <= 1 && dots.charAt(0) == '.') {
                    flush(result, sb);
                } else {
                    sb.append(dots);
                    flush(result, sb);
                }
                continue;
            }
            if (c == ',' || c == '，' || c == '。' || c == '\n' || c == '\r') {
                flush(result, sb);
            } else if (c == '!' || c == '！' || c == '?' || c == '？' || c == '~' || c == '～') {
                sb.append(c);
                flush(result, sb);
            } else {
                sb.append(c);
            }
            i++;
        }
        flush(result, sb);
        return result;
    }

    private static void flush(List<String> result, StringBuilder sb) {
        String seg = sb.toString().trim();
        if (!seg.isEmpty()) {
            result.add(seg);
        }
        sb.setLength(0);
    }
}
