package com.huhutalk.core.rescue;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 死亡现场的"环境预判"（服务端，纯判定无副作用）。
 * <p>
 * 决定两件事：
 * <ol>
 *   <li><b>值不值得救</b> —— 虚空死亡（{@link #isBeyondRescue}）直接放弃：
 *       把人送回虚空只会立刻再死一次，白白消耗女仆的药水。</li>
 *   <li><b>要不要多带一瓶抗火</b> —— 岩浆/火焰系死亡（{@link #needsFireResistance}）
 *       复活回原坐标会被接着烧，治疗药水等于白喝。</li>
 * </ol>
 * 判据用<b>伤害类型的 msgId 字符串</b>而不是 {@code DamageTypes} 常量引用：
 * 原版这些 id 是稳定的，而引用比较在部分模组包装过伤害源时容易漏判。
 * <p>另外还负责把"主人到底是怎么死的"翻译成一句人话（{@link #describeDeath}），
 * 供女仆叙事使用。
 */
public final class RescueEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger("huhutalk.rescue_environment");

    /** 虚空系伤害类型（原版 id） */
    private static final Set<String> VOID_DAMAGE_IDS = Set.of(
            "out_of_world",
            "fell_out_of_world"
    );

    /** 火焰/高温系伤害类型（原版 id） */
    private static final Set<String> FIRE_DAMAGE_IDS = Set.of(
            "lava",
            "on_fire",
            "in_fire",
            "hot_floor"
    );

    /** 虚空判定的 Y 轴余量：低于世界最低高度这么多格即视为回不来 */
    private static final int VOID_Y_MARGIN = 64;

    private RescueEnvironment() {
    }

    /**
     * 这次死亡是否"超出救援范围"（虚空）。
     * <p>判据：伤害类型是虚空系 <b>或</b> 死亡点 Y 已经在世界底部之下足够深。
     * 两者取或 —— 有些位移类模组不会走原版虚空伤害类型，只靠伤害类型会漏判。
     */
    public static boolean isBeyondRescue(ServerPlayer player, DamageSource source) {
        if (player == null) {
            return true;
        }
        if (source != null && VOID_DAMAGE_IDS.contains(source.getMsgId())) {
            return true;
        }
        return player.getY() < player.serverLevel().getMinBuildHeight() - VOID_Y_MARGIN;
    }

    /**
     * 取「服务器原始死亡提示」文案 —— 就是原版聊天栏里那句，例如「咲夜试图在岩浆里游泳」。
     *
     * <p><b>为什么用它而不是自己维护死法清单</b>：原版这句是伤害类型 × 场景 × 凶手的完整组合，
     * 天然覆盖所有死法（含模组自定义伤害类型），并且自带主人的名字，女仆一听就知道发生了什么事。
     * 我们只需要把它原样转达，不需要（也不该）枚举"岩浆/虚空/摔死…"。
     *
     * <p><b>已知退化点</b>：专用服务器上不加载客户端语言文件，
     * {@code Component#getString()} 可能返回未翻译的键名（形如 {@code death.attack.lava}）。
     * 检测到该情况就退回一句"够用但不漂亮"的描述并打日志；真正的本地化留待与模组翻译工作一起做。
     * 单机 / 局域网（集成服务器与客户端同 JVM）不受影响，拿到的就是中文原句。
     */
    public static String describeDeath(ServerPlayer player, DamageSource source) {
        if (player == null || source == null) {
            return "";
        }
        try {
            String localized = source.getLocalizedDeathMessage(player).getString();
            if (localized != null && !localized.isBlank() && !isUntranslated(localized)) {
                return localized;
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        String msgId = source.getMsgId();
        LOGGER.warn("[HuhuTalk][救援] 死亡提示未本地化（msgId={}），改用兜底描述。"
                + "专用服务器属预期情况，单机若出现请反馈。", msgId);
        return "因「" + msgId + "」而死";
    }

    /** 未翻译时 {@code Component#getString()} 会直接返回翻译键，原版死亡键一律以 {@code death.} 开头。 */
    private static boolean isUntranslated(String text) {
        return text.startsWith("death.");
    }

    /**
     * 复活回原坐标是否需要抗火。
     * <p>判据：伤害类型是火焰/高温系 <b>或</b> 死亡瞬间身处岩浆
     * （实体在岩浆里，或脚下方块就是岩浆）—— 两者取或。
     */
    public static boolean needsFireResistance(ServerPlayer player, DamageSource source) {
        if (player == null) {
            return false;
        }
        if (source != null && FIRE_DAMAGE_IDS.contains(source.getMsgId())) {
            return true;
        }
        if (player.isInLava()) {
            return true;
        }
        BlockPos pos = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        BlockState state = player.serverLevel().getBlockState(pos);
        return state.getFluidState().is(FluidTags.LAVA);
    }
}
