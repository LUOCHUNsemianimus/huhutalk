package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让女仆在 AI 回复时附带一张表情包的 LLM 工具。
 * <p>
 * LLM 通过它挑选一种情绪/表情，工具把对应的表情图「挂到」本次回复上；
 * 客户端在显示文本回复时，把它作为<b>附件</b>在同一时刻分组尾部一并展示（只在狐狐手机里显示）。
 * <p>
 * 注意：车万女仆默认表情只有数字编号（emoji_01~20，无语义名），
 * 故这里的「情绪 → 表情」映射由本 mod 内置维护（内容经人工辨认整理）。
 */
public class SendEmoteTool implements ITool<SendEmoteTool.Result> {

    public static final String TOOL_ID = "huhutalk_send_emoji";
    private static final String EMOTION = "emotion";
    private static final String NAMESPACE = "touhou_little_maid";
    private static final String DIR = "textures/chat_bubble/maid_emoji/";

    /** 情绪(语义名) → 表情文件名（人工辨认 emoji_01~20 后整理）。 */
    private static final Map<String, String> EMOTION_TO_EMOJI = new LinkedHashMap<>();

    static {
        EMOTION_TO_EMOJI.put("shy", "emoji_01.png");          // 害羞冒汗
        EMOTION_TO_EMOJI.put("happy", "emoji_02.png");        // 被摸头冒爱心
        EMOTION_TO_EMOJI.put("silent", "emoji_03.png");       // 草丛冒省略号（沉默）
        EMOTION_TO_EMOJI.put("melon", "emoji_04.png");        // 吃瓜
        EMOTION_TO_EMOJI.put("explode", "emoji_05.png");      // TNT（爆了）
        EMOTION_TO_EMOJI.put("no", "emoji_06.png");           // 不可以
        EMOTION_TO_EMOJI.put("laugh", "emoji_07.png");        // 捧腹大笑
        EMOTION_TO_EMOJI.put("cheers", "emoji_08.png");       // 干杯
        EMOTION_TO_EMOJI.put("work", "emoji_09.png");         // 严肃劳作
        EMOTION_TO_EMOJI.put("angry", "emoji_10.png");        // 生气伸拳
        EMOTION_TO_EMOJI.put("scared", "emoji_11.png");       // 抱尾发抖（害怕/冷）
        EMOTION_TO_EMOJI.put("rich", "emoji_12.png");         // 钱堆（发财）
        EMOTION_TO_EMOJI.put("confused", "emoji_13.png");     // 冒问号
        EMOTION_TO_EMOJI.put("sleepy", "emoji_14.png");       // 困
        EMOTION_TO_EMOJI.put("dying", "emoji_15.png");        // 没生命值
        EMOTION_TO_EMOJI.put("me", "emoji_16.png");           // 指自己
        EMOTION_TO_EMOJI.put("cry", "emoji_17.png");          // 抱头哭哭
        EMOTION_TO_EMOJI.put("hungry", "emoji_18.png");       // 想吃蛋糕（饿）
        EMOTION_TO_EMOJI.put("take_me", "emoji_19.png");      // 被装袋里（带我走）
        EMOTION_TO_EMOJI.put("tease", "emoji_20-24x24.png");  // 杂鱼杂鱼（挑衅/逗玩家）
    }

    private static final String TOOL_DESC = """
            Use this when you want to attach a cute emoji to your reply (in addition to your text).
            Pick the emotion that best matches how you feel; the emoji will be shown to the master as part of your reply.
            emotion: one of the predefined emotions (see the parameter list). Choose the closest fit; do not invent new names.
            """.trim();

    /** 表情文件名 → 中文词（用于消息列表预览等展示；与 EMOTION_TO_EMOJI 一一对应） */
    private static final Map<String, String> EMOJI_LABEL = new LinkedHashMap<>();

    static {
        EMOJI_LABEL.put("emoji_01.png", "害羞");
        EMOJI_LABEL.put("emoji_02.png", "开心");
        EMOJI_LABEL.put("emoji_03.png", "沉默");
        EMOJI_LABEL.put("emoji_04.png", "吃瓜");
        EMOJI_LABEL.put("emoji_05.png", "爆了");
        EMOJI_LABEL.put("emoji_06.png", "不行");
        EMOJI_LABEL.put("emoji_07.png", "大笑");
        EMOJI_LABEL.put("emoji_08.png", "干杯");
        EMOJI_LABEL.put("emoji_09.png", "努力");
        EMOJI_LABEL.put("emoji_10.png", "生气");
        EMOJI_LABEL.put("emoji_11.png", "害怕");
        EMOJI_LABEL.put("emoji_12.png", "发财");
        EMOJI_LABEL.put("emoji_13.png", "疑惑");
        EMOJI_LABEL.put("emoji_14.png", "困");
        EMOJI_LABEL.put("emoji_15.png", "没血");
        EMOJI_LABEL.put("emoji_16.png", "我吗");
        EMOJI_LABEL.put("emoji_17.png", "哭哭");
        EMOJI_LABEL.put("emoji_18.png", "饿");
        EMOJI_LABEL.put("emoji_19.png", "带我走");
        EMOJI_LABEL.put("emoji_20-24x24.png", "逗你");
    }

    /** 由表情资源路径反查中文词；未知路径返回 null */
    public static String labelForPath(String emotePath) {
        if (emotePath == null) {
            return null;
        }
        int idx = emotePath.lastIndexOf('/');
        String file = idx >= 0 ? emotePath.substring(idx + 1) : emotePath;
        return EMOJI_LABEL.get(file);
    }

    /** 表情的预览文本，如 "（干杯）"；未知路径返回 null */
    public static String previewText(String emotePath) {
        String label = labelForPath(emotePath);
        return label == null ? null : "（" + label + "）";
    }

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(EMOTION).forGetter(Result::emotion)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter emotion = StringParameter.create().setDescription(
                "The emotion/expression to attach. Options: " + emotionDescriptions());
        emotion.addEnumValues(EMOTION_TO_EMOJI.keySet().toArray(new String[0]));
        root.addProperties(EMOTION, emotion);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        String file = EMOTION_TO_EMOJI.get(result.emotion());
        if (file == null) {
            return callback.addToolResult(
                    ITool.invalidParam(EMOTION, EMOTION_TO_EMOJI.keySet(),
                            "Unknown emotion '" + result.emotion() + "'"),
                    toolCallId);
        }
        String path = NAMESPACE + ":" + DIR + file;
        if (callback instanceof HuhuTalkChatCallback hc) {
            hc.attachEmote(path);
        }
        return callback.addToolResult(
                "Emoji '" + result.emotion() + "' attached to your reply. Now continue with your short in-character text reply.",
                toolCallId);
    }

    private static String emotionDescriptions() {
        return EMOTION_TO_EMOJI.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .toList().toString();
    }

    public record Result(String emotion) {
    }
}
