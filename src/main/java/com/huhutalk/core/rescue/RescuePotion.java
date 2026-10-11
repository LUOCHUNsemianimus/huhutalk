package com.huhutalk.core.rescue;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「治疗系 / 抗火系」药水的识别与结算（纯函数，无副作用）。
 *
 * <h2>回血量口径（与「取回血量最高的那瓶」共用同一套算式）</h2>
 * <ul>
 *   <li><b>瞬间治疗 heal</b>：原版每次生效回 4 点 → {@code 4 << amplifier}（I=4 点，II=8 点）。</li>
 *   <li><b>再生 regeneration</b>：原版每 {@code 50 >> amplifier} tick 回 1 点 →
 *       总回血 = {@code duration / (50 >> amplifier)}。这样"再生 II 45 秒"就比"再生 I 45 秒"更值钱，
 *       挑药水时不会因为只认瞬间治疗而错选。</li>
 * </ul>
 * 两者相加 = 这瓶药水理论上能回多少血，也就是挑选与展示的数字。
 *
 * <h2>效果为什么分两处给</h2>
 * 复活时我们<b>直接 {@code setHealth(回血量)}</b>（确定性、可预期），
 * 再把药水的<b>非瞬间</b>效果（再生、抗火…）补挂上去。
 * 瞬间效果（heal/harm）必须剔除：否则 {@code addEffect} 会立刻再回一次血，变成双倍。
 */
public final class RescuePotion {

    /** 使用方式：决定"怎么用"的语义与客户端表现 */
    public enum Usage {
        /** 饮用型：直接消耗 */
        DRINK,
        /** 喷溅型：砸在玩家坐标上 */
        SPLASH,
        /** 滞留型：砸在玩家坐标上 */
        LINGERING
    }

    private RescuePotion() {
    }

    // ==================== 判定 ====================

    /** 是否是一瓶"药水"（带 POTION_CONTENTS 组件） */
    public static boolean isPotion(@Nullable ItemStack stack) {
        return contentsOf(stack) != null;
    }

    /** 是否治疗系：理论上能回血 > 0（含瞬间治疗或再生） */
    public static boolean isHealing(@Nullable ItemStack stack) {
        return healAmount(stack) > 0;
    }

    /** 是否抗火系：效果里含防火 */
    public static boolean isFireResistance(@Nullable ItemStack stack) {
        PotionContents contents = contentsOf(stack);
        if (contents == null) {
            return false;
        }
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().is(MobEffects.FIRE_RESISTANCE)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 结算 ====================

    /** 这瓶药水理论上能回的血量（点）。非治疗药水返回 0。 */
    public static int healAmount(@Nullable ItemStack stack) {
        PotionContents contents = contentsOf(stack);
        if (contents == null) {
            return 0;
        }
        int total = 0;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().is(MobEffects.HEAL)) {
                // 瞬间治疗：4 点 × 2^amplifier（原版语义）
                total += 4 << clamp(effect.getAmplifier(), 0, 10);
            } else if (effect.getEffect().is(MobEffects.REGENERATION)) {
                // 再生：每 (50 >> amplifier) tick 回 1 点
                int interval = 50 >> clamp(effect.getAmplifier(), 0, 5);
                total += Math.max(1, effect.getDuration() / Math.max(1, interval));
            }
        }
        return total;
    }

    /**
     * 只算<b>瞬间治疗</b>（HEAL）的那部分点数，<b>不含再生</b>。
     * <p>给「远程补给」用：它把药水喂给一个<b>还活着</b>的玩家，会把非瞬间效果（再生…）
     * 按原版正常挂上去，让游戏自己结算 —— 所以直接加血时只能加瞬间那部分，
     * 否则再生会被算两次（救援那边是 {@code setHealth} 一次性结算，口径不同，别混用）。
     */
    public static int instantHealAmount(@Nullable ItemStack stack) {
        PotionContents contents = contentsOf(stack);
        if (contents == null) {
            return 0;
        }
        int total = 0;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().is(MobEffects.HEAL)) {
                total += 4 << clamp(effect.getAmplifier(), 0, 10);
            }
        }
        return total;
    }

    /** 使用方式（饮用 / 喷溅 / 滞留）。 */
    public static Usage usageOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Usage.DRINK;
        }
        if (stack.getItem() instanceof SplashPotionItem) {
            return Usage.SPLASH;
        }
        if (stack.getItem() instanceof LingeringPotionItem) {
            return Usage.LINGERING;
        }
        return Usage.DRINK;
    }

    /**
     * 使用后要补挂到玩家身上的效果实例（<b>已剔除瞬间效果</b>，见类注释）。
     * 返回的是副本，调用方随意修改不会污染原物品。
     */
    public static List<MobEffectInstance> lastingEffects(@Nullable ItemStack stack) {
        List<MobEffectInstance> out = new ArrayList<>();
        PotionContents contents = contentsOf(stack);
        if (contents == null) {
            return out;
        }
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getEffect().value().isInstantenous()) {
                continue;
            }
            out.add(new MobEffectInstance(effect));
        }
        return out;
    }

    /** 药水显示名（用于气泡文案与叙事注入）。 */
    public static String displayName(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    /** 药水液色（用于在死亡坐标喷药水粒子）。非药水返回 -1。 */
    public static int colorOf(@Nullable ItemStack stack) {
        PotionContents contents = contentsOf(stack);
        return contents == null ? -1 : contents.getColor();
    }

    // ==================== 内部 ====================

    @Nullable
    private static PotionContents contentsOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.get(DataComponents.POTION_CONTENTS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
