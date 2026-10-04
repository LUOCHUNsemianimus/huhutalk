# HuhuTalk 手机「模型屏」· 工作日志与交接

> 本文是**进行中**任务的交接文档（与已完结的 [`phone-model-notes.md`](phone-model-notes.md) / [`phone-animation-handoff.md`](phone-animation-handoff.md) 区分）。
> 目标：把手机 Overlay 屏幕**直接画在玩家手持的 3D 手机模型上**，并让整块 UI（含拍照）可以在模型屏上正常操作。

---

## 0. 一句话状态

**期 0 / 1a / 1b / 1c / 1d 全部完成并实机验收；相机模式（期 1e）功能已通、UI 刚重排完待验收。**

当前可玩状态：

- **主手拿出 Pro 手机 → 模型屏自动亮起**（不需要按 O、也不需要低头到阈值才显示）
- 低头 ≥40° 后可用鼠标侧键操控；视角在其他任何情况下都不受影响
- 按 O 可随时把 UI 切成悬浮窗（两种屏幕由 `renderMode` 结构性互斥）
- **聊天页点输入框打字时，手机仍握在手里**（不再塌回普通物品）
- **相机模式**：手机抬到眼前、屏幕透出真实世界当取景框；鼠标只转视角，动作全交给侧键

**最近一轮改动（待实机验收）**：① 相机模式姿态终于不随视角乱转（见 §5.3-16）；② 相机模式视距与常态持握对齐（§5.5-19）；③ 相机 UI 按"少提示、留预览"重排（§4 期 1e）；④ 修掉重拍导致纹理变 magenta 方块（§5.5-20）。

---

## 1. 已完成并验收

| 期 | 内容 | 验收结论 |
|---|---|---|
| 0 | `ProPhoneHandRenderer`：第一人称双手持握（照抄原版地图姿势）+ 手机本体 | 通过；实机微调为**三轴旋转全 0、Y 偏移 +0.05** |
| 1a | 离屏帧缓冲出图 + 屏幕四边形 + dirty 机制 + 画布随 GUI Scale | 通过；修掉 `-0.5` 双重居中、绘制时机、GUI 耦合、切屏动画 |
| 1b | 独立光标 + 侧键点击/返回 + 按住拖动滚动 + 灵敏度设置 | 通过；修掉光标归一化（两屏偏移/够不到边） |
| 1c | 「操控屏幕」状态机：低头阈值 + 相机降速 + 状态可视化 | 通过；阈值由 30° 调整为 **40°**，补上**顶栏放行** |
| 1c+ | 独立光标**出生点 = 顶栏正中** | 通过 |
| 1c++ | **点输入框手机不再消失**：手持渲染不再因 `Screen` 打开而让位 | 通过（原理见 §5.2-8） |
| 1d | 期 1 收尾：拆旧 Alt 通路 + `renderMode` 互斥 + 掏出即渲染 | 通过（见 §4） |
| 1e | 相机模式：擦除纹理当取景框 + 独立姿态 + 侧键状态机 | **功能已通；UI 刚重排，待验收**（见 §4） |

---

## 2. 文件定位

### 本任务新增/改动

| 文件 | 职责 |
|---|---|
| `client/render/ProPhoneHandRenderer.java` | **新增**。订阅 `RenderHandEvent` 接管第一人称手持渲染：双手姿势 + 手机本体 + 挂屏幕四边形 + **相机模式姿态**。`ENABLED=false` 一键回退原版。判据集中在 `shouldRender(mc, stack)`，**不排除 Screen 打开**（§5.2-8） |
| `client/render/PhoneScreenTarget.java` | **新增**。离屏帧缓冲、动态纹理注册、屏幕面矩形几何、出图驱动、dirty 判定、`DIAG` 探针。判据用 `PhoneOverlay.hasModelScreen()` |
| `phone/PhoneOverlay.java` | 手机 UI 总控。`RenderMode` 状态机 + `phoneOpen`（`isVisible()` 由两者算出）+ `onHolderTick()` 掏出即渲染 + 独立光标全套 + `isCameraMode()` + **相机侧键状态机 `cameraSideButton` / `tickCameraHold`** |
| `phone/pages/ChatPage.java` | 聊天页 + **相机界面**（`renderCameraMode` / `handleCameraClick` / `cameraBarLayout`）。相机侧键入口是三个语义方法：`cameraPrimaryAction()` / `cameraBackAction()` / `cameraSaveAction()` |
| `init/ItemPhoneCamera.java` `init/HuhuTalkItems.java` | **新增**隐藏物品 `phone_camera`（不进创造栏、不是 `ItemMaidPhone`）。它只是"模型名片"，让 `ItemRenderer` 能按注册名查到擦除版模型，见 §4 期 1e |
| `client/PhotoLibrary.java` | 图库 + 临时截图 + **「已保存」气泡**。纹理注册改为**每次唯一 `ResourceLocation`**（§5.5-20） |
| `event/ClientModEvents.java` | 两个手机侧键位；`RenderFrameEvent.Pre` 出图 + 俯仰低通；`CalculatePlayerTurnEvent` 降速；每 tick `onHolderTick()` + `tickCameraHold()`。~~`RELEASE_CURSOR`~~ 已于期 1d 删除 |
| `mixin/MouseHandlerMixin.java` | 只剩**独立光标单通路**（`turnPlayer → onMouseDelta`、`onPress → onPhoneSideButton`，**都不 cancel**） |
| `phone/PhoneInputScreen.java` `phone/PhoneGroupNameInputScreen.java` | 透明输入屏（`render` 全空 + `isPauseScreen()==false`，只接键盘）；`onClose()` 无条件恢复 `GLFW_CURSOR_DISABLED` |
| `client/settings/HuhuTalkClientSettings.java` | `phoneCursorSensitivity`（默认 **1.2**） |

### 资源

| 资源 | 说明 |
|---|---|
| `models/item/phone_3d.json` | 手机本体模型（16 个 element / 5 个 group；`case_core` 是那块实心机身） |
| `models/item/phone_camera.json` | **与 `phone_3d.json` 几何逐字相同**，只把 `textures` 指向擦除版纹理 |
| `textures/item/phone_3d.png` | 手机纹理（**已换成用户修复版**，见 §5.5-21） |
| `textures/item/phone_3d_erased.png` | 擦除版：把"玩家侧可见面"擦成全透明，相机模式的取景框效果就靠它 |

### 相邻但**本任务未改动**（别混淆）

- `client/render/PhoneInHandLayer.java` —— **女仆**手上的手机渲染层（gecko 骨骼挂点）
- `mixin/GeckoLayerMaidHeldMixin.java` —— 女仆用手机时压掉右手手持物
- `init/ItemMaidPhone.java` —— 玩家手机物品本体（Pro = `phone_smart`）
- `client/MaidAvatarManager.java` —— 已有的离屏渲染先例（头像拍照），FBO 写法参考它

---

## 3. 可调常量速查（人工微调入口）

### 操控 / 光标

| 想调什么 | 文件 | 常量 / 键 | 当前值 |
|---|---|---|---|
| **低头进入「操控」的阈值**（只管操控，**不管亮不亮屏**） | `PhoneOverlay` | `LOOK_DOWN_DEG` | **40f** |
| 俯仰低通（消边界抖动） | `PhoneOverlay` | `PITCH_FILTER_MS` | `120f` |
| **视角降速倍率** | `ClientModEvents` | `CAMERA_TURN_SCALE` | `0.25` |
| 光标灵敏度 | `run/huhutalk/settings.json` | `phoneCursorSensitivity` | `1.2` |
| 拖动滚动阈值 / 折算 | `PhoneOverlay` | `DRAG_SCROLL_THRESHOLD` / `DRAG_PIXELS_PER_SCROLL_UNIT` | `4f` / `10.0` |
| **光标出生点** | `PhoneOverlay` | `defaultCursorNY()` + `resetCursorToSpawn()` | X 恒 `0.5`；Y = 顶栏中点（比例换算） |

### 模型屏出图

| 想调什么 | 文件 | 常量 / 键 | 当前值 |
|---|---|---|---|
| 画布高占 GUI 逻辑高比例 | `PhoneScreenTarget` | `CANVAS_HEIGHT_RATIO` | `0.87f` |
| **屏幕在手机正面的矩形**（模型单位 0~16） | `PhoneScreenTarget` | `SCREEN_LEFT/RIGHT/BOTTOM/TOP/DEPTH` + `SCREEN_DEPTH_PUSH` | `6.8/9.2/5.3/9.7/8.25 + 0.01` |
| 画面镜像/颠倒/不可见 | `PhoneScreenTarget` | `FLIP_U` / `FLIP_V` / `FLIP_WINDING` | 全 `false`（`DOUBLE_SIDED=true` 免疫剔除） |
| 空转刷新上限 / 诊断日志 | `PhoneScreenTarget` | `MAX_IDLE_MS` / `DIAG` | `100L` / `false` |

### 手机本体（常态持握）

| 想调什么 | 文件 | 常量 / 键 | 当前值 |
|---|---|---|---|
| 手机大小 / 位置 / 朝向 | `ProPhoneHandRenderer` | `PHONE_SCALE` / `PHONE_OFFSET_Y` / `PHONE_OFFSET_Z` / `PHONE_ROT_*` | `3.0 / 0.05 / 0 / 全 0` |

### 相机模式（**与常态持握是两套**）

| 想调什么 | 文件 | 常量 / 键 | 当前值 |
|---|---|---|---|
| `tilt` 判定点偏移 | `ProPhoneHandRenderer` | `CAM_TILT_REF_PITCH` | **`90f`**（取正！负号会失效，见 §5.3-16） |
| 左右 / 上下 / 前后偏移（视线坐标系） | `ProPhoneHandRenderer` | `CAM_OFFSET_X/Y/Z` | `0 / 0.04 / -0.72`（**与常态持握同值**，别乱改，见 §5.5-19） |
| 俯仰 / 水平 / 翻滚微调 | `ProPhoneHandRenderer` | `CAM_ROT_X/Y/Z` | 全 `0` |
| 长按保存阈值 | `PhoneOverlay` | `CAM_HOLD_SAVE_MS` | `350L` |
| 相机姿态诊断日志 | `ProPhoneHandRenderer` | `CAM_DIAG` | `false` |

### 相机 UI 版面

| 想调什么 | 文件 | 位置 | 当前值 |
|---|---|---|---|
| 快门圆直径 | `ChatPage.cameraBarLayout` | `shutterD` | `max(sh(18), w * 0.30)` |
| 确认胶囊尺寸/位置 | `ChatPage.cameraBarLayout` | `confirmW/H/X` | `max(sh(34), w*0.30)` / `max(sh(11), barH/3)` / 贴右 |
| 上一张预览尺寸 | `ChatPage.cameraBarLayout` | `thumbSize` | `max(sh(14), barH - 8)`，贴左 |

---

## 4. 目标与剩余执行计划

### 期 1 收尾（**已完成并验收**）

1. **拆除旧输入通路** ✅
   - 删 `RELEASE_CURSOR` 键位（含注册与 `lang`）、`PhoneOverlay.setAltDown()` / `updateCursorMode()`（`grabMouse/releaseMouse`）/ `shouldCaptureMouse()`，以及 `MouseHandlerMixin` 里旧通路的 cancel 分支。
   - `MouseHandlerMixin` 只剩两条**不 cancel** 的注入：`turnPlayer → onMouseDelta`、`onPress → onPhoneSideButton`。
   - 两个输入屏的 `onClose()` 改为无条件恢复 `GLFW_CURSOR_DISABLED`。
2. **两种屏幕互斥** ✅ —— 由 `renderMode` 结构性保证：
   - `render()` 里 `external ? !hasModelScreen() : !isFloatingScreen()` 是唯一分流点；
   - `PhoneScreenTarget.update` / `renderScreen` 都判 `hasModelScreen()`；
   - **`MODEL` → `FLOATING` 的切换由 O 完成**（"O = 我就是要那块悬浮窗"，用户拍板）。不需要"允许同时显示"设置项。
3. **掏出即渲染** ✅
   - `visible` 拆成 `phoneOpen` + `renderMode(MODEL/FLOATING/NONE)`，`isVisible()` 由两者算出。
   - `onHolderTick()` 检测**主手举起 Pro 手机的上升沿** → 自动打开。⚠ 必须存**边沿**（§5.3-14）。
   - **不做意图门**（用户决策）：主手被手机占着没法挖矿，掏出动作本身就是足够强的意图。

### 期 1e 相机模式（**功能已通，UI 刚重排待验收**）

**取景框原理（关键，别推翻）**：不给模型掏窟窿，而是**换一张擦除纹理**。
`phone_3d_erased.png` 把"玩家侧可见面"擦成全透明 ⇒ 机壳在那些面上**直接透出后面的真实场景**，几何零改动。
`phone_camera.json` 就是"同一份几何 + 换一行纹理引用"，由隐藏物品 `phone_camera` 让 `ItemRenderer` 能寻址。

**侧键语义（已按用户口径定稿）**：

| 状态 | 侧键 4（手机左键） | 侧键 5（手机右键/返回） |
|---|---|---|
| 取景中 | 拍照 | 退出相机模式 |
| 已拍成 | 轻点 = 确认发送（选中并回聊天）<br>**长按 = 存相册**（弹气泡，留在已拍成页） | 返回取景（不丢弃这张） |

**UI 版面（本轮重排）**：
- 顶栏：左 `< 返回 <键名>`（**纯文字，不做按钮底**），右「拍照」标题
- 底栏：左**上一张预览**（取景中/拍成后**都有**）+ 中间**圆形快门**（粉芯 + 白圈）
- 已成：中间快门消失，换成**底部偏右的小号「按<键名>确认」**
- 所有键名走 `KeyMapping.getTranslatedKeyMessage()`，**与玩家绑定耦合**

**待验收清单**：
- [ ] 取景中侧键 4 拍照、侧键 5 退出
- [ ] 已成侧键 4 确认发送、侧键 5 返回取景（**不再重拍**）
- [ ] 长按侧键 4 → 「已保存到相册」气泡，且留在已成页
- [ ] 快门大小合适、不盖住左侧预览
- [ ] 预览在取景中（显示上一次拍的）与已成（显示刚拍的）都出现
- [ ] 改绑键位后，顶栏与确认胶囊里的键名跟着变

### 期 2（画质与对齐）

- 屏幕四边形**滤波/mipmap**：目前走 `RenderType.text`，`TextureStateShard` 请求 nearest；斜视/缩小时会闪烁。`FboTexture.setFilter` 已留空实现，可在此接管
- UV 内缩 ~0.5px 防边缘渗色
- 屏幕矩形与机身边框的贴合精修
- **平常手机模型是否还闪烁**：待确认（用户已换过修复版贴图；z-fighting 的另一半原因见 §5.5-19）

### 期 3（设置与清理）

- **设置页滑块**：光标灵敏度、降速倍率、（可选）低头阈值。SettingsPage 的行是手工排 Y 的 `computeLayout()`，插行需同步改它
- **清理探针**：`PhoneScreenTarget.DIAG`、`ProPhoneHandRenderer.CAM_DIAG`
- `PhoneInHandLayer` 里遗留的女仆侧诊断日志（历史遗留，见 `phone-model-notes.md` §9）

### 预留 / 待办（用户提出，**明确不在本期做**）

- **锁屏（防误触的替代方案）**：一段时间不操作 → 屏幕上贴黑色底 + 时间。
  用户判断：大部分玩家想要即拿即用，所以这是**后期优化，先不做**。
- **副手持有手机**：副手拿着时手机始终垂直于视角（同副手地图），因此
  - `shouldRender` / `hasModelScreen` / `LOOK_DOWN_DEG` 三者都需重新设计，**别直接复用主手这套**；
  - 不过相机模式的姿态问题反而会简化 —— 副手持握本来就垂直于视角。
- 按 O 自动把主手物品换成手机 Item（只加一个类即可）
- **独立光标在"透明输入屏"期间冻结**：`turnPlayer` 只在鼠标被抓取时调用，输入屏必然释放鼠标。
  目前判定为正确行为（§5.3-17）。

---

## 5. 踩过的坑（**重点，重踩代价很高**）

### 5.1 坐标与几何

1. **`-0.5` 双重居中**（最难查的一个）：物品模型 baked 顶点在**原始 0~1 空间**；`ItemRenderer.render` 会做一次 `translate(-0.5,-0.5,-0.5)` 居中。我们的 pose 已逐字照抄它 ⇒ **手写顶点的坐标不要再减 0.5**。当时按"已居中空间"给坐标，整块屏幕沿手机三条轴各平移半个模型单位（×3 缩放 ≈ 1.5 格）。
2. **MC 的 `xRot` 正值 = 低头**（`-90` 看天，`+90` 看地）。讨论"低头 30°"时若按负数写会正好反掉。
3. **屏幕矩形用模型单位 0~16**，与 `phone_3d.json` / Blockbench 里读到的数字一致；画布宽度由矩形宽高比**自动导出**。
4. **纹理 UV 的标尺**：`phone_3d.json` 的 `texture_size` 是 `[128,128]`，而 Blockbench 项目里纹理的 `uv_size` 是 `16×16` / `bitmap_size 128×128` ⇒ **UV 数值 × 8 = 位图像素**。
   屏幕四边形 UV `[6.8,6.3]~[9.2,10.7]` → 位图 **X 54.4~73.6 / Y 50.4~85.6**。
   实测对照：`case_core` 北面 UV `[3.6,0.5]~[6.2,4.9]` → 位图 **X 28.8~49.6 / Y 4~39.2**，与屏幕区**几乎不重叠**。
   ⚠ 别再用"文件字节数"去推断模型是否同步（我这么干过，结论全错）；用 element 数量 / 坐标实值比对。

### 5.2 渲染顺序与投影

5. **`MultiBufferSource.BufferSource.endBatch()` 的刷新顺序**：先刷**共享缓冲**（`RenderType.text`），再刷**固定缓冲**（`Sheets.solid`，机身）。所以机身画完必须**主动 `endBatch()`**，再排屏幕四边形。
6. **屏幕四边形必须立即 `endBatch(renderType)` 刷出**。若留在共享缓冲等后续统一 flush，一旦落到 GUI 段（正交投影），屏幕就会脱离手机、钉死在某角。**"位置固定不随视角动" ≈ 投影/时机问题，不是坐标问题**。
7. **离屏靶尺寸与 scissor 强耦合**：`GuiGraphics.applyScissor` 只从 `window.getHeight()` + `window.getGuiScale()` 取数 ⇒ 离屏靶**像素高必须 = 窗口帧缓冲高**、正交换算系数**必须 = GUI Scale**。满足后页面里满地的 `enableScissor` 自动正确，**一个原版方法都不用碰**（这是"页面零改动"的根本原因）。
8. **GUI pass 的投影是 `ortho(...,1000,21000)` + 模型视图 `translate(0,0,-11000)`**，照抄；否则 z≈0 的内容被裁掉。
9. **第一人称手持渲染与 `Screen` 完全无关**（"点输入框手机就没了"的根因）。
   `GameRenderer.renderLevel` 里是 `if (this.renderHand) { clear(...); this.renderItemInHand(...); }`，
   而 `renderItemInHand` 的唯一门是 `isFirstPerson && !isSleeping && !options.hideGui && 非旁观`（原版 L951-954）——**没有一句和 `mc.screen` 有关**。
   所以 `ProPhoneHandRenderer` 里那句 `if (mc.screen != null) return;` 是**自己主动让位**，一弹输入屏就走原版 `renderArmWithItem`。
   修法：删早退，与 `isActive()` 合成唯一判据 `shouldRender(mc, stack)`。
   **副作用**：输入屏期间独立光标冻结 —— 这是对的（`turnPlayer` 只在鼠标被抓取时调用，`setScreen` 会释放鼠标）。
   补充：`ClientHooks.guiLayers` 是**父级 Screen 栈**，不是 HUD 层；HUD 层是 `GuiLayerManager`（`RegisterGuiLayersEvent` 的目标），只由 `Gui.render()` 驱动 —— 所以 `PhoneOverlay.render` 在开着 Screen 时**不会**被调用，此时模型屏纹理完全由 `PhoneScreenTarget.update`（`RenderFrameEvent.Pre`）喂。

### 5.3 状态与逻辑

10. **`render()` 里 `uiAnimEnabled()` 的动画重置分支**：离屏模式下第一支不成立会落进 `else`，**每帧**清空 `transTween/launchActive` → 两边都没有切屏动画。必须写成 `} else if (!uiAnimEnabled()) {`。
    ⚠ **期 1d 又踩了一次，同一个坑的变体**：把条件改成 `!uiAnimEnabled() || renderMode != FLOATING`，但**没删分支体里那 5 行 `transTween/transFrom/transTo/launchActive/launchTween` 的清空** —— 而 `renderMode != FLOATING` 在**离屏出图那一帧恒为真**，于是又变成每帧清空，切屏动画再次消失。
    **教训**：这种"每帧清状态"的分支，改条件时必须同步审查**分支体**；条件放宽 = 副作用范围被放大。
11. **`MouseHandler.turnPlayer` 每帧都会被调用**（鼠标没动时增量为 0）。光标喂入与标脏都必须对零增量**提前返回**，否则每帧标脏、dirty 机制失效。
12. **两个模式下手机逻辑尺寸不同**（模型屏画布 ≈119×219；悬浮窗 ≈137×244）。光标若按局部像素存，会被两套矩形**交替钳制取交集** → 较大屏幕上永远够不到右边/底边。**必须归一化 0~1**。
13. **相机降速的作用域必须是硬约束**：`CalculatePlayerTurnEvent` 处理器第一行判断，不在状态内就 `return`。
14. **「掏出即渲染」必须检测上升沿，不能检测状态**。`onHolderTick()` 比对 `holderWasActive`：只在**从没举着变成举着**的那一刻打开。若写成"只要举着就 `phoneOpen = true`"，玩家按 O 收起后下一 tick 立刻被重新打开 —— 表现为**手机永远关不掉**。`armHolderAutoOpen()` 供新世界/拍照恢复时重新武装。
15. **`isVisible()` 必须是算出来的，不能是字段**。拆成 `phoneOpen` + `renderMode` 之后，可见性 = `phoneOpen && renderMode != NONE`。这样「两种屏幕互斥」**结构上成立**：任何一处只要问 `isVisible()` 就自动正确，不必在 20 多个早退点手写条件。
16. **相机模式手机随视角乱转 —— 根因是 `tilt` 是俯仰的非线性函数**（反复三次才修对，务必读完）。
    - `tilt = calculateMapTilt(xRot)` = `clamp(1 - xRot/45 + 0.1, 0, 1)` 再取 `-cos(fπ)/2 + 0.5`；手部施加 `mulPose(XP, tilt * -85)`。
    - 它的语义是"平视时手机几乎平躺、越低头越竖起来正对视线"——**这个随俯仰滑动的过程就是打转的唯一来源**。
    - ⚠ **事后补常数角度永远补不干净**（我补过 `+(90+tilt*85)`、`-xRot`、`180`，全错）：它不是常数旋转，且 `RotX` 与 `RotY` **不对易**。
    - **正解**：让它**不要产生**。给相机模式换一个参考俯仰：
      ```java
      float tilt = camera ? calculateMapTilt(pitch + CAM_TILT_REF_PITCH) : calculateMapTilt(pitch);
      ```
      取 `CAM_TILT_REF_PITCH = 90`，则平视/低头时 `pitch+90` 都 ≥ 90 ⇒ `f ≤ 0` ⇒ clamp 到 **0** ⇒ 手机先天垂直于视线，`applyCameraPose` 只剩一个兜底的抵消项。
    - ⚠ **符号别搞反**：`xRot` 低头为正。要取 **`+90`**：
      平视 `0+90=90 → 1-2+0.1=-0.9 → clamp 0` ✓；低头 `90+90=180 → 更负 → 0` ✓；抬头 `-90+90=0 → 1.1 → 1`（此时才趋近平行，正是"判定点挪到看天"）。
      取 `-90` 会让平视和低头都算成 1，**等于没挪**（改这个值前务必按这三点验算）。
17. **`Screen` 打开时 `turnPlayer` 根本不调用**（原版 `MouseHandler.handleAccumulatedMovement` 只在 `isMouseGrabbed()` 时调）。拆掉旧 Alt 通路后，透明输入屏期间手机光标必然冻结 —— 是正确表现，**别去加 `setScreen` 之外的喂入路径**（那会让真实光标与手机光标同时活着，正是旧通路的病根）。
18. **侧键映射绝不能走"合成坐标点击"**（相机 UI 反复出错的根因）。
    早先的实现：`PhoneOverlay` 把"按了哪个侧键"翻译成一个**屏幕坐标**，再交给页面做命中判定。
    后果：① 排版一改命中区就挪位；② 两个命中区在 y 方向**重叠 1 像素**时（`hintH = max(11, rowH-2)` 被迫超出 `rowH`），"按侧键 5 没反应 / 按侧键 4 却返回"。
    **正解：直接调用语义方法** —— `ChatPage.cameraPrimaryAction()` / `cameraBackAction()` / `cameraSaveAction()`。
    键位映射变成**显式**的，与渲染排版彻底解耦；`cameraSideButtonTarget` / `cameraSaveButtonTarget` / `synthClick` 整套机制已删除。
    **通用教训**：把"输入意图"编码进"屏幕坐标"里，等于让 UI 排版成为逻辑正确性的前提 —— 别这么干。

### 5.4 资源与纹理

19. **相机模式视距必须与常态持握同值，否则"手机忽大忽小"**。
    常态势握的基准位移是 `translate(0, 0.04 + …, -0.72)`；相机模式去掉了 `tilt` 项，所以
    `CAM_OFFSET_X/Y/Z` 就取 **`0 / 0.04 / -0.72`**。
    曾写成 `-0.06 / -0.62`（**近了 0.1 单位**），实机表现是"相机模式更靠近玩家、看起来更大" ——
    **那不是缩放问题，是距离问题**（近大远小）。两边缩放本来就是同一个 `PHONE_SCALE`，**不要新增相机专属缩放常量**：
    曾有过 `CAM_SCALE = 3.0`，与 `PHONE_SCALE` 相乘 = 实机 **9 倍**。
20. **`DynamicTexture` 复用同一 `ResourceLocation` 会导致"拍第二张变黑紫方块"**。
    现象：拍一张 → 进发送页 → 取消勾选 → 回拍照页再拍 → 新照片是黑紫相间的缺失纹理方块。
    根因链：`TextureManager.register` 只是**覆盖映射**，被换掉的 `DynamicTexture` 及其 GL 纹理不会自动回收；
    而我为防泄漏加的 `release(key)` 走的是 `RenderSystem.recordRenderCall`（**渲染线程延后执行**）——
    等它执行时，新纹理**已经用同一个 `ResourceLocation` 注册进去了**，于是那次释放删掉的正是**新纹理**。
    **正解**：`registerTexture` **每次都用全新的 `ResourceLocation`**（`photo/<key>_<seq>`），
    新旧永不同名，注册与释放就不可能指向同一对象；旧的那个另行 `scheduleRelease` 回收。
21. **换贴图要让用户确认基准**：mod 里的 `phone_3d.png` 与用户在工作目录给的 `phone.png` 曾差 **89 个像素**
    （说明当时跑的是旧图）。用户把修复版放到工作目录后需**显式覆盖**进 `textures/item/`，别假设"已经在用了"。
    ⚠ 另外：Blockbench 的 `java_block` 导出**不能直接覆盖 mod 里的模型 JSON** ——
    导出的 `textures` 路径是 `huhutalk:phone`（缺 `item/`）、没有 `texture_size`、`display` 块是"仅导出可见元素"的残缺版
    （`ground.scale 0.6`、缺 `fixed`）。要用 bb 当作者源，必须把 format version 调回 1.21.1 并关掉局部导出。

### 5.5 API 与工具

22. **真源码 jar**：`build/moddev/artifacts/neoforge-21.1.243-sources.jar`，用 `jar!/net/minecraft/.../Foo.java` 路径读。
    反编译版（`-merged.jar!/...class`）会被 `… N lines truncated …` 吃掉中间段。
    - ⚠ `mcp__IDEA__read_file` 对**大文件**也会在 110 行左右就截断。可靠做法：把单个 `.java` 抽到 `build/tmpsrc/` 再用 `grep`/`read`
      （`Expand-Archive` 不认 `.jar` 后缀 —— 先 `Copy-Item` 成 `.zip`，再用 `System.IO.Compression.ZipFile`）。
23. **`AbstractTexture.load(ResourceManager)` 是抽象方法**必须实现；**`TextureManager.register(ResourceLocation, AbstractTexture)` 返回 `void`**。
24. **日志节流必须按时间、不能按内容**：内容每帧都变，按内容判重等于没节流。
25. 两个**免 mixin** 的官方钩子（优先用它们）：
    - `RenderHandEvent`（`ICancellableEvent`）—— 接管第一人称手持渲染
    - `CalculatePlayerTurnEvent.setMouseSensitivity` —— 改视角灵敏度
26. **原版地图的双手持握是第一人称专属**：`ItemInHandRenderer.renderArmWithItem` 里 `stack.getItem() instanceof MapItem` 是硬编码特判；`HumanoidModel.ArmPose` 里**没有** MAP。
27. **别把 `ClientHooks.guiLayers` 当成 HUD 层**（见 §5.2-9）。
28. **Toast**：保存提示用原版 `SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component, null)`
    经 `mc.getToasts().addToast(...)` 弹出，并整段 try-catch —— 提示失败绝不能影响"照片已落盘"这个事实。

---

## 6. 环境与工具

- **`pwsh` 工具在本机不可用**（宿主 ACL 故障：`SetNamedSecurityInfoW failed (Win32 5)`）。命令行走 IDEA MCP 的 `execute_terminal_command`。
- **IDEA MCP 偶尔 `server is disconnected`**，会自行恢复。**编译验证一律用 `build_project`**（IDEA 的 javadoc 级报错不影响编译，别被红标误导）。
- **启动方式**：用户走 IDEA 的 `runClient`，**直接吃 `build/classes`**。
  ⚠ `build/libs/huhutalk-1.0.0.jar` 可能是很久以前的、`run/mods/` 里**没有**本模组的 jar ——
  排查"改动没效果"时先确认启动方式，别在代码里空转。
- 日志：`run/logs/latest.log`；设置：`run/huhutalk/settings.json`（原子写入 + `.corrupt` 备份）。
- 验收设置（当时的实机环境）：`guiScale=4`、帧缓冲高 1009（GUI 逻辑高 252）、`phoneSize≈0.97`、`uiFontScale=1.0`。
- Blockbench：MCP 已连（5.2.1，项目 `huhutalk_phone`，`java_block`，16 cube / 5 group / 1 纹理）。
  存档在女仆资源包目录，**不是** mod 资源目录。

---

## 7. 设计原则（别违背，否则会走回头路）

1. **手机只做加法，从不做减法。** 鼠标移动照常转视角，左右键照常攻击/放置，只有玩家绑定的**手机侧键**才额外被手机接收。
2. **唯一的例外是"操控屏幕"态**，且严格限定为：掏出手机 + 主手 Pro 手机 + 低头 ≥ 阈值。任何其他情况在**同一个 tick 内**恢复原状。
3. **状态是俯仰角的纯函数，进出共用同一阈值（无滞回带）。** 退出随时可行、归因自然。边界抖动靠**时间低通**，不靠空间滞回。
4. **顶栏是"松手出口"**：光标推到状态栏 → 视角立刻恢复全速。进一步：**光标出生点就在顶栏正中** —— 出口不该藏在玩家得自己试出来的一划里。
5. **状态必须可视化**：光标在操控态画亮、冻结态画暗。玩家永远不该猜"为什么光标不动了"。
6. **光标活在 Overlay 自己的坐标系里** —— 因此会随 Overlay 一起进离屏纹理，悬浮窗与模型屏两边**零额外适配**。
7. **输入意图要直接表达，不要编码成屏幕坐标**（§5.3-18）。排版与逻辑必须解耦。
8. **UI 提示越短越好，语义交给直觉。** 相机界面的按键说明从"按『鼠标侧键 4』拍照"长句
   退回到"中间那颗圆钮 = 快门、左侧缩略图 = 上一张"。**一行里塞不下三个带键名的长标签**，
   强行塞只会让字号缩到看不清、还挤掉真正有用的预览。
9. **宁可少画一个控件，也不要盖住别的控件**：`cameraBarLayout` 会算出快门右缘，
   空间不够时**不画**右侧确认胶囊（侧键 4 依然可用），而不是让预览图被压掉。
