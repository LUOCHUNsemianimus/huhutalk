# HuhuTalk 手机「模型屏」· 工作日志与交接

> 本文是**进行中**任务的交接文档（与已完结的 [`phone-model-notes.md`](phone-model-notes.md) / [`phone-animation-handoff.md`](phone-animation-handoff.md) 区分）。
> 目标：把手机 Overlay 屏幕**直接画在玩家手持的 3D 手机模型上**，并让整块 UI 可以在模型屏上正常操作。

---

## 0. 一句话状态

**期 0 / 1a / 1b / 1c 全部完成并已实机验收；期 1 收尾（拆旧输入通路 + 两种屏幕互斥）尚未开工。**
当前可玩状态：主手拿 Pro 手机 + 按 O → 手机模型屏上显示真实 Overlay，低头 ≥40° 后可用鼠标侧键操控，视角在其他任何情况下都不受影响。

---

## 1. 已完成并验收

| 期 | 内容 | 验收结论 |
|---|---|---|
| 0 | `ProPhoneHandRenderer`：玩家第一人称双手持握（照抄原版地图姿势）+ 手机本体 | 通过；实机微调为**三轴旋转全 0、Y 偏移 +0.05** |
| 1a | 离屏帧缓冲出图 + 屏幕四边形 + dirty 机制 + 画布随 GUI Scale | 通过；修掉 `-0.5` 双重居中、绘制时机、GUI 耦合、切屏动画 |
| 1b | 独立光标 + 侧键点击/返回 + 按住拖动滚动 + 灵敏度设置 | 通过；修掉光标归一化（两屏偏移/够不到边） |
| 1c | 「操控屏幕」状态机：低头阈值 + 相机降速 + 状态可视化 | 通过；阈值由 30° 调整为 **40°**，补上**顶栏放行** |

---

## 2. 文件定位

### 本任务新增/改动

| 文件 | 职责 |
|---|---|
| `client/render/ProPhoneHandRenderer.java` | **新增**。订阅 `RenderHandEvent` 接管第一人称手持渲染：双手姿势 + 手机本体 + 挂屏幕四边形。`ENABLED=false` 一键回到原版手持 |
| `client/render/PhoneScreenTarget.java` | **新增**。离屏帧缓冲、动态纹理注册、屏幕面矩形几何、出图驱动、dirty 判定、`DIAG` 探针 |
| `phone/PhoneOverlay.java` | 手机 UI 总控。新增：`renderExternal`（离屏视口）、独立光标全套、`isScreenControl` 状态机、`LOOK_DOWN_DEG` |
| `event/ClientModEvents.java` | 新增两个手机侧键位；`RenderFrameEvent.Pre` 出图 + 俯仰低通；`CalculatePlayerTurnEvent` 降速 |
| `mixin/MouseHandlerMixin.java` | 鼠标输入**双通路分流**（旧 Alt 通路 + 新独立光标通路） |
| `client/settings/HuhuTalkClientSettings.java` | 新增 `phoneCursorSensitivity`（默认 **1.2**） |
| `lang/zh_cn.json` `lang/en_us.json` | 两个新键位的显示名 |

### 相邻但**本任务未改动**（别混淆）

- `client/render/PhoneInHandLayer.java` —— **女仆**手上的手机渲染层（gecko 骨骼挂点）
- `mixin/GeckoLayerMaidHeldMixin.java` —— 女仆用手机时压掉右手手持物
- `init/ItemMaidPhone.java` `init/HuhuTalkItems.java` —— 物品本体（Pro = `phone_smart`）
- `client/MaidAvatarManager.java` —— 已有的离屏渲染先例（头像拍照），本任务的 FBO 写法参考了它

---

## 3. 可调常量速查（人工微调入口）

| 想调什么 | 文件 | 常量 / 键 | 当前值 |
|---|---|---|---|
| **低头进入阈值** | `PhoneOverlay` | `LOOK_DOWN_DEG` | **40f** |
| 俯仰低通（消边界抖动） | `PhoneOverlay` | `PITCH_FILTER_MS` | `120f` |
| **视角降速倍率** | `ClientModEvents` | `CAMERA_TURN_SCALE` | `0.25` |
| 光标灵敏度 | `run/huhutalk/settings.json` | `phoneCursorSensitivity` | `1.2` |
| 拖动滚动阈值 / 折算 | `PhoneOverlay` | `DRAG_SCROLL_THRESHOLD` / `DRAG_PIXELS_PER_SCROLL_UNIT` | `4f` / `10.0` |
| 画布高占 GUI 逻辑高比例 | `PhoneScreenTarget` | `CANVAS_HEIGHT_RATIO` | `0.87f` |
| **屏幕在手机正面的矩形**（模型单位 0~16） | `PhoneScreenTarget` | `SCREEN_LEFT/RIGHT/BOTTOM/TOP/DEPTH` + `SCREEN_DEPTH_PUSH` | `6.8/9.2/5.3/9.7/8.25 + 0.01` |
| 画面镜像/颠倒/不可见 | `PhoneScreenTarget` | `FLIP_U` / `FLIP_V` / `FLIP_WINDING` | 全 `false`（另有 `DOUBLE_SIDED=true` 免疫剔除） |
| 空转刷新上限 | `PhoneScreenTarget` | `MAX_IDLE_MS` | `100L` |
| 模型屏诊断日志 | `PhoneScreenTarget` | `DIAG` | `false` |
| 手机模型大小 / 位置 / 朝向 | `ProPhoneHandRenderer` | `PHONE_SCALE` / `PHONE_OFFSET_Y` / `PHONE_OFFSET_Z` / `PHONE_ROT_*` | `3.0 / 0.05 / 0 / 全 0` |

---

## 4. 目标与剩余执行计划

### 期 1 收尾（下一步）

1. **拆除旧输入通路**：`ClientModEvents.RELEASE_CURSOR` 键位、`PhoneOverlay.updateCursorMode()` 的 `grabMouse/releaseMouse`、`shouldCaptureMouse()` 相关分支、`MouseHandlerMixin` 里两条旧分支。**前提：独立光标已验收（已完成）**
2. **两种屏幕互斥**：手持 Pro 手机时**模型屏优先**，O 不再召唤悬浮窗（加一个"允许同时显示"的设置项，默认关）
3. **掏出即渲染**：把 `PhoneOverlay.visible` 拆成 `appOpen` + `renderMode(FLOATING|MODEL|NONE)`，使"掏出手机"本身就等于打开
   - ⚠ 注意：当前状态机要求 `visible`，所以**必须按 O 才会触发降速**（这其实是天然的意图门）。改成"掏出即渲染"后这个门会消失，误触发率会上升 —— 需要重新评估是否加显式意图（如侧键按下才进入操控态）

### 期 2（画质与对齐）

- 屏幕四边形**滤波/mipmap**：目前走 `RenderType.text`，`TextureStateShard` 请求 nearest；斜视/缩小时会闪烁。`FboTexture.setFilter` 已留空实现，可在此接管
- UV 内缩 ~0.5px 防边缘渗色
- 屏幕矩形与机身边框的贴合精修

### 期 3（设置与清理）

- **设置页滑块**：光标灵敏度、降速倍率、（可选）低头阈值。SettingsPage 的行是手工排 Y 的 `computeLayout()`，插行需同步改它
- **清理 `DIAG` 探针**（目前默认关，代码还在）
- `PhoneInHandLayer` 里遗留的女仆侧诊断日志（历史遗留，见 `phone-model-notes.md` §9）

### 预留

- 按 O 自动把主手物品换成手机 Item（用户提过，只加一个类即可）

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

### 5.3 状态与逻辑

8. **`PhoneOverlay.render` 里 `uiAnimEnabled()` 的 `else` 分支会每帧清空 `transTween/launchActive`**（这些状态是静态共享的）。离屏模式下第一支不成立 → 每帧落进 `else` → **悬浮窗和模型屏两边都没有切屏动画**。必须写成 `} else if (!uiAnimEnabled()) {`。
9. **`MouseHandler.turnPlayer` 每帧都会被调用**（鼠标没动时增量为 0）。光标喂入与标脏都必须对零增量**提前返回**，否则每帧标脏、dirty 机制直接失效。
10. **两个模式下手机逻辑尺寸不同**（模型屏画布 119×219；悬浮窗 = 屏幕逻辑高 × phoneSize ≈ 137×244）。光标若按局部像素存，会被两套矩形**交替钳制取交集** → 较大屏幕上永远够不到右边/底边。**必须归一化 0~1 存储**。
11. **相机降速的作用域必须是硬约束**：`CalculatePlayerTurnEvent` 处理器第一行判断，不在状态内就 `return` —— 完全不碰事件，既保持原版也尊重其他模组。

### 5.4 API 与工具

12. **真源码 jar**：`build/moddev/artifacts/neoforge-21.1.243-sources.jar`，用 `jar!/net/minecraft/.../Foo.java` 路径读，**带行号的真源码**。
    用 `neoforge-21.1.243-merged.jar!/...class` 读反编译版会被 `… N lines truncated …` 吃掉中间段，白花好几次查询。
13. **`AbstractTexture.load(ResourceManager)` 是抽象方法**，必须实现（可空实现）；**`TextureManager.register(ResourceLocation, AbstractTexture)` 返回 `void`**（`String` 重载才是返回 `ResourceLocation` 的那个，但那是给 `DynamicTexture` 用的）。
14. **日志节流必须按时间、不能按内容**：NDC 每帧都在变，按"内容相同才跳过"等于没节流 —— 上一版把 `latest.log` 刷到了近万行。
15. 两个**免 mixin** 的官方钩子（优先用它们，别急着写 mixin）：
    - `RenderHandEvent`（`ICancellableEvent`）—— 接管第一人称手持渲染
    - `CalculatePlayerTurnEvent.setMouseSensitivity` —— 改视角灵敏度
16. **原版地图的双手持握是第一人称专属**：`ItemInHandRenderer.renderArmWithItem` 里 `stack.getItem() instanceof MapItem` 是硬编码特判；`HumanoidModel.ArmPose` 里**没有** MAP，第三人称不存在对应姿势（要做得自己扩展 `ArmPose` + mixin `PlayerRenderer`）。

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
5. **状态必须可视化**：光标在操控态画亮、冻结态画暗。玩家永远不该猜"为什么光标不动了"。
6. **光标活在 Overlay 自己的坐标系里** —— 因此会随 Overlay 一起进离屏纹理，悬浮窗与模型屏两边**零额外适配**。
