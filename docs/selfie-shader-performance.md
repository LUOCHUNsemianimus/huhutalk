# 自拍光影渲染交接说明

## 当前实现

- **无 Iris / 关闭光影**：沿用原 400x600 独立离屏靶，直接渲染自拍。
- **启用 Iris 光影且窗口足够大**：自拍在 `RenderFrame.Pre` 阶段借用真实主渲染靶（不替换其身份、不 resize），
  完整世界渲染后立即把 2:3 中央区域 LINEAR 缩放到 400x600 输出靶；同一帧稍后正常主场景重新清屏覆盖。
- **极小窗口回退**：只有 `mainRenderTarget` 宽 ≥ `IMAGE_WIDTH`(400) 且高 ≥ `IMAGE_HEIGHT`(600) 时才走
  裁切光影路径；任一低于该像素数就不做裁切放大，回退原 400x600 独立渲染，
  避免把低于照片像素数的画面放大。这是保画质回退，不是全场景性能保证。

保持不变：照片/预览固定 400x600、70° 竖屏 FOV、镜像预览、正常方向保存、拍照/重拍流程、30fps 节流、
光影档位/阴影/玩家设置。

## 已知开销与边界

- 光影路径仍会完整重渲一次主场景，开销存在；它优化的是避免 Iris 缓冲尺寸在 400x600 与窗口尺寸间反复切换。
- 自拍 pass 与 Iris 共享帧内历史/缓冲，尚非完全隔离；不能据此声称对任意 Iris 版本或任意光影包全面兼容。
- 只在现有 `RenderFrame.Pre → SelfieCameraTarget.update` 前置帧序调用安全；不能在 HUD 等主场景已绘制后调用。

## 光影下的后摄抓图（2026-10-17 追加）

Iris 的帧序会改变「手还没画」这个原版前提：

- `MixinLevelRenderer.iris$beginTranslucents` 在 `LevelRenderer.renderLevel` 的 "translucent" 处就调用
  `HandRenderer.renderSolid(...)`，**第一人称手 / 手机（`RenderHandEvent`）在世界 pass 内就被画进 colortex**；
- `renderLevel` RETURN 处 `finalizeLevelRendering()` → `FinalPassRenderer.renderFinalPass()`
  直接把最终合成写进 `mainRenderTarget` 的颜色纹理，随后 NeoForge 才派发 `AFTER_LEVEL`；
- 原版 `GameRenderer.renderLevel` 之后那次 `renderItemInHand` 已被 `MixinGameRenderer`
  `iris$disableVanillaHandRendering` 取消。

因此后摄抓图不能只依赖 `AFTER_LEVEL` 的时机：现由 `PhoneOverlay.beginWorldCaptureFrame()`
在拍照帧（`RenderFrameEvent.Pre`）临时 `setRenderHand(false)`；本帧 `AFTER_LEVEL` 的
`consumeWorldCapture()` 在 `try` 内调用 `PhotoLibrary.captureMainTargetPng()`，并在 `finally`
中恢复 `renderHand`。切世界/退出清场时（`resetForNewWorld`）会作废在途的 `grabPending`
并恢复，未兑现的抓图请求和关手状态都不会留到下一个世界。
HUD 不在这条通路里（`Gui` 在 `renderLevel` 返回后才画），不要为此改用 `options.hideGui`。

## 实测记录（2026-10-17）

环境：RTX 4060 Laptop，窗口 2560x1600，GUI Scale 4，FOV 70，渲染距离 12，关闭 VSync，帧率上限 260；
Iris 1.8.14-beta.1 + Sodium 0.8.13 + Complementary Reimagined r5.9.3 HIGH；和平生存、独立平坦世界。

| 场景 | 结果 |
| --- | --- |
| 同进程普通（无自拍） | 151.848 FPS |
| 旧路径 | 15.683 FPS |
| 新路径 | 114.510 / 116.386 FPS |
| 关闭光影再开启 | 116.699 FPS |
| 15s 内 Iris 缓冲尺寸变化 | 旧路径 470 次，新路径 0 次 |

注意：该测试场景不是用户原场景的 70~80 FPS 条件，不能据此外推绝对 FPS。

## 已检查 / 未检查

上游已检查：窗口 960x720、480x1000，拍照确认/重拍，光影开关切换。

仍待上游最终检查：极小窗口回退路径、无 Iris 启动与回归。
