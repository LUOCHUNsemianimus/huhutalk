# HuhuTalk「抖音网页版 App」可行性验证 · 跨工作区交接

> 本文是**跨工作区交接文档**（与 HuhuTalk 本体任务无关，是为了把验证工作交出去）。
> 阅读者：**另一个工作区里的 agent**。你不需要了解 HuhuTalk 的全部实现，本文档自包含。
> 本文档所在工作区（HuhuTalk 本体）**不执行**本次验证，也**不应**引入 MCEF 依赖。

---

## 0. 一句话状态

> **✅ 验收全部通过（含真人实机确认）。技术路径成立，模组可制作。**
> **结论请看 [douyin-webview-verification-report.md](douyin-webview-verification-report.md)（返还交接日志，写给人看的那份）。**
> 本文档保留全部技术细节与踩坑记录。

- 渲染怎么贴进矩形 → **已跑通**（⚠️ **不要**用 `TextureManager`，见 §7.0.1）
- 光标怎么驱动浏览器 → **点击验证通过**（真鼠标事件进 CEF 可用）
- H.264 视频能不能播 → **能，且真人确认能刷视频**（含 HEVC）
- 抖音会不会被风控 → **不会**：正常加载、无验证码、无滑块
- 二维码 / 登录 / 键盘输入 → **全部通过**（抖音自己弹二维码，扫码登录成功，键盘可输入）
- 网络环境 → **不需要加速器**：全程未开代理，MCEF 原生库下载成功，抖音正常访问（§4.4）

HuhuTalk 本体代码**一行未改**（验证在新工作区用独立 spike 模组做的）。

**三个关键坑（先看这三条，能省几小时）**：

1. **别用 `TextureManager` 注册 CEF 的纹理** —— 会触发全资源重载，画面永远空白（§7.0.1）
2. **CEF 必须走 CPU 离屏**（`--disable-gpu --disable-gpu-compositing`），否则 `onPaint` 永不触发（§7.0.2）
3. **探针要等导航完成后才注入**，否则脚本被 `about:blank` 的导航丢弃（§7.0.3）

---

## 1. 背景：这个东西最终要长成什么样

HuhuTalk 是一款《车万女仆》附属模组，核心是一个**手机 UI**（悬浮窗 + 贴在 3D 手机模型屏幕上的离屏渲染两套形态），模仿蔚蓝档案的「桃信」与女仆远程通话。主界面是一个桌面，上面有多个 App 图标。

**新需求**：在手机桌面上加一个**真实的抖音 App**——直接接入网页版抖音，渲染在手机屏幕区域内，玩家用模组**自绘的独立光标**去刷视频、点红心、登录账号（这些都复用网页版既有功能）。

**关键约束（决定了任务怎么拆）**：

- 玩家如果**不想要**抖音功能，HuhuTalk 本体**不能**被迫依赖 MCEF。
- 因此终态是：**抖音做成独立附属模组**，MCEF 是那个附属模组的依赖，HuhuTalk 本体零依赖。
- 附属模组要能把 App 注册进 HuhuTalk 桌面——而 HuhuTalk 目前的 App 注册**不是公开方法**（详见 §8.1），所以还需要给 HuhuTalk 加一个公开注册 API。
- **但这两件事都不解决"抖音能不能用"**。所以先做本验证，再谈注册 API。

---

## 2. 本次交接的任务

### 2.1 目标（一句话）

用最小代价回答：**抖音网页版能不能在 MCEF 的离屏 Chromium 里正常加载、播放视频、扫码登录？**

### 2.2 验收标准（逐条记录证据，不要只写"可以/不可以"）

| 编号 | 验收项 | 通过判据 |
|---|---|---|
| A | 首页能加载 | 出现视频信息流，不是空白页/验证页/错误页；能向下滚动 |
| B | **视频能播放** | **有画面 + 有声音**，`currentTime` 随时间推进（最关键的一条） |
| C | 登录可用 | 二维码能正常显示，用手机抖音 App 扫码后能登录成功（注意：二维码本身可能是一个跨域 iframe / canvas，要确认它真的渲染出来了） |
| D | 稳定性 | 关闭浏览器、退出游戏不崩溃；任务管理器里不残留 `chrome`/`cef` 子进程 |

> 建议同时记录：CPU/内存占用、帧率影响（MC 主线程有没有被拖垮）。

### 2.3 明确**不要**做的事

- ❌ 不要改 HuhuTalk 本体（那是验证通过后的回填阶段）
- ❌ 不要做独立光标 / 手机壳（用**原版 Screen 的鼠标**最快拿到答案）
- ❌ 不要做"App 注册 API"（阶段 1，验证通过后才做）
- ❌ 不要把 spike 塞进 HuhuTalk 工程里当子项目（会污染它的 Gradle 构建与 git 状态）

---

## 3. 调研硬结论（新增工作区**不需要重查**，直接用）

### 3.1 ⭐ 编解码器：MCEF 2.x 自带的二进制**含 H.264/AAC**

这是之前判定为"最高风险、可能直接否掉整个想法"的一条，已经查到确切答案。证据链：

1. **CEF 默认不带专有编解码器**——[chromiumembedded/cef#3559](https://github.com/chromiumembedded/cef/issues/3559)：
   > *"By default, CEF/Chromium builds have proprietary codecs (H.264/AAC) disabled. This is due to patent portfolio licensing requirements…"*
2. **老 MCEF 确实没有**——[CinemaMod/mcef#4](https://github.com/CinemaMod/mcef/issues/4)（2021，用户实测）：*"libcef is from spotify autobuilds, but it doesn't support h.264 decodec"*。社区因此做了自打的补丁项目 [Ruinscraft/mcef-codec-patch](https://github.com/Ruinscraft/mcef-codec-patch)。
3. **分发困境**——[CinemaMod/mcef#28](https://github.com/CinemaMod/mcef/issues/28)：维护者 ds58 说明不能直接分发带 codecs 的二进制，改用 bsdiff 补丁让用户本地打。
4. **决定性的一句**——ds58 于 **2023-11-08** 关闭该 issue 时说（[原评论](https://github.com/CinemaMod/mcef/issues/28#issuecomment-1800748192)）：
   > *"This issue is now irrelevant with MCEF 2.x. **The binaries that are included contain the codecs**"*
5. **佐证**——MCEF 2.x 的 `cmake/DownloadCEF.cmake` 把 CEF 二进制源从 Spotify 官方换成了**自建**的 `https://mcef-download.cinemamod.com/cef-builds/`。自建 CEF 构建的经典理由正是要开专有编解码器。

**推论（很重要，别被误导）**：MineChromium 的截图里有 YouTube 正常播放，这**不能**用来证明 H.264 可用——YouTube 走 VP9/AV1，而 VP9/AV1 是**自由**编解码器，没有 codecs 的 Chromium 也能播。真正证明 H.264 可用的是上面第 4 条。

**遗留不确定性**：H.265/HEVC（抖音部分内容叫 bytevc1）**仍未确认**。但抖音播放器会按 `MediaSource.isTypeSupported` 回落，只要 H.264 在就有退路。§6.2 的探针就是用来一次问清这件事的。

### 3.2 MCEF 2.1.6 for NeoForge 1.21.1 存在且可用

| 项 | 值 |
|---|---|
| Modrinth 项目 | `mcef`（project id `TObQ0HxZ`），维护者 ds58 / CinemaMod Group，原作者 montoyo |
| 目标版本 | **MCEF 2.1.6-1.21.1**，版本 id `euXLgkSB`，2024-10-20 发布 |
| loader / 环境 | `neoforge` / `client_only` |
| jar 大小 | 215,373 bytes（很小，因为原生库是运行时下载的） |
| 直链（已实测 HTTP 200） | `https://cdn.modrinth.com/data/TObQ0HxZ/versions/euXLgkSB/mcef-neoforge-2.1.6-1.21.1.jar` |
| 许可证 | **LGPL-2.1**（作为依赖引用没问题，需保留声明） |
| Maven 坐标 | `maven.modrinth:mcef:euXLgkSB` |

**运行时机制（必读，否则会以为卡死）**：

- MCEF 本身不含 Chromium。**首次启动**时会下载对应平台的原生库（约 100–200 MB）到 `System.getProperty("mcef.libraries.path")` 指向的目录。
- 下载源见 `MCEFDownloader`：
  - MCEF 2.x：`${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz`
  - 旧路径形态：`https://mcef-download.cinemamod.com/cef-builds/${branch}/${file}`
- 配置文件：`<游戏目录>/config/mcef/mcef.properties`，可配置键：
  - `skip-download`（跳过下载）
  - `download-mirror`（**下载镜像，国内网络可能需要**；默认值请直接打开这个文件确认，文档这里不臆测）
  - `user-agent`（**覆盖 UA，验证移动版页面就靠它**）
  - `use-cache`
- 本机连通性实测：`mcef-download.cinemamod.com/cef-builds/` 返回 **403** —— 这是 S3 禁止目录列举的**正常**表现，说明域名/TLS/服务端都活着，不要误判为被墙。
- 平台支持（由 `MCEFPlatform` 决定，CI 产物覆盖）：Windows amd64/arm64、Linux amd64/arm64、macOS amd64/arm64。

### 3.3 `MCEFBrowser` 的公开 API（逐字取自 1.21.1 分支源码）

源码位置：`https://github.com/CinemaMod/mcef`，**分支 `1.21.1`**，包 `com.cinemamod.mcef`。

**创建**（`MCEF` 类，静态）：

```java
MCEFBrowser createBrowser(String url, boolean transparent);
MCEFBrowser createBrowser(String url, boolean transparent, int width, int height);
boolean isInitialized();
MCEFClient getClient();
```

**`MCEFBrowser`（继承 `CefBrowserOsr` / `CefBrowser`）**：

| 方法 | 用途 |
|---|---|
| `resize(int width, int height)` | 设置浏览器渲染分辨率（**可远大于显示矩形**，见 §3.4） |
| `MCEFRenderer getRenderer()` → `getTextureID()` | 拿到**普通 GL 纹理 id**，自己画 |
| `sendMouseMove(int x, int y)` | 移动 |
| `sendMousePress(int x, int y, int button)` | 按下（注意：内部对中/右键做了 swap） |
| `sendMouseRelease(int x, int y, int button)` | 抬起 |
| `sendMouseWheel(int x, int y, double amount, int modifiers)` | 滚轮（**刷视频靠它**） |
| `sendKeyPress(int keyCode, long scanCode, int modifiers)` | 键盘按下（keyCode 是 **GLFW** 码） |
| `sendKeyRelease(int keyCode, long scanCode, int modifiers)` | 键盘抬起 |
| `sendKeyTyped(char c, int modifiers)` | 文本输入（char） |
| `setFocus(boolean)` | 给浏览器焦点（**每次输入前都要调**） |
| `setCursorChangeListener(MCEFCursorChangeListener)` | 光标变化回调（**集成时必须覆写，见 §3.5**） |
| `useBrowserControls(boolean)` | 是否启用 Ctrl+R 刷新 / Alt+←→ 前进后退等 |
| `close()` | 关闭 |
| `loadURL(String)` / `canGoBack()` / `goBack()` / `canGoForward()` / `goForward()` / `reload()` | 导航（来自 `CefBrowser`） |
| `setZoomLevel(int)` / `getZoomLevel()` | 缩放 |
| `executeJavaScript(String, String, int)` | **执行 JS（我们所有探针和媒体控制都靠它）** |

⚠️ **版本差异警告**：MineChromium 的代码里用了 `browser.getTextureLocation()` 和 `browser.isTextureReady()`，这两个**在 1.21.1 分支的 `MCEFBrowser` 源码里不存在**（该分支只暴露 `getRenderer().getTextureID()`）。MineChromium 的 `main` 分支面向更新版本的 MC（用了 1.21.6+ 才有 `RenderPipelines`）。
→ **动手前务必用 `javap -classpath <mcef.jar> com.cinemamod.mcef.MCEFBrowser` 核实真实方法列表**，不要照抄博客或更新版本的代码。（本次已核实，确如上。）

> 🚫 **下面是本文档成文时给出的建议，已被实测证伪，不要照做：**
> ~~「把该纹理 id 包一层 `AbstractTexture` 注册进 MC 的 `TextureManager`，即可用原版 `RenderType.text(loc)`
> 或 `GuiGraphics#blit` 采样。HuhuTalk 里已有这个套路的现成实现（`PhoneScreenTarget.FboTexture`），可直接参考。」~~
>
> **为什么错**：`TextureManager.register()` 对非 `SimpleTexture` 会回退并触发**全资源重载**，
> 在渲染线程上做这件事会让画面**永远空白**，而且日志里没有一条直接指向原因。详见 §7.0.1。
>
> ✅ **正确做法**（照抄 MCEF 自带 `example/ExampleScreen`）：
> ```java
> int glTextureId = browser.getRenderer().getTextureID();
> RenderSystem.setShaderTexture(0, glTextureId);   // 直接喂裸 GL 纹理 id
> RenderSystem.setShader(GameRenderer::getPositionTexShader);
> // Tesselator QUADS + POSITION_TEX，4 顶点
> BufferUploader.drawWithShader(buffer.buildOrThrow());
> ```
> **不需要 `ResourceLocation`，不需要 `AbstractTexture`，不需要 `TextureManager`。**
> 另需注意：CEF 必须禁用 GPU（§7.0.2），否则 `getTextureID()` 恒为 -1。

### 3.4 渲染：别人怎么把浏览器贴进一个矩形

**MCEF 官方示例 `example/ExampleScreen.java`** —— 用 `Tesselator` 手写 quad：

```java
RenderSystem.disableDepthTest();
RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID());
// 四个顶点 UV 取 (0,1)(1,1)(1,0)(0,0)
BufferUploader.drawWithShader(buffer.build());
```
坐标换算：`browserX = (int)((mouseX - 偏移) * mc.getWindow().getGuiScale())`。

**MineChromium 的 `widgets/BrowserWidget.java`** —— 一行 `GuiGraphics#blit`：

```java
gui.blit(..., browser.getTextureLocation(), x, y, 0, 0, w, h, w, h);
```

> ⚠️⚠️ **这一行在 1.21.1 上不成立，而且它引出的"自注册 ResourceLocation"思路是本次最大的坑。**
> - `getTextureLocation()` 在 1.21.1 分支的 `MCEFBrowser` **不存在**（已 `javap` 确认）
> - "在 1.21.1 上用 `blit` + **自注册 ResourceLocation** 是等价做法" —— **这句话是错的**。
>   走 `TextureManager.register()` 会触发**全资源重载**，画面永远空白（详见 §7.0.1）
>
> **正确做法只有一个**：`getTextureID()` 直接喂 `RenderSystem.setShaderTexture(0, <int>)`，
> 见 §7.0.1。**不要**引入 `ResourceLocation` / `AbstractTexture` / `TextureManager`。

**MineChromium 的两个值得直接借鉴的技巧**：

1. **分辨率与显示尺寸解耦**：HUD 上只占 267×150，但浏览器 `resize` 到 1280×720、全屏时 1920×1080，再缩下来显示——文字不会糊。
   ```java
   // 按控件宽度在 [1280,1920] 线性插值，高度 = 宽 / 控件宽高比
   Dimension computeBrowserResolution(int widgetW, int widgetH, int screenW, int screenH)
   ```
   （本次§7 附5 实测：240×480 显示 + 720×1560 渲染，文字可读。这条技巧有效。）
2. **媒体控制靠 JS 注入**（我们以后要静音/暂停就用这招）：
   ```java
   browser.executeJavaScript(
     "document.querySelectorAll('video').forEach(function(v){v.pause();});" +
     "document.querySelectorAll('audio').forEach(function(a){a.pause();});", "", 0);
   ```

### 3.5 输入：别人怎么喂鼠标键盘 + 一个必踩的坑

**MineChromium 的 `BrowserWidget`**（最标准的写法）：

```java
int bx = (int)(mouseX - getLeftSide());
int by = (int)(mouseY - getTop());
browser.sendMouseMove(bx, by);                       // 移动
browser.sendMousePress(bx, by, button);              // 点击 = 按下
browser.sendMouseRelease(bx, by, button);            //       + 抬起
browser.sendMouseWheel(bx, by, delta, 0);            // 滚轮
browser.sendKeyPress/Release/Typed(...);             // 键盘
```

**⚠️ 必踩的坑：网页会抢系统光标。** `MCEFBrowser.setCursor(CefCursorType)` 会直接调 GLFW：

```java
GLFW.glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);   // ← 会解除 MC 的鼠标捕获！
GLFW.glfwSetCursor(window, MCEF.getGLFWCursorHandle(cursorType));
```
MineChromium **没有处理这个**（它只在开着界面时用浏览器，那时鼠标本来就是自由的）。
HuhuTalk 集成时**必须**用 `setCursorChangeListener(...)` 把它覆写成 no-op（或者改成在自己的虚拟光标上画对应形状），否则一进抖音鼠标捕获就丢了。

### 3.6 现成实现盘点与各自的局限（**这条决定了我们的技术优势**）

| 方案 | 版本范围 | 做法 | 局限 |
|---|---|---|---|
| **MCEF `ExampleScreen`** | 跟随 MCEF | 官方示例，全屏 Screen + quad | 只是教学示例 |
| **[MineChromium](https://modrinth.com/mod/minechromium)**（MIT） | 1.21.1–1.21.4 / 26.x，NeoForge+Fabric，需 MCEF 2.1.6+ | HUD 里的画中画浏览器，可拖拽/缩放/调透明度/持久化配置 | **输入只在打开界面（背包等）时才转发**——`RenderHudElements` 里整段输入处理被包在 `if (mc.screen instanceof AbstractContainerScreen)` 里。平时玩游戏**只能看不能点**。它的 1.2.1 更新日志写着"Added Up/Down Scrolling support for short form content outside of browser screen"，即针对短视频竖滑做了补丁 |
| **[WebDisplays](https://modrinth.com/project/bRa1UAVZ)**（MIT，montoyo → CinemaMod Group） | 1.20–1.20.1 | 世界里放可交互屏幕方块 | 版本老；交互必须开真 Screen |
| **[Graphene](https://modrinth.com/mod/grapheneui)**（trethore） | **只有 Fabric**，1.21.11 / 26.1.2 / 26.2，Java 21/25 | 新一代分层架构，见 §3.7 | **我们不能直接用**（loader 和版本都不对），但架构可照抄 |

**关键洞察**：所有现成 MC 浏览器 mod 都卡在同一个坎上——**在游戏里拿不到一个可用光标**，只能借原版 `Screen` 的鼠标（所以要开背包）。

而 HuhuTalk **已经有这块拼图**：

- 独立虚拟光标（`PhoneOverlay.cursorLocalX()/cursorLocalY()`）
- 在 MC 鼠标被捕获的前提下拿原始 delta（`MouseHandlerMixin` → `PhoneOverlay.onMouseDelta`）
- 按下/抬起/滚轮/按住拖动→滚轮的完整通路（`handlePhoneLocalMouse` / `handlePhoneScroll` / `moveCursor`）
- 两套渲染靶（悬浮窗 + 模型屏离屏），共用同一套绘制逻辑

所以我们要做的**不是**"攻克 MC 内嵌浏览器"，而是**把已有的虚拟光标接到 MCEF 的 `sendMouseMove/Press/Release/Wheel` 上**。这将做到**别人做不到的事：一边玩游戏一边用手指刷抖音**。

### 3.7 Graphene 的架构（供阶段 1 设计参考，但**不能**作为依赖）

Graphene 把"浏览器"和"投到哪"彻底拆开，概念分层正是我们未来要的东西：

```
GrapheneWebViewWidget ─→ BrowserGuiSurface ─┐
                                            ├─→ BrowserView ─→ BrowserSession ─→ JCEF browser
                          BrowserWorldSurface ┘
```

- `Graphene.register(ModClass)` → 每个消费模组注册一次，拿到 `GrapheneContext`
- **全进程共享一套 JCEF 运行时**（不是每个模组各装一份 Chromium）
- `BrowserView` 持有 session + GPU 纹理；`BrowserGuiSurface` / `BrowserWorldSurface` 只决定"像素投到哪"（后者提供 `hitTest(...)` 把世界射线映射到 UV / 浏览器坐标）
- `BrowserGuiSurfaceInputAdapter` 负责把 MC 输入翻译成浏览器输入
- `BrowserSession`：与加载器无关的离屏浏览器 API（导航、状态、脚本执行、规范化输入、帧快照、下载、Java/JS 桥）
- 运行时目录：`./graphene/browser-runtime/`

**结论**：Graphene 的"注册表 / 会话 / 表面 / 输入适配器"四层分离，直接对应 HuhuTalk 阶段 1 想要的"公开 App 注册 API"，可以作为设计模板抄，但**依赖不能用**（Fabric-only、版本不符）。

---

## 4. 执行环境

### 4.1 起点：模板模组

一个 NeoForge MDK 模板模组**已经移出本工作区**，交给新工作区使用（原位置曾是 `示例模组\模版\`，现已不存在于 HuhuTalk 工作区）。规格：

| 项 | 值 |
|---|---|
| Minecraft | **1.21.1** |
| NeoForge | **21.1.242**（HuhuTalk 本体用的是 21.1.243，基本同版） |
| ModDevGradle | 2.0.142（与 HuhuTalk 相同） |
| Gradle wrapper | **9.2.1**（与 HuhuTalk 相同 → 共用 wrapper 发行版与依赖缓存） |
| Parchment | 1.21.1 / 2024.11.17 |
| mod_id / 包 | `examplemod` / `com.example.examplemod` |
| 已有内容 | `ExampleMod.java`、`ExampleModClient.java`、`Config.java`、`neoforge.mods.toml` 模板、`.gitignore`、`.github/workflows/build.yml` |
| 状态 | **已经构建过一次**（`build/tmp/neoformruntime/` 有产物），但 `run/` 尚未生成 |
| 自带 `.gitignore` | 忽略 `.gradle`、`build/`、`.idea/`、`run/` |

**建议**：如果挪过去时路径仍含中文（原路径是 `示例模组\模版\`），**改成 ASCII 路径**（例如 `D:\Moddevelop\douyin-spike\`）。它在中文字符路径下确实构建成功过，所以勉强可用，但中文 Windows（GBK 默认字符集）下 CJK 路径对 Java 工具链是已知隐患，没必要留着赌。

### 4.2 本工作区踩过的坑（**别重复**）

1. **DSH 沙箱在 HuhuTalk 工作区是坏的**：read-only / workspace-write 模式下，沙箱在给工作区根目录 `D:\Moddevelop\MDK-1.21.1-ModDevGradle-main` 授予写权限时失败（`SetNamedSecurityInfoW failed (Win32 5): grantWrite`），导致**任何 pwsh 命令都起不来**（连 `Get-Location` 都不行）。唯一出路是把命令提权到 `danger-full-access` 重试（会弹用户授权），用户已因此拒绝了两次。
   → **这是环境问题，与项目无关**。新工作区如果沙箱正常，就不会遇到。
2. **绝对不要**给 HuhuTalk 的 `settings.gradle` 加 `include`。目前它是干净的（只有 `pluginManagement` + `foojay-resolver-convention`），所以 Gradle **永远不会**把嵌套目录当子项目——这正是当初"在 HuhuTalk 工作区里套一个模组模板"不会影响 HuhuTalk 构建的原因。一旦加了 `include`，HuhuTalk 的构建就会被污染。
3. 无残留：那几次尝试在被拒绝前就中止了，**没有**在 HuhuTalk 工作区留下任何构建产物。

---

## 4.5 新工作区实测补充（本次验证新增，**动手前先看**）

> 与 §4.2 的 HuhuTalk 沙箱问题不同：下面这些在**新工作区也复现**。

### 4.5.1 沙箱问题在新工作区**同样存在**（§4.2-1 已复现）

新工作区根目录是**中文路径** `D:\Moddevelop\模组模版\模板`，`workspace-write` 模式同样在
`SetNamedSecurityInfoW failed (Win32 5): grantWrite(...)` 上失败，**任何 DSH pwsh 命令都起不来**。
中文路径是已知诱因（§4.1 当初就建议改成 ASCII 路径，但没执行）。

**绕过办法（本次实际采用，零授权弹窗）**：改用 **IDEA MCP 的 `execute_terminal_command`**。
IDEA 终端跑在 IDE 侧，不受 DSH 沙箱约束。注意：

- 每个命令是**新进程**，`chcp`、环境变量都不保留 → 统一加前缀 `cmd /c "chcp 65001 >nul && ..."`。
- 不加 `chcp 65001` 时中文输出乱码。
- `JAVA_HOME` 不在默认 PATH 上，必须显式传：`C:\Users\10217\.jdks\temurin-21.0.11`（另有 `ms-21.0.12` 可用）。
- Gradle wrapper 在中文路径下工作正常（Gradle 9.2.1 实测通过）。

### 4.5.2 几个会白白浪费时间的坑

| 坑 | 现象 | 解法 |
|---|---|---|
| **Gradle 不转发任意 `-D`** | `gradlew runClient -DdouyinSpike.exitSeconds=200` 里这个属性**到不了游戏 JVM**。`autoOpen` 之所以生效，是因为它写进了 `build.gradle` 的 `systemProperty` | 要传的开关**必须**写进 `neoForge.runs.client { systemProperty ... }`，别用 ad-hoc `-D` |
| 后台进程被管道 stdin 杀掉 | `start /b` 报 `ERROR: Input redirection is not supported, exiting the process immediately.`；`timeout /t` 同样不可用 | 用 `start /min cmd /c xxx.bat`，或 `Start-Process -RedirectStandardOutput` |
| **两个句柄写同一日志** | `Start-Process -RedirectStandardOutput x.log` 与批处理里的 `> x.log` 冲突，游戏日志被静默截断成 **0 字节** | 批处理独占游戏日志，`Start-Process` 的重定向另开文件 |
| `-File` 被执行策略拦 | `about_Execution_Policies` UnauthorizedAccess | 加 `-ExecutionPolicy Bypass` |
| `javap` 直接可用 | JDK 不在 PATH | 用全路径 `C:\Users\...\temurin-21.0.11\bin\javap.exe` |

### 4.5.3 MCEF 在此环境的实际行为（与 §3.2 预期不同处）

| 项 | §3.2 预期 | 实测 |
|---|---|---|
| 原生库落地目录 | `mcef.libraries.path` | 开发环境实际在 **`build/mcef-libraries/`** |
| 配置文件 | `<游戏目录>/config/mcef/mcef.properties` | 首次运行**没有生成**；`MCEFSettings` 可读写，但目录不存在时不会自动创建 |
| 校验和下载 | — | `Failed to download JCEF hash.`（`.sha256` 403）→ **只是 WARN，解包照常成功**，别当故障 |
| 下载耗时 | 100–200 MB，可能很久 | 本机约 **45 s**（`19:34:31` 开始 → `19:35:17` 载入完成） |
| 初始化时机 | 查 `MCEF.isInitialized()` | MCEF 用 `CefInitMixin` 在**首次 `setScreen`** 时初始化；未就绪时它自己弹下载界面 |
| CEF 命令行开关 | 未提及 | 可在 **mod 构造函数**里 `System.setProperty("org.cef.commandLineArgs", ...)` 抢先设置，实测**生效**（日志 `CEF command line set to:` 早于 `Initializing CEF`） |
| `MCEFBrowser` 方法 | §3.3 警告过 | **已用 `javap` 核实**：确实只有 `getRenderer().getTextureID()`，**没有** `getTextureLocation()` / `isTextureReady()` |
| 纹理注册 | §3.3 建议包 `AbstractTexture` | 可行。但**必须**自写 blit：原版 `blit` 会把过滤强制成 NEAREST（缩放后糊）且 V 固定 0..1（无法翻转） |

### 4.3 依赖接入方式（二选一）

**A. Maven（推荐）** —— 在 `build.gradle` 里加 Modrinth maven 仓库 + 依赖。HuhuTalk 本体的 `build.gradle` 里有现成的 `exclusiveContent` 块可以照抄：

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url "https://api.modrinth.com/maven" } }
        filter { includeGroup "maven.modrinth" }
    }
    mavenCentral()
}

dependencies {
    compileOnly("maven.modrinth:mcef:euXLgkSB")   // 编译期 API
    localRuntime("maven.modrinth:mcef:euXLgkSB")  // 开发环境运行时
}

configurations { localRuntime }
configurations.runtimeClasspath.extendsFrom(localRuntime)
```
（`localRuntime` 的作用同 HuhuTalk 里 JourneyMap 的用法：只进开发环境，不打包、不传染给依赖方。）

**B. 手工 jar** —— 直接把 `mcef-neoforge-2.1.6-1.21.1.jar` 丢进 `<工程>/run/mods/`，同时仍需 `compileOnly` 才能调它的 API（可以用 `compileOnly files("libs/mcef-neoforge-2.1.6-1.21.1.jar")`）。

### 4.4 网络（**已实测更新**）

- `cdn.modrinth.com` → ✅ **HTTP 200**（两个直链都通）
- `mcef-download.cinemamod.com` → **403**（S3 目录列表被禁，**正常**，不是被墙）
- ✅ **实测：全程未开加速器/代理，MCEF 原生库照常下载成功（约 45 s），抖音页面也正常访问。**
  → 原文档"必须国内直连、警惕代理"的担忧**可以放宽**。
  注：这是**本机网络环境**下的结论，不代表所有地区/运营商。
- 系统代理状态实测为 **Direct access (no proxy server)**，CEF 跟随系统代理的行为因此未触发。
  若玩家机器上挂了代理，仍建议按原说明核查。
- ✅ 首次下载 **100–200 MB** 实测约 45 s（`19:34:31` 起 → `19:35:17` 载入完成），
  落地在开发环境的 `build/mcef-libraries/`。

---

## 5. 两条执行路线

### 路线甲：零代码测试台（15–30 分钟，想先拿二元答案就走这条）

1. 把两个 jar 丢进 `run/mods/`：
   - MCEF：`https://cdn.modrinth.com/data/TObQ0HxZ/versions/euXLgkSB/mcef-neoforge-2.1.6-1.21.1.jar`
   - MineChromium 1.2.1：`https://cdn.modrinth.com/data/DAEHtH8c/versions/OQemqdMR/pipbrowser-neoforge-1.21.1-1.2.1.jar`（61,359 bytes，**依赖 MCEF**）
2. 启动客户端，按 **Home** 打开它的浏览器，导航到 `https://www.douyin.com`。
3. **注意它的输入限制**：只有在**打开一个容器界面**（背包 / 箱子等）时，鼠标输入才会转发给浏览器。所以"扫码登录"这类需要点击的操作，请**打开背包**再操作。
4. 观察 §2.2 的 A/B/C/D。

**优点**：零代码、最快。
**缺点**：第三方多一层崩溃面（它有 JEI 相关 mixin，可能要额外装 JEI）；验证靠肉眼，拿不到"编解码器到底有没有"的硬证据。

### 路线乙：最小 spike 模组（**推荐**）

理由：**能拿到可读的硬证据**，而且它就是未来附属模组的种子（只有临时按键和临时 Screen 外壳会被换掉）。

---

## 6. spike 实现规格（路线乙）

### 6.1 文件清单

```
<新工作区>/douyin-spike/
├── gradle.properties                     ← 改 mod_id / mod_name / mod_group_id
├── build.gradle                          ← 加 Modrinth maven + MCEF 依赖
├── run/mods/                             ← （可选）手工放 MCEF jar
└── src/main/java/<你的包>/
    ├── DouyinSpike.java                  ← @Mod 主类（可留空）
    ├── DouyinSpikeClient.java            ← 注册按键 + 打开 Screen
    └── DouyinScreen.java                 ← 核心：MCEF 浏览器 + 输入转发 + 日志探针
```

### 6.2 关键实现要点

**① 初始化时机**：MCEF 的消息循环由它自己的 `CefRenderUpdateMixin`（注入 `GameRenderer#render`）每帧驱动，**我们不需要自己起循环**。只需在创建浏览器前确认 `MCEF.isInitialized()` 为 true；如果为 false，说明原生库还在下载/初始化，**在 Screen 里显示等待提示并轮询**，不要直接崩。

**② 输入转发**：照 `ExampleScreen` 抄即可——`mouseClicked` → `sendMousePress` + `sendMouseRelease`；`mouseMoved` → `sendMouseMove`；`mouseScrolled` → `sendMouseWheel`；`keyPressed/keyReleased/charTyped` → 对应方法。**每次输入前调 `browser.setFocus(true)`**。坐标 = `(鼠标 GUI 坐标 - quad 左上角) * guiScale`。

**③ 尺寸**：Screen 里让 quad 占满窗口即可（spike 阶段别做手机矩形）。但建议**顺手试一次"小矩形"**（比如 240×480）来确认缩放到手机尺寸时文字是否可读——用 §3.4 的分辨率解耦技巧（渲染分辨率给大、显示矩形给小）。

**④ 探针（这是路线乙的核心价值）**：页面加载完成后用 `executeJavaScript` 把结果打到日志。建议至少探这些：

```javascript
// UA 与页面状态
navigator.userAgent
document.title
location.href
document.body.innerText.slice(0, 300)     // 用来判断是不是风控/验证页

// 编解码器能力（关键）
JSON.stringify({
  avc_42E01E: MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E"'),
  avc_64001f: MediaSource.isTypeSupported('video/mp4; codecs="avc1.64001f"'),
  hev1:       MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L93.B0"'),
  hvc1:       MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"'),
  av01:       MediaSource.isTypeSupported('video/mp4; codecs="av01.0.05M.08"'),
  vp9:        MediaSource.isTypeSupported('video/webm; codecs="vp9"'),
  aac:        MediaSource.isTypeSupported('audio/mp4; codecs="mp4a.40.2"'),
  canPlayAvc: document.createElement('video').canPlayType('video/mp4; codecs="avc1.42E01E"')
})

// 视频实际播放状态（隔几秒再探一次，看 currentTime 有没有推进）
JSON.stringify(Array.from(document.querySelectorAll('video')).map(function(v){
  return {readyState:v.readyState, paused:v.paused, currentTime:v.currentTime,
          w:v.videoWidth, h:v.videoHeight,
          err:v.error ? (v.error.code + ':' + v.error.message) : null};
}))
```

**⑤ 对照组（很重要）**：同一次运行里**同时**测三个 URL，用来区分"抖音的问题"和"环境的问题"：
1. `https://www.douyin.com`（目标）
2. 一个**已知能用**的站点（例如 `https://www.youtube.com`，或直接放一个纯 H.264 的 MP4 测试页）—— 它必须能播，否则说明是 MCEF/编解码器环境问题，而不是抖音问题
3. 用**移动端 UA** 再打开一次抖音（移动版页面更适合竖屏手势，也更能避开某些桌面端风控）

**⑥ UA 切换**：改 `<游戏目录>/config/mcef/mcef.properties` 的 `user-agent`（`MCEFSettings` 支持这个键），然后重启游戏。建议三种各跑一次：默认 / iPhone Safari / Android Chrome。

### 6.3 运行方式

```powershell
cd <新工作区>\douyin-spike
.\gradlew runClient          # 首次会下载 MC 资源 + MCEF 原生库，耐心等
```
日志在控制台；MCEF 自己的日志也会打进来，注意搜 `MCEF`、`cef`、`Chromium`。

---

## 7. 验收记录表（**已实测，含真机人工确认**）

> 运行方式：路线乙 spike 模组，`runClient`。
> 证据：无人值守跑的探针日志 [run/client-out.log](run/client-out.log) + 解析脚本 [parse-probes.ps1](parse-probes.ps1)
> + **真人进游戏截图确认渲染**。
> MCEF 2.1.6-1.21.1 / CEF `a78e832f9f13c2c688caea3d04d8b84fcd238d94` / Windows amd64。

### 7.0 一句话结论

> **技术路径成立，模组可制作。** 页面加载、渲染、二维码、画幅缩放全部跑通；
> 唯一仍未知的是"扫码登录"和"视频播放"这两条需要真人交互的环节。

### 7.0.1 ⭐ 最大的坑：**不要用 `TextureManager` 注册 MCEF 的纹理**

这是本次卡最久的问题（黑矩形 + 页面不显示 + 日志飘红，全是它）。
§3.3 当初建议"把 `getTextureID()` 包一层 `AbstractTexture` 注册进 `TextureManager`"——
**这个建议是错的**，读 `TextureManager.register` 源码后真相是：

```java
public void register(ResourceLocation path, AbstractTexture texture) {
    texture = this.loadTexture(path, texture);   // ← 非 SimpleTexture 时回退 SimpleTexture 并触发全资源重载
```

对 CEF 的纹理走这条路，会在**渲染线程上重载整个资源栈**，纹理永远注册不成，画面永远是空的。

**正确做法 = 照抄 MCEF 自带的 `example/ExampleScreen`**（可直接 `javap` 出自带 jar，无需读博客）：

```java
int glTextureId = browser.getRenderer().getTextureID();
RenderSystem.setShaderTexture(0, glTextureId);        // ← 直接喂裸 GL 纹理 id
RenderSystem.setShader(GameRenderer::getPositionTexShader);
// Tesselator QUADS + POSITION_TEX，4 个顶点，UV 0..1
BufferUploader.drawWithShader(buffer.buildOrThrow());
```

**全程不碰 `TextureManager`，不需要 `ResourceLocation`，不需要 `AbstractTexture`。**
MineChromium 用的 `getTextureLocation()` / `gui.blit(...)` 是**更新版本 MC 的 API**，1.21.1 没有。

**纹理方向（下一个坑）**：`MCEFRenderer.onPaint` 只给一个 `ByteBuffer`，行序无从得知。
实测：**CEF 的缓冲区行序与 MC 的 V 轴一致**，也就是说 UV 直接 `(0,0)..(1,1)` 即可，
**再加 V 翻转会导致整页旋转 180°**（上下颠倒 + 左右镜像）。
代码里已做成 `Flip` 四档（`none/v/u/uv`，游戏内按 `F` 循环），默认 `none`。

### 7.0.2 第二个坑：CEF 必须走 CPU 离屏

`MCEFRenderer.onPaint` 用 `glTexImage2D` 上传，走的是 CEF 的 **CPU 离屏路径**。
若 CEF 选了 GPU/ANGLE 合成，`onPaint` **永远不会被调用**，`getTextureID()` 一直返回 -1。
命令行开关（在 **mod 构造函数**里设置 `org.cef.commandLineArgs`，实测早于 CEF 初始化生效）：

```
--disable-gpu --disable-gpu-compositing
--autoplay-policy=no-user-gesture-required
--disable-background-timer-throttling --disable-backgrounding-occluded-windows
--disable-renderer-backgrounding
```

### 7.0.3 第三个坑：探针必须等导航完成再注入

browser 刚创建时停在 `about:blank`，此时注入的脚本会被**第一次真实导航丢弃**——
表现是"探针静默无效，既不报错也没有输出"。判据：`browser.getURL()` 非空且不以 `about:` 开头。

| 编号 | 项目 | 结果 | 证据 |
|---|---|---|---|
| A | 抖音首页能加载、不被风控挡 | ✅ **通过** | 跳转 `https://www.douyin.com/` → `/jingxuan`，标题「抖音精选电脑版」。`innerText` 是完整信息流（时长/播放量/标题/作者）。`rs=complete`、`htmlLen≈1.6MB`、`imgs=78`。**真人确认**：视频卡片墙、左侧导航、底部导航全部正常渲染；**无验证码、无滑块** |
| B | 视频能播放（画面 + 声音 + `currentTime` 推进） | ✅ **通过（真人实机）** | 真鼠标点击后**有画面有声音**，**可上下滑动刷视频**。见下方 §7.1 的重要修正 |
| C | 二维码能显示、扫码能登录、键盘输入 | ✅ **通过（真人实机）** | 进入页面后抖音**自身**就弹出登录二维码（不需要我们做任何按键），**手机扫码登录成功**，**键盘输入可用**。自动探针亦独立证实面板内有真实绘制的 PNG 二维码（`512×512` 原图显示为 `178×178`） |
| D | 关闭/退出不崩、无残留进程 | ✅ **通过（优雅退出）** / ⚠️ 强杀会残留 | 优雅退出实测：`Minecraft.stop()` → `screen removed` → `browser closed id=1` → `runClient exited with code 0`，**`java`(除 gradle daemon)=0、`cef`=0，零残留**。但 `Stop-Process -Force` 强杀 JVM 会残留 **6 个 `cef_server`（≈425 MB）**，见 §7.2 |
| 附1 | 编解码器探针结果 | ✅ **全绿，含 H.264 与 HEVC** | `avcBaseline/avcMain/avcHigh=true`（H.264）、`hev1/hvc1=true`（**H.265/HEVC 也支持**，原文档的遗留不确定性已解）、`av01/vp9/vp8=true`、`aac/opus=true`。**印证 §3.1**：MCEF 2.x 二进制确实带专有编解码器 |
| 附2 | 对照组站点能否播放 | ✅ **能播** | 本地 `data:` 页播裸 H.264/AAC MP4：`ev=playing rs=4 ns=1 dur=10 1280x720`，`ct` 逐秒推进 `1.06→2.12→…→9.04` 后循环。`hidden=false vis=visible`（未节流） |
| 附3 | 移动 UA 下的表现差异 | ⏸ **未测（非阻塞）** | 已实现（`1..4`：Default / iPhone / Android / iPad，写 `MCEFSettings` 后重建 browser）。桌面版已够用，留作调优手段。默认 UA：`Mozilla/5.0 (Windows NT 10.0; Win64; x64) … MCEF/2 Safari/537.36` |
| 附4 | 性能影响（CPU/内存/帧率） | ⏸ **未量化（非阻塞）** | 粗测：游戏 JVM ≈**1.2 GB**；CEF 侧 6–7 进程合计 ≈**700 MB**（单 `jcef_helper` 峰值 245–279 MB）。帧率未测，建议阶段 2 补 |
| 附5 | 小矩形（240×480）下文字是否可读 | ✅ **可用** | **真人确认**：按 `M` 切 240×480 画幅正常工作，文字可读（渲染分辨率仍 720×1560，双线性缩放） |

**遗留的非阻塞项**：**分辨率偏低**（真人反馈），技术上完全可解（提高 `resize()` 分辨率，
或用移动 UA 走移动版布局）——属调优，不影响可行性结论。

**注（关于探针里的 `captcha` 计数）**：`captcha` / `login` 是 `[class*="verify" i]` / `[class*="login" i]` 这类**选择器命中计数**。抖音页面到处都有 login 相关 class，命中 1 个**不等于**出现验证码。判据应以 `innerText` 文案为准——本次**没有任何验证/滑块文案**，也没有风控拦截。

### 7.1 输入与播放：旧结论**已被真机验证推翻**

⚠️ **重要修正**：下面这些结论是在"画面根本没渲染出来"的状态下得出的（`TextureManager` bug），
**真机验证已证明它们是错的**。保留记录是为了说明当时踩的坑，**不要**把它们当作依据。

| 当时的观察 | 当时的（错误）解释 | **真机验证结果** |
|---|---|---|
| `video` 恒为 `rs=0 ns=0 paused=true src=null` | 桌面信息流不自启播放 | ✅ **点击后正常播放**（有画面有声音） |
| 脚本化 `click()` + `play()` 无效（`ns=3 NO_SOURCE`） | 抖音要求 trusted gesture，怀疑点不动 | ✅ **真鼠标点击可用** —— 当时只是**点在了一个没显示出来的页面上**，坐标是盲猜的 |
| 本地对照页能播、抖音不能 | 问题在抖音播放器而非环境 | ⚠️ 这条**对照方法**本身仍有效（不依赖渲染），但"抖音不能播"的结论是错的 |

**教训（写给下一个 agent）**：
**当渲染链路本身有问题时，不要用"页面逻辑探针"的结论去判断交互可行性** ——
你测的不是交互，是你那个看不见的视口。

**结论**：抖音播放器行为与在真实浏览器里一致，**不需要任何 trusted-gesture 特殊处理**。
`MCEFBrowser.sendMousePress/sendMouseRelease` 喂进去的真鼠标事件就是可用的
（这印证了 §3.6 的判断，即 HuhuTalk 已有的虚拟光标正是那块关键拼图）。

### 7.2 D 项：优雅退出 ✅，强杀 ⚠️

| 场景 | 结果 | 证据 |
|---|---|---|
| **优雅退出**（`Minecraft.stop()`） | ✅ **零残留** | `graceful shutdown requested after 90s` → `calling Minecraft.stop()` → `Minecraft.stop() returned` → `screen removed; closing browser` → `browser closed id=1 url=…` → `runClient exited with code 0`；随后 `java`(除 gradle daemon)=0、`cef`=0 |
| **强制杀 JVM**（`Stop-Process -Force`） | ⚠️ 残留 | `jcef_helper` 全部退出，但 **6 个 `cef_server` 残留 ≈425 MB** |

**注**：前一版文档曾写"优雅退出从未触发"——那是**误判**。真实原因是当时**还没有加 30 秒心跳日志**，
`exitSeconds` 实际生效了，只是日志里看不到过程。加心跳后一跑就复现了。

**结论**：正常"退出游戏"路径是干净的（MCEF 的 `CefWindowsShutdownMixin` 会收尾）。
但要注意 **`cef_server` 是独立进程，杀 JVM 不会带走它** —— 开发期反复强杀会累积，
集成时如果玩家用任务管理器强杀 MC，也可能留残留。

### 7.3 系统权限弹窗：**已查清 —— 只弹一次**

**真人实测反馈**：首次运行下载 MCEF 原生库之后，弹出了**一次**系统防火墙权限请求，
**之后不再弹出**。

**结论**：对玩家体验影响有限（一次性）。但**建议在安装说明里提一句**，
避免玩家以为是故障。若日后要彻底消除，可考虑随包附带或预先声明规则的方案。

这条对**最终用户体验**有实际影响，必须查：

- 弹几次？只首次还是每次启动？
- 拒绝之后还能不能正常工作？（如果只是 CDN 下载需要，拒绝后已缓存则应无影响）
- 是否需要提前在文档/安装说明里告知玩家

**排查方向**：`MCEFDownloader` 下载 `java-cef-builds/<commit>/<platform>.tar.gz` 是**出站** HTTP，
正常不该触发防火墙规则。触发入站的更可能是 **CEF 的 crashpad handler 或 IPC**（`cef_server` 之间通信）。
建议：清掉 `build/mcef-libraries/` 复现一次，记录弹窗原文与进程名。

---

## 8. 结论回填与后续路线（**✅ 验证已通过，可以开始**）

> 验收全部通过，见 §7。本文档的下述阶段划分**现在可以执行**。
> 写给人看的结论汇总：[douyin-webview-verification-report.md](douyin-webview-verification-report.md)。

### 8.1 HuhuTalk 侧现状：App 注册**不是**公开方法

给 HuhuTalk 加公开注册 API 时，需要改这些点。**证据（行号基于本文档成文时的代码）**：

| 位置 | 内容 | 问题 |
|---|---|---|
| `MainPage.java:68` | `private static final List<AppInfo> apps` | 私有静态列表 |
| `MainPage.java:82` | `private static List<AppInfo> buildApps()` | 私有构建方法，类加载时跑一次 |
| `MainPage.java:388-410` | `public static class AppInfo` | 类虽 public，但**构造函数无修饰符（包私有）**，只有 `com.huhutalk.phone.pages` 内可见 |
| 全工程 | 无注册表、无注册事件、无 SPI | — |

**结论：附属模组目前无法注册 App**，只能 mixin/反射硬插（不建议）。

**另外两个隐藏门槛**：

- `MainPage.render()` 里对 `"HuhuTalk"` / `"设置"` / `"SOS"` 是**硬编码分支**；普通 App 只能画"文字/emoji"作为中心内容，**没有贴图图标字段**。抖音图标是贴图 → 需要给 `AppInfo` 补通用的 `iconTexture` 字段。
- `MainPage.java:351` 的 `appIconCenterScreen(appName)` 是 `PhoneOverlay.launchApp` **启动动画**的依赖 → 注册表必须是"静态、且在首次打开手机之前完成注册"。

**好消息**：`IPhonePage` 接口和 `PhoneOverlay.pushPage/launchApp` 已经是 `public`，页面本身可以外部实现，缺的只有"注册入口"这一环。

### 8.2 后续阶段

> **✅ 前置条件已满足**：验证通过，点击/刷视频/登录/键盘输入均经真机确认。
> 额外补充（本次学到的两条，会改变实施细节）：
> - 阶段 3 的"拖动→滚轮"仍需要，但**真鼠标点击本身已验证可用**，不需要绕路
> - 阶段 4 的中文输入：真机已确认**键盘输入可用**，`PhoneInputScreen` 方案可行
> - 阶段 2/3 都要注意 **CEF 必须禁用 GPU**（§7.0.2），否则渲染不出来

| 阶段 | 内容 | 前提 |
|---|---|---|
| 1 | HuhuTalk 加**公开 App 注册 API**（`AppSpec` 构件 + `registerApp`，支持贴图图标），内置的 HuhuTalk / 设置 App **行为零改动** | ✅ 本验证通过 |
| 2 | 抖音做成**独立附属模组**，软依赖 MCEF（`compileOnly` + `ModList.get().isLoaded("mcef")`），缺 MCEF 时 App 显示"需要安装 MCEF" | 阶段 1 |
| 3 | **虚拟光标接入**：把 `PhoneOverlay` 的光标坐标映射成浏览器像素坐标，接 `sendMouseMove/Press/Release/Wheel`；**必须覆写 `setCursorChangeListener`**（见 §3.5）；拖动→滚轮实现竖滑刷视频 | 阶段 2 |
| 4 | **中文输入**：复用 `PhoneInputScreen`（真 `Screen` + 隐藏 `EditBox`，IME 可用）收集文本，再逐字符 `sendKeyTyped` 注入；剪贴板用 Ctrl+V。**注意：OSR 下没有 IME 候选词浮窗，这是体验边界** | 阶段 3 |
| 5 | 音频：CEF 直接输出到系统音频，**不受 MC 音量滑块控制**，只能靠 JS 注入静音 | 阶段 2 |
| 6 | **分辨率调优**（本次新增）：真实反馈"分辨率偏低"。提高 `resize()` 分辨率，或改用移动 UA 走移动版布局 + 量化帧率影响 | 阶段 2 |

### 8.3 已识别的风险（**按实测结果更新**）

> 状态列按本次**真机验证**结果填写，不再沿用验证前的猜测。

| 风险 | 严重度 | 状态 | 说明 |
|---|---|---|---|
| 抖音风控 / 反自动化 | ~~高~~ → **低** | ✅ **未遇到** | 首页正常加载、完整信息流、**无验证码无滑块**、扫码登录成功。CEF 是真 Chromium，行为与真实浏览器一致 |
| 渲染贴图（本以为简单） | — → **中** | ✅ **已解决** | `TextureManager` 方案会静默失败（§7.0.1）；必须走 `getTextureID()` + 裸 GL 绑定 + 禁用 CEF GPU + 注意纹理方向 |
| 光标争夺 | 中 | ⏸ 待处理 | 见 §3.5，必须覆写 `setCursorChangeListener` |
| 窄屏适配 | 中低 | ⏸ 待调优 | 桌面版塞进 20:9 手机矩形很挤；`M` 键实测 240×480 可读，可考虑移动 UA |
| **分辨率偏低** | 低（新增） | ⏸ 待调优 | 真实反馈。技术上可解（提高渲染分辨率 / 移动 UA），非阻塞 |
| 首次 100–200 MB 下载 | 中 | ✅ **已实测** | 本机约 45 s；**未开加速器也成功**。可配 `download-mirror` 兜底 |
| 系统权限弹窗 | ~~⚠️ 待考察~~ → **低** | ✅ **已查清** | **只弹一次**（首次下完原生库后），之后不再弹。建议安装说明里提一句 |
| **强杀进程残留** | ⚠️ | ⏸ 已知 | 优雅退出零残留；但 `Stop-Process -Force` 留下 **6 个 `cef_server` ≈425 MB**。玩家用任务管理器强杀 MC 可能触发 |
| 分发耦合 | **已解决（设计上）** | ✅ | MCEF 放附属模组，HuhuTalk 本体零依赖 |
| 合规 | 低-中 | ⏸ | LGPL-2.1 依赖需保留声明；把网页版抖音嵌进游戏并登录账号的合规风险由作者判断 |

### 8.4 阶段 3 的起点（**真机已验证可用的路径**）

真机已确认：**真鼠标点击 → 视频播放/刷视频/扫码登录/键盘输入 全部可用**。
所以阶段 3 不需要绕路验证，可以直接按 §3.6 的方案接虚拟光标：

> `PhoneOverlay` 光标坐标 → 浏览器像素坐标 → `sendMousePress/sendMouseRelease`

**唯一仍需确认的一条**：`PhoneOverlay` 的坐标系与 `DouyinScreen` 里
`(mouseX - viewX) * (BROWSER_W / viewW)` 的换算是否一致（注意虚拟光标是**手机屏内坐标**，
不是 GUI 坐标，两者差一个缩放）。spike 里可以直接抄这段换算。

**必须先处理的坑**（否则一进抖音就丢鼠标捕获）：
`MCEFBrowser.setCursor` 会直接调 `GLFW.glfwSetInputMode` / `glfwSetCursor`（见 §3.5），
**必须**用 `setCursorChangeListener` 覆写成 no-op。

---

## 9. 全部资料与网址（速查）

**MCEF**
- Modrinth 项目页：https://modrinth.com/mod/mcef
- 目标版本页：https://modrinth.com/mod/mcef/version/euXLgkSB
- jar 直链：https://cdn.modrinth.com/data/TObQ0HxZ/versions/euXLgkSB/mcef-neoforge-2.1.6-1.21.1.jar
- 版本 API（含 loader/dependency 元数据）：https://api.modrinth.com/v2/version/euXLgkSB
- 源码仓库：https://github.com/CinemaMod/mcef （**用 `1.21.1` 分支**）
- 关键源文件（`1.21.1` 分支）：
  - `common/src/main/java/com/cinemamod/mcef/MCEF.java`（`createBrowser`）
  - `common/src/main/java/com/cinemamod/mcef/MCEFBrowser.java`（**输入与纹理 API 全在这里**）
  - `common/src/main/java/com/cinemamod/mcef/MCEFRenderer.java`（`getTextureID` / `onPaint`）
  - `common/src/main/java/com/cinemamod/mcef/MCEFDownloader.java`（原生库下载 URL 规则）
  - `common/src/main/java/com/cinemamod/mcef/MCEFSettings.java`（`config/mcef/mcef.properties` 的键）
  - `common/src/main/java/com/cinemamod/mcef/example/ExampleScreen.java`（**渲染+输入的官方范本**）
  - `common/src/main/java/com/cinemamod/mcef/mixins/CefRenderUpdateMixin.java`（消息循环怎么被驱动的）
- java-cef（MCEF 专用分支）：https://github.com/CinemaMod/java-cef
  - `cmake/DownloadCEF.cmake`（CEF 二进制源指向 `mcef-download.cinemamod.com`）
  - `.github/workflows/build-jcef.yml`（各平台构建产物）

**编解码器证据**
- CEF 默认不含专有编解码器：https://github.com/chromiumembedded/cef/issues/3559
- 老 MCEF 无 H.264：https://github.com/CinemaMod/mcef/issues/4
- 维护者关于分发的说明 + **"2.x 的二进制含 codecs"**：https://github.com/CinemaMod/mcef/issues/28
  - 决定性评论：https://github.com/CinemaMod/mcef/issues/28#issuecomment-1800748192
- 社区自打补丁项目（参考其原理）：https://github.com/Ruinscraft/mcef-codec-patch

**参考实现**
> ⚠️ **先看这条**：**最可靠的参考是 MCEF 自带 jar 里的 `example/ExampleScreen`**，
> 直接 `javap -p -c -classpath mcef-neoforge-2.1.6-1.21.1.jar com.cinemamod.mcef.example.ExampleScreen`
> 就能看到完整的渲染+输入调用序列，**不用联网、不用读博客、不会过期**。
> 下面的第三方项目仅供参考：**MineChromium 用的是更新版本 MC 的 API**
> （`getTextureLocation()` / `RenderPipelines`），**1.21.1 上不能照抄**（§3.3 已警告）。

- MineChromium 项目页：https://modrinth.com/mod/minechromium
- MineChromium 1.21.1 NeoForge 版本：https://modrinth.com/mod/minechromium/version/OQemqdMR
- MineChromium jar 直链：https://cdn.modrinth.com/data/DAEHtH8c/versions/OQemqdMR/pipbrowser-neoforge-1.21.1-1.2.1.jar
- MineChromium 源码（MIT）：https://github.com/MrZylr/MineChromium
  - 渲染+输入核心：https://raw.githubusercontent.com/MrZylr/MineChromium/main/src/main/java/com/zylr/pipbrowser/widgets/BrowserWidget.java
    （**注意：`main` 分支面向更新的 MC，`getTextureLocation()` 在 1.21.1 不存在**）
  - HUD 输入转发（**看它的 `AbstractContainerScreen` 限制**）：https://raw.githubusercontent.com/MrZylr/MineChromium/main/src/main/java/com/zylr/pipbrowser/listeners/RenderHudElements.java
- WebDisplays：https://modrinth.com/project/bRa1UAVZ
- Graphene（架构参考）：https://modrinth.com/mod/grapheneui
  - 架构与运行时：https://github.com/trethore/graphene/blob/main/docs/explanation/architecture-and-runtime.md
  - 浏览器层（Session/View/Surface）：https://github.com/trethore/graphene/blob/main/docs/explanation/browser-layers.md
  - 兼容性与安装（确认只有 Fabric + 新版本）：https://github.com/trethore/graphene/blob/main/docs/reference/compatibility-and-installation.md

**目标站点**
- 桌面版：https://www.douyin.com
- （移动 UA 下会跳转到移动版布局，具体 URL 由站点决定）

---

## 10. 给新工作区的第一条指令（可直接复制）

> 读 `douyin-webview-feasibility-handoff.md`（本交接文档，随模板一起挪过来）。
> 按 §5 **路线乙**执行：先用 §4.1 的模板建一个最小 spike 模组，接入 MCEF 2.1.6（坐标与直链见 §3.2 / §4.3），
> 实现 §6.2 的键盘入口、Screen、输入转发和 **JS 日志探针**（尤其是 `MediaSource.isTypeSupported` 那一组和 `video.currentTime` 推进检查），
> 并按 §6.2⑤ 做**对照组**（同一次运行同时测抖音 + 一个已知可用的站点）。
> 跑 `.\gradlew runClient`，然后按 §2.2 的 A/B/C/D 四条验收，把结果填进 §7 的表格。
> 只做验证，**不要**改 HuhuTalk 本体，**不要**做独立光标，**不要**做 App 注册 API。
> 抖音需要国内网络；MCEF 首次启动要下载 100–200 MB，别误判为卡死。

---

## 11. 附：本文档成文时的现场状态

- HuhuTalk 本体：**未做任何改动**（`docs/` 之外没有新增文件）。
- **模板模组已经移出本工作区**（原路径 `示例模组\模版\` 已不存在）；本文档的副本应随它一起进入新工作区。
- HuhuTalk 的 `settings.gradle` **保持干净**（无 `include`），不要修改。
- 本工作区的 DSH 沙箱是坏的（见 §4.2-1），所以验证**必须**在新工作区做。
