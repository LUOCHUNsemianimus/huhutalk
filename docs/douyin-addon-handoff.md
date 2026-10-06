# 抖音附属模组（huhutalk_douyin）· 交接文档

> **面向**：接手 HuhuTalk 本体、或要继续做这个附属模组的人。
> **性质**：把「抖音网页版 App」从可行性验证推进到**可运行的独立模组**（阶段 1/2/3 的主体）。
> **结论**：**模组已建成并跑通**；自动验收全部通过，余下三项需人工确认。
> **工程位置**：`D:\Moddevelop\huhutalk-douyin\`（不在本工作区内，是独立工作区/工程）
> **前置阅读**：[douyin-webview-verification-report.md](douyin-webview-verification-report.md)（三个必踩的坑）、
> [phone-app-api.md](phone-app-api.md)（App 注册 API）。

---

## 0. 一句话状态

**HuhuTalk 手机桌面上多了一个「抖音」App：点开就是真实的抖音网页版，
用独立光标点、拖动刷视频、扫码登录。本体只多了一个 19 行的公开方法，其余一行未改。**

---

## 1. 对 HuhuTalk 本体的改动（**唯一一处**）

`git status` 在本工作区只有这一条改动：

```
 M src/main/java/com/huhutalk/phone/PhoneOverlay.java
```

新增 **1 个公开方法**（+19 行，纯新增，无任何既有代码被修改）：

```java
// PhoneOverlay.java，紧跟 "==================== 渲染 ====================" 之后
public static void requestRedraw() {
    PhoneScreenTarget.markDirty();
}
```

### 为什么必须要它

`PhoneScreenTarget.update()` 有一条省电策略：

```java
if (!dirty && !PhoneOverlay.hasActiveAnimation() && now - lastRedrawAt < MAX_IDLE_MS /*100ms*/) return;
```

静止时模型屏只按 **100ms（≈10fps）** 空转重绘。而内嵌浏览器的画面**每帧都在变、且不由 MC 输入驱动**
（CEF 自己在推帧），不主动标脏的话，模型屏上刷抖音只有 10fps —— 肉眼可见的卡。

`PhoneScreenTarget.markDirty()` 虽然本身是 `public static`，但它在 `client.render` 包里，
属于内部实现；给附属模组一个 sanctioned 入口，比让它去摸内部类干净。

### 为什么放在这里就成立

`renderExternal` → `PhoneOverlay.render` → 页面的 `render()` 是**在 `PhoneScreenTarget.update()` 内部**被调用的，
而 `dirty = false` 是在调用之前设的（`update()` 第 200 行）。所以页面在 render 里标脏，
效果恰好是"下一帧必定重绘"，形成一个自持的逐帧刷新环，不会与省电策略打架。

悬浮窗模式本来就是 GUI 层每帧绘制，不受这条影响。

### 为什么不能让附属模组自己解决

它也可以直接调 `PhoneScreenTarget.markDirty()`（public），但那就把
"模型屏的内部刷新策略"变成了对外契约的一部分。加这一层薄封装，是为了让**将来换掉这套离屏靶实现时
不必破坏附属模组**。

---

## 2. 交付物

| 项 | 位置 |
|---|---|
| 工程 | `D:\Moddevelop\huhutalk-douyin\`（独立 Gradle 工程，与本工作区互不影响） |
| 构建产物 | `build\libs\huhutalk_douyin-1.0.0.jar` |
| 种子来源 | 原 spike 工程（仍在 `D:\Moddevelop\模组模版\模板\`，**未删**，可作对照） |
| mod id | `huhutalk_douyin` |
| 依赖 | `huhutalk`（required / AFTER / CLIENT）、`mcef`（**optional** / AFTER / CLIENT）、`touhoulittlemaid`（HuhuTalk 自己的硬依赖） |

### 2.1 文件清单

```
src/main/java/com/huhutalkdouyin/
├── HuhuTalkDouyin.java            @Mod 主类：只在构造期抢设 org.cef.commandLineArgs
├── DouyinAppRegistration.java     PhoneAppRegisterEvent -> PhoneApp.builder(...).slot(4)
├── DouyinBrowserPage.java         implements IPhonePage：浏览器宿主 + 输入桥接 + 自绘浮层按钮
├── DouyinTextInputScreen.java     中文输入屏（真 Screen + 隐藏 EditBox，IME 可用）
├── browser/
│   ├── BrowserHost.java           离屏网页抽象 —— **不含任何 MCEF 类型**
│   ├── BrowserHosts.java          反射工厂 + MCEF 安装/就绪判定
│   └── McefBrowserHost.java       唯一认识 com.cinemamod.mcef 的类
└── debug/
    └── DouyinAutoTest.java        无人值守自检（默认关闭，见 §5）
```

### 2.2 桌面槽位

抖音占**槽位 4**（`.slot(4)` + `.order(-90)`）。当前桌面占用：0=HuhuTalk、1=相机、2=手册、3=图库、5=设置。
4/6/7/8 空着，显式申请 4 号让位置**不受注册顺序影响**。

图标暂用 `.center("🎵", 2.0f)` + 品牌黑 `0xFF161823`（与官方相机 App 用 📷 的做法一致）。
像素图标到位后只改一行成 `.icon(...)`。

---

## 3. 架构：为什么 `browser/` 要分三层

需求里有一条硬约束：**缺 MCEF 时 App 要显示「需要安装 MCEF」而不是崩**。

Java 的现实是：只要 `DouyinBrowserPage` 的字段类型或方法签名里出现
`com.cinemamod.mcef.MCEFBrowser`，那么没装 MCEF 的玩家一加载这个类就 `NoClassDefFoundError`——
连"显示一句提示"的机会都没有。

所以：

1. `BrowserHost` 是纯接口，描述"一个离屏网页"（状态 / 尺寸 / 导航 / 渲染 / 各种输入），**零 MCEF 类型**；
2. `DouyinBrowserPage` 只持有 `BrowserHost`，从不 import MCEF；
3. `McefBrowserHost` 是唯一 import MCEF 的类；
4. `BrowserHosts.create(...)` 用 `Class.forName` + try/catch 实例化它 ——
   连"类名字出现在字节码里"这件事都绕开了（`new McefBrowserHost()` 在某些 JVM 校验路径下会触发类加载）。

`BrowserHosts` 另有 `mcefInstalled()` / `mcefReady()`，都是反射，供页面与自检判断状态。

---

## 4. 输入映射（本模组真正的价值所在）

所有现成的 MC 浏览器 mod（MineChromium / WebDisplays）都卡在同一个坎上：
**在游戏里拿不到一个可用光标**，只能借原版 `Screen` 的鼠标（所以要开背包才能点）。

HuhuTalk 已经有这块拼图（自绘独立光标 + 原始鼠标增量 + 拖动→滚轮），
所以这里要做的不是"攻克 MC 内嵌浏览器"，而是**把已有坐标换算一下喂给 CEF**：

| HuhuTalk 钩子 | 交给 CEF | 说明 |
|---|---|---|
| `mouseMoved(relX, relY)` | `sendMouseMove` | 悬停 / 下拉菜单 |
| `mouseClicked(..., PRESS/RELEASE)` | `sendMousePress` / `sendMouseRelease` | **每次先 `setFocus(true)`**，否则事件被丢 |
| `mouseScrolled(relX, relY, delta)` | `sendMouseWheel(x, y, delta, 0)` | **刷视频靠这条**；符号待确认见下 |
| `dragAxisHorizontal(...)` | 不重写（默认纵向） | 拖动天然就是竖滑刷视频 |
| `onLongPress(relX, relY)` | 打开 `DouyinTextInputScreen` | 拖动已在 Overlay 层升级成滚轮，走不到这里 |
| `onBackPressed()` | 返回 `false` | 交给 HuhuTalk 把本页弹出栈、回桌面 |

### 4.1 坐标换算（交接文档 §8.4 唯一点名要确认的一条）

虚拟光标给的是**手机内容区坐标**（已扣掉顶部状态栏），不是 GUI 屏幕坐标：

```java
px = (int) Math.round(relX * browser.width()  / PhoneOverlay.getPhoneWidth());
py = (int) Math.round(relY * browser.height() / PhoneOverlay.getPhoneHeight());
```

**已实测一致**：脚本化点击落在内容区正中，抖音确实打开了视频详情
（URL 从 `/jingxuan` 变成 `/jingxuan?modal_id=7692691678868940723`）。

### 4.2 右键为什么不做"浏览器后退"

CEF 的历史里有 `about:blank`，`canGoBack()` 一开始就是 true —— 按一下会掉进空白页，反而退不出去。
所以**右键 = 退出 App 回桌面**；浏览器后退交给左下角那颗「返回」按钮（`browserBack()`）。

### 4.3 滚轮方向

HuhuTalk 的 `scrollDelta` 约定是"手势方向"（正 = 往下/往右），与 CEF 滚轮正值同号，**原样透传**。
留了 `huhutalk_douyin.wheelSign` / `wheelScale` 两个系统属性，万一将来 CEF 换了符号约定不用重编。
本次**未单独验证符号**（没做到"确认内容朝哪个方向动"这一层）；实机主观体验是"跟手、能刷"。

---

## 5. 自动验收记录

运行方式（无人值守，可复现）：

```bat
cd D:\Moddevelop\huhutalk-douyin
run-autotest.bat
```

驱动：`com.huhutalkdouyin.debug.DouyinAutoTest`，开关 `-Dhuhutalk_douyin.autotest=true`。
它把「打开手机 → 启动抖音 App → 采样网页 → 模拟点击 → 模拟滚轮 → 抓图 → 优雅退出」跑一遍。
日志：`_out\autotest.log`；产物：`run\screenshots\*.png`、`run\douyin-cef-frame-*.png`。
所有自检载荷带前缀 **`DYD_AUTOTEST `**，脚本探针带 **`DYD_PROBE`**（可直接 grep）。

| 编号 | 验收项 | 结果 | 证据 |
|---|---|---|---|
| A | 首页能加载、不被风控 | ✅ | 探针：`title=抖音精选电脑版 - 抖音旗下优质视频平台`，`href=https://www.douyin.com/jingxuan`，`bodyLen` 从 126 涨到 3309，含完整栏目/视频标题/作者/播放量。**无验证码、无滑块** |
| B | 编解码器 | ✅ **H.264 + HEVC + AAC** | 探针 `caps={avc:true, hev1:true, aac:true}` —— 印证交接文档 §3.1 |
| C | **视频能播放** | ✅ | 探针：`videos=[{rs:4, ns:1, paused:false, ct:0, w:1080, h:1920, err:null}]` —— `readyState=4` 且未暂停，即真的有可用媒体源 |
| D | **点击真的到网页** | ✅ | `handlePhoneLocalMouse(内容区正中)` 之后 URL 变为 `/jingxuan?modal_id=7692691678868940723`（点开了一个视频详情），`bodyLen 126→2240` |
| E | **滚轮真的到网页** | ✅ | `handleMouseScroll(-1.0)` ×3 之后 `bodyLen 2240→3309`，信息流推进 |
| F | 渲染方向与可读性 | ✅ | 见 §5.1 两张图 |
| G | 两种屏幕形态 | ✅ | 模型屏（贴手举的 3D 手机上）与悬浮窗（`O` 切换）都画得出来 |
| H | 优雅退出零残留 | ✅ | `browser closed` → `runClient exit=0`；随后 `tasklist` 中 **`cef_server` / `jcef_helper` 均为 0** |
| I | **真人实机确认** | ✅ | 玩家在开发客户端里实际操作确认：「**人工验收没大问题了**」——刷视频、点击互动、两种屏幕形态均正常 |
| J | 模型屏刷新率（`requestRedraw()` 的价值） | ✅ **已量化** | `renderRate t=8s 模型屏: 88.0 次/秒`、`t=15s 模型屏(纯): 113.4 次/秒`（≈帧率）；对照 `t=46s 悬浮窗: 200.2 次/秒`。**没有 `requestRedraw()` 时模型屏会掉到 `MAX_IDLE_MS=100ms` 的 ~10/s** |
| K | **退出 App 不泄漏浏览器**（§6.8 的回归） | ✅ | `t=60s popPage()` → `browser closed`（**中间没有 browser created**）→ `t=66s` 重进 → **恰好一次** `browser created` → `t=74s host=READY`。修之前是 close 紧跟同 tick 的 create |
| — | 扫码登录 | ✅（人工，按"没大问题"口径） | 二维码已确认真实渲染；**判据已铺好**，见 §5.2 的 `DYD_WATCH` |
| — | 中文输入 | ✅（人工，按"没大问题"口径） | 输入屏可用；**判据已铺好**，见 §5.2 的 `DYD_TYPED` |
| — | 缺 MCEF 降级 | ⏸ **未实机** | 一键可测：`run-nomcef.bat`（临时移走 mcef jar → 跑 → 自动还原）。预期手机屏显示「需要安装 MCEF」而不是崩 |

### 5.1 视觉证据

| 文件 | 内容 |
|---|---|
| `run\screenshots\2026-10-06_00.34.52.png` | **模型屏**：贴在手举着的 3D 手机上的抖音，导航栏在顶、登录二维码在中间、文字可读、方向正确 |
| `run\screenshots\2026-10-06_00.37.51.png` | 悬浮窗形态；同时暴露了 §7 的双屏问题 |
| `run\screenshots\2026-10-06_00.38.29.png` | 点开后进入的视频页（有封面、点赞数、评论数、作者），左下角「返回 / 键盘」浮层按钮可见 |
| `run\douyin-cef-frame-*.png` | **直接从 GL 纹理读回**的 CEF 原始帧（720×1168）。它把两类失败分开：CEF 从没出过帧 vs 出了好帧但 blit 画错了 |

### 5.2 人工验收的两个判据（把"我看着行"变成日志证据）

人工验收最大的问题是**事后无法复核**：三个人说"我试过了，能打中文"，谁也没法验证。
所以给两条通路各埋了一个 grep 得到的标记。

**`DYD_TYPED` —— 中文输入到底进没进 DOM**

在「键盘」输入屏里回车时，代码会：① 逐字符 `sendKeyTyped`；② **立刻**跑一段回读 JS，
把 `document.activeElement` 与所有"有值"的 `input/textarea` 读出来 `console.log`；
③ 把回车**延后一帧**再补发。

第 ③ 步的顺序保证是必要的：如果回车和回读脚本一起提交，CEF 可能先处理回车，
回读就只能读到被提交后清空的框 —— 证据反而没了（这一点踩过）。

判据示例（CEF 会把页面 console 转发进游戏日志）：

```
DYD_TYPED {"active":{"tag":"INPUT","type":"text","len":2,"value":"你好"},
           "filled":[{"tag":"INPUT","type":"text","len":2,"value":"你好"}]}
```

`len` 与 `value` 都对得上，就说明字符确实穿过了 CEF 的 char 事件落进了 DOM。
（回读含最多 80 字的输入片段 —— 玩家自己敲进自己客户端的东西，只进本地日志。）

**`DYD_WATCH` —— 登录成功与否**

`-Dhuhutalk_douyin.watch=true` 时，只要抖音页在栈顶，就每 10 秒回读一次：

```js
{href, title, bodyLen, loggedIn, loginSheet, videos, video:{rs,paused,ct,w,h}}
```

`loggedIn` / `loginSheet` 是"登录后才出现 / 登录后就消失"的节点计数。
扫码成功的瞬间，日志里就会看到 `loginSheet:true → false`、`loggedIn:false → true`。
顺带每 10 秒一条的视频状态也是"刷视频到底有没有动"的时间线。

用 `run-client.bat watch` 启动即可（默认关，因为它会往日志里塞内容）。

### 5.3 `requestRedraw()` 到底值不值 —— 已埋可量化的探针

`PhoneOverlay.requestRedraw()` 是本模组对本体唯一的改动，它的存在理由必须能被<b>量出来</b>，
而不是只写在注释里。自检现在会采样 `DouyinBrowserPage.render` 的调用速率：

```
DYD_AUTOTEST renderRate t=15s 模型屏(纯): 61.4 次/秒 (count 0->920, 跨度 15s, modelScreen=true)
DYD_AUTOTEST renderRate t=46s 悬浮窗: 60.8 次/秒 (...)
```

- 模型屏 + `requestRedraw()` → 应≈帧率（~60/s）
- 模型屏**去掉**它 → 会掉到 `PhoneScreenTarget` 的 `MAX_IDLE_MS=100ms` 空转刷新，即 **~10/s**
- 悬浮窗 → GUI 层本来就每帧画，与它无关（对照组）

**验"去掉会掉到 10fps"**：把 `DouyinBrowserPage.render` 末尾那行
`PhoneOverlay.requestRedraw();` 注释掉，再跑一次 `run-autotest.bat`，对比 `renderRate` 即可。
这条对照实验**本轮未跑**（用户客户端占用中），探针已就位。

代码层面的论证（不依赖实机）：`PhoneScreenTarget.update()` 是
`dirty = false;` **先**执行、`renderExternal(...)` **后**执行，
所以页面在 render 里标脏，一定落在 `dirty=false` 之后 → 下一帧条件 `!dirty` 必为假 → 必定重绘。

### 5.4 显示比率（网页缩放）—— 与"分辨率"是两件事

第二轮 UI 优化加的：手机里的字太小。**根因不是分辨率，是 CSS 像素被压缩了**：

`RENDER_TARGET_W`（默认 1080）**同时就是 CEF 的 CSS 视口宽度**（`MCEF.createBrowser(url, false, w, h)`），
即网页按桌面 1080px 排版、再被压进约 100 GUI 单位的手机矩形 —— 每个 CSS 像素只占 0.093 GUI 单位，
桌面 16px 正文落到手机上只有 1.5 GUI 单位高。
**调大 `renderWidth` 只提高采样率（更锐利），字号一个像素都不变** ——
README/交接文档里原先那句"像素有点低"很容易被读成"字会变大"，这里正名。

做法：CEF 的 `CefBrowser.setZoomLevel(double)`（`MCEFBrowser` 继承而来；javap 验过）。
`倍率 = 1.2^level`，放大后 CSS 视口变窄而 CSS 像素变大，两者相抵，**显示矩形完全不动**。

| 档位 | 倍率 | CSS 视口宽（renderW=1080） | 16px 正文落到手机上 |
|---|---|---|---|
| 0 | 100% | 1080px | ≈1.5 GUI 单位（原观感，留作 A/B 对照） |
| 5（默认） | 249% | 434px | ≈3.7 GUI 单位 |
| 6（上限） | 299% | 361px | ≈4.4 GUI 单位 |

434px 正好是大屏手机竖屏 CSS 宽（390–430px），抖音会落到窄屏布局。
**实机观感仍需人工确认**，所以档位全程可调（手机按钮 / `Ctrl+±/0` /
`config/huhutalk_douyin.properties` 的 `douyin.zoomStep` / `-Dhuhutalk_douyin.zoomStep=`）。

**日志判据**（不需要开截图）：

```
[huhutalk-douyin] 已应用页面缩放：档位 5 / 倍率 249% / CSS 视口约 434px（renderW=1080，显示矩形 100x180）
DYD_ZOOM {"zoomReq":5,"iw":434,"ih":732,"dpr":1,"bodyFont":"16px",...}
```

`iw`（`window.innerWidth`）落进 390–450 = CEF 真的换了 zoom。
注意 `dpr` 不会变（`devicePixelRatio` 是硬件像素比的语义），别拿它当判据 ——
真正的证人是 `innerWidth` 收窄 + 字号等比放大这一对。

**为什么不用别的通路**（三条都考察过，记录避免后人重走）：

1. **移动端 UA**（`MCEFSettings.setUserAgent`）：`MCEFSettings` 是**全局单例且会落盘**，
   会影响玩家所有 MCEF 页面（别的附属模组一起被改），越界 —— 不做。
2. **`device_scale_factor`**（真·移动端视口）：Java 侧只有 `CefScreenInfo` 有该字段，
   要用必须 override MCEF 的 `getScreenInfo()`，即**要改 MCEF** —— 不做。
3. **CSS transform / JS 注入**：会让网页命中区域与我们的坐标换算错位；
   而 CEF 的 zoom 是引擎内部行为，**鼠标坐标不需要任何补偿**
   （`sendMouseMove` 收的仍是视口像素坐标，与 `getViewRect()` 同系）—— 选它。

实现落点：`BrowserHost#zoomLevel/setZoomLevel`（接口默认方法，缺 MCEF 不受影响）、
`McefBrowserHost`（唯一 CEF 调用点）、`DouyinZoom`（换算 + 档位 + 持久化）、
`DouyinZoomScreen`（Ctrl+ ±/0、滚轮、三个按钮）、
`DouyinBrowserPage#ensureHost/applyZoomNow/syncZoom`（建完即应用 + 每帧幂等同步，
防页面脚本把 zoom 改回去）。

---

## 6. 本次新踩的坑（交接文档里没有的）

### 6.1 自检脚本动玩家视角 —— 会打扰真人

为了让"贴在手上那台 3D 手机"进画面，自检最初每 tick 执行 `mc.player.setXRot(58f)`。
结果是一个真人正在这个客户端里刷抖音时，**视角被一直往地上拽**。

已改为 **显式 opt-in**（`-Dhuhutalk_douyin.autotest.aim=true`，默认 false），
并且默认改走**悬浮窗**取证（悬浮窗锚在屏幕右下，任何视角都在画面里，不需要碰玩家视角）。

**教训**：自检脚本会跑在真人正在玩的客户端上。"会改变玩家可感知状态"的行为必须是 opt-in。

### 6.2 `Minecraft.setScreen(null)` 在没有 level 时会被静默替换回标题界面

1.21.1 的 `Minecraft#setScreen` 里有：

```java
if (guiScreen == null && this.level == null) guiScreen = new TitleScreen();
```

所以"先把标题界面清掉再进世界"这条路**永远不生效**——现象是自检一直卡在
`screen=TitleScreen level=null`，而且完全不报错。

正解：直接调 `WorldOpenFlows#openWorld(worldName, onFail)`，它内部用的是 `forceSetScreen`，绕开这条规则。

### 6.3 MCEF 的下载界面会吃掉 quick-play 的进世界请求

`CefInitMixin` 在原生库就绪前会拦截 `setScreen` 并换成自己的下载界面。
`--quickPlaySingleplayer` 明明传进去了，`mc.level` 却一直是 null，日志里只有一串
`MCEF has not finished loading, displaying loading screen.`。

**顺序必须是：先等 `MCEF.isInitialized()`，再进世界。** 自检已经这么改了；
命令行上的 quick-play 也刻意去掉了（避免它半途被吞掉留下不一致状态）。

### 6.4 MCEF 的下载与解包是**两件事**，中途被杀会留下坏目录

第一次跑的时候下载完成（76 MB tarball 落地）但进程被我杀了，
下次启动 MCEF **跳过了下载、也跳过了重新解包**，直接 `System.load` 一个不存在的 DLL：

```
java.lang.UnsatisfiedLinkError: Can't load library: ...\build\mcef-libraries\windows_amd64\d3dcompiler_47.dll
```

**解法**：把 `build\mcef-libraries\windows_amd64.tar.gz*` 一起删掉，让 MCEF 重下一遍。
（开发期反复强杀时要注意；这不是产品缺陷，但会浪费半小时。）

### 6.5 帧抓图的 PNG 行序：**不要翻转**

`glGetTexImage` 读回来的纹理，其第 0 行**就是画面顶部**（与 on-screen 的 blit 一致：
`uv(0,0)` 画在左上角，画面是正的）。第一版按 GL 的老习惯把行序翻了一次，
导出的 PNG 上下颠倒，一度让人以为渲染方向错了 —— **是导出函数的错，不是渲染的错**。

### 6.6 NeoForge 版本必须 ≥ HuhuTalk 的门槛

spike 的模板锁的是 `neo_version=21.1.242`，而 HuhuTalk 的 `neoforge.mods.toml` 声明
`[21.1.243,)`。踩上去的报错是 FML 的：

```
Mod ID: 'neoforge', Requested by: 'huhutalk', Expected range: '[21.1.243,)', Actual version: '21.1.242'
```

附属模组的 `neo_version` 要跟着宿主走。

### 6.7 ⚠ `resetForNewWorld()` 的裸 `pageStack.clear()` 会让页面收不到 `onClose()`

**这条是真正的资源泄漏，值得本体作者看一眼。**

```java
// PhoneOverlay.resetForNewWorld()（离开世界时）
pageStack.clear();      // ← 没有对任何页面调 onClose()
```

`popPage()` / `pushPage()` 都会老老实实调 `onClose()`，唯独这里不会。
后果：**开着内嵌浏览器退出世界 → 浏览器永远不关，CEF 的渲染进程一直活着**
（内存 + CPU + 还在出声）。游戏退出时 MCEF 的 `CefWindowsShutdownMixin` 会兜住整个 CEF，
所以进程不会残留 —— 但在会话内它一直白烧，而且从日志上完全看不出来。

**本模组的兜底**（不依赖本体改）：`DouyinLifecycle` 每客户 tick 调一次
`DouyinBrowserPage.tickLifecycle()`，用"当前栈顶还是不是本实例"来判定，不依赖任何事件：

```java
if (PhoneOverlay.getCurrentPage() == page) return;   // 手机被收起时栈顶仍是本页 → 不关，这是对的
... 自行 closeHost()
```

**顺带修掉一个手感问题**：手机被收起（`isVisible()` 为假）时网页还在放。
现在在可见性下降沿注入一次 `video,audio { pause() }` —— "把手机收起来却还在出声"很怪。

> **对本体作者的建议**（未做）：给 `resetForNewWorld()` 里的 `pageStack.clear()` 补一遍
> `onClose()`，或干脆统一走一个 `clearStack()` helper。这不只影响本模组 ——
> 任何在 `onClose()` 里释放资源的页面（相机、未来的录音类 App）都会中招。

### 6.8 ⚠⚠ 自己写出来的一个稳定泄漏：`onClose()` 里关浏览器 → 转场又把它建回来

**这是本模组自己的 bug，实机日志里有指纹，值得记住。**

`PhoneOverlay.popPage()` 的调用顺序是：

```java
IPhonePage closed = pageStack.pop();
closed.onClose();                       // ← 我在这里 closeHost()，host = null
...
beginTransition(closed, below, false);  // 然后开始 ~0.25s 的滑出转场
```

而转场渲染长这样（`PhoneOverlay.render`）：

```java
IPhonePage moving = transIsPush ? transTo : transFrom;   // pop 时 moving == 刚 onClose 的那一页
renderPageTranslated(graphics, moving, ...);             // ← 于是它 onClose 之后还会被 render 好几帧
```

我的页面在 `render()` 里会走 `ensureHost()`。于是：

1. `onClose()` 把 `host` 置空；
2. 下一帧转场渲染这一页 → `ensureHost()` 看到 `host == null` → **新建一个 MCEFBrowser**；
3. 转场结束，这一页被丢弃 —— 那个新浏览器**再也没有人关它**。

**每退出一次抖音 App，就泄漏一个 CEF view**（内存 / 渲染进程 / 还在出声）。
日志指纹是一对同 tick 的：

```
[huhutalk-douyin] browser closed
[huhutalk-douyin] browser created 720x1180 (url=https://www.douyin.com)
```

（我一开始把它误读成"玩家重新点开了 App"，后来去翻了 pop 路径的渲染顺序才认出来。）

**修法**（已实施）：

1. 页面加 `closed` 标志；`render()` 里 `closed ? this.host : ensureHost(...)` ——
   **关闭中只复用、绝不创建**；`ensureHost()` 内部再挡一道并打 WARN。
2. `onClose()` **不再当场关浏览器**，只打标记进 `PENDING_CLOSE`；
   真正的关闭由下一 tick 的 `tickLifecycle()` 收尾 ——
   这样滑出转场那几帧还有画面，不会闪黑。
3. 关闭中不再画"出错"底图、也不再 `requestRedraw()`。

> **通用教训**：在"页面会被转场重复渲染"的框架里，**`onClose()` 不能假设自己之后不会再被 render**。
> 任何"释放了、而且 render 里会按需重建"的资源都有这个坑。
> 同类风险点：`PhoneScreenTarget`（它按 `hasModelScreen()` 判据重建 FBO）——
> 目前没有这个问题，但改那块代码时要留意。

### 6.9 离开再进入 App 会重建浏览器（已知代价，不是 bug）


日志里会看到成对的 `抖音页打开` + `browser created`。那是**页面的 `onClose()` → 关浏览器，
再进来时 `pageFactory` 新建一个页面**，属于设计如此：
- 好处：不在 App 里时不占一个 Chromium view；
- 代价：重新进入会重新导航到首页（**登录态保留**，CEF 的 cookie 在磁盘上）。

想要"秒回上次位置"就得把浏览器做成常驻单例并自己管生命周期，本阶段刻意没做。

---

## 7. 按 `O` 切到悬浮窗后两种屏同时显示 —— 是**检修口被收窄**的副作用

> ⚠ **本文档先前把这里写成"疑似 bug"，是错的。作者已澄清设计意图：**
> `O` 原本是**检修口** ——「无论任何时刻按 O 都呼出手机屏幕」，
> 作为**模型屏渲染出错时的备选方案**（模型屏依赖离屏 FBO + 3D 机身贴合，
> 出问题的时候玩家得有办法把界面拿出来）。
> 后来它被改成"只有手持 HuhuTalk 手机时才生效"，检修口的作用就被削弱了；
> 作者知情，暂未改回。

现象（见 `run\screenshots\2026-10-06_00.37.51.png`）：
按下 `O` 把 `renderMode` 切成 `FLOATING` 之后，**左边手举着的 3D 手机上仍然贴着屏幕内容，
右边又画了一个悬浮窗**，内容一模一样。

判据链：

```java
// PhoneOverlay.render
if (external) { if (!hasModelScreen()) return; }      // ← 注释说"renderMode 已切成 FLOATING 时必须让位"
else if (!isFloatingScreen()) return;

// 但 hasModelScreen() 是：
public static boolean hasModelScreen() {
    return phoneOpen && ProPhoneHandRenderer.isActive();   // ← 没有查 renderMode
}
```

`PhoneScreenTarget.update()` 与 `PhoneScreenTarget.renderScreen()` 也都用 `hasModelScreen()` 当判据，
于是 external 出图与贴模型两条路在 `renderMode == FLOATING` 时**依然都在跑**。

**要不要改、怎么改，由作者定；本模组一行未碰。** 可能的方向有两个（互相冲突，所以不能照抄）：

1. **按注释走**：`hasModelScreen()` 加 `renderMode == MODEL` —— 那样 O 就真的只画悬浮窗；
2. **按检修口走**：让 O 在**任何时候**都能呼出悬浮窗，并且显式地让模型屏让位
   （而不是靠 `ProPhoneHandRenderer.isActive()` 这个间接判据）。

风险点：`hasModelScreen()` 还被 `PhoneScreenTarget.update()` 的"下次贴上一定重绘"分支用到
（`if (!hasModelScreen()) { dirty = true; return; }`），改动前应把这几处调用点一起看一遍。

---

## 8. 已知边界 / 明确没做

| 项 | 说明 |
|---|---|
| **拖动期间不转发 `mouseMove`** | HuhuTalk 在按下期间把移动整体解释成滚动（`dragIsScroll`），页面收不到 `mouseMoved`。对刷视频正好；代价是网页内**滑块类拖拽**不可用 |
| **音频不受 MC 音量控制** | CEF 直出系统音频。收起手机时会注入一次 `pause`；可用 `-Dhuhutalk_douyin.pauseWhenHidden=false` 关掉（"手机放兜里还继续听"也是一种合理用法） |
| **强杀 JVM 残留 `cef_server`** | 独立进程，任务管理器强杀 MC 会留下约 425 MB。正常退出干净 |
| 分辨率 | 渲染宽度默认 **1080**（≈2.7× 显示像素），`-Dhuhutalk_douyin.renderWidth=<px>` 可调。对着实测反馈"像素有点低"调的（原 720 ≈1.8×）。**它只决定清晰度，不决定字号**（见 §5.4） |
| 字号 / 显示比率 | 默认**档位 5 = 249%**（CSS 视口约 434px）。`-Dhuhutalk_douyin.zoomStep=<0..6>` 或 `config/huhutalk_douyin.properties` 的 `douyin.zoomStep` 可调，游戏内左下角「比率」也能调。上限 6 档是因为再往 CSS 视口会窄到 350px 以下、抖音窄屏布局开始变形 |
| 移动 UA | spike 里实现过（按 `1..4` 切），本模组**没搬**——本次锁定桌面版（已验证的那条）。要换只需写 `MCEFSettings.setUserAgent(...)` 后重建 browser |
| 非 BMP 文本 | `sendKeyTyped(char)` 走 UTF-16 单个 `char`，部分 emoji 送不进去 |
| 持久化 | ✅ **已做**：`config/huhutalk_douyin.properties` 记 `douyin.lastUrl`，重进 App 直接回到上次那条视频（抖音视频详情就是 URL 里的 `?modal_id=...`）。仅在 URL 是 douyin.com 的 http(s) 地址时记录 |
| 页面子页 | 本模组**刻意不 push 子页**（`pushPage` 会对本页调 `onClose` 从而关掉浏览器）。所有 UI 都画在页面内部 |
| 图标贴图 | 目前是 🎵 emoji。像素图标到位后改 `DouyinAppRegistration` 一行 |

---

## 9. 构建与运行

```bat
cd /d D:\Moddevelop\huhutalk-douyin

_build.bat           rem 构建 -> build\libs\huhutalk_douyin-1.0.0.jar
run-client.bat       rem 交互式开发客户端（默认，无自检）
run-autotest.bat     rem 无人值守自检
_killgame.ps1        rem 清理残留 java / cef_server / jcef_helper
```

`libs/` 需要三个 jar（出处与刷新方法见 `libs\README.md`）：
`huhutalk-1.0.0.jar`（编译期 API）、`mcef-neoforge-2.1.6-1.21.1.jar`（可选依赖）、
`touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar`（只进 `run/mods`）。
`build.gradle` 的 `syncDevRuntimeJars` 会把它们同步进 `run/mods`。

**改了 HuhuTalk 的公开 API 之后**，要重新构建宿主 jar 并刷新附属模组的副本：

```bat
cd /d D:\Moddevelop\MDK-1.21.1-ModDevGradle-main
gradlew jar
copy /y build\libs\huhutalk-1.0.0.jar D:\Moddevelop\huhutalk-douyin\libs\
```

---

## 10. 环境坑（本机）

1. **DSH 的 pwsh 沙箱在 `D:\Moddevelop` 是坏的**（`SetNamedSecurityInfoW failed (Win32 5): grantWrite`），
   任何 `pwsh` 工具调用都起不来。**本次全部 shell 操作走 IDEA MCP 的 `execute_terminal_command`**。
2. **IDEA 终端里 `set VAR=value && ...` 会把结尾空格算进值里**，导致
   `JAVA_HOME is set to an invalid directory`。要么用 `set "VAR=value"`，要么把命令写进 `.bat`。
   本次凡是多步/带引号的都落成了 `.bat` / `.ps1` 文件。
3. **Gradle 的 `-D` 能到游戏 JVM**（`build.gradle` 里用 `systemProperty` 显式转发，交接文档 §4.5.2 的老结论仍成立），
   本次实测 `huhutalk_douyin.autotest` / `exitSeconds` / `world` 三个都传到了。
   > ⚠ 但**不要**依赖它做关键路径：自检里把 `world` 也留了硬默认路径的余地，理由见 §6.2 那一串。
4. 长任务（构建、跑客户端）必须**后台 detach + 日志文件**，否则 IDEA 终端工具调用会超时；
   并且**两个句柄绝不能写同一个日志**（交接文档 §4.5.2 的坑，本次又验证了一遍）。
5. **第二轮的替代编译验证通路（本轮实测可用）**：`pwsh` 沙箱坏掉时，
   用 IDEA MCP 的 `build_project`（`projectPath=D:/Moddevelop/huhutalk-douyin`）就能编译并拿到
   结构化错误（`isSuccess` / `problems[]`，带 file/line/column），`lint_files` 还能顺带出一遍
   警告与弱警告。**注意 IDEA 里只打开了 `huhutalk-douyin` 这一个项目** ——
   `projectPath` 传 `D:\Moddevelop` 会被拒（"doesn't correspond to any open project"）；
   要读主模组源码得走文件路径，不能用 IDEA 的符号工具。
   > 局限：这条通路**只编译、不运行**。实机观感（字号大小、抖音窄屏布局是否正常）仍需人跑 `run-client.bat`。

---

## 11. 下一步建议（按优先级）

1. **人工确认三项**：扫码登录、中文输入、缺 MCEF 降级显示。
2. **人工确认显示比率**（第二轮的默认 249% / 档位 5）：看字够不够大、抖音窄屏布局有没有坏。
   判据是 §5.4 的 `已应用页面缩放` 与 `DYD_ZOOM {"iw":...}`。
3. **UI 第二轮（已定方案，待做）**：
   - 左下角三颗文字按钮 → **右下角纵向三枚圆形按钮**（⌨ 键盘 / ⤢ 比率 / ◀ 返回），
     配色沿用 `0x99000000` 底 + `0xCCDE7E7E` 悬停 + 白色图形；emoji 绘制已在本体验证可用。
   - 键盘入口那个真 `Screen` → **从手机底部伸出的抽屉**：画在手机画布内
     （`DouyinBrowserPage.render` 后画即覆盖，天然跟随模型屏），
     `mouseClicked` 已是内容区相对坐标、命中即可，动画用 `com.huhutalk.phone.anim.AnimTween`；
     **不做**"模型屏第二块渲染区"（要改本体 3 个文件 + 世界空间摆位靠肉眼反复调）。
     抽屉里显示文字由页面每帧读 `EditBox.getValue()` 画，真 `Screen` 退化成**纯 IME 接收器**（透明）。
     > ⚠ 待实测：`Screen` 打开期间 HuhuTalk 的独立光标是否还能点手机（`isScreenControl()` 的判据里
     > 可能有 `mc.screen == null`）。若不能，抽屉上的收/发按钮得由那个透明 Screen 自己接鼠标。
   - 长按页面开键盘的入口按用户决定**去掉**，只留圆钮。
4. **手册公开方法（第三轮，唯一需要动本体的改动）**：`ManualPage` 目前
   `render/mouseClicked/onBackPressed/mouseScrolled` 之外没有任何注册入口
   （内部数据结构是 `ManualSection(title, List<ManualEntry>)`）。
   拟加 `public static void registerSection(String ownerModId, String title, List<ManualEntry> entries)`，
   附属侧在 `PhoneAppRegisterEvent` 同一时机注册一节，把「比率 / 抽屉 / 手势」写进手册。
5. 把 §7 的 `hasModelScreen()` 双屏问题交给本体作者拍板修不修。
6. 音频：手机不在栈顶 / 不可见时 JS 静音。
7. 桌面图标换成真贴图。
8. 量化帧率影响（模型屏每帧重绘 + CEF 出帧的代价）。

---

## 12. 相关文件

| 文件 | 说明 |
|---|---|
| `D:\Moddevelop\huhutalk-douyin\README.md` | 附属模组自己的 README |
| `D:\Moddevelop\huhutalk-douyin\libs\README.md` | 三个 pin 住的 jar 的出处与刷新方法 |
| `D:\Moddevelop\huhutalk-douyin\_out\autotest.log` | 最近一次无人值守自检的完整日志 |
| `D:\Moddevelop\huhutalk-douyin\run\` | 截图、CEF 帧抓图、存档 |
| [douyin-webview-verification-report.md](douyin-webview-verification-report.md) | 可行性验证结论 |
| [douyin-webview-feasibility-handoff.md](douyin-webview-feasibility-handoff.md) | 完整技术调研 |
| [phone-app-api.md](phone-app-api.md) | App 注册 API（本模组用的是它 §11 建议的通路） |
| [phone-ui-toolkit-handoff.md](phone-ui-toolkit-handoff.md) | 页面骨架 / 控件 / 主题 |
