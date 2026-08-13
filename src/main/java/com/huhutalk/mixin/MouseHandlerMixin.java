package com.huhutalk.mixin;

import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private double xpos;
    @Shadow private double ypos;

    /** 双保险：Alt 按住时锁定视角 */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(CallbackInfo ci) {
        if (PhoneOverlay.shouldCaptureMouse()) {
            ci.cancel();
        }
    }

    /**
     * 拦截鼠标按键（方法名是 onPress，不是 press）。
     * 注意：onPress 只有在鼠标被抓取时才会被原版调用，但我们的 Mixin
     * 注入在方法入口，因此只要 shouldCaptureMouse 为真就会先执行。
     * 由于我们通过 releaseMouse() 释放了鼠标，原版不会主动调用 onPress，
     * 但我们仍需要这个注入来在“未抓取但 Alt 按住”的情况下接收点击。
     * 实际上当鼠标未抓取时 onPress 不会被调用，所以这里的拦截主要为了
     * 应对边界情况（例如 grabMouse 与 releaseMouse 切换瞬间）。
     */
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onMousePress(long window, int button, int action, int mods, CallbackInfo ci) {
        if (!PhoneOverlay.shouldCaptureMouse()) return;

        double guiX = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double guiY = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();

        PhoneOverlay.handleMouseClicked(guiX, guiY, button, action);
        ci.cancel();
    }

    /** 拦截滚轮（onScroll 名称未变） */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double scrollDeltaX, double scrollDeltaY, CallbackInfo ci) {
        if (!PhoneOverlay.shouldCaptureMouse()) return;

        double guiX = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double guiY = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();

        PhoneOverlay.handleMouseScroll(guiX, guiY, scrollDeltaY);
        ci.cancel();
    }
}