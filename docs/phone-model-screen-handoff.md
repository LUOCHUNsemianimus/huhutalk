# HuhuTalk 手机「模型屏」· 工作日志与交接

> 本文是**进行中**任务的交接文档（与已完结的 [`phone-model-notes.md`](phone-model-notes.md) / [`phone-animation-handoff.md`](phone-animation-handoff.md) 区分）。
> 目标：把手机 Overlay 屏幕**直接画在玩家手持的 3D 手机模型上**，并让整块 UI 可以在模型屏上正常操作。

---

## 0. 一句话状态

**期 0 / 1a / 1b / 1c 全部完成并已实机验收；期 1 收尾（1d）已实现、待实机验收。**
当前可玩状态：**主手拿出 Pro 手机 → 模型屏自动亮起**（不再需要按 O、也不再需要低头到阈值才显示）；低头 ≥40° 后可用鼠标侧键操控，视角在其他任何情况下都不受影响；按 O 可随时把 UI 切成悬浮窗。
最新改动（待实机验收）：**光标出生点改为顶栏正中**；**点输入框不再把手持模型踢回普通物品**；**期 1 收尾三件套**（拆旧 Alt 通路 / 两种屏幕互斥 / 掏出即渲染）。

---

## 1. 已完成并验收

| 期 | 内容 | 验收结论 |
|---|---|---|
| 0 | `ProPhoneHandRenderer`：玩家第一人称双手持握（照抄原版地图姿势）+ 手机本体 | 通过；实机微调为**三轴旋转全 0、Y 偏移 +0.05** |
| 1a | 离屏帧缓冲出图 + 屏幕四边形 + dirty 机制 + 画布随 GUI Scale | 通过；修掉 `-0.5` 双重居中、绘制时机、GUI 耦合、切屏动画 |
| 1b | 独立光标 + 侧键点击/返回 + 按住拖动滚动 + 灵敏度设置 | 通过；修掉光标归一化（两屏偏移/够不到边） |
| 1c | 「操控屏幕」状态机：低头阈值 + 相机降速 + 状态可视化 | 通过；阈值由 30° 调整为 **40°**，补上**顶栏放行** |
| 1c+ | 独立光标**出生点 = 顶栏正中**（掏出手机即重置） | 待实机验收（见 §5.3-11） |
| 1c++ | **修掉「点输入框手机就没」**：手持渲染不再因 `Screen` 打开而让位 | 已编译通过，待实机验收（见 §5.2-8） |
| 1d | **期 1 收尾**：拆旧 Alt 通路 + `renderMode` 两种屏幕互斥 + 掏出即渲染 | 已编译通过，待实机验收（见 §4） |

---

## 2. 文件定位

### 本任务新增/改动

| 文件 | 职责 |
|---|---|
| `client/render/ProPhoneHandRenderer.java` | **新增**。订阅 `RenderHandEvent` 接管第一人称手持渲染：双手姿势 + 手机本体 + 挂屏幕四边形。`ENABLED=false` 一键回到原版手持。接管判据集中在 `shouldRender(mc, stack)`，**不排除 Screen 打开**（见 §5.2-8） |
| `client/render/PhoneScreenTarget.java` | **新增**。离屏帧缓冲、动态纹理注册、屏幕面矩形几何、出图驱动、dirty 判定、`DIAG` 探针 |
| `phone/PhoneOverlay.java` | 手机 UI 总控。新增：`renderExternal`（离屏视口）、独立光标全套、`isScreenControl` 状态机、`LOOK_DOWN_DEG`；**`RenderMode` 状态机 + `phoneOpen`（`isVisible()` 由两者算出）+ `onHolderTick()` 掏出即渲染** |
| `event/ClientModEvents.java` | 新增两个手机侧键位；`RenderFrameEvent.Pre` 出图 + 俯仰低通；`CalculatePlayerTurnEvent` 降速；每 tick `PhoneOverlay.onHolderTick()`。~~`RELEASE_CURSOR` 键位~~ 已于期 1 删除 |
| `mixin/MouseHandlerMixin.java` | 鼠标输入分流。期 1 后只剩**独立光标单通路**（`turnPlayer → onMouseDelta`、`onPress → onPhoneSideButton`，**都不 cancel**）；旧 Alt 的双分支已删 |
| `phone/PhoneInputScreen.java` `phone/PhoneGroupNameInputScreen.java` | 透明输入屏（`render` 全空 + `isPauseScreen()==false`，只接键盘）。期 1 改了 `onClose()`：无条件恢复 `GLFW_CURSOR_DISABLED` |
| `client/settings/HuhuTalkClientSettings.java` | 新增 `phoneCursorSensitivity`（默认 **1.2**） |
| `lang/zh_cn.json` `lang/en_us.json` | 手机键位显示名（`release_cursor` 两条随旧通路一并删除） |

### 相邻但**本任务未改动**（别混淆）

- `client/render/PhoneInHandLayer.java` —— **女仆**手上的手机渲染层（gecko 骨骼挂点）
- `mixin/GeckoLayerMaidHeldMixin.java` —— 女仆用手机时压掉右手手持物
- `init/ItemMaidPhone.java` `init/HuhuTalkItems.java` —— 物品本体（Pro = `phone_smart`）
- `client/MaidAvatarManager.java` —— 已有的离屏渲染先例（头像拍照），本任务的 FBO 写法参考了它

---

## 3. 可调常量速查（人工微调入口）

| 想调什么 | 文件 | 常量 / 键 | 当前值 |
|---|---|---|---|
| **低头进入「操控」的阈值**（只管操控，**不管亮不亮屏**） | `PhoneOverlay` | `LOOK_DOWN_DEG` | **40f** |
| 俯仰低通（消边界抖动） | `PhoneOverlay` | `PITCH_FILTER_MS` | `120f` |
| **视角降速倍率** | `ClientModEvents` | `CAMERA_TURN_SCALE` | `0.25` |
| 光标灵敏度 | `run/huhutalk/settings.json` | `phoneCursorSensitivity` | `1.2` |
| 拖动滚动阈值 / 折算 | `PhoneOverlay` | `DRAG_SCROLL_THRESHOLD` / `DRAG_PIXELS_PER_SCROLL_UNIT` | `4f` / `10.0` |
| **光标出生点**（掏出手机时的默认位置） | `PhoneOverlay` | `defaultCursorNY()` + `resetCursorToSpawn()` | X 恒 `0.5`；Y = 顶栏中点（比例换算，非固定像素） |
| 画布高占 GUI 逻辑高比例 | `PhoneScreenTarget` | `CANVAS_HEIGHT_RATIO` | `0.87f` |
| **屏幕在手机正面的矩形**（模型单位 0~16） | `PhoneScreenTarget` | `SCREEN_LEFT/RIGHT/BOTTOM/TOP/DEPTH` + `SCREEN_DEPTH_PUSH` | `6.8/9.2/5.3/9.7/8.25 + 0.01` |
| 画面镜像/颠倒/不可见 | `PhoneScreenTarget` | `FLIP_U` / `FLIP_V` / `FLIP_WINDING` | 全 `false`（另有 `DOUBLE_SIDED=true` 免疫剔除） |
| 空转刷新上限 | `PhoneScreenTarget` | `MAX_IDLE_MS` | `100L` |
| 模型屏诊断日志 | `PhoneScreenTarget` | `DIAG` | `false` |
| 手机模型大小 / 位置 / 朝向 | `ProPhoneHandRenderer` | `PHONE_SCALE` / `PHONE_OFFSET_Y` / `PHONE_OFFSET_Z` / `PHONE_ROT_*` | `3.0 / 0.05 / 0 / 全 0` |

---

## 4. 目标与剩余执行计划

### 期 1 收尾（**已完成，待实机验收**）

1. **拆除旧输入通路** ✅
   - 删 `ClientModEvents.RELEASE_CURSOR` 键位（含 `RegisterKeyMappingsEvent` 注册、`lang` 两条显示名）、`PhoneOverlay.setAltDown()`、`PhoneOverlay.updateCursorMode()`（`grabMouse/releaseMouse`）、`shouldCaptureMouse()`，以及 `MouseHandlerMixin` 里旧通路对 `turnPlayer`/`onPress`/`onScroll` 的 cancel 分支。
   - `MouseHandlerMixin` 现在只剩两条**不 cancel** 的注入：`turnPlayer → onMouseDelta`（光标动）、`onPress → onPhoneSideButton`（侧键点）。
   - `PhoneInputScreen` / `PhoneGroupNameInputScreen` 的 `onClose()` 改为无条件恢复 `GLFW_CURSOR_DISABLED`（原先按 `shouldCaptureMouse()` 分流）。
2. **两种屏幕互斥** ✅ —— 由 `renderMode` 结构性保证，不是靠各分支手写 if：
   - `render()` 里 `external ? !hasModelScreen() : !isFloatingScreen()` 是唯一分流点；
   - `PhoneScreenTarget.update` / `renderScreen` 都改判 `hasModelScreen()`；
   - **`MODEL` → `FLOATING` 的切换由 O 完成**（"O = 我就是要那块悬浮窗"，用户拍板）。不需要"允许同时显示"设置项 —— 结构上已经不可能同屏。
3. **掏出即渲染** ✅
   - `visible` 拆成 `phoneOpen`（页面栈开没开）+ `renderMode`（`MODEL` / `FLOATING` / `NONE`），`isVisible()` 由两者算出。
   - `PhoneOverlay.onHolderTick()` 检测 **主手举起 Pro 手机的上升沿** → 自动 `phoneOpen = true; renderMode = MODEL`。由 `ClientModEvents.onClientTick` 每 tick 调用。
   - ⚠ **必须存边沿**（`holderWasActive`）：若存状态，玩家按 O 收起后下一 tick 又被无条件打开，形成"关不掉"。`armHolderAutoOpen()` 供新世界/拍照恢复时重新武装。
   - **不做意图门**（用户决策）：主手被手机占着时根本没法挖矿，掏出动作本身就是足够强的意图；误触发担忧不成立。等将来做**副手持有时再重新评估** —— 副手地图式持握始终垂直于视角，低头阈值那套判据会被彻底绕开。
   - 连带：低头阈值**不再参与"亮不亮屏"**，只管「操控」（光标能动 + 相机降速）。整机呼出/收起滑动（`containerAnim`）现在只在 `FLOATING` 生效 —— 模型屏贴在手里那台模型上，整机位移没有意义。

### 期 2（画质与对齐）

- 屏幕四边形**滤波/mipmap**：目前走 `RenderType.text`，`TextureStateShard` 请求 nearest；斜视/缩小时会闪烁。`FboTexture.setFilter` 已留空实现，可在此接管
- UV 内缩 ~0.5px 防边缘渗色
- 屏幕矩形与机身边框的贴合精修

### 期 3（设置与清理）

- **设置页滑块**：光标灵敏度、降速倍率、（可选）低头阈值。SettingsPage 的行是手工排 Y 的 `computeLayout()`，插行需同步改它
- **清理 `DIAG` 探针**（目前默认关，代码还在）
- `PhoneInHandLayer` 里遗留的女仆侧诊断日志（历史遗留，见 `phone-model-notes.md` §9）

### 预留 / 待办（用户提出，**明确不在本期做**）

- **锁屏（防误触的替代方案）**：一段时间不操作手机 → 屏幕上贴一层黑色底色 + 时间，表示"已锁屏"。
  - 这是**取代意图门**的方案：即拿即用不牺牲，误触由"静置后自动锁"挡住，而不是由"用之前先点一下"挡住。
  - 用户判断：大部分玩家想要即拿即用，所以这是**后期优化，先不做**。
- **副手持有手机**：副手拿着时手机始终垂直于视角（同副手地图），因此
  - 低头/抬头那套**意图与朝向判据会整体失效**（不需要再关心低头阈值）；
  - `shouldRender` / `hasModelScreen` / `LOOK_DOWN_DEG` 三者都需要重新设计，别直接复用主手这套。
- 按 O 自动把主手物品换成手机 Item（用户提过，只加一个类即可）
- **独立光标在"透明输入屏"期间冻结**（期 1 已知行为）：`turnPlayer` 只在鼠标被抓取时调用，输入屏必然释放鼠标。
  目前判定为正确行为；若将来希望打字时也能用侧键移动光标，需要另开一条不依赖 `turnPlayer` 的喂入路径。

---

## 5. 踩过的坑（**重点，重踩代价很高**）

### 5.1 坐标与几何

1. **`-0.5` 双重居中**（最难查的一个）：物品模型 baked 顶点在**原始 0~1 空间**；`ItemRenderer.render` 会做一次 `translate(-0.5,-0.5,-0.5)` 居中。我们的 pose 已逐字照抄了它 ⇒ **手写顶点的坐标不要再减 0.5**。当时按"已居中空间"给坐标，整块屏幕沿手机三条轴各平移半个模型单位（×3 缩放 ≈ 1.5 格），表现为"屏幕在游戏里、跟着模型、但整体偏出去"。
2. **MC 的 `xRot` 正值 = 低头**（`-90` 看天，`+90` 看地）。讨论"低头 30°"时若按负数写会正好反掉。
3. **屏幕矩形用模型单位 0~16**，与 `phone_3d.json` / Blockbench 里读到的数字一致；画布宽度由矩形宽高比**自动导出**，改边界不必手工同步 `CANVAS`。

### 5.2 渲染顺序与投影

4. **`MultiBufferSource.BufferSource.endBatch()` 的刷新顺序**：先刷**共享缓冲**（`RenderType.text` 走这里），再刷**固定缓冲**（`Sheets.solid`，机身走这里）。所以机身画完必须**主动 `endBatch()`**，再排屏幕四边形 —— 否则后刷的机身会盖住先刷的屏幕。
5. **屏幕四边形必须立即 `endBatch(renderType)` 刷出**。若留在共享缓冲等后续统一 flush，一旦时机落到 GUI 段（那里投影是**正交**的），屏幕就会脱离手机、钉死在屏幕某个角上。把这个坑的特征记下来：**"位置固定不随视角动" ≈ 投影/时机问题，不是坐标问题**。
6. **离屏靶尺寸与 scissor 的强耦合**：`GuiGraphics.applyScissor` 只从 `window.getHeight()`（帧缓冲像素高）+ `window.getGuiScale()` 取数 ⇒ 离屏靶**像素高必须 = 窗口帧缓冲高**、正交换算系数**必须 = GUI Scale**。满足后页面里满地的 `enableScissor` 自动正确，**一个原版方法都不用碰**（这是整个方案能"页面零改动"的根本原因）。
7. **GUI pass 的投影是 `ortho(...,1000,21000)` + 模型视图 `translate(0,0,-11000)`**，照抄；否则 z≈0 的内容被裁掉。
8. **第一人称手持渲染与 `Screen` 完全无关**（"点输入框手机就没了"的根因）。
   `GameRenderer.renderLevel` 里那段是：
   ```java
   if (this.renderHand) {
       RenderSystem.clear(256, Minecraft.ON_OSX);
       this.renderItemInHand(camera, f, matrix4f1);
   }
   ```
   而 `renderItemInHand` 的唯一门是 `isFirstPerson && !isSleeping && !options.hideGui && 非旁观`（原版 GameRenderer L951-954）——
   **没有一句和 `mc.screen` 有关**。透明输入屏（`PhoneInputScreen`：`render` 全空 + `isPauseScreen()==false`）
   既不停世界也不停手持。
   所以 `ProPhoneHandRenderer` 里那句 `if (mc.screen != null) return;` 是**自己主动让位**：
   一弹输入屏就走原版 `renderArmWithItem`，手机从"双手持握 + 模型屏"瞬间塌回普通手持物品。
   修法是删掉该早退，并把它与 `isActive()` 合成**唯一判据** `shouldRender(mc, stack)`（只判第一人称 + 主手是 Pro 手机），
   保证"离屏出图判定"与"真正接管"永远同步。**副作用是独立光标在输入屏期间冻结 —— 这是对的**：
   `MouseHandler.handleAccumulatedMovement` 只在 `isMouseGrabbed()` 时调 `turnPlayer`，而 `setScreen` 会释放鼠标，
   此时玩家的鼠标本来就该归真实 GUI 光标用。
   补充事实（别搞混）：`ClientHooks.guiLayers` 是**父级 Screen 栈**，不是 HUD 层；HUD 层是
   `GuiLayerManager`（`RegisterGuiLayersEvent` 的注册目标），只由 `Gui.render()` 驱动 —— 所以
   `PhoneOverlay.render` 在开着 Screen 时**不会**被调用，模型屏这张纹理此时完全由
   `PhoneScreenTarget.update`（`RenderFrameEvent.Pre`，与 GUI pass 无关）喂，照常逐帧更新。

### 5.3 状态与逻辑

8. **`PhoneOverlay.render` 里 `uiAnimEnabled()` 的 `else` 分支会每帧清空 `transTween/launchActive`**（这些状态是静态共享的）。离屏模式下第一支不成立 → 每帧落进 `else` → **悬浮窗和模型屏两边都没有切屏动画**。必须写成 `} else if (!uiAnimEnabled()) {`。
    ⚠ **期 1d 又踩了一次，同一个坑的变体**：当时把那支改成 `} else if (!uiAnimEnabled() || renderMode != RenderMode.FLOATING) {`，但**没有删掉分支体里那 5 行 `transTween/transFrom/transTo/launchActive/launchTween` 的清空** —— 而 `renderMode != FLOATING` 在**离屏出图那一帧恒为真**（出图时 `renderMode == MODEL`），于是又变成每帧清空，切屏动画再次消失。
    **教训**：这种"每帧清状态"的分支，改条件时必须同步审查**分支体**，别只看条件；条件放宽 = 副作用范围被放大。正确写法是**只重置容器动画**，并给「非悬浮窗形式」单独一支，见 §4 期 1d。
9. **`MouseHandler.turnPlayer` 每帧都会被调用**（鼠标没动时增量为 0）。光标喂入与标脏都必须对零增量**提前返回**，否则每帧标脏、dirty 机制直接失效。
10. **两个模式下手机逻辑尺寸不同**（模型屏画布 119×219；悬浮窗 = 屏幕逻辑高 × phoneSize ≈ 137×244）。光标若按局部像素存，会被两套矩形**交替钳制取交集** → 较大屏幕上永远够不到右边/底边。**必须归一化 0~1 存储**。
11. **相机降速的作用域必须是硬约束**：`CalculatePlayerTurnEvent` 处理器第一行判断，不在状态内就 `return` —— 完全不碰事件，既保持原版也尊重其他模组。
12. **光标出生点必须在顶栏，不能在正中**：正中出生时，玩家低头进入操控态后的第一下鼠标移动（多半是继续下划）会把视角继续压下去，等他意识到屏幕已滑出视野，第一反应是上划 —— 而这一划又被 0.25 倍速拖住，体验上是"低头就完蛋"。放在顶栏正中则**出生即处于「顶栏放行」态**：上划视角全速（玩家立刻明白屏幕被放走了），下划才进入降速操控。**把出口放在默认状态上，玩家不需要先学会怎么退出。**
    实现有两个坑：① 顶栏高度是**手机局部像素**，得知道 `phoneTotalHeight` 才能换算成归一化值，而掏出手机那一刻布局还没算、两个模式的手机高度又不同 ⇒ 必须用 `cursorResetPending` 标记，推迟到 `render()` 里 `phoneTotalHeight = totalHeight` 之后再消费（`resetCursorToSpawn()` 会先按已知尺寸算一次，避免可见首帧错位）；② 出生点用**比例**表达，两个模式各自映射，别写成固定像素。

13. **`isVisible()` 必须是**算出来的**，不能是字段**。期 1 把 `visible` 拆成 `phoneOpen`（页面栈开没开）+ `renderMode`（`MODEL`/`FLOATING`/`NONE`）之后，可见性 = `phoneOpen && renderMode != NONE`。这样「两种屏幕互斥」是**结构上成立**的：任何一处只要问 `isVisible()` 就自动正确，不需要在 20 多个早退点里手写 `&& renderMode == ...`。反过来，若保留一个 `visible` 字段再由各处手工维护，迟早会出现"悬浮窗和模型屏同时画"或"关不掉"。
14. **「掏出即渲染」必须检测上升沿，不能检测状态**。`onHolderTick()` 里比对 `holderWasActive`（上一 tick 主手是否真举着手机）：只在**从没举着变成举着**的那一刻自动打开。若写成"只要举着就 `phoneOpen = true`"，玩家按 O 收起后下一 tick 立刻被重新打开 —— 表现为**手机永远关不掉**。`armHolderAutoOpen()` 供新世界/拍照流程恢复时重新武装，否则那些流程里状态翻转会被漏掉。
15. **`Screen` 打开时 `turnPlayer` 根本不调用**（原版 `MouseHandler.handleAccumulatedMovement` 只在 `isMouseGrabbed()` 时调）。所以拆掉旧 Alt 通路之后，透明输入屏期间那只手机光标必然冻结 —— 这不是 bug，是"鼠标已交还 GUI"的正确表现。**别为此去加一条 `setScreen` 之外的喂入路径**，那会让真实光标和手机光标同时活着（旧通路的病根）。

### 5.4 API 与工具

16. **真源码 jar**：`build/moddev/artifacts/neoforge-21.1.243-sources.jar`，用 `jar!/net/minecraft/.../Foo.java` 路径读，**带行号的真源码**。
    用 `neoforge-21.1.243-merged.jar!/...class` 读反编译版会被 `… N lines truncated …` 吃掉中间段，白花好几次查询。
    - ⚠ 补充（本次踩到）：`mcp__IDEA__read_file` 对**大文件**会在 110 行左右就 `… N lines truncated …`。可靠做法是把 jar 里的单个 `.java` 抽到 `build/tmpsrc/` 再用 `grep` / `read` 看（`Expand-Archive` 不认 `.jar` 后缀，先 `Copy-Item` 成 `.zip`，再用 `System.IO.Compression.ZipFile`）。
17. **`AbstractTexture.load(ResourceManager)` 是抽象方法**，必须实现（可空实现）；**`TextureManager.register(ResourceLocation, AbstractTexture)` 返回 `void`**（`String` 重载才是返回 `ResourceLocation` 的那个，但那是给 `DynamicTexture` 用的）。
18. **日志节流必须按时间、不能按内容**：NDC 每帧都在变，按"内容相同才跳过"等于没节流 —— 上一版把 `latest.log` 刷到了近万行。
19. 两个**免 mixin** 的官方钩子（优先用它们，别急着写 mixin）：
    - `RenderHandEvent`（`ICancellableEvent`）—— 接管第一人称手持渲染
    - `CalculatePlayerTurnEvent.setMouseSensitivity` —— 改视角灵敏度
20. **原版地图的双手持握是第一人称专属**：`ItemInHandRenderer.renderArmWithItem` 里 `stack.getItem() instanceof MapItem` 是硬编码特判；`HumanoidModel.ArmPose` 里**没有** MAP，第三人称不存在对应姿势（要做得自己扩展 `ArmPose` + mixin `PlayerRenderer`）。
21. **别把 `ClientHooks.guiLayers` 当成 HUD 层**：它是 NeoForge 的**父级 Screen 栈**（`pushGuiLayer/popGuiLayer` 用的），`drawScreen` 遍历它只是为了把下层 Screen 当背景画。真正的 HUD 层是 `GuiLayerManager`（`RegisterGuiLayersEvent` 的注册目标），只由 `Gui.render()` 驱动 —— 所以 `PhoneOverlay.render` 在开着 Screen 时不会被调用。

---

## 6. 环境与工具

- **`pwsh` 工具在本机不可用**（宿主 ACL 故障：`SetNamedSecurityInfoW failed (Win32 5)`）。命令行走 IDEA MCP 的 `execute_terminal_command`。
- **IDEA MCP 偶尔 `server is disconnected`**，会自行恢复；恢复后 `mcp__IDEA__build_project` / `get_file_problems` 可正常用。**编译验证一律用 `build_project`**（IDEA 的 javadoc 级报错——例如 `{@link}` 指向 private 方法——不影响编译，别被红标误导）。
- 日志：`run/logs/latest.log`；设置：`run/huhutalk/settings.json`（原子写入 + `.corrupt` 备份）。
- 验收设置（当时的实机环境）：`guiScale=4`、帧缓冲高 1009（GUI 逻辑高 252）、`phoneSize≈0.97`、`uiFontScale=1.0`。

---

## 7. 设计原则（别违背，否则会走回头路）

1. **手机只做加法，从不做减法。** 鼠标移动照常转视角，左右键照常攻击/放置，只有玩家绑定的**手机侧键**才额外被手机接收。
2. **唯一的例外是"操控屏幕"态**，且它被严格限定为：掏出手机 + 主手 Pro 手机 + 低头 ≥ 阈值。任何其他情况在**同一个 tick 内**恢复原状。
3. **状态是俯仰角的纯函数，进出共用同一阈值（无滞回带）。** 这样玩家随时能退出、且能自然归因"为什么变慢"。边界抖动靠**时间低通**解决，不靠空间滞回。
4. **顶栏是"松手出口"**：光标推到状态栏 → 视角立刻恢复全速（光标仍可动，拉下来重新降速）。这是低头 90° 掏手机时的唯一快捷出路。
   进一步：**光标出生点就放在顶栏正中**（掏出手机即重置）—— 出口不该藏在玩家得自己试出来的一划里，默认状态就该是安全的。
5. **状态必须可视化**：光标在操控态画亮、冻结态画暗。玩家永远不该猜"为什么光标不动了"。
6. **光标活在 Overlay 自己的坐标系里** —— 因此会随 Overlay 一起进离屏纹理，悬浮窗与模型屏两边**零额外适配**。
