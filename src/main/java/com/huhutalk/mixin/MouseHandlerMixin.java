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
    @Shadow private double xpos, ypos;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(CallbackInfo ci) {
        if (PhoneOverlay.shouldCaptureMouse()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void onPress(long window, int button, int action, int mods, CallbackInfo ci) {
        if (!PhoneOverlay.shouldCaptureMouse()) return;
        double guiX = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double guiY = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
        if (PhoneOverlay.handleMouseClicked(guiX, guiY, button, action)) {
            ci.cancel();
        } else {
            ci.cancel(); // 即使手机未消费，也阻止游戏响应（防止误触）
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long window, double scrollDeltaX, double scrollDeltaY, CallbackInfo ci) {
        if (!PhoneOverlay.shouldCaptureMouse()) return;
        double guiX = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double guiY = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
        if (PhoneOverlay.handleMouseScroll(guiX, guiY, scrollDeltaY)) {
            ci.cancel();
        } else {
            ci.cancel();
        }
    }
}