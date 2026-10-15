# 前摄（自拍）交接文档

> **阶段**：执行阶段 U1 —— 只做**契约、输入、手持、页面、文档**，**沿用**已有的
> `SelfieCameraTarget` 渲染核心（R1），本轮**没有重写**渲染核心。
> **状态**：源码已改完，**尚未实机运行验收**；构建/运行由上游 CMD 复核。
> **不要在验收前声称"实机通过"。** 本文给出的是实现事实 + 待验清单。

相关契约文档：[phone-camera-api.md](phone-camera-api.md)。

---

## 0. 一句话

两个摄影入口（聊天内相机 `ChatPage`、桌面相机 `PhoneCameraPage`）现在都支持**前摄**：

- 默认仍是后摄，后摄行为**一字未改**；
- 取景时按 **V**（可重绑定）或点页内切换按钮 → 前摄；
- 前摄取景显示的是**玩家本人 + 其身后的场景**（相机在玩家前方回看，拍到的是本人与其身后画面）的实时镜像画面，不是头像/天空/主视角截图；
- 前摄按下快门 → **确认页**（保存 / 重拍），保存进相册；
- 第一人称手持在后摄取景是"抬到眼前"，在前摄取景是**右手单手持机、屏朝玩家**，确认页回到常规地图式双手持握。

---

## 1. 键位（用户拍板）

| 操作 | 后摄 | 前摄取景 | 前摄确认页 |
|---|---|---|---|
| **手机确认键**（`PHONE_CLICK`，默认鼠标侧键 4） | 拍照存相册，留取景 | 拍照进确认页 | **保存**入相册（失败保留可重试） |
| **手机返回键**（`PHONE_BACK`，默认鼠标侧键 5） | 退出相机 | 退出相机 | **重拍**（丢当前照片回自拍取景） |
| **前/后摄切换**（`PHONE_CAMERA_FACING`，默认 V，可重绑定） | 切到前摄 | 切到后摄 | 不响应（确认页不换镜头） |
| **普通鼠标右键**（`GLFW_MOUSE_BUTTON_RIGHT` = button 1） | 原版行为 | **快门**（补充通路，取消原版） | 原版行为 |
| 长按确认键 | 无第二级语义（`handlesLongPress()` 视页面而定） | 同左 | 聊天页长按 = 存相册；桌面相机无长按 |

**⚠ 普通鼠标右键 ≠ 手机侧键 5。** 前者是 `button=1`，只在**前摄取景**、`mc.screen == null`、
事件来自本机窗口、**且第一人称主手真的举着手机（`ProPhoneHandRenderer.isActive()`）**时被接管为快门——
与实际自拍出图条件严格一致，换物品 / F5 / 手机不在手里都不会吞世界右键或发抓图；后者是玩家绑定的功能键，
语义永远是"手机返回/重拍"，走 `PhoneOverlay.onPhoneSideButton`。两者互不混淆。

**双快门防护**：若玩家把 `PHONE_CLICK` / `PHONE_BACK` 绑到了右键，右键事件交给原有侧键通路，
接管逻辑只负责取消原版右键、**不再**额外调一次动作。

**按下锁存**：前摄右键 PRESS 置 `selfieRightLatch`，RELEASE 只在该锁存置位时消费并清位。
这样"按下立刻拍照 → 下一帧进确认页（`isSelfieMode()` 变 false）"时，这次 RELEASE 仍不会漏回原版
（否则会顺带"使用一次手里的物品"）。

V 键经 `PHONE_CAMERA_FACING.consumeClick()` 在 `ClientTickEvent.Post` 里消费，
路由到 `PhoneOverlay.toggleCameraFacing()` → 当前页 `PhoneCameraHost.cameraToggleFacing()`。
相机没有光标，页内那颗按钮**点不到**，它只显示"当前前/后摄 + 切换键名"。

---

## 2. 镜像（只镜像预览）

| 画面 | 方向 |
|---|---|
| 前摄取景**预览** | **左右镜像**（自拍镜面感），由 `SelfieCameraTarget.renderPreview` / `renderPreviewFit` 处理 |
| 拍下的**照片** | **正常方向、未镜像**（`requestCapture` 走 `Screenshot.takeScreenshot(Fbo)`） |
| **确认页**里显示的照片 | 正常方向 |

页面只需调 `SelfieCameraTarget.renderPreviewFit(g, x, y, w, h)`，**固定 2:3、等比 letterbox**，
绝不把 400×600 拉伸变形。前摄取景矩形**永远 opaque 填黑**：准备中（还没出第一帧）/ 失败时
不画镜像图，而是给一句短状态「前置相机准备中…」/「前置相机不可用」（用
`SelfieCameraTarget.isReady()` / `isFailed()` 区分），**绝不把主视角或世界透出来冒充自拍**；就绪后才画镜像图。
`SelfieCameraTarget.renderPreview` 用"负的源区域尺寸"同时修正 GL 的 V 轴与自拍 U 镜像，
不改几何绕序，详见该类注释。

**透明判据只留给后摄取景**：`cameraScreenTransparent()` 在前摄/确认页都必须返回 false
（机身实心 + 正常机壳纹理，不再走擦除机壳）；后摄取景仍为 true，让游戏画面透出。因此
`PhoneOverlay.render` 里那段 `if (external && !cameraTransparent)` 的 alpha 钉底在前摄/确认页会执行——
这没问题，因为前摄取景框与确认页照片本身就是不透明的。

---

## 3. 真实渲染（R1 核心，本轮沿用，未改渲染逻辑）

前摄取景与拍照都由 `client/render/SelfieCameraTarget.java` 完成：

- 把真实玩家本人 + 其身后的完整世界，用原版整条世界渲染管线
  （`GameRenderer.renderLevel`）实时画进一张独立的竖屏离屏 FBO（400×600）；
- `CameraSelfieMixin` 把这帧相机强成第三人称正面（detached + reverse），距离约 1.35 格，自带原版碰撞裁剪；
- `GameRendererSelfieMixin` 把投影换成竖屏 2:3；`ViewportEvent.ComputeFov` 固定 70°；
- `renderHand=false`、`renderBlockOutline=false`，自拍 pass 不画手、不画描边，HUD 本就不在 `renderLevel` 内；
- 自拍 pass 里 **本机玩家手上的 PHONE_SMART 手机本体不进入画面**：这是前置镜头视角，
  不该像"对着镜子"那样把本机手机拍进去。由新增的 `ItemInHandLayerSelfieMixin` 在
  `ItemInHandLayer.renderArmWithItem` HEAD 处取消<b>仅本地玩家 + 仅 PHONE_SMART</b>这一件物品；
  **真实右臂模型姿势仍照常渲染**（`HumanoidModelSelfieMixin` 未动），其他玩家/其他物品一律不受影响；
- Fabulous 三重隔离，退出时全量恢复。

**它只在 `mc.getCameraEntity() == mc.player`（本地玩家视角）时出图**，pass 内绝不改相机实体，
因此**不会强改 F5 相机设置**，也不会影响其他玩家/第三人称。

`ClientModEvents.RenderFrame.Pre` 每帧：

```java
boolean selfie = PhoneOverlay.isSelfieMode() && ProPhoneHandRenderer.isActive()
        && PhoneOverlay.isModelScreen();
SelfieCameraTarget.setActive(selfie);
if (selfie) {
    SelfieCameraTarget.update(event.getPartialTick());  // 内核 30fps 节流 + 有请求时立即出帧
    PhoneScreenTarget.markDirty();                      // 让手机屏纹理复制到最新预览
}
```

- 只有"当前页前摄取景 **且** 第一人称主手真的举着手机 **且手机 UI 处于 MODEL 屏**"才开 pass；
  悬浮窗（`renderMode=FLOATING`）下不跑前摄，与"UI 贴在哪台手机上"严格一致；
- 第三人称、手机不在手里、手机 UI 收起 → `setActive(false)`，兑现未完成请求并作废画面，**空闲不渲染**；
- 切世界/退出 → `PhoneOverlay.resetForNewWorld()` 调 `SelfieCameraTarget.release()` 释放离屏资源；
- 自拍 pass 内的 `AFTER_LEVEL` 由 `isRendering()` 早退，**绝不把自拍 FBO 当成后摄抓图目标**。

---

## 4. 第一人称手持（局部模型姿态）

`client/render/ProPhoneHandRenderer.java`：

| 状态 | 姿态 |
|---|---|
| 非相机（平时） | 原版地图式双手持握（未改） |
| 后摄取景 / 后摄确认 | 抬到眼前的取景姿态（未改） |
| **前摄取景** | **只画右臂**（复用 `renderPhoneHand(..., RIGHT)`），手机仍由 `renderPhoneBody` + `PhoneScreenTarget.renderScreen` 绘制，屏朝玩家 |
| **前摄确认页** | `cameraUseDefaultHoldPose()==true` → 回到常规地图式双手持握（把手机"放下"看照片） |

**没有**改回原版 `ItemRenderer`：`onRenderHand` 仍 `setCanceled(true)` 并自己画 `renderPhoneBody` +
屏幕四边形，所以手机屏**不会熄灭**。前摄只是"少画一条左臂"，其余管线逐字复用。

**模型右臂**（第三人称离屏里那个"你"）由核心 `HumanoidModelSelfieMixin` 在自拍 pass 的
`HumanoidModel.setupAnim` TAIL 摆好（右臂前抬、略向中线），本轮**没有**动它。
那处注入发生在 `PlayerModel` 复制袖子之前，所以袖子/护甲会跟着动。

**前置镜头不会把本机手机本体拍入画面**：新增 `mixin/ItemInHandLayerSelfieMixin` 只在自拍 pass、
且被渲染实体是本地玩家、且物品是 `PHONE_SMART` 时，取消 `ItemInHandLayer.renderArmWithItem`
这一次第三方物品绘制。因此实机照片里不再出现"挡在胸前的手机本体"，而**模型右臂仍真实渲染**
（只是空手姿态）。该 mixin 不隐藏整个玩家、不隐藏手臂、不影响第一人称手持手机。
前摄整条链路（离屏 pass、第一人称单手持机）**只在第一人称 + 手机 MODEL 屏时运行**，
悬浮窗下不跑。

---

## 5. 页面行为

### 5.1 聊天内相机 `ChatPage`

- 保留原有 `cameraShot` / `__camshot__` / `renderCameraMode` / `renderShotPreview` / `cameraPrimaryAction` / `cameraBackAction`。
- **后摄流程完全保持**：`PhoneOverlay.requestWorldCapture`（AFTER_LEVEL 抓干净世界）。
- **前摄拍摄改走** `SelfieCameraTarget.requestCapture`，不抓主画面。
- **防重复**：`selfieShotPending` 挡住按下到回调之间的连按；
  **作废旧请求**：`selfieRequestSeq` 在切后摄 / 退出相机 / 再次按下时自增，
  回调只有序号匹配、且 `cameraActive && frontCamera && cameraShot == null` 时才落照片——
  **退出后绝不把旧照片复活**。
- 确认页仍是原来的全屏照片 + 「重拍 / 发送」；前摄标志在"重拍"时保留，退出相机时重置。
- 前摄确认页手持回到地图式（见 §4）。
- **每次状态切换（拍照回调进确认页 / 重拍 / 保存 / 切前后摄 / 进退相机）都立即 `requestRedraw()`**，
  不让模型屏的 100ms 空闲缓存把确认页或缩略图拖慢一拍。

### 5.2 桌面相机 `PhoneCameraPage`

- **后摄**不变：按下即拍照 + 自动存相册，成功不弹气泡、失败才弹。
- **仅前摄新增**确认页（整屏照片 + 「重拍 / 保存」）；保存成功弹「已保存」并回自拍预览，
  失败弹「保存失败，可重试」且**保留照片**；重拍丢当前照片回自拍取景。
- 临时照片 key 与聊天页**不同**：`__appcamselfieshot__`（聊天页是 `__camshot__`，
  桌面后摄缩略图是 `__appcamshot__`）。
- 离开页面 `onClose()` **作废在途请求 + 标脏**，且只清自己的临时状态，**不改变其它页面的照片**；
  拍照回调进确认页 / 重拍 / 保存同样立即 `requestRedraw()`。

### 5.3 切换按钮

取景中控件条右侧一颗胶囊：显示"前摄/后摄"+ `PHONE_CAMERA_FACING` 的真实键名
（`getTranslatedKeyMessage()`，默认显示 V，玩家改绑立刻跟着变）。
与居中快门**刻意分开**：布局先算快门右缘，胶囊只落在其右侧；**极窄 GUI 放不下时缩成
「前摄/后摄 + V」单行短提示并移到取景框右上角**，绝不覆盖中央快门。命中区与渲染对齐，
只是当前相机独占输入、点不到，将来放开光标即可复用。

---

## 6. 不网络同步

前摄取景、拍照、切换、保存**全部是纯客户端**：离屏 FBO、纹理、相册落盘都在本地，
不新增任何网络包，服务端与女仆侧完全不知道玩家用了前摄。
前摄照片只有玩家主动"发送"（聊天页确认后走原有图片发送通路）才会离开本机。

---

## 7. 相机性能

- 前摄离屏 pass 有 **30fps 节流**（`SelfieCameraTarget.MIN_FRAME_INTERVAL_MS`），
  有未兑现的拍照请求时当帧立即出图；
- 仅在"前摄取景 + 第一人称手持"时运行；第三人称 / 手机收起 / 真实 Screen 遮挡 / 切世界都会停
  （透明的手机输入屏不算遮挡）；
- 手机屏纹理（`PhoneScreenTarget`）在前摄帧被标脏以复制最新预览；静止时的空转刷新仍走它自己的省电策略；
- 落盘（`PhotoLibrary.saveToGallery`）是同步 PNG 编码 + 写文件，在渲染线程执行；
  单张没问题，**连拍会掉帧**——本轮没有做异步化。

---

## 8. 已知限制 / 未测项

- **第三方光影 / 后处理（shader pack）未做兼容测试**：自拍 pass 直接调 `renderLevel`，
  不经过 `GameRenderer.render` 里的 postEffect，光影下自拍观感可能与主画面不同。
  请实机逐项记录，本文件**不声称**光影下已通过。
- **前摄手持姿态是"少画左臂 + 后摄取景姿态"**，没有为"单手持机"单独调过手臂位置/角度；
  实机若觉得手和手机没对上，改 `ProPhoneHandRenderer` 里的常量，不要另起一套渲染。
- **确认页在地图式持握下**：玩家需要按平常看手机的角度低头才能看全照片，这是按需求刻意为之。
- 相机没有光标，页内按钮不可点，只能靠 V / 侧键；这是既定设计，不是缺陷。

---

## 9. 实机验收步骤（待上游执行）

1. **后摄回归**：聊天内相机、桌面相机分别按侧键 4 拍照；聊天页应进确认页（重拍/发送），
   桌面相机应"按下即存、留取景"；侧键 4 长按（聊天页）存相册。确认与改造前**完全一致**。
2. **切换**：取景中按 V，画面应在"后摄透出世界"与"前摄镜像自拍"之间切换；
   页内胶囊文字随之前/后摄变化；改绑 `key.huhutalk.camera_facing` 后键名提示立刻变化。
   前摄取景时手机体应<b>实心/正常机壳</b>（不是擦除机壳）；后摄取景才透出世界。
3. **前摄预览**：前摄下应看到"你自己 + 身后的场景"的实时镜像画面（不是头像/天空/主视角截图），
   比例 2:3，窗口大小/GUI Scale 改变时不变形、不拉伸。抬头/低头转动视角，画面应跟随。
   **刚切入前摄的头一两帧 / 出图失败时，取景框应是黑底 +「准备中/不可用」提示，绝不允许透出主视角。**
   **画面中不应出现本机手机本体**（前置镜头视角），但你的右臂模型应仍在画面里。
4. **前摄快门**：前摄取景下按普通鼠标右键，应拍照并进确认页；
   同一按只触发一次；松开不应顺带"使用物品"。再按侧键 4 亦同。
   **换物品 / 按 F5 切第三人称 / 手机不在主手时，世界右键必须恢复原版、不得发抓图。**
5. **确认页**：确认页照片方向应正常（非镜像）；侧键 4 保存 → 相册可见、弹「已保存」并回自拍预览；
   侧键 5 重拍 → 回自拍取景且仍是前摄；断开相册目录可写性后保存应保留照片可重试。
   每次状态切换后模型屏应<b>立即</b>更新，无明显 100ms 延迟。
6. **姿态**：前摄取景应是右手单手持机、屏朝玩家；确认页应是常规地图式双手持握。
7. **边界**：切世界/退出重进不崩溃、不残留上一世界画面；第三人称（F5）下不跑前摄；
   收起手机（O）后不渲染前摄、右键恢复原版。
8. **性能 / 光影**：记录主画面与前摄下的帧率；开着光影重复 3/5，记录差异（**预期可能有差异**）。
9. **多窗口 / 输入屏**：开着任意 Screen 时前摄右键不应被接管；确认侧键 5 仍是返回/重拍。
10. **极窄 GUI**：把 GUI Scale 调到很大（手机很窄），切换胶囊不得压在中央快门上；
    放不下时应缩成简短「前摄/后摄 + V」提示并移到取景框右上角。

---

## 10. 本轮改动文件清单

| 文件 | 改动 |
|---|---|
| `api/phone/camera/PhoneCameraHost.java` | 新增 4 个**全默认**方法：`isSelfieMode()` / `supportsCameraFacingSwitch()` / `cameraToggleFacing()` / `cameraUseDefaultHoldPose()` |
| `phone/PhoneOverlay.java` | 相机判据补 `phoneOpen` 守卫；新增 `isSelfieMode()` / `cameraUseDefaultHoldPose()` / `toggleCameraFacing()` / `handleSelfieRightButton()`；右键锁存；`resetForNewWorld` 释放前摄核心 |
| `event/ClientModEvents.java` | 新增 `PHONE_CAMERA_FACING`（默认 V）；tick 消费；`RenderFrame.Pre` 前摄 pass 开关；`AFTER_LEVEL` 自拍重入守卫 |
| `mixin/MouseHandlerMixin.java` | `onPress` 改 `cancellable`；前摄普通右键路由 + 取消 |
| `mixin/ItemInHandLayerSelfieMixin.java` | 新增：自拍 pass 只隐藏本地玩家手持的 PHONE_SMART，保留右臂模型 |
| `client/render/ProPhoneHandRenderer.java` | 前摄只画右臂；前摄确认页回地图式持握 |
| `phone/pages/ChatPage.java` | 前摄标志 / 切换 / 前摄抓图与预览 / 切换胶囊 / 确认页姿态 |
| `api/phone/camera/PhoneCameraPage.java` | 同上，并新增前摄确认页（保存 / 重拍）、独立临时 key |
| `assets/huhutalk/lang/{zh_cn,en_us}.json` | `key.huhutalk.camera_facing` |
| `docs/phone-camera-api.md` | 契约表 + 新增 §8 前摄 |
| `docs/selfie-camera-handoff.md` | 本文件（新增） |

**未改**：`SelfieCameraTarget` 的渲染逻辑、`CameraSelfieMixin`、`GameRendererSelfieMixin`、
`HumanoidModelSelfieMixin`、`MinecraftSelfieAccessor`、`PhoneScreenTarget`。
> 唯一对核心的改动是在 `SelfieCameraTarget` 上新增一个**只读**查询 `isFailed()`（供页面区分
> "准备中 / 不可用"），不影响任何渲染路径。

## 11. 2026-10-15 预览黑带修复

以上第10节的“未改核心”描述仅对应之前的页面整合阶段。本次另行修改了 `SelfieCameraTarget` 与 `HumanoidModelSelfieMixin`：

- 实机读取自拍纹理发现地平线 RGB 有效，但部分 alpha 为0。`Screenshot.takeScreenshot` 的 `downloadTexture(0, true)` 会强制不透明，因此保存照片正常；GUI 的 `position_tex.fsh` 则丢弃 alpha=0 的像素，导致实时预览露出黑底。
- 世界 pass 结束后，只开放 alpha 写入并清颜色附件的 alpha 为1，再恢复颜色写掩码和清屏色；不修改 RGB、深度、相机投影或地形剔除。
- 自拍右臂旋转在原偏置上加 `head.xRot/head.yRot`，随镜头俯仰、转向，仅影响本地自拍渲染。
- Java21离线完整构建通过。隔离运行目录中复现修复前黑带；修复后两个视角均240000像素全部alpha=255。右键拍摄、侧键确认保存、侧键返回重拍通过。临时测试代理仅用于设置页面和视角，未打入模组。
