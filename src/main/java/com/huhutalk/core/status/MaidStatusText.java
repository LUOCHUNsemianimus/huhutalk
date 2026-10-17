package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.huhutalk.common.SystemText;
import net.minecraft.world.entity.schedule.Activity;

/**
 * 根据女仆当前日程/工作模式生成可爱的状态文案（供手机"状态小字"显示）。
 * <p>仅用于展示，不改动任何实际状态。
 * <p>返回值是 {@link SystemText} 的可翻译表示（稳定 {@code status.huhutalk.*} 键），
 * 与语言无关，便于比较与低频推送；显示端由 {@code MaidStatusCache}/{@code PhoneText.system} 解析。
 */
public final class MaidStatusText {

    private MaidStatusText() {
    }

    public static String compute(EntityMaid maid) {
        // 睡眠/休息：日程活动为 REST
        Activity activity = maid.getScheduleDetail();
        if (activity == Activity.REST) {
            return SystemText.of("status.huhutalk.sleeping");
        }

        IMaidTask task = maid.getTask();
        if (task == null) {
            return SystemText.of("status.huhutalk.idle");
        }
        String path = task.getUid().getPath();
        return switch (path) {
            case "farm", "sugarcane", "melon", "cocoa", "honey" -> SystemText.of("status.huhutalk.farming");
            case "grass" -> SystemText.of("status.huhutalk.mowing");
            case "snow" -> SystemText.of("status.huhutalk.snow");
            case "attack", "bow_attack", "crossbow_attack", "danmaku_attack", "trident_attack" -> SystemText.of("status.huhutalk.fighting");
            case "fish", "fishing" -> SystemText.of("status.huhutalk.fishing");
            case "milk" -> SystemText.of("status.huhutalk.milking");
            case "shears" -> SystemText.of("status.huhutalk.shearing");
            case "torch" -> SystemText.of("status.huhutalk.torching");
            case "feed_owner", "feed_animal" -> SystemText.of("status.huhutalk.feeding");
            case "extinguishing" -> SystemText.of("status.huhutalk.extinguishing");
            case "board_games" -> SystemText.of("status.huhutalk.games");
            case "idle" -> SystemText.of("status.huhutalk.idle");
            default -> SystemText.of("status.huhutalk.working");
        };
    }
}
