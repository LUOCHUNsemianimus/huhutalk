package com.huhutalk.mixin;

import com.huhutalk.event.ClientModEvents;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入分流：手机是<b>旁路</b>，不是接管。
 *
 * <h2>唯一通路：独立光标（旧 Alt 通路已于期 1 拆除）</h2>
 * 鼠标保持抓取、<b>视角照常转动</b>，同一份鼠标增量额外喂给手机独立光标；
 * 只有玩家绑定的「手机侧键」才被手机接收，鼠标左右键的原版行为（攻击/放置）完全不受影响。
 * <p>
 * 被拆掉的旧通路是「按住 Alt 释放系统鼠标、用真实光标点手机」那一套。它有两个致命代价：
 * <ul>
 *   <li>必须 {@code grabMouse/releaseMouse}，于是真实光标与手机光标两套坐标系并存；</li>
 *   <li>释放鼠标期间 {@code turnPlayer} 根本不被调用，所以旧通路<b>天生无法与"视角照常转"共存</b>，
 *       一旦屏幕上出现透明输入屏（{@code setScreen} 必然释放鼠标）就会和输入屏抢鼠标。</li>
 * </ul>
 * 独立光标把这两件事一起解决了：它活在 Overlay 自己的坐标系里，因此模型屏与悬浮窗零额外适配。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    /**
     * 鼠标移动：手机独立光标与视角同时被驱动。
     * <p>
     * 累积量 {@code accumulatedDX/DY} 由调用方 {@code handleAccumulatedMovement}
     * 在 {@code turnPlayer} <b>返回之后</b>才清零，所以这里读到的是本帧全部增量；
     * 即使取消调用也不会漏清零。
     * <p>
     * 1.20.1 的 {@code turnPlayer} 每帧都会被调用，抓取/窗口激活判断在其内部；
     * 所以本方法在 HEAD 显式检查 {@code isMouseGrabbed()} 与窗口激活状态。
     * 开着 Screen（含透明输入屏）或失焦时独立光标随之冻结，这正是想要的：
     * 此刻玩家真实的鼠标已经交还给 GUI 了。
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void onTurnPlayer(CallbackInfo ci) {
        // 1.20.1 把 grabbed / windowActive 判断放在 turnPlayer 内部，HEAD 尚未经过它：
        // 这里显式补同样的门控，保证开 Screen、失焦或未抓取鼠标时不会继续推独立光标。
        if (((MouseHandler) (Object) this).isMouseGrabbed() && Minecraft.getInstance().isWindowActive()) {
            PhoneOverlay.onMouseDelta(this.accumulatedDX, this.accumulatedDY);
        }
    }

    /**
     * 只拦截 {@code turnPlayer} 内第一个 {@code OptionInstance.get()}（即 sensitivity），
     * 不碰随后的 {@code invertYMouse().get()}，也不写回全局 Options。
     */
    @Redirect(
            method = "turnPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;",
                    ordinal = 0))
    private Object huhutalk$adjustSensitivity(OptionInstance<?> option) {
        Object value = option.get();
        if (value instanceof Double sensitivity) {
            return ClientModEvents.adjustMouseSensitivity(sensitivity);
        }
        return value;
    }

    /**
     * 鼠标按键：只有玩家绑定的手机侧键额外转发给手机；<b>前摄取景</b>时再额外把
     * 本机<b>普通右键</b>（button=1）当快门并取消原版行为。<b>其余情况一律不 cancel</b>，
     * 因此左右键的攻击/放置、滚轮切快捷栏全部保持原版。
     * <p>
     * 刻意的分工：{@code PhoneOverlay.onMouseDelta} 负责"光标动"，这里负责"点下去"，
     * 两者都只在 {@link PhoneOverlay#isScreenControl()} 成立时才真正生效（内部各自把关）。
     * <p>
     * ⚠ 普通右键（button=1）与手机侧键 5（默认鼠标侧键 5）是两个按钮，不能混为一谈：
     * 后者是玩家的"手机返回"，永远走 {@link PhoneOverlay#onPhoneSideButton}，本方法不碰它。
     */
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onMousePress(long window, int button, int action, int mods, CallbackInfo ci) {
        boolean boundToPhoneKey =
                ClientModEvents.isPhoneClickButton(button) || ClientModEvents.isPhoneBackButton(button);
        // 前摄取景：普通右键 = 快门（自带按下锁存，保证确认页出现后的松开也不漏给原版）
        boolean selfieHandled = PhoneOverlay.handleSelfieRightButton(window, button, action, boundToPhoneKey);
        if (ClientModEvents.isPhoneClickButton(button)) {
            PhoneOverlay.onPhoneSideButton(true, action);
        } else if (ClientModEvents.isPhoneBackButton(button)) {
            PhoneOverlay.onPhoneSideButton(false, action);
        }
        if (selfieHandled) {
            ci.cancel();
        }
    }
}
