# 相机黑屏 Bug · 调查交接

> **面向**：接手继续查这个 bug 的对话。
> **状态**：✅ **已定位、已修复（`PhoneOverlay` 一行条件）**。根因见 §0.5 ——
> 与「页面骨架重构」确实无关，是 **`515600e`（10-13 21:11「更新图标与手册…」）** 引入的
> **离屏靶 alpha 钉底**把取景框一并涂成了不透明黑。
> **前置阅读**：[phone-camera-api.md](phone-camera-api.md)（相机契约）、[phone-ui-toolkit-handoff.md](phone-ui-toolkit-handoff.md)（本轮重构的工作日志）。
> **本机工具注意**：`pwsh` 在本机**不可用**（沙箱给 `D:\Moddevelop` 授权时报
> `SetNamedSecurityInfoW failed (Win32 5)`）。编译校验一律走 IntelliJ MCP
> （`mcp__IDEA__build_project` / `mcp__IDEA__lint_files`），git 命令走
> `mcp__IDEA__execute_terminal_command`（IDE 终端，可用；注意它的管道行为与普通 shell 不同，
> 例如 `git ... | findstr /C:"x"` 会把 `/C:x` 当成 git 参数——请改用 `git diff -G<regex>`）。

---

## 0. 一句话状态

一次 6 批的手机页面重构刚做完（编译全绿），作者实机验收时报告：

> 「拍照页变黑屏了 —— 本来的逻辑是覆盖透明屏幕，并且切换到相机专用模型。现在直接画了个黑底」

**我把整条相机链路逐文件 diff 过了：本轮重构在相机路径上的改动全是「改名」，
且改名前后的实现逐字节相同，不可能改变行为**（证据见 §3）。
**后续复查证实了这个结论：真正的凶手是今天之前的 `515600e`**（见 §0.5）。

---

## 0.5 根因与修复（**结论**）

### 是什么

`515600e`（10-13 21:11，提交说明是「更新图标与手册…」）为了修「桌面图标柔边那圈随昼夜变色的描边」，
在 `PhoneOverlay.render()` 的离屏分支末尾加了一段**离屏靶专用收尾**（§5.8-38）：
用 `colorMask(false,false,false,true)` 关掉 RGB 写、只写 alpha，把**整个手机矩形**的 alpha 钉成 255。

```java
// PhoneOverlay.java（修复前在第 1593 行）
if (external) {                                       // ← external = 模型屏（离屏靶）
    RenderSystem.colorMask(false, false, false, true);
    ...
    fillRoundedRect(graphics, phoneX, ry, phoneWidth, totalHeight,
            PhoneText.px(STATUSBAR_H), PhoneText.px(WATERMARK_H), 0xFFFFFFFF);   // alpha=255，铺满整机
    ...
}
```

**它没有排除相机取景态。** 而取景框能透出世界，靠的恰恰是
「取景区域在离屏靶里保持 `(0,0,0,0)`」：

| 步骤 | 取景区的 RGBA |
|---|---|
| 离屏靶 `clear()`（`PhoneScreenTarget:259` 是 `setClearColor(0,0,0,0)`） | `(0,0,0,0)` |
| 手机底 `0x00111111`（相机态，`alpha=0` ⇒ 混合等于什么都没写，`PhoneOverlay:1489`） | `(0,0,0,0)` |
| `PhoneCameraPage.render` 取景框**不铺任何底**（`PhoneCameraPage:178-197` 只画四角线 + 准星） | `(0,0,0,0)` |
| **alpha 钉底（`PhoneOverlay:1593`）** | **`(0,0,0,255)` ← 变黑** |
| 屏幕四边形 `emitQuad` 用 `setColor(-1)` ⇒ `src.a = 1`，走 `RenderType.text` 的 SRC_ALPHA 混合 | `dst = src.rgb × 1 + 世界 × 0 = 纯黑` |

所以实机表现就是作者那句话：**「本来的逻辑是覆盖透明屏幕…现在直接画了个黑底」**
—— 黑底不是页面画的，是这块 alpha 钉底把「本该透明」的像素变成了不透明黑。

### 为什么「只有模型屏黑」而悬浮窗正常

`if (external)` 只在 `PhoneOverlay.renderExternal`（模型屏出图）那条路上为真。
悬浮窗直接画在不透明主帧缓冲上，alpha 不参与最终显示，也就没有这一步。

### 为什么「今天才出现」

不是今天。`git log -S "colorMask(false, false, false, true)"` 指向唯一的引入提交 `515600e`，
而**相机 App 是 10-05 就做完的**（`92cf6ba`）。相机在 10-13 21:11 之前是好的，
之后没人再开过相机，直到本轮重构做完才顺带验收 —— 于是看起来像"重构搞坏的"。
`4519262` 只动了三张图标 png + 文档（`git show --stat 4519262`），与相机无关。

### 修法（已落地）

```java
// PhoneOverlay.java
if (external && !cameraTransparent) {     // ← 相机取景时必须整段跳过
```

`cameraTransparent = isCameraScreenTransparent()` 这一行本来就在上面（第 1479 行），
直接用即可 —— **不需要新增字段、不需要改判据链**。
非相机页面没有任何「本该留空」的区域，alpha 钉底照旧生效，图标描边的修复不受影响；
「已拍成页」（`ChatPage.cameraShot != null` / 相机 App 的已拍成态）时
`cameraScreenTransparent()` 本来就是 `false`，钉底照常应用，全屏照片依旧不透明。

**编译状态**：`build_project` → `{"isSuccess":true,"problems":[]}`。
**待作者实机验收**：相机 App 与聊天内相机两条路都要看（判据不同，见 §4③）。

---

## 1. 仓库状态（接手时先核对）

主模组：`D:\Moddevelop\MDK-1.21.1-ModDevGradle-main`，分支 `main`。

```
b4cc604 重构主模组p2      ← 作者提交，含本轮批次 0~3
a348762 重构主模组p1      ← 作者提交，含本轮批次 0~3
4519262 ...              ← 重构之前的最后一个作者提交（★ 回归测试的基线）
515600e 更新图标与手册，试了一大堆办法秀图标，没成，现价识图工具选择，修了图标和视图页面的bug
```

**未提交的工作区改动**（都属于本轮批次 4~6）：

| 文件 | 内容 |
|---|---|
| `phone/pages/ManualPage.java` | 批次 4：迁到 `PhonePage`（对外 API 四符号未变） |
| `phone/pages/FileTransferPage.java` | 批次 4：迁到 `PhonePage` + 两个 `PhoneGrid` |
| `phone/pages/MainPage.java` | 批次 4：只换 helper |
| `phone/pages/ChatPage.java` | 批次 5：`UiFont`→`PhoneText`（~150 处）、删两个 `drawScaledText` 副本 |
| `phone/pages/GalleryPage.java`、`GalleryDeleteButton.java` | 批次 5 收尾：`UiFont`→`PhoneText` |
| `api/phone/camera/PhoneCameraPage.java` | 批次 5 收尾：`UiFont`→`PhoneText`（33 行，纯改名） |
| `phone/PhoneOverlay.java` | 批次 6：`UiFont`→`PhoneText` + 删私有 `drawScaledText` |
| `docs/phone-page-skeleton.md`、`docs/phone-ui-toolkit-handoff.md` | 文档同步 |
| `tools/__pycache__/*.pyc` | 无关的未跟踪产物 |

**编译状态：全绿** —— `build_project` → `{"isSuccess":true,"problems":[]}`。

---

## 2. 本轮重构做了什么（与相机无关，但先知道背景）

6 个批次把硬编码页面改用公开 API（`PhonePage`/`PhoneText`/`PhoneShapes`/`PhoneList`/`PhoneGrid`/`PhoneRow`）：

| 批次 | 内容 | 状态 |
|---|---|---|
| 0 | 扩公开 API：`PhoneText` 的 `FormattedCharSequence` 重载、`PhoneList.itemHeight(int)/invalidateLayout/separatorColor(int)`、新增 `PhoneGrid`/`PhoneLayout`、`PhonePage.bottomInset`；**顶栏统一到内置页真实的 17px** | ✅ 已提交 |
| 1 | 删死代码 `MaidListPage`/`MaidSettingsPage`（−428 行）；`ComingSoonPage`/`PhoneCasePickerPage` 迁骨架；`UiShapes`→`PhoneShapes`（11 文件 ≈60 处） | ✅ 已提交 |
| 2 | `SettingsPage` → `PhonePage`+`PhoneRow`+四种控件；新增 `PhoneRow.rowHeight(int)` 统一行高（28px） | ✅ 已提交 |
| 3 | `MessageListPage`/`ContactListPage`（可变行高）/`MaidInfoPage`；抽出 `PlayerHeader` | ✅ 已提交 |
| 4 | `ManualPage`/`FileTransferPage`/`MainPage` | ⬜ 未提交 |
| 5 | `ChatPage` 换 helper；`GalleryPage`/`GalleryDeleteButton`/`PhoneCameraPage` 的 `UiFont` 清零 | ⬜ 未提交 |
| 6 | `PhoneOverlay` 私有 `drawScaledText` 删除 + `UiFont`→`PhoneText` | ⬜ 未提交 |

**尚未实机验收**（本机不能跑游戏）：顶栏 17px 观感、设置页 28px 行高与滑块拖动、
联系人页分组折叠后命中、手册三级返回与滚动记忆、传文件页两个网格、聊天页行为不变。
→ 详见 [phone-ui-toolkit-handoff.md](phone-ui-toolkit-handoff.md) §1.18。

---

## 3. 已完成的调查与证据（**结论：重构不是原因**）

### 3.1 相机链路的全部改动都是「改名」

用 `git diff` 逐条核对，**没有一处语义变化**：

```
# 已提交的 p1/p2（相机路径只动了这三个文件）
git --no-pager diff --stat 4519262 b4cc604
  src/main/java/com/huhutalk/phone/PhoneOverlay.java          |  12 +-   ← UiShapes. → PhoneShapes. 改名
  src/main/java/com/huhutalk/phone/pages/ChatPage.java        |  42 +-   ← 同上（21 处调用点）
  src/main/java/com/huhutalk/client/theme/PhoneCaseRenderer.java | 6 +- ← 同上（2 处调用点）
```

```
# 未提交的工作区改动（相机路径）
git --no-pager diff -G"cameraActive|cameraShot|renderCameraMode|renderViewfinder|renderShotPreview" \
    -- src/main/java/com/huhutalk/phone/pages/ChatPage.java
  （输出为空 —— ChatPage 的相机代码一行都没动）
```

`PhoneOverlay` 与 `PhoneCameraPage` 的未提交 diff 也逐行看过：全部是 `UiFont.sh/sc/uiScale` →
`PhoneText.px/scale`。

**关键**：`PhoneText` 里这两个方法就是转发，**逐字节等价**（`api/phone/ui/PhoneText.java`）：

```java
public static float scale(float relative) { return UiFont.sc(relative); }   // 第 28 行
public static int px(int pixels)          { return UiFont.sh(pixels); }     // 第 33 行
```

所以「`UiFont.*` → `PhoneText.*`」是**零行为变化**的改名。
`UiShapes` 同理：它是个纯转发壳（`@Deprecated`），`UiShapes.fillRoundedRect*` 的方法体就是
`PhoneShapes.fillRoundedRect*(...)`。

### 3.2 相机判定链本身完好

逐处读过，全部原样：

| 位置 | 内容 |
|---|---|
| `api/phone/camera/PhoneCameraHost.java:86-113` | 三个默认判据 `cameraScreenTransparent()` / `cameraSideKeysActive()` / `cameraInputExclusive()` 默认都 `= isCameraMode()` |
| `api/phone/camera/PhoneCameraPage.java:82-84` | `isCameraMode()` 恒 `true`（整页取景）→ 默认 `cameraScreenTransparent()` 也恒 `true` |
| `phone/PhoneOverlay.java:889-891` | `isCameraScreenTransparent() = getCurrentPage() instanceof PhoneCameraHost h && h.cameraScreenTransparent()` |
| `phone/PhoneOverlay.java:1479` | `boolean cameraTransparent = isCameraScreenTransparent();` |
| `phone/PhoneOverlay.java:1488-1489` | 手机体填充 `cameraTransparent ? 0x00111111 : 0xFF111111` ← **能画出"黑底"的正是这个 `0xFF111111`** |
| `client/render/ProPhoneHandRenderer.java:394-401` | `stackForMode`：`isCameraScreenTransparent()` 为真才换成 `HuhuTalkItems.PHONE_CAMERA`（相机专用模型） |
| `phone/pages/ChatPage.java:270,280` | `isCameraMode() = cameraActive`；`cameraScreenTransparent() = cameraActive && cameraShot == null` |

### 3.3 页面自身也没有铺黑底

| 位置 | 内容 |
|---|---|
| `api/phone/camera/PhoneCameraPage.java:155-205` | 只画顶栏（`0xFFDE7E7E`）+ 四角标记 + 准星，**取景框"不铺任何底色"**；唯一允许的填充是控件区 `graphics.fill(..., 0x88000000)`（第 205 行，半透明，只覆盖底部控件带） |
| `phone/pages/ChatPage.java:1045-1048` | `if (cameraActive) { renderCameraMode(...); return; }` —— **在铺页面底色之前就 return 了**（页面底色在第 1051 行） |
| `phone/pages/ChatPage.java:1855-1884` | `renderCameraMode`：只画顶栏，然后分派到 `renderShotPreview` / `renderViewfinder` |
| `client/theme/PhoneCaseRenderer.java:41-68` | 手机壳画的是**镂空外框**（`fillRoundedRectFrame(Tiled)`，hole = 屏幕内容区），不盖屏幕 |

### 3.4 已被**排除**的假设

| 假设 | 为什么排除 |
|---|---|
| 相机 App 的**启动动画色块**卡住（`#424242` 深灰铺满 = "黑底"） | `CameraAppRegistration.java:63` 确实开了 `.launchAnimation(true)`、`ICON_COLOR = 0xFF424242`（第 43 行）；**但** `PhoneOverlay.hasActiveAnimation()`（第 1628-1640 行）**包含 `launchActive`**，所以动画期间模型屏逐帧重绘；且该分支在 `raw >= 1f` 时把 `launchActive = false`（`PhoneOverlay.java:1513-1516`）。另外 `PhoneScreenTarget.MAX_IDLE_MS = 100`（第 132 行）保证**任何情况下都不会永久冻结**一帧 |
| 手机壳挡住了屏幕 | 见 §3.3，是镂空外框 |
| 相机页没被画出来 | 启动动画分支里也调用了 `renderPageTranslated(graphics, page, ...)` |

---

## 4. 当时的怀疑方向（**已收敛，仅供归档**）

### ① 离屏靶 → 模型屏幕面的 **RenderType 失去 alpha 混合**（✅ 就是这个方向）
取景框之所以能透出世界，靠的是离屏靶在取景框区域**保留 `(0,0,0,0)` 透明像素**，再贴到
3D 模型屏幕面上做 alpha 混合。**如果透明像素被"补成不透明"，就会被合成为纯黑**
—— 与作者描述的"直接画了个黑底"完全吻合。

> **实际命中的是同一个方向的另一种写法**：不是 RenderType 丢了混合，
> 而是 **`515600e` 主动把离屏靶的 alpha 钉成了 255**，把透明像素从 `(0,0,0,0)`
> 变成了 `(0,0,0,255)`。细节与证据见 **§0.5**。
> `PhoneScreenTarget.renderScreen` 本身没问题（`515600e` 在那里加的
> `setShaderColor(1,1,1,1)` + `enableBlend/defaultBlendFunc` 反而是**修好**混合的）。

### ② 相机专用模型 / 擦除机壳没生效（已排除）
`git diff --stat 515600e HEAD` 里**没有任何模型/贴图资源改动**（只有三张 App 图标 png），
`HuhuTalkItems.PHONE_CAMERA` / `phone_camera.json` / `phone_camera_2.png` 全部原样。
且该假设无法解释"只有模型屏黑、悬浮窗正常"这两条路的差异。

### ③ 现象在**聊天内相机**而不是相机 App（已排除）
两者的判据不同：相机 App 恒透明；聊天内是 `cameraActive && cameraShot == null`。
但两条路**都**会走 `external` 分支 ⇒ **都会被同一块 alpha 钉底涂黑**，
所以两条路当时应该都是黑的。§0.5 已逐行核对判据链完好
（`PhoneCameraPage.isCameraMode() ≡ true` ⇒ `cameraScreenTransparent() ≡ true`）。

---

## 5. 当时的下一步计划（**已完成 / 已作废**）

> 下面的二分实验**不需要再做了**：根因已由 §0.5 的代码与历史证据定案。
> 保留原文只为记录当时的推理路径。

### 第 0 步：先问清楚现象（省时间）
1. 是**桌面「相机」App**，还是**聊天页里的 📷**？
2. 黑屏是**一直黑**，还是闪一下就恢复？
3. 是不是**只有拿在手上（模型屏）**才黑，悬浮窗模式下正常？

### 第 1 步（决定性实验）：二分定位是不是重构引起的
```bash
# 先把未提交的重构改动收起来
git stash push -u -m "refactor p3"      # 只 stash 源码；docs 与 __pycache__ 无所谓

# A. 回到重构之前的作者提交，跑游戏看相机
git checkout 4519262
#    → 如果这里也黑：bug 与本次重构无关，直接跳到第 3 步（去查 515600e）
#    → 如果这里正常：继续 B

# B. 回到 HEAD（含 p1/p2 两个重构提交），跑游戏
git checkout main           # 或 b4cc604
#    → 若这里黑：用 git bisect 在 4519262..b4cc604 之间定位
#      git bisect start b4cc604 4519262
#    → 若这里正常：git stash pop 恢复批次 4~6，再测（此时嫌疑只在未提交的改动里，
#      而它们已证明是纯改名 —— 那种情况下请优先怀疑环境/构建产物，而不是源码）

git stash pop
```
**我的预测**：`4519262` 上同样黑，即与重构无关。

### 第 2 步：如果确认与重构无关，去查作者自己的 `515600e`
```
git --no-pager show --stat 515600e
git --no-pager show 515600e -- src/main/java/com/huhutalk/client/render/ \
                               src/main/java/com/huhutalk/client/CameraAppRegistration.java
```
重点看：模型屏幕面的 RenderType、`PhoneScreenTarget` 的 blit 通路、图标渲染分支的改动
（提交说明提到「试了一大堆办法秀图标，没成」+「修了图标和视图页面的bug」）。

### 第 3 步：如果一定要在重构改动里找原因
`ChatPage` 的 `drawTopShadow` → `PhoneLayout.topShadow` 是本次唯一有**独立实现**的替换
（不是纯转发），已核对边界一致：
```java
// PhoneLayout.topShadow(g, x, y, w) → horizontalShadow(..., 2)
// i=0: alpha 0x16 → 0x16000000   i=1: alpha 0x0C → 0x0C000000
// 与 ChatPage 原来的 drawTopShadow 逐字节相同
```
另外它只用在消息区（`ChatPage.java:1110` 附近），与相机无关。

### 第 4 步：加诊断（如果以上都不成立）
`PhoneScreenTarget` 已有 `diag(...)` 通路（第 191-194 行）。可在 `PhoneOverlay.render` 的
相机分支打印 `cameraTransparent` / `getCurrentPage().getClass()` / `uiAnimEnabled()`，
一次性回答"透明判定到底是不是 false"。作者此前用过 `-Dhuhutalk.debugApps=true` 这类开关，
日志输出方式可参考 [phone-app-api.md](phone-app-api.md) §9。

---

## 6. 关键文件与行号索引

| 关注点 | 位置 |
|---|---|
| 相机判据接口（三个默认值） | `api/phone/camera/PhoneCameraHost.java:66-113` |
| 相机 App 页面（恒透明） | `api/phone/camera/PhoneCameraPage.java:46,82,155,178,205,259,264,273` |
| 透明判定 / 手机体填充 | `phone/PhoneOverlay.java:889,1479,1488` |
| **离屏靶 alpha 钉底（本次根因）** | `phone/PhoneOverlay.java:1580-1611`（修复前在第 1593 行；条件现为 `external && !cameraTransparent`） |
| 启动动画色块 | `phone/PhoneOverlay.java:1498-1516`；`client/CameraAppRegistration.java:43,63` |
| 活跃动画判据（含 `launchActive`） | `phone/PhoneOverlay.java:1628-1640` |
| 换相机专用模型 | `client/render/ProPhoneHandRenderer.java:394-401` |
| 离屏靶重绘策略 | `client/render/PhoneScreenTarget.java:132,144,157,197` |
| 聊天内相机 | `phone/pages/ChatPage.java:270,280,1024,1045,1855,1891` |
| 手机壳（镂空外框） | `client/theme/PhoneCaseRenderer.java:41-68` |
| `PhoneText` 的等价转发 | `api/phone/ui/PhoneText.java:28,33` |

---

## 7. 别浪费时间的地方

- **不要给离屏靶的 alpha 钉底「补条件」补出新 bug**：`PhoneOverlay` 那段
  `colorMask(false,false,false,true)` + `fillRoundedRect(..., 0xFFFFFFFF)`（§0.5）**必须**带
  `&& !cameraTransparent`。任何"把整机矩形一次性弄成实心"的做法都会吃掉取景框的透明。
  已记录到 [phone-model-screen-handoff.md](phone-model-screen-handoff.md) §5.8-38。
- 不要再怀疑 `UiFont` → `PhoneText` 的改名：`PhoneText.px/scale` 就是 `UiFont.sh/sc` 的转发，
  已用 `git diff` 逐行核对过。
- 不要再怀疑 `UiShapes` → `PhoneShapes`：前者是纯转发壳，方法体就是调用后者。
- 不要再怀疑"启动色块卡住"：`hasActiveAnimation()` 包含 `launchActive`，
  且 `MAX_IDLE_MS = 100` 保证不会永久冻帧。
- 不要去动 `PhoneOverlay` 的私有 `fillRoundedRect(topR, bottomR, ...)`：它是**上下圆角分别指定**，
  和 `PhoneShapes.fillRoundedRect`（统一半径）不是同一个图元 —— 这是本轮**刻意保留**的，
  理由写在 `phone-ui-toolkit-handoff.md` §1.16。
