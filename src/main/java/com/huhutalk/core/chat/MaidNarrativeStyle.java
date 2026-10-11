package com.huhutalk.core.chat;

/**
 * 女仆「主动冒泡」文案的公共风格约束。
 *
 * <h2>为什么抽出来</h2>
 * 「药水复活」（{@link com.huhutalk.core.rescue.RescueNarrative}）与「远程补给」
 * （{@code com.huhutalk.core.supply.RemoteSupplyNarrative}）都会让女仆主动开口。
 * 两条链路共用同一套语气与长度约束，是为了将来做**消息协调 / 合并**时不出现两种腔调
 * （交接文档「任务 1 与任务 2 的兼容」一节）。
 *
 * <h2>口径（需求方定稿）</h2>
 * 轻松、游戏化、不煽情；<b>不写</b>"担忧/悲伤"这类过重的词，也<b>不写</b>"不用端着/不用瞒着"
 * 之类"要坦率"的要求 —— 那会覆盖玩家给女仆设的「口是心非」「傲娇」人设。
 * 每条注入都带一句长度约束：主人多半正在交战，消息刷屏是干扰。
 */
public final class MaidNarrativeStyle {

    /** 语气引导：只给"按自己性子来"和几种可能的方向，避开"担忧/悲伤"这类过重的词。 */
    public static final String TONE_GUIDE =
            "说点什么吧：按你自己的性子来，轻松点——打游戏磕磕碰碰很正常，不用搞得像生离死别。"
                    + "给他打打气，说一句你还会帮他，顺带关心他一句也行。";

    /** 长度约束：主人多半正在交战/跑图，消息刷屏是干扰。 */
    public static final String BREVITY_LINE =
            "话要短：一两句就够（主人多半正忙着打架，消息刷屏反而碍事）。";

    private MaidNarrativeStyle() {
    }
}
