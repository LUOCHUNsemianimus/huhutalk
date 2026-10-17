package com.huhutalk.client;

import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.client.settings.HuhuTalkClientSettings;

/**
 * 女仆分组工具（客户端）。
 * <p>
 * 分组有两种来源：
 * <ul>
 *   <li><b>默认分组</b>：由女仆实时状态（{@link MaidStatus}）决定——在线/离线/已关机/死亡。</li>
 *   <li><b>手动分组</b>：玩家在信息页手动调整，存客户端 {@link HuhuTalkClientSettings}，
 *       覆盖默认状态分组（兼容状态不准的 bug 场景）。</li>
 * </ul>
 */
public final class MaidGroupHelper {

    /** 分组名称常量 */
    public static final String GROUP_ONLINE = "在线";
    public static final String GROUP_OFFLINE = "离线";
    public static final String GROUP_SHUTDOWN = "已关机";
    public static final String GROUP_DEAD = "死亡";

    private MaidGroupHelper() {
    }

    /**
     * 计算某女仆应归属的分组名。
     * 优先读手动分组；未手动设置则按状态默认分组。
     */
    public static String resolveGroup(String stableId, MaidStatus status) {
        String manual = HuhuTalkClientSettings.getMaidGroup(stableId);
        if (manual != null && !manual.isBlank()) {
            return manual;
        }
        return defaultGroup(status);
    }

    /** 按状态返回默认分组名 */
    public static String defaultGroup(MaidStatus status) {
        if (status == null) {
            return GROUP_OFFLINE;
        }
        return switch (status) {
            case ONLINE -> GROUP_ONLINE;
            case DEAD -> GROUP_DEAD;
            case SHUTDOWN -> GROUP_SHUTDOWN;
            // 魂符内：暂归入"离线"（她确实联系不上）。
            // 若需求方要独立分组，需要同时加 GROUP_* 常量 + 设置页的默认分组名白名单。
            default -> GROUP_OFFLINE;
        };
    }

    /** 该女仆是否有手动分组 */
    public static boolean hasManualGroup(String stableId) {
        String g = HuhuTalkClientSettings.getMaidGroup(stableId);
        return g != null && !g.isBlank();
    }

    /**
     * 分组名的<b>显示</b>文本：只解析四个内置分组标识（在线/离线/已关机/死亡），
     * 玩家自定义分组名原样返回。
     * <p>
     * ⚠ {@link #GROUP_ONLINE} 等常量是持久化与比较用的<b>稳定标识</b>，
     * 绝不能把本方法的翻译结果拿去比较、排序或落盘；渲染分组标题/分组选项时调用它即可。
     */
    public static String displayGroup(String stableGroup) {
        if (stableGroup == null) {
            return "";
        }
        return switch (stableGroup) {
            case GROUP_ONLINE -> PhoneText.tr("gui.huhutalk.group.online");
            case GROUP_OFFLINE -> PhoneText.tr("gui.huhutalk.group.offline");
            case GROUP_SHUTDOWN -> PhoneText.tr("gui.huhutalk.group.shutdown");
            case GROUP_DEAD -> PhoneText.tr("gui.huhutalk.group.dead");
            default -> stableGroup;
        };
    }

    /** 分组名的排序权重（用于固定显示顺序：在线/离线/已关机/死亡/自定义） */
    public static int groupSortIndex(String group) {
        if (GROUP_ONLINE.equals(group)) return 0;
        if (GROUP_OFFLINE.equals(group)) return 1;
        if (GROUP_SHUTDOWN.equals(group)) return 2;
        if (GROUP_DEAD.equals(group)) return 3;
        return 4; // 自定义分组排最后
    }
}
