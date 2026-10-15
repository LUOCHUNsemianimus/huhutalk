package com.huhutalk.mixin;

import com.huhutalk.client.render.SelfieCameraTarget;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 自拍 pass 把相机强制成「第三人称正面」（等价原版 {@code CameraType.THIRD_PERSON_FRONT}）。
 *
 * <h2>为什么改这里而不是改 cameraType 选项</h2>
 * {@code GameRenderer.renderLevel} 在调用 {@code camera.setup(level, entity, detached, mirrored, tick)}
 * 时，两个布尔值直接取自 {@code options.getCameraType().isFirstPerson()} 与 {@code isMirrored()}。
 * 直接改选项会写盘 / 触发回调，有持久化风险；这里只在这两个实参上进方法级替换：
 * <ul>
 *   <li>{@code detached = true} —— 相机会沿视线反向退到玩家身前（可见本地玩家模型 + 原版碰撞裁剪）；</li>
 *   <li>{@code thirdPersonReverse = true} —— 视角翻 180°，相机朝玩家看。</li>
 * </ul>
 * 退出的距离由 {@code CalculateDetachedCameraDistanceEvent} 在 {@link SelfieCameraTarget} 里设为约 1.35 格。
 *
 * <p>非自拍 pass 原样返回实参，正常第一/第三人称完全不受影响。
 * 相机不修改玩家实体自身的 {@code xRot/yRot}（{@code setRotation} 只写相机字段）。
 */
@Mixin(Camera.class)
public abstract class CameraSelfieMixin {

    /** {@code setup} 的第 1 个 boolean 实参：detached */
    @ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean huhutalk$selfieDetached(boolean detached) {
        return SelfieCameraTarget.isRendering() || detached;
    }

    /** {@code setup} 的第 2 个 boolean 实参：thirdPersonReverse（正面/镜像） */
    @ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean huhutalk$selfieFlipped(boolean thirdPersonReverse) {
        return SelfieCameraTarget.isRendering() || thirdPersonReverse;
    }
}
