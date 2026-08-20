package com.huhutalk.core.status;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.schedule.Activity;

/**
 * 根据女仆当前日程/工作模式生成可爱的状态文案（供手机"状态小字"显示）。
 * <p>仅用于展示，不改动任何实际状态。
 */
public final class MaidStatusText {

    private MaidStatusText() {
    }

    public static String compute(EntityMaid maid) {
        // 睡眠/休息：日程活动为 REST
        Activity activity = maid.getScheduleDetail();
        if (activity == Activity.REST) {
            return "我睡着啦…";
        }

        IMaidTask task = maid.getTask();
        if (task == null) {
            return "待命中…";
        }
        String path = task.getUid().getPath();
        return switch (path) {
            case "farm", "sugarcane", "melon", "cocoa", "honey" -> "种地中…";
            case "grass" -> "割草中…";
            case "snow" -> "扫雪中…";
            case "attack", "bow_attack", "crossbow_attack", "danmaku_attack", "trident_attack" -> "战斗中…";
            case "fish", "fishing" -> "钓鱼中…";
            case "milk" -> "挤奶中…";
            case "shears" -> "剪毛中…";
            case "torch" -> "插火把中…";
            case "feed_owner", "feed_animal" -> "喂食中…";
            case "extinguishing" -> "灭火中…";
            case "board_games" -> "玩游戏呢~";
            case "idle" -> "待命中…";
            default -> "工作中…";
        };
    }
}
