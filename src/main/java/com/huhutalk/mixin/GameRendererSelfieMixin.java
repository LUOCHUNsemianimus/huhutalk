package com.huhutalk.mixin;

import com.huhutalk.client.render.SelfieCameraTarget;
import com.huhutalk.client.render.SelfieRenderAccess;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自拍 pass 的投影矩阵覆盖 + {@code GameRenderer} 私有开关读写。
 *
 * <h2>为什么必须覆盖投影</h2>
 * 原版 {@link GameRenderer#getProjectionMatrix(double)} 固定用
 * {@code window.getWidth() / window.getHeight()} 作 aspect，也就是<b>窗口横屏比例</b>。
 * 自拍离屏靶是竖屏（{@link SelfieCameraTarget#IMAGE_WIDTH}×{@link SelfieCameraTarget#IMAGE_HEIGHT}），
 * 若沿用窗口 aspect，照片里的人物会被横向压扁 —— 取景构图与手机屏不一致。
 *
 * <p>方法内部只有一行 {@code matrix4f.perspective(...)} 值得改写，其余（zoom 位移/缩放）
 * 自拍不需要，所以直接在 {@code RETURN} 处整体换成本类算出的竖屏透视矩阵。
 * 未处于自拍 pass 时（{@link SelfieCameraTarget#isRendering()} 为 false）原样放行，绝不影响正常帧。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSelfieMixin implements SelfieRenderAccess.GameRendererAccess {

    @Override
    @Accessor("renderHand")
    public abstract boolean huhutalk$getRenderHand();

    @Override
    @Accessor("renderBlockOutline")
    public abstract boolean huhutalk$getRenderBlockOutline();

    @Override
    @Accessor("renderBlockOutline")
    public abstract void huhutalk$setRenderBlockOutline(boolean value);

    @Inject(method = "getProjectionMatrix(D)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
    private void huhutalk$selfieProjection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (SelfieCameraTarget.isRendering()) {
            GameRenderer self = (GameRenderer) (Object) this;
            cir.setReturnValue(SelfieCameraTarget.buildProjection((float) fov, self.getDepthFar()));
        }
    }
}
