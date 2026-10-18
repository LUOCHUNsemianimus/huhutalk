# 手机模型屏与 Iris 光影兼容（BSL TAA/Bloom 重影）

## 问题

Iris 光影下，第一人称手持渲染发生在 Iris 世界 pass 内部（`RenderHandEvent`）。
手机模型屏原先在 `PhoneScreenTarget.renderScreen` 里直接向世界缓冲提交屏幕四边形，
这张 UI 四边形因此进入 colortex，被光影包的 TAA / Bloom 当作世界内容处理：

- 视角移动时字边出现重影；
- 静止时高亮 UI 出现黄白泛光/过曝。

机身与手臂本身仍应参加世界光影，问题只在屏幕 UI 四边形。

## 机制

1. `PhoneScreenTarget.renderScreen` 在 `PhoneOverlay.hasModelScreen()`、离屏靶有效、
   `IrisShaderCompat.isShaderPackInUse()` 为真且不在自拍 pass 时，**不提交世界几何**，
   只暂存当帧的：
   - `PoseStack.last().pose()`（手持 + 机身局部变换）；
   - `RenderSystem.getProjectionMatrix()`（Iris 手持 `setupGlState` 后的原矩阵，含 Z=0.125 缩放）；
   - `RenderSystem.getModelViewMatrix()`；
   - `RenderSystem.getVertexSorting()`。
   暂存对象静态复用，每帧最多一个四边形。
2. `ClientModEvents` 在 `RenderFrameEvent.Pre` 的自拍早退之后调用 `PhoneScreenTarget.beginFrame()`，
   清掉上一帧未兑现的暂存，避免切页 / 不持机 / F1 隐藏 HUD / 第三人称 / 无 world pass 时旧帧残留。
   离屏靶 resize 重建时同样清 pending。
3. `RenderLevelStageEvent.AFTER_LEVEL`（Iris 已在 `renderLevel` RETURN 处完成 finalize/composite，
   原版 HUD 尚未绘制）中，先 `PhoneOverlay.consumeWorldCapture()` 兑现拍照抓图，
   再调用 `PhoneScreenTarget.renderAfterLevel()`：
   - 校验模型屏、手持、世界玩家与离屏靶仍有效；
   - 绑定真实 `mc.mainRenderTarget`；
   - 用暂存的投影 / 模型视图 / pose 和共用的 `drawScreenQuad` 绘制；
   - 保持深度测试（`GL_LEQUAL`），不画成置顶 HUD；
   - 在 `finally` 中恢复投影、模型视图、视口、深度测试/深度写掩码、背面剔除、
     scissor box/enable、混合与着色器颜色状态。
4. 无 Iris 或关闭光影时仍走原来的即时绘制路径；机身/手路径完全不变。
   不修改光影 ZIP、用户配置，不全局关闭 TAA/Bloom，也不新增 Iris 编译期依赖
   （沿用 `IrisShaderCompat` 的可选反射层）。

## 实现取舍

本方案只让**手机屏幕 UI 四边形**不参加世界后处理，因此屏幕文字/图标保持界面原色，
不会获得世界的 TAA 抗锯齿或 Bloom 泛光；机身与手仍完整参加光影。
这消除了「屏幕 UI 被当世界内容」造成的重影和过曝，但**不承诺**任意光影版本/特效链的兼容，
也不处理世界自身或机身/手本身已有的光影残影——那些不属于本次屏幕修复范围。

## 目标与已验证环境

- Minecraft / NeoForge 1.21.1（开发环境 NeoForge 21.1.243）。
- Iris 1.8.14-beta.1 + Sodium 0.8.13 + BSL 10.1.8 HIGH。
- 分辨率 2560x1600、GUI Scale 4、FOV 70。
- 静态手机界面与 180 帧转视角（前 120 帧约 0.30°/帧）同轨迹对照：异常黄白泛光与
  文字重影消失；原 BSL ZIP、TAA、Bloom 配置均未修改。
- 窗口 1280x900、取走手机、第三人称、隐藏 HUD 均无旧屏幕残留。
  （隐藏 HUD 是 F1 / `hideGui` 等效操作，F3 只是调试信息，不是隐藏 HUD。）
- 后摄拍照 2560x1600 无手机 UI；自拍 400x600 正常保存。
- Complementary Reimagined r5.9.3 HIGH 下行为正常。

## 最终验收（2026-10-18 上游通过）

- 无 Iris 安装启动：MaidInfoPage 屏幕正常，pending=false、GL0。
- 有 Iris 但关闭光影：同样正常。
- 最终 BSL 10.1.8 HIGH 180 帧同轨迹：无文字重影/异常泛光；原包 SHA256 未变，
  TAA/Bloom 仍开启。
- 额外状态哨兵检查 `renderAfterLevel` 前后：非默认 depthTest/func/writeMask、cull、
  blend 因子、scissor enable/box、viewport、projection、modelView、shaderColor
  均完全恢复，GL0；临时测试工具未进入发布 jar。

## 未覆盖限制

- 不同 Iris / 光影包版本、Fabulous 图形模式、超宽屏/多显示器、其他 GUI Scale 与 FOV
  下的深度与矩阵行为仍需实机确认。
- 不声称对任意光影包/特效链全面兼容；世界自身或机身/手已有的光影残影不属于本次
  屏幕修复范围。
