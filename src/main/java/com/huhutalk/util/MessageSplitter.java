package com.huhutalk.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息断句工具（聊天页气泡分段与未读计数共用）。
 * <p>在逗号、感叹号、问号（含中英文）处断句：
 * 逗号被消耗；感叹号/问号作为句尾字符保留。去除每段首尾空白与空段。
 */
public final class MessageSplitter {
    private MessageSplitter() {}

    /** 把整段回复按断句规则拆成多条短句 */
    public static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '，') {
                flush(result, sb);
            } else if (c == '!' || c == '！' || c == '?' || c == '？') {
                sb.append(c);
                flush(result, sb);
            } else {
                sb.append(c);
            }
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
