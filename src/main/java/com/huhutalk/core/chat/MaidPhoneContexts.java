package com.huhutalk.core.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.List;

/**
 * 女仆的「手机能力」常识 —— 注册进 TLM 的<b>上下文知识库</b>（{@link GameContextRegister}）。
 *
 * <h2>为什么放这里，而不是写在救援提示词里</h2>
 * "她都会什么"属于她本来就该知道的常识。若每次都塞进救援的唤醒词里，会把她的注意力拉到
 * "让主人看消息"这类事情上，反而冲淡当下该有的反应。放进知识库后：
 * <ul>
 *   <li>它作为常驻上下文出现在聊天里，她<b>自然知道</b>自己有远程支援的手段；</li>
 *   <li>玩家开口让她"辅助我 / 帮帮我"时，她答得上话，也不会答应做不到的事；</li>
 *   <li>救援提示词可以彻底回归"这一刻发生了什么"。</li>
 * </ul>
 *
 * <h2>两条渲染路径（重要）</h2>
 * <ul>
 *   <li><b>TLM 自带聊天</b>：{@code MaidAIChatManager.normalChat} 会调
 *       {@code UserPromptContexts.addContext}，把 {@code promptContext=true} 的类别
 *       拼进 {@code <context>} 块 —— 本类注册后自动生效，无需额外代码。</li>
 *   <li><b>HuhuTalk 手机聊天</b>：{@code MaidChatHandler.buildMessages} 是自建的，
 *       <b>绕过了</b>上面那条路径，所以必须显式调 {@link #render(EntityMaid)}
 *       把本类别拼进它自己的来源上下文里（见 {@code MaidChatHandler#chatContext}）。</li>
 * </ul>
 *
 * <h2>文案口径</h2>
 * 只讲事实：手机通道、能把背包/隙间的物品递过去、出事时补血是自动发生的。
 * 最后一句"不用你操心"是刻意加的 —— 防止她答应用户"我这就远程救你"这种她无法主动触发的事。
 * <p>语言与模组其余提示词保持一致（中文），本地化随整体翻译工作一起做（见交接文档 §一.20）。
 */
public final class MaidPhoneContexts {

    /** 本模组在 TLM 上下文注册表里的类别 id（需全局唯一） */
    public static final String CATEGORY = "huhutalk_phone";

    /** 类别描述（TLM 用于"有哪些上下文类别"的展示与工具说明） */
    private static final String CATEGORY_SUMMARY = "Master's phone channel and your remote support.";

    private MaidPhoneContexts() {
    }

    /** 由 {@code HuhuTalkTLMExtension#registerAIMaidContext} 调用（TLM 启动时注册一次）。 */
    public static void registerAll(GameContextRegister register) {
        // promptContext = true：作为常驻上下文注入提示词，而不是等模型主动查询
        register.registerCategory(CATEGORY, CATEGORY_SUMMARY, true);
        register.registerContext(CATEGORY, new RemoteSupportContext());
        register.registerContext(CATEGORY, new PhoneMemoryContext());
    }

    /**
     * 把本类别渲染成可插入 SYSTEM 消息的文本（每条 `- 标签: 值`）。没有内容时返回空串。
     * <p>供 HuhuTalk 自己的手机聊天路径使用（TLM 那条路径不需要，它会自动带上）。
     */
    public static String render(EntityMaid maid) {
        if (maid == null) {
            return "";
        }
        List<String> lines = GameContextRegister.getContext(CATEGORY, maid);
        return lines == null || lines.isEmpty() ? "" : String.join("\n", lines);
    }

    /**
     * 「手机档案」条目：她能检索/核对/引用本轮主人与自己的手机会话记录。
     */
    private static final class PhoneMemoryContext extends AbstractMaidContext {
        private PhoneMemoryContext() {
            super("phone_memory", "手机档案引用");
        }

        @Override
        public String getValue(EntityMaid maid) {
            if (maid == null) {
                return "";
            }
            return "你和当前主人通过手机聊天的已归档内容可以被检索和核对：用短关键词搜索原话，"
                    + "也可以按物品名/物品 ID 或图片、物品关键词回忆真实的物品与媒体事件；"
                    + "物品和媒体记录可能归档在 tool/system 角色下，查它们时用 role=all。"
                    + "按 message_id 读取原文，只有确认后设置 quote=true 才能把一条真实消息作为引用"
                    + "附在当前回复上。不要把你自己过去的回答或当时的图片观察当成主人的原话；"
                    + "普通 tool/system 文字仍不能当作主人或你说过的话，只有真实的成功物品发送事件"
                    + "可以按实际方向引用。搜索没有命中只说明归档里没有匹配记录，不代表事情没发生过。";
        }
    }

    /**
     * 「远程支援」条目：她能做什么、哪部分是自动的。
     */
    private static final class RemoteSupportContext extends AbstractMaidContext {
        private RemoteSupportContext() {
            super("remote_support", "远程支援");
        }

        @Override
        public String getValue(EntityMaid maid) {
            if (maid == null) {
                return "";
            }
            return "你和主人靠手机联系：他走到哪儿都能给你发消息，你也能把背包或隙间绑箱里的东西递给他"
                    + "（他在聊天里点一下气泡就能收下）。他出事倒下时，你身上的治疗药水会自动用在他身上"
                    + "——这部分不用你操心。";
        }
    }
}
