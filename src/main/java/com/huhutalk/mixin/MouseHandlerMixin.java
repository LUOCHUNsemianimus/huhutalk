package com.huhutalk.mixin;

import com.huhutalk.event.ClientModEvents;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入分流：手机是<b>旁路</b>，不是接管。
 *
 * <h2>两条通路</h2>
 * <ol>
 *   <li><b>旧通路（Alt 释放鼠标）</b>：{@link ClientModEvents#RELEASE_CURSOR} 按住时系统光标可见、
 *       手机按<b>屏幕坐标</b>做命中判定。为保持该通路可用，此模式下锁视角（cancel turnPlayer）。</li>
 *   <li><b>新通路（独立光标）</b>：鼠标保持抓取、<b>视角照常转动</b>，
 *       同一份鼠标增量额外喂给手机独立光标；只有玩家绑定的「手机侧键」才被手机接收，
 *       鼠标左右键的原版行为（攻击/放置）完全不受影响。</li>
 * </ol>
 * 两条通路天然互斥：旧通路下鼠标已释放，{@code turnPlayer} 根本不会被调用。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    /**
     * 鼠标移动：手机独立光标与视角同时被驱动。
     * <p>
     * 累积量 {@code accumulatedDX/DY} 由调用方 {@code handleAccumulatedMovement}
     * 在 {@code turnPlayer} <b>返回之后</b>才清零，所以这里读到的是本帧全部增量；
     * 即使取消调用也不会漏清零。
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(double movementTime, CallbackInfo ci) {
        if (PhoneOverlay.shouldCaptureMouse()) {
            // 旧通路：系统光标在点屏幕，必须锁视角，否则命中判定会飘
            ci.cancel();
            return;
        }
        PhoneOverlay.onMouseDelta(this.accumulatedDX, this.accumulatedDY);
    }

    /**
     * 鼠标按键。
     * <ul>
     *   <li>旧通路：把屏幕坐标转发给手机并吞掉事件（原有行为）；</li>
     *   <li>新通路：只有手机侧键额外转发给手机，<b>不 cancel</b>。</li>
     * </ul>
     */
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onMousePress(long window, int button, int action, int mods, CallbackInfo ci) {
        if (PhoneOverlay.shouldCaptureMouse()) {
            double guiX = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
            double guiY = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
            PhoneOverlay.handleMouseClicked(guiX, guiY, button, action);
            ci.cancel();
            return;
        }
        if (ClientModEvents.isPhoneClickButton(button)) {
            PhoneOverlay.onPhoneSideButton(true, action);
        } else if (ClientModEvents.isPhoneBackButton(button)) {
            PhoneOverlay.onPhoneSideButton(false, action);
        }
    }

    /** 滚轮：仅旧通路转发给手机；新通路下滚轮仍然切快捷栏（滚轮已按设计让出给手机之外的原生行为） */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double scrollDeltaX, double scrollDeltaY, CallbackInfo ci) {
        if (!PhoneOverlay.shouldCaptureMouse()) return;

        double guiX = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double guiY = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();

        PhoneOverlay.handleMouseScroll(guiX, guiY, scrollDeltaY);
        ci.cancel();
    }
}
