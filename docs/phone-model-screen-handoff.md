# HuhuTalk 手机「模型屏」· 工作日志与交接

> 本文是**进行中**任务的交接文档（与已完结的 [`phone-model-notes.md`](phone-model-notes.md) / [`phone-animation-handoff.md`](phone-animation-handoff.md) 区分）。
> 目标：把手机 Overlay 屏幕**直接画在玩家手持的 3D 手机模型上**，并让整块 UI（含拍照）可以在模型屏上正常操作。

---

## 0. 一句话状态

**期 0 / 1a / 1b / 1c / 1d 全部完成并实机验收；相机模式（期 1e）功能已通、UI 已按第二轮反馈重排，待验收。**

当前可玩状态：

- **主手拿出 Pro 手机 → 模型屏自动亮起**（不需要按 O、也不需要低头到阈值才显示）
- 低头 ≥40° 后可用鼠标侧键操控；视角在其他任何情况下都不受影响
- 按 O 可随时把 UI 切成悬浮窗（两种屏幕由 `renderMode` 结构性互斥）
- **聊天页点输入框打字时，手机仍握在手里**（不再塌回普通物品）
- **相机模式**：手机抬到眼前、屏幕透出真实世界当取景框；鼠标只转视角，动作全交给侧键

**最近一轮改动（待实机验收）**：
① **拍照拍的是「干净的世界」**（`RenderLevelStageEvent.AFTER_LEVEL` 抓图），不再是整屏原版截图（§4 期 1e / §5.5-29）；
② **已拍成页按侧键 5 = 取消这张、留在取景中重拍**，不再直接扔回聊天页（§5.5-31）；
③ 快门缩到 0.5 倍并**在圆内写绑定键名**；确认胶囊回到**1 倍默认字号**（§3「相机 UI 版面」）；
④ 保存成功的反馈改为**手机屏内半透明气泡「保存成功」**，不再用原版 toast；且**已拍成页顶栏右侧由「拍照」改成「长按&lt;确认键名&gt;保存」**（键名随玩家绑定走，§5.5-30 / §7-10）；
⑤ **聊天界面底部扩展栏的手势全部修正**：表情网格拖动方向反了（§5.6-32）、图库改成**横向**拖动（§5.6-33）、图库顺序改成**最新在最左**（§5.6-34）；
⑥ **修掉"空手低头也降速"**：「操控屏幕」态补上遗漏的**手持**条件，并让 UI 在手机离手时收屏（§5.7-35）。

**⑦ 已修复「图标柔边一圈随昼夜变色的描边」** —— 根因是**离屏靶的 alpha 没有钉到 255**，
屏幕四边形用这个 alpha 和世界混合，柔边就把天空/雾透了出来（**只有模型屏有、悬浮窗没有**）。
修法见 **§5.8**（`PhoneOverlay.render` 离屏分支末尾用 `colorMask` 只写 alpha）。
**⚠ 这个坑查了一整天、绕过四条错路，动模型屏前请务必读 §5.8。**
**⚠⚠ 而且它的第一版修法本身又埋了一个 bug**：那段 alpha 钉底当时没排除相机取景态，
把取景区一并涂成不透明黑 ⇒ 相机页"直接画了个黑底"。**已修**（条件是
`external && !cameraTransparent`），完整复盘见 **§5.8-38b** 与
[camera-black-screen-handoff.md](camera-black-screen-handoff.md) §0.5。


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
| `phone/PhoneOverlay.java` | 手机 UI 总控。`RenderMode` 状态机 + `phoneOpen`（`isVisible()` 由两者算出）+ `onHolderTick()` 掏出即渲染 + 独立光标全套 + `isCameraMode()` + **相机侧键状态机 `cameraSideButton` / `tickCameraHold`** + **拍照抓图请求 `requestWorldCapture` / `consumeWorldCapture`** + **屏内气泡 `showBubble`** + **离屏分支末尾"把 alpha 钉成 255"的收尾**（§5.8-38） |
| `phone/pages/ChatPage.java` | 聊天页 + **相机界面**（`renderViewfinder` / `renderShotPreview` / `shotBarLayout` / `cameraBarLayout`）。相机侧键入口是三个语义方法：`cameraPrimaryAction()` / `cameraBackAction()` / `cameraSaveAction()`。**已拍成页现为全屏照片 + 「重拍 / 发送」两颗按钮**（不透明，不再透出背后世界）；判据拆分见 `PhoneCameraHost.cameraScreenTransparent()` |
| `init/ItemPhoneCamera.java` `init/HuhuTalkItems.java` | **新增**隐藏物品 `phone_camera`（不进创造栏、不是 `ItemMaidPhone`）。它只是"模型名片"，让 `ItemRenderer` 能按注册名查到擦除版模型，见 §4 期 1e |
| `client/PhotoLibrary.java` | 图库 + 临时截图。纹理注册改为**每次唯一 `ResourceLocation`**（§5.5-20）。保存**不再自己弹提示**（改由 `PhoneOverlay.showBubble` 在手机屏内弹，§5.5-30） |
| `event/ClientModEvents.java` | 两个手机侧键位；`RenderFrameEvent.Pre` 出图 + 俯仰低通；**`RenderLevelStageEvent.AFTER_LEVEL` 抓拍照帧**（§5.5-29）；`CalculatePlayerTurnEvent` 降速；每 tick `onHolderTick()` + `tickCameraHold()`。~~`RELEASE_CURSOR`~~ 已于期 1d 删除 |
| `mixin/MouseHandlerMixin.java` | 只剩**独立光标单通路**（`turnPlayer → onMouseDelta`、`onPress → onPhoneSideButton`，**都不 cancel**） |
| `phone/PhoneInputScreen.java` `phone/PhoneGroupNameInputScreen.java` | 透明输入屏（`render` 全空 + `isPauseScreen()==false`，只接键盘）；`onClose()` 无条件恢复 `GLFW_CURSOR_DISABLED` |
| `client/settings/HuhuTalkClientSettings.java` | `phoneCursorSensitivity`（默认 **1.2**） |

### 资源

| 资源 | 说明 |
|---|---|
| `models/item/phone_3d.json` | 手机本体模型（16 个 element / 5 个 group；`case_core` 是那块实心机身） |
| `models/item/phone_camera.json` | **与 `phone_3d.json` 几何逐字相同**，只把 `textures` 指向擦除版纹理（现指向 `phone_camera_2`） |
| `textures/item/phone_3d.png` | 手机纹理（**已换成用户修复版**，见 §5.5-21） |
| `textures/item/phone_camera_2.png` | **当前的擦除版**（用户手绘，128×128）：把"玩家侧可见面"擦成全透明，相机模式的取景框效果就靠它 |
| `textures/item/phone_3d_erased.png` | ~~旧的擦除版~~ **已弃用**：擦得不够干净（用户反馈）。文件留着仅供对照，没有任何引用指向它 |

### 图标素材的离线工具（`tools/`）

桌面 App 图标的贴图有一套独立流水线，**与渲染器无关**，但两者会产生外观很像的描边，别混淆（判别与机制见 §5.8-41）。

| 文件 | 职责 |
|---|---|
| `tools/sprite_alpha.py` | **纯标准库**的精灵图诊断/修复：`report`（量化诊断，含按 alpha 分档的边缘均色）、`fix`（un-matte / erode / 颜色外扩）、`matte`（"底色漏出"三联图）、`sim`（把"游戏里那一步缩放"离线重放，出对比图）。支持 1/2/4/8bit 与**调色板 PNG（PLTE + tRNS）** |
| `tools/import_manual_assets.py` | 把 `D:\Moddevelop\模组配图\` 里的素材导入 mod 资源目录。手册配图原样拷贝；**App 图标走「抠背景 → 边缘去污 → 预降采样 96px → 再去污 → 写 `.mcmeta`」**。源由 `ICON_SOURCES` 显式有序候选决定 |

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
| **「操控」态总判据（三条件缺一不可）** | `PhoneOverlay` | `isScreenControl()` | `isVisible() && isHoldingPhone() && filteredPitch >= LOOK_DOWN_DEG`（§5.7-35） |
| 俯仰低通（消边界抖动） | `PhoneOverlay` | `PITCH_FILTER_MS` | `120f` |
| **视角降速倍率** | `ClientModEvents` | `CAMERA_TURN_SCALE` | `0.25` |
| 光标灵敏度 | `run/huhutalk/settings.json` | `phoneCursorSensitivity` | `1.2` |
| 拖动滚动阈值 / 折算 | `PhoneOverlay` | `DRAG_SCROLL_THRESHOLD` / `DRAG_PIXELS_PER_SCROLL_UNIT` | `4f` / `10.0` |
| **画廊拖动跟手系数** | `ChatPage` | `GALLERY_DRAG_SPEED` | `10`（按"1 单位 = 10 像素手势"折算，= 严格跟手；见 §5.6-32） |
| 表情网格滚动速度 | `ChatPage` | `SCROLL_SPEED` | `12` |
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
| **快门直径** | `ChatPage` | `SHUTTER_W_RATIO` / `SHUTTER_MIN` | `0.15`（= 手机宽 ×15%，约上一版 0.5 倍）/ `9` |
| 快门内键名字号 | `ChatPage.renderCameraMode` | 内联算式（以粉芯 `inner` 为可用区，宽 ×0.86 / 高 ×0.52 取小，钳在 `0.20~0.70`） | 默认键名下 ≈ **0.27** |
| 已拍成时左侧缩略图边长 | `ChatPage` | `THUMB_SHOT` | `24`（**上限**；实际取 `min(barH-8, sh(24), 胶囊左缘之前的空间)`，下限 `sh(12)`）；取景中仍是 `barH - 8` ≈ 36 |
| 确认胶囊尺寸/位置 | `ChatPage.cameraBarLayout` | `confirmW/H/X` + `CONFIRM_PAD_X` | 先按"按<键名>确认"的 1 倍字号算出宽度（`标签宽 + 10`）→ 让缩略图让位 → 兜底再钳到剩余空间；高度 = `max(sh(18), barH*0.5)`、贴右 |
| **确认胶囊字号** | `ChatPage.renderCameraMode` | 内联 | **1 倍默认字号**（`UiFont.sc(1.0f)`，下限 `sc(0.8f)`） |
| 保存成功气泡时长 | `PhoneOverlay` | `BUBBLE_MS` / `BUBBLE_FADE_IN_MS` / `BUBBLE_FADE_OUT_MS` | `1400 / 120 / 380`（毫秒）；位置 = 内容区 **58% 高**、水平居中 |
| 相机顶栏两段文字的字号 | `ChatPage.renderCameraMode` | 内联（`barTs`，两段**共用**一个缩放，上限 `sc(0.85f)`、下限 `0.40`） | 取景中两段 =「`< 返回 <返回键名>`」+「拍照」；**已拍成时右段 =「长按<确认键名>保存」** |
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

### 期 1e 相机模式（**功能已通，UI 按第二轮反馈重排待验收**）

**取景框原理（关键，别推翻）**：不给模型掏窟窿，而是**换一张擦除纹理**。
`phone_camera_2.png` 把"玩家侧可见面"擦成全透明 ⇒ 机壳在那些面上**直接透出后面的真实场景**，几何零改动。
`phone_camera.json` 就是"同一份几何 + 换一行纹理引用"，由隐藏物品 `phone_camera` 让 `ItemRenderer` 能寻址。
（旧的 `phone_3d_erased.png` 擦得不干净，已弃用；换纹理只需改 `phone_camera.json` 里那两行 `textures`。）

**拍照抓图（本轮重做，别改回去）**：`PhoneOverlay.requestWorldCapture` 只登记请求，
真正的抓取发生在 `RenderLevelStageEvent.Stage.AFTER_LEVEL` —— 那一刻**世界已画完，手/手机/HUD 都还没画**
（1.21.1 源 `GameRenderer.renderLevel`：L1279 派发 AFTER_LEVEL，L1281 才 `renderItemInHand`；
HUD 更在整段 `renderLevel` 之后）。所以照片 = 玩家眼里那片干净的世界，整屏 16:9，**不裁剪**（用户拍板）。
旧版"临时隐藏手机一帧 + 在 GUI 层抓图"的通路已整体删除，原因见 §5.5-29。

**侧键语义（已按用户口径定稿）**：

| 状态 | 侧键 4（手机左键） | 侧键 5（手机右键/返回） |
|---|---|---|
| 取景中 | 拍照 | 退出相机模式（回聊天） |
| 已拍成 | 轻点 = 确认发送（选中并回聊天）<br>**长按 = 存相册**（弹气泡，留在已拍成页） | **取消这张**（丢弃），留在相机里回到取景中重拍 |

**UI 版面（本轮重排）**：
- 顶栏：左 `< 返回 <返回键名>`；右取景中「拍照」、**已拍成「长按<确认键名>保存」**（两段共用同一个缩放，见 §3）
- 底栏：左**上一张预览**（取景中/拍成后**都有**；已拍成时按需缩到 12~24 逻辑像素，给确认胶囊让宽度）
- 取景中：中间**小圆快门**（直径 = 手机宽 ×15%，约上一版 0.5 倍；白圈 + 粉芯，**粉芯里写绑定键名**）
- 已拍成：快门消失，换成右侧「按&lt;键名&gt;确认」胶囊，**字号 1 倍默认**
- 保存反馈：手机屏内半透明气泡「保存成功」（1.4 秒，淡入淡出）
- 所有键名走 `KeyMapping.getTranslatedKeyMessage()`，**与玩家绑定耦合**
- ⚠ 相机模式**不画光标**（整条用侧键），所以界面上不存在"可点但点不到"的按钮噱头：
  凡是写出来的动作提示，都必须是侧键动作

**待验收清单**：
- [ ] 取景中侧键 4 拍照、侧键 5 退出
- [ ] 已成侧键 4 确认发送、侧键 5 **取消这张并回到取景中**（不是回聊天页）
- [ ] 长按侧键 4 → 屏内气泡「保存成功」，且留在已成页；随后打开聊天页底部扩展栏的图库能看到这张
- [ ] **拍出来的照片是干净的世界**：没有 HUD / 物品栏 / 准星 / 手臂 / 手机本体
- [ ] 快门大小合适（约上一版一半）、键名写在圆里、不压到左侧预览
- [ ] 已成页确认胶囊的字是 1 倍默认字号（不再是缩到 0.45 倍的小字）
- [ ] 已成页顶栏右侧写着「长按<鼠标按键4>保存」；改绑键位后这里跟着变
- [ ] 预览在取景中（显示上一次拍的）与已成（显示刚拍的）都出现
- [ ] 改绑键位后，顶栏、快门内、确认胶囊里的键名跟着变
- [ ] 扩展栏手势：表情网格**往下拖 = 内容跟着往下走**（看到更早的表情）
- [ ] 扩展栏手势：图库**用左右拖**翻页（上下拖不再翻它），且拖 10 像素内容走 10 像素（跟手）
- [ ] 图库顺序：**最新拍的那张在最左**
- [ ] **不拿手机低头不降速**；拿着手机低头才降速（空手 / 拿着别的物品都试一遍）
- [ ] 拿着手机时切到别的物品 → 手机 UI 立刻收起；再切回手机 → 自动重新亮屏


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
    ⚠ **本条已作废（相机保存的反馈不再走 toast）**，见下面 §5.5-30。
29. **拍照抓图必须在 `RenderLevelStageEvent.AFTER_LEVEL`；别靠"隐藏手机 + GUI 层抓图"，更别碰 `options.hideGui`。**
    - 症状（旧版）：图库里的"照片"是一张**整屏原版截图** —— HUD、物品栏、手臂、手机本体全在里面
      （`run/huhutalk/photos/` 里那张 1920×1009 就是物证）。
    - ⚠ **`options.hideGui` 是陷阱**：`Gui` 的 layerManager 条件就是 `!options.hideGui`
      （1.21.1 源 `Gui.java` L248），置真会让**整个 GUI 层**都不绘制 —— 包括本模组通过
      `RegisterGuiLayersEvent` 注册的回调。于是"隐藏 HUD 后用 GUI 层回调抓图"这条路**永远不会触发**，
      表现为"按下拍照后手机永久消失"（`captureHidden` 再也回不来）。
    - **正解**：`RenderLevelStageEvent.Stage.AFTER_LEVEL`。那一刻本帧世界已画完，而
      `GameRenderer.renderLevel` 里的 hand 段排在它之后（L1279 派发 / L1281 `renderItemInHand`），
      HUD 更在整段 `renderLevel` 之后 —— 手、手机、HUD 全都还没进主靶，抓到的天然是干净画面。
    - 顺带把 `captureHidden` 那一套（字段 + `render` 里的早退 + 两处动画门 + `armHolderAutoOpen` 补丁）
      整体删掉：不再隐藏任何东西，也就没有状态需要恢复。
30. **保存成功的反馈画在手机屏内，不要用原版 `SystemToast`。**
    - 气泡画在 `PhoneOverlay.render` 内部 ⇒ 随 Overlay 一起进模型屏的离屏纹理，玩家是在
      **自己手里那台手机上**看到"保存成功"的；原版 toast 挂在屏幕角落，和手里那台手机没有视觉联系。
    - 气泡显示期间必须让 `hasActiveAnimation()` 返回 true：否则离屏出图只按 `MAX_IDLE_MS`（100ms）
      空转刷新，淡入淡出会一顿一顿。
    - 落盘与提示解耦：`PhotoLibrary.saveToGallery` 只写文件并返回文件名（失败返回 null），
      弹气泡由调用方在拿到非 null 之后做 —— 提示绝不该影响"照片已落盘"这个事实。
31. **同一个"返回"键在两个状态下不能共用一个 `exitCameraMode()`。**
    - 症状（旧版）：拍照成功页按侧键 5，本该"取消这张、回取景中重拍"，实际直接退回了聊天页。
    - 根因：`cameraBackAction()` 无条件 `exitCameraMode()`，同时承担了"取景中退出相机"与
      "已拍成取消这张"两种语义 —— 两种意图在结果上不可区分。
    - **正解**：`cameraShot != null` 时只清 `cameraShot`（留在相机里），否则才 `exitCameraMode()`。
      丢弃的那张**仍留作左下角"上一张"缩略图**（`lastCamShotKey` 不动）—— 真机相机的缩略图正是刚拍的那张。
    - 通用教训：**一个键位的语义必须按状态显式分支**。"两个状态都能用同一个动作收场"往往是假象。

### 5.6 列表与手势

32. **拖动必须"跟手"，而"跟手"在不同渲染约定下是**不同符号**。**
    - 统一约定：喂给 `mouseScrolled` 的 `scrollDelta` **正值 = 手势往下 / 往右**
      （写在 `IPhonePage.mouseScrolled` 的 javadoc 里）。它描述的是**手势方向**，不是"内容往哪跑"。
    - 各面板按自己的渲染约定换算成内容位移：
      - 消息列表：`y = msgBottom + scrollOffset` ⇒ **加**（offset 变大 = 内容往下走）
      - 表情网格：`y = pTop + row*rowH - offset` ⇒ **减**（offset 变大 = 内容往上走）
      - 图片画廊：`x = sh(12) - offset` ⇒ **减**
    - 症状：表情包面板写成"加"，于是往下拖、内容反而往上跑 —— 实机反馈"往下拖他反而下降 / 写反了"。
      **判断口诀：先把面板的渲染式子写出来，看 offset 变大时内容往哪走，再决定加还是减。**
    - ⚠ **单位要对齐**：拖动的 1 个单位 = `PhoneOverlay.DRAG_PIXELS_PER_SCROLL_UNIT`（10）像素手势。
      面板里的 `*_SPEED` 必须按这个单位折算：画廊严格跟手就是 `10`。
      `GALLERY_DRAG_SPEED` 曾按"1 像素 = 1 像素"写成 `1`，实际会**慢 10 倍**。
33. **拖动主轴要问页面，不能在 Overlay 里硬写"纵向"。**
    - `IPhonePage.dragAxisHorizontal(relX, relY)`（默认 `false` = 纵向）。坐标与 `mouseClicked` 一致：
      **内容区**相对坐标（扣掉顶部状态栏）。
    - 图库扩展栏是横向画廊，重写它返回 `true` 之后，`moveCursor` 才会拿 `dx` 当主轴、
      并用 `dx` 做"升级为滚动"的判据。不重写的话**左右拖永远不会被识别成滚动**（横着拖半天没反应）。
    - 主轴在**升级为滚动的那一刻锁定**（`dragHorizontal` 字段），中途不换轴 —— 否则手一抖，
      横向画廊会突然改用纵向分量，手感直接碎掉。
    - ⚠ 顺带删掉了 `ChatPage` 里那套自持的 `galleryDragging` / `updateGalleryDrag`：它读的是
      **真实鼠标** `mc.mouseHandler.xpos()`，而操控态下真实鼠标是被抓取的（该坐标根本不更新），
      所以那套早就死了，还可能与新手势抢状态。**一条手势通路**即可（同 §7-7 的原则）。
34. **图库顺序：`PhotoLibrary.listGallery()` 必须倒序（新 → 旧）。**
    文件名是 `photo_<毫秒时间戳>_<随机后缀>.png`，与时间同序，所以**按名字倒序 = 新的在前**。
    画廊从左往右读，最左必须是刚拍的那张；正序会让玩家每次都要横拖到最后才能看到新照片
    （实机反馈"放在最左侧的是最早的照片"）。

### 5.7 状态机

35. **「操控屏幕」态是三个条件，少一个就会静默降速（实机 bug）。**
    - 正确判据：`isScreenControl() = isVisible() && isHoldingPhone() && filteredPitch >= LOOK_DOWN_DEG`，
      再叠加调用方 `onCalculatePlayerTurn` 里的"光标不在顶栏"放行（§7-2 从第一天就是这么写的）。
    - 症状（旧版）：**不拿手机低头，视角照样被降到 0.25 倍速**，而且玩家找不到任何理由。
    - 根因有**两层**，两层都要修：
      1. `isScreenControl()` 只判了"可见 + 低头"，**漏了"手里到底有没有手机"**。
         它的 javadoc 从第一天就写着"主手确实拿着 Pro 手机"，代码里却没这一条
         —— 文档与代码对不上时，**信代码**。
      2. 更根本的是**没人把 UI 关掉**：`onHolderTick()` 只处理上升沿，
         "手机离手"（换物品 / 丢掉）从来不会收屏，于是 `phoneOpen`/`renderMode` 永远留在 MODEL、
         `isVisible()` 永远为真 —— 状态机本身在撒谎。光补第 1 条只能治表面。
    - 修法：
      - 新增 `isHoldingPhone()`，`isScreenControl()` 加上它；
      - `onHolderTick()` 补上"离手就收屏"，且必须是**查状态**（`!held && phoneOpen`）而不是**查下降沿** ——
        按 O 空手开悬浮窗时压根没有下降沿（一直是 false→false），查边沿会漏掉它。
        由此得到一条不变式：**`phoneOpen` ⇒ `isHoldingPhone()`，每个 tick 都成立**。
    - ⚠ 为什么 `isHoldingPhone()` **不能**直接复用 `ProPhoneHandRenderer.isActive()`：
      `isActive()` 还额外要求**第一人称**（模型屏只在第一人称贴得上），而"手里有没有手机"与相机类型无关
      —— 第三人称下 O 召唤的悬浮窗照样应该能用。**渲染判据 ≠ 状态判据**，
      别为了"同源"把两件不同的事合并（这里正好和 §5.6-32 的教训相反：
      该同源的地方同源，不该同源的地方分开）。
    - 已知遗留（本期不做）：第三人称 + 手持手机 + 低头三条都成立，仍会进这个态，
      而模型屏在第三人称本来就不可见 —— 那只独立光标此时"不可见但仍可动"。
      要彻底干净就得把 `renderMode` 也绑到 `isActive()`，影响面比收益大，先记着。

### 5.8 离屏靶的 alpha —— 图标柔边上那圈随昼夜变色的描边（**最贵的一个坑，值得整段读完**）

> 现象：桌面 App 图标的轮廓外有一圈**饱和度很高的描边**，白天发青/发白、黄昏偏粉、夜里变暗，
> 而且——**同一张图标，悬浮窗（O）干净，模型屏（贴模型那套）有**。
> 前后查了一整天，绕过四五条错路，最后根因只有一句话：**离屏靶的 alpha 没有钉到 255。**

36. **起手式：把两块屏幕截进同一张图，做对照。**
    "同样的代码、同样的贴图，唯一变量是走的哪条路"——这个结构一旦搭好，剩下的就是量数字。
    本次决定性的一张图就是左边悬浮窗 + 右边模型屏的并排截图。

37. **根因：离屏靶的 alpha 不是"画完就算了"，它会被交出去。**
    - **直接画在屏幕上**：主帧缓冲已经是不透明的，alpha 只参与**混合计算**，**不参与最终显示**
      —— 柔边 alpha 再低也无所谓。
    - **画进离屏靶再贴到模型上**：`PhoneScreenTarget.renderScreen` 用 `RenderType.text`（TRANSLUCENT）
      把 FBO 贴到屏幕四边形，`fragColor.a` **就是与世界混合的权重**
      ⇒ 靶里任何 `alpha < 255` 的像素，最后都要和它背后的世界**再混一次**。
      图标柔边的 alpha 本来就低，世界（天空/雾/地形）就从这些地方透出来。
    - **这就是"两条路结果不一致"的全部原因**——不是贴图、不是着色器、不是光照，是 alpha 有没有交出去。
    - **量化证据**（右=模型屏 完全可用下面这个式子拟合，左=悬浮窗则逐位精确）：
      `dst = fbo.rgb × 0.987 + 世界 × 0.013`（那个"世界"≈纯黑）。
      页面底左 `#F2E8EF`（= 源码 `ColorScheme.PEARL_PINK.pageBg` 原值）、右 `#EFE5EC`；
      图标格 66 → 65；狐狸橘 253,197,79 → 250,195,78 —— 同一个 0.987。

38. **修法：`colorMask` 只放行 alpha，把手机矩形内的 alpha 钉成 255。**
    加在 `PhoneOverlay.render()` 的**离屏分支末尾**（`renderCursor` 之后）：
    ```java
    if (external && !cameraTransparent) {   // ⚠ 相机取景时必须跳过，见下面的 38b
        RenderSystem.colorMask(false, false, false, true);   // 关 RGB 写，只留 alpha 写
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.flush();
        fillRoundedRect(graphics, phoneX, ry, phoneWidth, totalHeight,
                PhoneText.px(STATUSBAR_H), PhoneText.px(WATERMARK_H), 0xFFFFFFFF);
        graphics.flush();
        RenderSystem.colorMask(true, true, true, true);
    }
    ```
    关掉 RGB 写 ⇒ **对画面零副作用**，只是把"屏幕必须是实心的"这件事
    从"靠每一层 `fill` 自觉"改成**结构上保证**。
    另加两处防御（都是本来就该修的）：
    - `PhoneOverlay` 手机底 `0xEE111111` → `0xFF111111`（全屏唯一一处非不透明的底）；
    - `PhoneScreenTarget.renderScreen()` 画四边形前 `setShaderColor(1,1,1,1)` + `defaultBlendFunc()`。
    **通用教训：UI 只要会进离屏靶再贴出来，就必须显式声明"这块要实心"，不能指望每一层恰好 alpha=255。**

    #### 38b. **⚠⚠ 这一步把相机取景框涂黑了（真机上踩到，务必读完）**
    本步最初写成 `if (external)` —— **没有排除相机取景态**，于是踩了个和本条目的初衷完全对称的坑：

    - **取景框能透出世界，靠的正是"取景区域在离屏靶里保持 `(0,0,0,0)`"**：
      `renderScreen` 的屏幕四边形走 `RenderType.text` 的 SRC_ALPHA 混合，
      靶里 `alpha=0` ⇒ `dst = src.rgb × 0 + 世界 × 1` ⇒ 看到世界。
    - 而 alpha 钉底把手机矩形内**整个**（含取景区）alpha 写成 255 ⇒ 取景区变成
      `(0,0,0,255)` ⇒ 四边形一贴就是 **不透明纯黑**。
    - 实机症状：**相机页"直接画了个黑底"**，而**同一台手机的悬浮窗（O）完全正常** ——
      因为 `if (external)` 只在 `renderExternal`（模型屏出图）那条路上为真。
      这个"只有模型屏坏"的差异，正是 §5.8-36 说的那种"唯一变量"结构。
    - **修法**：条件加上 `&& !cameraTransparent`（`cameraTransparent` 就是上面几十行处
      `isCameraScreenTransparent()` 的结果，直接复用）。
      - 非相机页面没有任何"本该留空"的区域 ⇒ 钉底照旧生效，图标描边的修复不受影响；
      - 「已拍成页」（`ChatPage.cameraShot != null` / 相机 App 的已拍成态）
        `cameraScreenTransparent()` 本来就是 `false` ⇒ 钉底照常应用，全屏照片依然不透明。
    - **教训（和 §5.8-37 同源）**：`alpha` 在离屏靶上是**有语义的**——
      它既可能是"这一层没画满"（要补成 255），也可能是"这里本来就该透"（绝不能补）。
      **凡是要"一次性把整块矩形弄实"的收尾，都必须先问一句：这块矩形里有没有刻意的透明区。**

    完整调查记录见 [camera-black-screen-handoff.md](camera-black-screen-handoff.md) §0.5。

39. **被排除的嫌疑（都已查实，别再走一遍）**
    - **不是雾**：读 `assets/minecraft/shaders/core/position_tex_color.fsh`
      （`RenderType.text` 用的就是它）—— 全文只有 `fragColor = texture(...) * vertexColor * ColorModulator;`
      **没有 `linear_fog`**。
    - **不是光照**：`renderScreen` 里写死 `int light = LightTexture.FULL_BRIGHT;`。
    - **不是离屏靶的 clearColor**：反编译 `com/mojang/blaze3d/pipeline/RenderTarget.class` 确认
      `clear()` 用的是**它自己的** `clearChannels`（建靶时 `setClearColor(0,0,0,0)`），
      **不是** `RenderSystem` 的全局 clearColor。靶底是透明黑，不是天空色。
    - **不是贴图**：`camera.png` 在任何 alpha 上都**一个青像素都没有**
      （用宽松判据 `g>r+15 且 b>r+15` 扫过 = 0），而同一时刻悬浮窗里同一张图完全干净。
    - **不是 `ColorModulator`**：加过复位、重启客户端实测**数据一字不差**，此路不通。

40. **⚠ 试错纪律：Java 改动必须重启客户端。**
    **`F3+T` 只重载资源，不重载 class。** 改了 Java 却用 `F3+T` 验收 = 白测一轮
    （本次真的发生过一次，白白多花了一轮）。判断"客户端到底吃没吃到我刚编译的代码"：
    比 `build/classes/java/main/**/*.class` 的 mtime 与客户端进程的**启动时间**——
    ```powershell
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
      Select-Object ProcessId, CreationDate, CommandLine
    ```
    认 `net.neoforged.devlaunch.Main` 那条；**Gradle daemon 也是 java 进程，别认错**。

41. **配套：图标贴图的离线流水线（`tools/sprite_alpha.py` + `tools/import_manual_assets.py`）**
    渲染器这个坑之外，贴图本身还有**另一类**"PS 里看不见、游戏里成描边"的问题，两者容易混淆：
    - **机制**：半透明像素的 RGB 是**满饱和**的颜色、alpha 只剩 **1~12**（被蒙版/线稿底稿压住的底色）。
      PS 与普通看图器按 alpha 合成 ⇒ **肉眼绝对看不见**；而生成 mipmap 时
      **RGB 与 alpha 各做各的无权平均**（不是预乘平均）⇒ 一个 `#38D0FF @ a=1` 会把**整份饱和青**
      塞进 mip 层的 RGB，alpha 却只贡献 `1/4^k` ⇒ mip 纹素 = "高 alpha + 被污染的 RGB" ⇒ 一圈青描边。
    - **`sprite_alpha.py`**（纯标准库）：`report` 量化诊断 / `fix`（un-matte、erode、颜色外扩）/
      `matte` 漏底三联图 / `sim` 把"游戏那一步缩放"离线重放。已支持**调色板 PNG（PLTE + tRNS）**。
    - **`import_manual_assets.py`** 每个图标的流水线（⚠ **2026-10-14 已改版**）：
      **原图分辨率直上**（`ICON_TARGET = 0`，不降采样）+ **透明区 RGB 去污**（`ICON_BLEED = True`）
      + `.mcmeta(blur/clamp)`。
      - 旧版是 `抠背景（可选）→ 边缘去污 decontaminate → 预降采样 96px → 再去污 → .mcmeta`，
        依据是"手机上每格图标约 28 真实像素"。**那个前提是错的**：28 是 GUI 逻辑像素，
        设备像素还要乘 GUI Scale（1080p 下图标实占 120~200 px）→ 96px 贴图一直是**被放大**显示的，
        降采样纯属丢像素，实机表现为"锯齿/毛边"。完整复盘与量化数据见
        [desktop-icon-manage-handoff.md](desktop-icon-manage-handoff.md) §1 与
        [manual-and-starter-kit.md](manual-and-starter-kit.md) §5.4。
      - `decontaminate` 是"以 alpha≥250 的实心像素为源做 BFS，把**所有非实心像素**的 RGB
        换成最近实心色，**alpha 一个不动**"——`bleed`（只填 a==0）救不了 `a=1~12` 那一圈。
        这一步**必须留**：原图透明区 RGB 是纯白，线性过滤会把白插进轮廓（实机确认过）。
    - ⚠ **源表 `ICON_SOURCES` 必须是显式有序候选**，别改回"按修改时间挑最新"：
      需求方**换回旧版**时会静默挑到磁盘上更新的那个，日志里还看不出挑错了（本次踩过）。
    - ⚠ Piskel「8 位图片」导出**常常没有透明**（索引色 + tRNS 全 255）：透明区变成实心白，
      上机就是手机屏上一个**白方块**。脚本检测到"几乎没有透明像素"会自动 `key_background`——
      **只抠与图像四边连通的纯白**，狐狸身上的白毛/耳内侧是被深色线稿封闭的，不会被误伤。


---

## 6. 环境与工具

- **`pwsh` 工具在本机不可用**（宿主 ACL 故障：`SetNamedSecurityInfoW failed (Win32 5)`）。命令行走 IDEA MCP 的 `execute_terminal_command`。
- **IDEA MCP 偶尔 `server is disconnected`**，会自行恢复。**编译验证一律用 `build_project`**（IDEA 的 javadoc 级报错不影响编译，别被红标误导）。
- **启动方式**：用户走 IDEA 的 `runClient`，**直接吃 `build/classes`**。
  ⚠ `build/libs/huhutalk-1.0.0.jar` 可能是很久以前的、`run/mods/` 里**没有**本模组的 jar ——
  排查"改动没效果"时先确认启动方式，别在代码里空转。
  ⚠⚠ **改了 Java 必须重启客户端；`F3+T` 只重载资源、不重载 class**（§5.8-40）。
  改完贴图（`src/main/resources/**`）想省一次 Gradle，可以直接把文件同步进
  `build/resources/main/` 与 `build/classes/java/main/` 的同名路径，再 `F3+T`。
- **Python**：3.11（`python` 在 PATH 上）。读 PSD 用的 Pillow 是临时 `pip install` 装的，
  **`tools/` 里的两个脚本是纯标准库**，不依赖它。
- **看实机效果不用等眼睛**：把两块屏幕或多张候选截进同一张图，用 `tools/sprite_alpha.py`
  逐像素量（计数、均色、包围盒）。本次那个"随昼夜变色的描边"就是靠这个量出
  `dst = fbo.rgb × 0.987 + 世界 × 0.013` 才定的案（§5.8-37）。
- 日志：`run/logs/latest.log`；设置：`run/huhutalk/settings.json`（原子写入 + `.corrupt` 备份）。
- **图库产物**：`run/huhutalk/photos/*.png` —— 验收"保存照片"到底进没进 mod 图库，直接看这个目录的
  文件时间与内容即可（判断"是不是原版整屏截图"，看图片尺寸是不是 1920×1009 就一眼明白）。
  发送过的图另存 `run/huhutalk/chat_images/`。
- 验收设置（当时的实机环境）：`guiScale=4`、帧缓冲高 1009（GUI 逻辑高 252）、`phoneSize≈0.97`、`uiFontScale=1.0`。
- Blockbench：MCP 已连（5.2.1，项目 `huhutalk_phone`，`java_block`，16 cube / 5 group / 1 纹理）。
  存档在女仆资源包目录，**不是** mod 资源目录。

---

## 7. 设计原则（别违背，否则会走回头路）

1. **手机只做加法，从不做减法。** 鼠标移动照常转视角，左右键照常攻击/放置，只有玩家绑定的**手机侧键**才额外被手机接收。
2. **唯一的例外是"操控屏幕"态**，且严格限定为：**掏出手机 + 主手 Pro 手机 + 低头 ≥ 阈值**
   （三条**缺一不可**；`isScreenControl()` 三条都要查，漏掉"手持"就会变成"空手低头也降速"，见 §5.7-35）。
   任何其他情况在**同一个 tick 内**恢复原状 —— 包括"手机离手"，由 `onHolderTick` 的收屏分支兜住。
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
   （⚠ 这条已随 0.5 倍快门/1 倍确认字号重排而放宽：现在已拍成页靠**缩小左侧缩略图**腾宽度，
   不再"宁可不画"。见 §3「相机 UI 版面」。）
10. **每一个游戏内按钮/动作提示都要写出"按&lt;键名&gt;做什么"。** 键名走
    `KeyMapping.getTranslatedKeyMessage()`，与玩家绑定耦合。相机模式尤其不能省 ——
    那个界面不画光标、只能按侧键，玩家没有"试试点一下"这条路。后期打算在第一次进入时
    用整屏文字说明替代这些常驻提示，之后再简化成图形按键 + "发送"字样（**明确不在本期做**）。
11. **"照片"= 玩家取景时看到的那片世界，不是屏幕截图。** 抓图时机（`AFTER_LEVEL`）就是这条原则的实现：
    任何 HUD / 手臂 / 手机本体都不该出现在照片里。改动抓图时机前先读 §5.5-29。
12. **输入意图要直接表达（重申 §5.3-18）**：侧键 → 语义方法的映射必须是显式的；
    渲染排版可以改，逻辑正确性不能依赖它。
13. **渲染到离屏靶时，alpha 是"输出的一部分"，不是内部草稿。**
    同一段 UI 代码，画在屏幕上和画进 FBO 再贴出来，**结果可以不一样** ——
    差别就在 alpha 有没有被交出去。所以：
    - 离屏的画布要么**显式钉成全不透明**（§5.8-38 的 `colorMask` 收尾），
      要么就明确接受"这块是半透明的、应该让世界透出来"（相机取景框就是后者，那是刻意的）；
    - **不能"靠每一层 fill 恰好是 255"** —— 只要有一处不是，边缘就会漏，而且症状会伪装成
      "贴图有白边/描边"，把人往素材那条路上带。
    - 判断"这条链有没有交出 alpha"是个可以**离线验证**的问题：截一张对照图，量一下像素就够了。

