# 抖音附属模组（huhutalk_douyin）· 交接文档

> ⚠ **最新状态请先读另一个文件**：`D:\Moddevelop\huhutalk-douyin\HANDOFF.md`
> （当前会话的交接日志：文件定位、**两个未修 bug 的根因与修复代码**、需求清单、构建命令）。
> 本文件是**历次改动与踩坑的完整记录**，当参考手册用：下面 §5.4~§5.9 是逐轮的实现与决策记录。
>
> **面向**：接手 HuhuTalk 本体、或要继续做这个附属模组的人。
> **性质**：把「抖音网页版 App」从可行性验证推进到**可运行的独立模组**（阶段 1/2/3 的主体）。
> **工程位置**：`D:\Moddevelop\huhutalk-douyin\`（不在本工作区内，是独立工作区/工程）
> **前置阅读**：[douyin-webview-verification-report.md](douyin-webview-verification-report.md)（三个必踩的坑）、
> [phone-app-api.md](phone-app-api.md)（App 注册 API）。

---

## 0. 一句话状态

**HuhuTalk 手机桌面上多了一个「抖音」App：点开就是真实的抖音网页版，
用独立光标点、拖动刷视频、扫码登录，**底边一条带子里左右拖可以平移页面**。
本体多出来的东西都是增量：
两个纯新增的公开方法（`PhoneOverlay.requestRedraw()`、`ManualPage.registerSection(...)`）、
一个 `Screen` 守卫（修 bug）、手册一级列表的滚动（修老 bug）、
以及第 9 轮的 `IPhonePage.dragAxisBoth()` / `onDragAxisResolved(...)`（纯新增 default 方法 + 一个短路分支）。**

> ★ **第 9 轮（横向拖动）已竣工**：结项报告在
> `D:\Moddevelop\huhutalk-douyin\PLAN-horizontal-drag.md`（含四轮实测数据、两次失败的取证、
> 原方案作废的段落、以及两个踩出来的坑）。§1.5 是本体那一处改动的形状。

---

## 1. 对 HuhuTalk 本体的改动（**共五处**：三处纯新增、两处修 bug）

### 1.1 `PhoneOverlay.requestRedraw()` —— 让模型屏能逐帧刷新（第 1 轮）

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

### 1.2 `PhoneOverlay.onPhoneSideButton` 开头的 `Screen` 守卫 —— 第 2 轮加的，**这条是修 bug**

```java
public static void onPhoneSideButton(boolean clickButton, int action) {
    if (!isVisible()) return;
    // ⚠ 有 Screen 打开时不驱动手机。
    if (Minecraft.getInstance().screen != null) return;
    PhoneScreenTarget.markDirty();
    ...
```

**没有它会稳定复现一个数据丢失**：本模组的透明输入屏开着打字时，玩家按一下手机返回键
（侧键 5 —— 手机上最自然的"收键盘"手势），事件链是：

```
MouseHandler.onPress（原版 onPress 在开屏时照常触发）
  → PhoneOverlay.onPhoneSideButton(false, PRESS)     ← 老代码只看 isVisible()
  → handlePhoneLocalMouse(..., RIGHT, PRESS)
  → DouyinBrowserPage.mouseClicked(relX, relY, RIGHT, PRESS)   ← 没人消费
  → PhoneOverlay 的返回逻辑 → onBackPressed()
  → popPage() → onClose() → 关掉 CEF 浏览器
```

**打字打到一半，整个 App 被弹掉、浏览器被销毁，字全丢。**

**为什么拦掉是安全的（不是"牺牲功能换安全"）**：`Screen` 一打开，原版 `MouseHandler` 就不再调
`turnPlayer`，而独立光标**只在那里**被喂增量（见 `MouseHandlerMixin` 类注释第 39–41 行），
所以此刻光标本来就是冻结的、"驱动它"没有任何可观察效果 —— 这条输入本来就没用。

**那"Screen 里也要收键盘"怎么办？** 附属模组自己注册一个鼠标键映射来轮询点击
（`PhoneInput.DISMISS`，默认也是侧键 5）。这条通路是独立的、不与 HuhuTalk 的 `PHONE_BACK` 冲突
（各读各的 `KeyMapping`，互不消费）。这正是本模组采用的做法，也是给将来别的附属模组的答案。

### 1.3 `ManualPage.registerSection(...)` —— 让附属模组能往手机手册里追加说明（第 7 轮）

新增 **2 个公开静态方法** + 1 个静态表 + 构造器末尾追加 **1 行**（既有代码只多了那一行）：

```java
// ManualPage.java
public static boolean registerSection(String ownerModId, String title, List<ManualEntry> entries);
public static List<ManualSection> externalSections();

// 构造器最后：
synchronized (ManualPage.class) { sections.addAll(EXTERNAL_SECTIONS); }
```

**为什么要有它（这不只是"锦上添花"）**：附属模组会引入本体没有的交互
（本模组就是"左下角三枚圆钮 + 两个抽屉 + 侧键5 收起 App"）。没有说明的 App
等于"功能存在但没人发现"，而 `sections` 原本是构造器里硬编码的私有 list，**没有任何入口**。

**设计取舍（几个刻意的决定）**：

| 决定 | 理由 |
|---|---|
| 静态方法，**不引入事件** | §11 原本就是这么拟的。注册表是静态的、手册每次打开都重读快照，所以"注册时机"不敏感——唯一要求是"早于玩家第一次打开手册"，而这不需要事件来保证 |
| 外部栏目**追加在内置之后** | 内置栏目的顺序与内容是玩家已经看惯的，不该因为装了什么附属模组而变化 |
| 去重键 = `ownerModId + title` | 重复注册只警告不抛异常，也不会出现两节一样的说明；附属模组炸了不该把玩家的手册搞没 |
| 参数非法（空 id / 空标题 / **空条目**）一律拒绝并警告 | "注册了但看不见"最常见的三种原因，都在一行日志里能分开（本模组的 `DYD_MANUAL` 探针就是照这个设计的） |
| entries 整份 `List.copyOf` 后保存 | 调用方之后改自己的 list 不该影响已注册的内容 |
| 客户端判定（`FMLEnvironment.dist != CLIENT` 直接拒） | 与 `PhoneAppRegistry.register` 一致：手册是纯客户端的东西 |

**用法**（本模组 `DouyinManualSection` 是参考实现，7 个条目）：

```java
ManualPage.registerSection("mymod", "我的功能", List.of(
        new ManualPage.ManualEntry("标题", "正文（\n 可换行，会自动折行）", null)));
```

**验收判据**（不需要开自检，手工跑也有）：

```text
[HuhuTalk/Manual] 已支持附属模组追加手册栏目（首次来自 huhutalk_douyin 的「抖音」）
DYD_MANUAL 手册追加栏目：count=1 titles=[抖音] 抖音条目数=7
```

### 1.4 一级栏目列表加滚动 —— 不做的话 1.3 等于白做（第 7 轮）

**这是本轮挖出来的老 bug，不是附属模组引进来的。**

`renderSectionRoot()` 原实现是"装不下就 `break`"：**既没有 scissor、也没有滚动**，
而 `mouseScrolled` 在一级直接 `return false`。于是：

- 内置栏目已经 **11 节**（huhutalk / 关于手机 / 关于设置 / 聊天页 / 联系人设置 / 联系人 /
  桌面 / 指令 / 模组兼容 / 物品与方块 / 写在最后）；
- 手机内容区默认只放得下约 **4 行**：
  `(h - sh(22) - sh(6) - sh(6)) / (sh(34) + sh(4))`，`h` 取实机日志里的 `内容区=…x185` → `151 / 38 ≈ 4`；
- 也就是说「物品与方块」「模组兼容」「写在最后」在多数机型上**本来就点不到**；
- §1.3 追加的栏目排在最后，**永远看不见** —— 公开通路再正确也没用。

**修法**：照抄二级列表（`renderSectionList`）那套已经验证过的写法
（`clamp` + `scissor` + 滚动偏移 + 跳过滚出上界的行），约 15 行，涉及三处：

| 位置 | 改动 |
|---|---|
| `renderSectionRoot()` | 加 `maxScroll` clamp、scissor、`cy = top - rootScroll`、跳过滚出上界的行 |
| `mouseClicked()` 一级分支 | 用**同一份几何**算命中行（否则"看到的位置"和"点到的位置"会不一样） |
| `mouseScrolled()` | 补一级分支；**装得下时仍 `return false`**，所以原本能全显示的机型行为一字不变 |

**新增字段 `rootScroll`**（一级专用）：`sectionScroll` 是二级的，两者必须独立 ——
从某一节退回一级时，一级应该还停在玩家离开时的那一屏。

**没做**：滚动条指示器（与二级列表现状一致，要加就两处一起加）。

**人眼验收**：手册首页能滚；列表最后有「抖音」；点进去 7 条；退回首页仍停在原来那一屏。

### 1.5 `IPhonePage.dragAxisBoth()` / `onDragAxisResolved(...)` —— 让页面按本次手势分派主轴（第 9 轮）

新增 **2 个 default 方法**（纯新增，既有页面行为一字不变）+ `PhoneOverlay.moveCursor`
latch 那一段约 12 行：

```java
// IPhonePage
/** true = 本页同时接受横向与纵向拖动；主模组改为"按本次手势的真实主轴分派"。 */
default boolean dragAxisBoth() { return false; }

/** 本次拖动被判为哪个轴（仅 dragAxisBoth() 为 true 时回调，早于同一帧的 mouseScrolled）。 */
default void onDragAxisResolved(boolean horizontal, double relX, double relY) { }
```

**为什么非改不可**：旧契约是"整页一个静态主轴"（`dragAxisHorizontal()`），
而那个主轴**同时兼任**"这次拖动算不算滚动"的判据
（`primary = horizontal ? dragTotalX : dragTotalY`，必须 `|primary| > |cross|` 才 latch）。
对一个"横竖都要收"的页面（网页看视频 + 平移页面）它返回什么都坏事：
返回 true ⇒ 一次以纵向为主的拖动永远 latch 不上，**竖向刷视频当场报废**；返回 false ⇒ 收不到横向增量。

**形状**：`dragAxisBoth()==true` 时，latch 那一刻按 `|dragTotalX| >= |dragTotalY|` 定轴，
回调 `onDragAxisResolved`，之后 `mouseScrolled` 收到的就是那个轴的增量；
`both==false` 的路径与旧行为**逐字节等价**（代码上就是一个短路 `if`）。

**与当初批准草案的唯一差异**：回调多带 `relX/relY`（latch 时刻的内容区坐标）。
理由：附属模组要按**位置**划分一块"滚动条操作带"，而拖动期间主模组不转发 `mouseMoved`，
只给一个布尔值页面无从知道"这次拖动从哪开始"。

**⚠ 下游必须自己记住轴**：附属模组原来的 `horizontalDrag` 字段是 `dragAxisHorizontal()`
顺手写入的，改走新钩子之后那个方法不再被调用 —— 漏了赋值就会出现
"拖底边却上下滚页面"（日志特征：`接管本次拖动` 有、`DYD_PAN_JS` 一条没有）。实测踩过。

**没有动的东西**：`dragAxisHorizontal()` 保持原样（既有页面继续用它）；没有任何
`both==false` 的代码路径被改动。

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
├── HuhuTalkDouyin.java            @Mod 主类：构造期抢设 org.cef.commandLineArgs + 注册手册栏目
├── DouyinAppRegistration.java     PhoneAppRegisterEvent -> PhoneApp.builder(...).slot(4)
├── DouyinManualSection.java       往手机手册追加一节「抖音」用法说明（7 条，见 §1.3）
├── DouyinBrowserPage.java         implements IPhonePage：浏览器宿主 + 圆钮/抽屉 + 输入桥接
├── DouyinWidgets.java             自绘小控件（圆钮/圆角矩形/圆角外框）+ 配色唯一源头
├── DouyinZoom.java                显示比率换算与档位（0..6）+ 持久化
├── PhoneInput.java                本模组自己的鼠标键映射（Screen 里收"侧键5"手势）
├── DouyinTextInputScreen.java     **透明**输入屏（真 Screen + 屏幕外 EditBox，只吃 IME 事件）
├── DouyinPrefs.java               config/huhutalk_douyin.properties 读写
├── DouyinLifecycle.java           每 tick 兜底 + park/stow 两个后台槽位
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

### 4.2 侧键5（返回）为什么不做"浏览器后退"

CEF 的历史里有 `about:blank`，`canGoBack()` 一开始就是 true —— 按一下会掉进空白页，反而退不出去。
所以**侧键5 = 退出 App 回桌面**；浏览器后退交给右下角那颗「◀」圆钮（`browserBack()`）。

第 2 轮加了抽屉之后，侧键5 的完整语义变成**一级一级退**：

| 当前状态 | 侧键5 的效果 |
|---|---|
| 透明输入屏开着 | 只收输入屏（抽屉留着）—— 由 `PhoneInput` 轮询发现，见 §1.2 |
| 输入屏没开、抽屉展开着 | 收抽屉（`DouyinBrowserPage#pollDismissKey` 消费，不透传到 `onBackPressed`） |
| 什么都没有 | 走 HuhuTalk 原路 `onBackPressed()` → 退出 App 回桌面 |

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
| `run\screenshots\2026-10-06_00.38.29.png` | 点开后进入的视频页（有封面、点赞数、评论数、作者）。注意截图里的「返回 / 键盘」是**第 1 轮的左下角浮层按钮**，第 2 轮已换成右下角圆钮（⌨ / ⤢ / ◀），这张图仅作历史对照 |
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
比率抽屉（`DouyinBrowserPage#clickDrawer` 里的 −/100%/+）、
`DouyinBrowserPage#ensureHost/applyZoomNow/syncZoom`（建完即应用 + 每帧幂等同步，
防页面脚本把 zoom 改回去）。

> 第 2 轮改版后，第 1 轮那个「比率屏」（`DouyinZoomScreen`，真 Screen + Ctrl+ +/-）
> **已删除**：档位调节搬进了比率抽屉，用虚拟光标点即可，不再需要为调个字号切换输入语言。
> 代价是丢失"浏览器里 Ctrl+ +/-"的肌肉记忆 —— 想加回来就照当时那份重写一个 Screen，
> 但要记住它的代价：Screen 一开，独立光标会冻结。

### 5.5 第 2 轮 UI：圆钮 / 抽屉 / 透明输入屏

**形态**：右下角纵向三枚圆钮 `⌨`（键盘）/ `⤢`（比率）/ `◀`（返回）；前两个各自展开一块
**从右侧横向滑出**的抽屉（`0x80181820` + 粉描边，圆角 8，160ms ease-out）。

**尺寸是实机反馈改出来的第四版**（记一下，省得再调回去）：

| 版本 | 尺寸 / 位置 | 实机结果 |
|---|---|---|
| v1 | 抽屉宽 80% × 高 62%、右下留边；圆钮在右下 | 抽屉太小偏高；圆钮挡住网页「转发」 |
| v3 | 抽屉满宽、上边界齐最上圆钮、下边界停在圆钮列上方；圆钮移左下 | 抽屉"大小正正好"，但**挤到圆钮列**、且与抽屉内按键重合 |
| v4 | 抽屉横向从右侧收回（`x = 4.5r`）给圆钮列让位；圆钮 `r = w/14` | 不挤了，但**抽屉偏窄** |
| **v5（当前）** | 抽屉左边界 = 圆钮列右边缘 + 一条细缝（`x = (r+margin)*2 + gap`），**与圆钮列相接** | 待人工确认 |
| 圆钮半径 | `r = min(12, max(5, w/14))`（≈ 手机宽 14%） | 1/12 偏大、1/11 放大后仍偏大 |
| 圆钮位置 | `cx = r + 0.7r`（离**左**边缘约 0.7 个按钮宽） | 右下挡「转发」、左下挡「分区」，只此一列不挡 |

> ⚠ v4/v5 的一个坑：抽屉收回来之后，关闭圆钮（✕）与输入框的 X 坐标必须用**面板内**的右边界
> （`drawerX + drawerW`），不能再用内容区宽 `w` —— 否则 ✕ 会被画到面板之外，
> 只在面板外边留一个看不见的命中区。

**两个抽屉无缝切换（实机反馈的 bug）**：命中顺序固定为 **圆钮 → 抽屉内部 → 面板外**
（与绘制顺序相反），这是"永远点得到"的硬保证。
v4 之后抽屉与圆钮列已经不重叠，这条顺序退化成保险。

**为什么是抽屉、不是"模型屏第二块渲染区"**（当时的决策记录，避免重走）：

| | 模型屏加第二块渲染区 | 抽屉（已采用） |
|---|---|---|
| 改动面 | 主模组 3 个文件（`PhoneScreenTarget` 加区域并扩大 FBO、`PhoneOverlay` 加绘制、`ProPhoneHandRenderer` 加一块世界几何四边形） | 只改本附属模组 |
| 几何 | 要在模型空间摆一块新面（跟机身共面打架 / 被手挡 / 第一人称出画），位置得靠肉眼反复调 | 画在手机画布内，`renderExternal` 本来就给了画布宽高 |
| 输入 | 独立光标是归一化到手机矩形的，第二块区域要另立一套映射 | `mouseClicked` 拿到的就是内容区相对坐标，一次 `hitRect` |
| FBO | 离屏靶像素高必须 = 窗口帧缓冲高（scissor 前提），并排后横向像素直接翻倍 | 不需要新 FBO |

**打字的闭环**（用户拍板的语义）：

```
点 ⌨ → 抽屉滑出 → 点抽屉里的输入框 → 透明输入屏打开（系统光标出现、虚拟光标冻结）
     → 打字（字实时画在手机抽屉里）→ Enter 送入网页 → 只关输入屏、抽屉留着
     → 侧键5 收抽屉；再按侧键5 才退 App
```

**几条刻意的设计决定**（都是"不这么做就会出问题"的）：

1. **输入屏什么都不画**（`DouyinTextInputScreen.render` 是空的）。
   字由页面每帧读 `EditBox.getValue()` 画进抽屉，于是玩家看到的字属于手机那套语言，
   而不是浮在游戏画面上的另一套 UI。代价：必须从页面侧读它，所以留了
   `currentTextOr()/isOpen()` 两个静态接口，以及 `debugText()` 给自检。
2. **点抽屉面板之外 → 先收抽屉，这一下照常转发给网页**。只收不转发的话，
   玩家点一下页面会发现"什么都没发生"，像卡住。
3. **面板内部的点击一律吞掉**，不穿透到下面的视频（模态语义）。
4. **侧键5 一级一级退**：`pollDismissKey()` 在页面层用 `PhoneInput` 轮询消费，
   抽屉没开时完全不拦，于是照旧走 `onBackPressed()` 退 App。
5. **`openDrawer` 里调 `PhoneInput.drain()`**：丢掉积压的点击，
   否则"开抽屉之前按的那几下"会立刻把刚开的抽屉关掉（这类幽灵点击会被误判成"一开就自动关"）。
6. **长按页面开键盘的入口删掉**：`onLongPress` 改成返回 `false`（不消费 → HuhuTalk 补一次普通点击），
   于是长按等价于一次点击。抽屉是显式动作，不该和一个用来刷视频的手势抢。
7. **命中用外接圆（半径 + 2）而非精确圆**：虚拟光标要一寸寸挪，宽容一点能明显减少"点上了却没反应"。

**验收判据**（圆钮/抽屉的命中是内容区坐标下的几何，自检不替人点它，理由见下）：

| 日志 | 含义 |
|---|---|
| `抽屉展开：KEYBOARD` / `抽屉收起：ZOOM` | 圆钮点中了、状态机在跑 |
| `输入屏打开（本屏不画任何东西…）` | 从抽屉进到了 IME 屏 |
| `已向网页送入 N 个字符（入口：输入屏回车 / 抽屉送入）` | 两条送入入口都通了（同一份 `commitText`） |
| `侧键5：收起输入屏（抽屉保留）` | 守卫 + `PhoneInput` 轮询这一套生效 |
| `侧键5：收起抽屉（下一次再按才会退出 App）` | 一级一级退的顺序正确 |
| `DYD_AUTOTEST t=38s drawer=… imeOpen=… screen=…` | 自检回读的抽屉/输入屏状态快照 |

**自检为什么不去点抽屉**：自检驱动走 `PhoneOverlay.handlePhoneLocalMouse`，
而圆钮/抽屉的命中是手机内容区坐标下的几何 —— 在无人值守里算出那个坐标得把圆钮布局复制一遍，
UI 一调它就静默失效（"自检自己算错坐标"比没有自检更糟）。
所以自检只回读状态（`isDrawerOpen/isKeyboardDrawerOpen/debugText`），点按验收交给人工。

### 5.6 第 3 轮：后台驻留（退出 App 不立刻关浏览器，3 分钟兜底）

**要解决的问题**（用户反馈）：每次退出再进抖音都要重新加载整页，那段黑屏有明显的阻塞感。

**做法**：退出 App 时浏览器不立刻销毁，留在后台继续活着；玩家再点进抖音时把同一个浏览器
**接管**过来，于是没有重建、没有加载、没有黑屏。

| 时机 | 行为 |
|---|---|
| `onClose()` | 标 closed、记 URL、**立刻 `pauseMedia()`**（不再出声），等转场画完 |
| 转场后（`settleAfterClose`） | host 还在 → `DouyinLifecycle.park()` 驻留；已被新页接管（host == null）→ 什么都不做 |
| 回到前台（`onOpen`） | ① 接管上一个页面的 host（覆盖"手滑退出又马上点回来"的转场窗口）<br>② 再试从驻留槽接管（覆盖"回桌面待一会儿再进来"） |
| 驻留满 **3 分钟** | `DouyinLifecycle.tickParked()` 强制 `closeHost()` 释放内存 |
| 打开**别的** App | 照旧驻留（按下面的判据），但**永远只留一个** —— 新驻留会先把上一个关掉 |

**3 分钟是用户拍板的**，可用 `-Dhuhutalk_douyin.parkSeconds=<秒>` 覆盖；
`=0` 就是第 1 轮的老行为（退出 App 立刻关浏览器）。

**接管时必须连 `hostAspect` 一起搬**：它决定"显示矩形比例变了就重建"（`ASPECT_TOLERANCE`）。
只搬 host 不搬比例的话，接管后的第一帧就会判成比例变化过大，立刻把浏览器关掉重建 ——
接管等于白做，还多一次黑屏。

**⚠ 一个差点写错的判据**：最初我按"`getCurrentPage() == null` = 玩家回到桌面"来写驻留判定。
读源之后发现是错的：`PhoneOverlay.popPage()` 里 App 顶层页返回桌面是
`pageStack.clear(); pushTop(new MainPage())` —— **桌面本身也是一个页**，
`getCurrentPage()` 在桌面时返回 `MainPage`、永远不为 null（`pageStack` 也恒为 1，见 `isInSubPage()`）。
按原来那个判据，驻留路径**永远不会触发**。现在 `settleAfterClose` 只在 `host != null` 时驻留，
而"切到另一个抖音页"那条路 host 早被接管掉了，天然不会误驻留。

**日志判据**：

```
退出抖音（当前页=MainPage），浏览器转入后台驻留
浏览器转入后台驻留，180 秒内回来可秒开（否则自动关闭）
抖音页打开：mcef=true 内容区=100x180
已从后台驻留接管浏览器（跳过重建，无黑屏加载）      ← 秒开成功的证据
后台驻留超过 180 秒，关闭浏览器释放内存              ← 兜底生效的证据
```

### 5.7 第 4 轮的四个实机问题（圆钮再缩小 / 抽屉让位 / 拖动收抽屉 / 横向拖动）

**① 圆钮再缩小** → `r = min(12, max(5, w/14))`。两轮反馈：1/12 偏大、1/11 放大后仍偏大。

**② 抽屉让位**：横向从右侧收回 `x = 4.5r`，下边界停在圆钮列上方。见 §5.5 的 v4 表。

**③ "拖动页面即关闭抽屉"没成功 —— 根因是判错了事件**

写在 `mouseClicked` 里永远不会触发。因为 HuhuTalk 把"按住拖动"整体归类成**滚动**：

```java
// PhoneOverlay.moveCursor（按住期间）
if (dragIsScroll) { scrollRemainder += ...; handlePhoneLocalScroll(...); }
// PhoneOverlay.onPhoneSideButton（松手）
if (!dragIsScroll) { ...补一次普通点击... }      // ← dragIsScroll 为真时**不补点击**
```

也就是说拖动过程中只有 `mouseScrolled`，没有 `mouseClicked`。
现在判据搬到 `mouseScrolled` 开头：抽屉开着就先收，然后**这一次拖动照常转发给网页**
（只收不转发会让玩家以为页面卡住了）。

**④ 横向拖动不成功 —— 两个独立原因，都修了**

**(a) 横向分量被丢了。** `MCEFBrowser.sendMouseWheel(x, y, deltaY, deltaX)` 有两个分量，
而 `BrowserHost.mouseWheel` 只传了 `deltaY`、`deltaX` 恒传 0。
现在接口扩成 `mouseWheel(x, y, deltaY, deltaX)`，横向手势走 `deltaX`。

**(b) `dragAxisHorizontal` 不会把纵向滚轮变成横向。** 它只决定 HuhuTalk 按哪个轴
**判定**"算不算拖动"、并按哪个轴累加增量；交给页面的仍是同一个标量 `mouseScrolled`。
所以轴的分派必须在页面里自己做（`mouseScrolled` 里按 `horizontalDrag` 分流）。

**⚠ 但用户提出的疑点很可能是对的，而且这才是关键**：
zoom 的本质是"CSS 视口变窄"，**不是"让内容变宽"**。所以除非页面里有
**固定宽度**的元素（比视口宽），否则 `scrollWidth == clientWidth`，
**根本没有横向可滚的内容** —— 那时横向拖动不是"没实现"，而是"没有可拖的东西"。

所以这一轮没有硬编码"缩放大了就横拖"，而是**按实测数据决定**，并为此加了两条探针：

| 探针 | 内容 | 在哪看 |
|---|---|---|
| `DYD_OVERFLOW {...}` | 每 2 秒一条：`scrollWidth / clientWidth / innerWidth / scrollHeight / innerHeight / over / zoom` | 页面 console（会进游戏日志） |
| `横向溢出=…` | 自检 t=38s 回读的结论 | `DYD_AUTOTEST` |

`over = 0` 就是"没有横向可拖"的铁证。判据（`dragAxisHorizontal`）只在 `over > 0` 时切横向；
**反过来绝不切** —— 因为切轴会同时换掉"刷视频"的判定轴，在没有可拖内容时切轴只会毁掉刷视频，
比不实现更糟。

> ### ⛔ 上面这段的结论在第 9 轮被推翻了 —— 保留它作为现场记录
>
> 1. **`over = 0` 是假阴性**：那个探针把一个元素的 `scrollWidth` 减了另一个元素的 `clientWidth`
>    （`max(html.sw,body.sw) - max(html.cw,body.cw)`），把真实存在的 107px 溢出抹平了。
> 2. **真正"切轴会毁掉刷视频"的原因不是 `over`，而是契约本身**：latch 的判据用的就是那个主轴。
>    所以修法不是"判据更准"，而是**换成按本次手势分派** —— 见 §1.5 与 `PLAN-horizontal-drag.md`。
> 3. **第 9 轮实测**：档位 4/5/6 上"把横向增量当滚轮喂给 CEF"和"合成原生 press→move→release"
>    **两条路全部 `moved=false`**；唯一有效的是**直接写 `documentElement.scrollLeft`**
>    （三个档位全部 `moved=true`）。最终实现就是走这条 + 内容区底边的一条"滚动条操作带"。
>
> **`dragAxisHorizontal` 及其判据现在对抖音页不再被调用**（`dragAxisBoth()` 返回 true 之后，
> 主模组走新分支）。重写与注释都保留了，方便回退与查阅。

**`over` 这个布尔值是怎么从页面回到 Java 的**（CEF 没有回调式返回值）：

新增 `BrowserHost.addConsoleListener(ConsoleListener)`，由 `McefBrowserHost` 通过
`MCEFClient.addDisplayHandler(new CefDisplayHandlerAdapter(){...})` 实现，
在 `onConsoleMessage` 里**返回 false（放行）**，让 MCEF 自己那份 handler 照旧把消息写进日志
（返回 true 会把页面 console 从 latest.log 吃掉，等于毁掉整条 `DYD_*` 证据链）。

这属新增接口方法（`BrowserHost` 是附属模组自己的抽象，**不涉及主模组**），
默认实现为空，缺 MCEF 时不受影响。

### 5.8 第 5 轮：持久化"没干活"的定位与修复

**实机反馈**："关掉抖音再进去直接重置了。"

**先看落盘文件**（这一步是决定性的，比读代码快得多）：

```properties
douyin.lastUrl=https\://www.douyin.com/?recommend\=1     ← 首页/推荐流，不含"看到哪条"
douyin.zoomStep=5
```

也就是说**写是写了的**，问题是**收下的 URL 根本没有"上次看到哪"的信息**。
抖音的视频详情是 `?modal_id=...`，而 `?recommend=1` 是首页 —— 靠它永远续不上。

**修复**：`DouyinPrefs.remember()` 分成两档：

| 类别 | 判据 | 行为 |
|---|---|---|
| 可续播 | 含 `modal_id=` / `/video/` / `/note/` / `/user/` | 直接覆盖（信息量一定更高） |
| 不可续播 | 首页 / 推荐流 / 根路径 | **不覆盖**已有的可续播地址 |

第二条是关键：否则"看着视频点回首页、然后退出"就会把视频地址冲掉 ——
现象与"持久化完全没干活"一模一样，极难与"根本没写文件"区分开。

**同时补了来源日志**（这类"到底读没读到"的故障必须一眼可判）：

```
缩放档位来源=配置(有记录) 值=3（默认是 5）                              ← DouyinZoom.initialStep
没有可接管的驻留浏览器，将按记录的位置重建（记录=/jingxuan?modal_id=…）   ← onOpen
记录浏览位置：/jingxuan?modal_id=…（可续播=true）                        ← remember 落盘
当前页 /?recommend=1 不含视频标识，保留已记录的地址（不覆盖）
不进后台驻留（PARK_MS=180000 page=ok hasLiveHost=false）
```

最后那一行是给"驻留没生效"准备的：它把三个可能的原因（属性被设成 0 / 页面为 null /
host 不是 READY）分别打出来；`hasLiveHost=false` 就意味着那次 CEF 实例在退出时已经不健康了。

> 附：`takeParked()` 发现"槽里的 host 已不可用"时，现在会**先把它关掉**再返回 null。
> 之前是直接丢掉槽位 —— 那会留下一个没人负责的 CEF view（内存泄漏）。

### 5.9 第 6 轮：侧键5 从「关掉 App」改成「收起 App」

**用户的提问**：能不能让侧键5 走和"把手机从主手拿下来"一样的路子 ——
视频暂停、下次进来还在这个位置，也就是**收起页面**而不是"返回"？这两条路到底差什么、开销如何？

**先回答"到底怎么回事"**（读源结论，两侧差异是真存在的）：

| | 收起手机（`doHideNow()`） | 侧键5（原实现 `popPage()`） |
|---|---|---|
| 页面栈 | **不动**，抖音页还在栈顶 | `pageStack.clear()` + push `MainPage` |
| `onClose()` | **不调** | 调 |
| 浏览器 | 同一个 CEF view 原地活着 | 进入 park → 3 分钟后关闭 |
| 媒体 | `tickLifecycle` 里暂停 | `onClose` 里暂停 |
| 回来时 | 同一个 page 对象、同一进度 | 3 分钟内接管；超时则重建并重新加载 |

**不是"杀掉 mcef 进程"**：CEF 进程由 MCEF 自己管、只在游戏退出时才关
（`CefWindowsShutdownMixin`）。侧键5 关掉的只是**那个页面对应的一个 CEF browser 实例**。

**开销对比（结论：几乎一样）**：两条路都"媒体暂停 + 不渲染"
（收起手机时 `hasModelScreen()` 为假，页面也不在 GUI 层画），
剩下都只是一个**闲置 CEF view 的常驻内存**。

#### ⚠ 中间走过一版错路（记下来，因为它看起来更"直接"）

第一版：`onBackPressed()` 只返回 `true`，再调 `PhoneOverlay.toggle()` ——
结果那等于**把手机整个收起来**。玩家反馈：*"进了抖音出不来了，按侧键直接关屏"*。

第二版：自己往栈里压一个 `MainPage`。能回桌面了，但有两个新问题：
栈里留下"僵桌面"，而且**桌面上再按一次侧键5 会退回抖音、而不是关掉手机** ——
根因是 `MainPage` 没有覆写 `onBackPressed`，于是 `popPage()` 里
"`pageStack.size() == 1` → `hide()`"那条分支永远走不到。

#### 最终做法（`stowApp()`）：反过来的顺序 —— 先摘除浏览器，再让 HuhuTalk 正常返回

```java
pauseMedia();
DouyinPrefs.remember(host.url());   // 摘除后就读不到 URL 了，先把地址落盘
DouyinLifecycle.stow(this, now);    // 浏览器进"已收起"槽，倒计时开始
this.host = null;                   // ★ 把浏览器从本页摘除 → 本页变成没有资源的空壳
PhoneOverlay.popPage();             // 让 HuhuTalk 正常清栈回桌面
```

于是四件事同时成立：

1. 栈被**正常清理**（与"退出 App"同一条路），桌面收到 `onOpen()`；
2. 桌面那套逻辑（含"再按一次侧键5 关手机"）**完全保持原样**；
3. 本页 `onClose()` 里 `host == null` → 它不会去关浏览器；
4. 浏览器在"已收起"槽里，3 分钟内玩家打开抖音就被新页面 `adoptHostFrom` 接管 ——
   对玩家就是同一个抖音、同一段视频、秒开。

#### 两个后台槽位的分工（`DouyinLifecycle`）

| 槽位 | 什么时候进 | 页面还在栈里吗 | 恢复方式 |
|---|---|---|---|
| `stow`（已收起） | 侧键5 收起 App / 收起整个手机 | 在（或刚被正常弹出） | 打开抖音 → `onOpen` 接管 |
| `park`（后台运行） | 打开了**别的 App**（手机还显示着） | 不在 | 重新打开抖音 → `onOpen` 接管 |

两者共用同一个时限 `PARK_MS`（默认 180 秒；`-Dhuhutalk_douyin.parkSeconds` 可调，`=0` 立即释放）。
`markForeground()` 在页面**每帧被真正渲染**时把两边的倒计时都取消 ——
"正在被渲染"就是前台的定义，不需要额外的回调。

> ⚠ 一个只会在"收起后又去开了别的 App"这条路径上出现的坑：如果同一个页面同时躺在两个槽里，
> 两个倒计时都会去关那个浏览器，玩家回来就变成"重建 + 黑屏加载"。
> 所以 `settleAfterClose()` 只登记 `revive` 槽、**不**再登记 `park`（`stow` 已经在按下那一刻登记过了）。



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
| **拖动期间不转发 `mouseMove`** | HuhuTalk 在按下期间把移动整体解释成滚动（`dragIsScroll`），页面收不到 `mouseMoved`。对刷视频正好；代价是网页内**滑块类拖拽**不可用（例如**拖动视频进度条**）。**第 9 轮没有改这一点**：横向平移走的是"按 1:1 写 `documentElement.scrollLeft`"，不需要指针事件 |
| **横向平移只在底边带里生效** | 内容区底部默认 5 个 GUI 单位（`-Dhuhutalk_douyin.bandHeight`）。带外横拖**什么都不做** —— 这是刻意的：页面内的横向手势（图文轮播、进度条）保持原样。方向 = 滚动条语义（`-Dhuhutalk_douyin.panSign=±1`），幅度 `-Dhuhutalk_douyin.panScale` |
| **横向平移的可行边界（第 9 轮实测）** | 视口的横向溢出被 `body{overflow-x:hidden}` 传播封死 ⇒ **不画滚动条、也不接受用户滚动**，只能由 JS 写 `scrollLeft`；真正能滚的量只有 `maxX`（档位 4/5/6 上依次 107/194/267 CSS px），**一屏就到底**。喂滚轮与合成原生拖拽**两条路都实测无效**，别再试 |
| **音频不受 MC 音量控制** | CEF 直出系统音频。收起手机时会注入一次 `pause`；可用 `-Dhuhutalk_douyin.pauseWhenHidden=false` 关掉（"手机放兜里还继续听"也是一种合理用法） |
| **强杀 JVM 残留 `cef_server`** | 独立进程，任务管理器强杀 MC 会留下约 425 MB。正常退出干净 |
| 分辨率 | 渲染宽度默认 **1080**（≈2.7× 显示像素），`-Dhuhutalk_douyin.renderWidth=<px>` 可调。对着实测反馈"像素有点低"调的（原 720 ≈1.8×）。**它只决定清晰度，不决定字号**（见 §5.4） |
| 字号 / 显示比率 | 默认**档位 5 = 249%**（CSS 视口约 434px）。`-Dhuhutalk_douyin.zoomStep=<0..6>` 或 `config/huhutalk_douyin.properties` 的 `douyin.zoomStep` 可调，游戏内右下角「⤢」抽屉里也能调（虚拟光标点 −/100%/+）。上限 6 档是因为再往 CSS 视口会窄到 350px 以下、抖音窄屏布局开始变形 |
| 输入屏开着时独立光标冻结 | 真 `Screen` 的必然结果（HuhuTalk 的独立光标只在鼠标被抓取时更新，见 `MouseHandlerMixin` 第 39–41 行）。所以"收键盘"只能靠侧键5 / `Enter` / `ESC`，**不能用虚拟光标去点关闭** |
| 抽屉会盖住网页 | 刻意如此：抽屉满宽、上边界与最上方圆钮齐平、下边界停在圆钮列上方（实机反馈定的尺寸）。命中顺序是"圆钮 > 抽屉 > 网页"；点抽屉之外会先收抽屉、并把这一下转发给网页 |
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
5. **第二轮的替代编译验证通路（已实测可用）**：`pwsh` 沙箱坏掉时，
   用 IDEA MCP 的 `build_project`（`projectPath=D:/Moddevelop/huhutalk-douyin`）就能编译并拿到
   结构化错误（`isSuccess` / `problems[]`，带 file/line/column），`lint_files` 还能顺带出一遍警告。
   **注意 IDEA 里只打开了 `huhutalk-douyin` 这一个项目** —— `projectPath` 传 `D:\Moddevelop`
   会被拒（"doesn't correspond to any open project"）；要读主模组源码得走文件路径，不能用 IDEA 的符号工具。
   > 局限：这条通路**只编译、不运行**。实机观感（字号大小、抽屉位置、抖音窄屏布局）仍需人跑客户端。
6. **要跑 Gradle 就落成 `.bat`，别在 IDEA 终端里拼引号。**
   `execute_terminal_command` 经 PowerShell 调 `cmd /c` 时嵌套引号会被吃掉：
   `set "JAVA_HOME=..."` 传进去只剩 `set JAVA_HOME=...`，尾随空格进了值里，
   报 `JAVA_HOME is set to an invalid directory`。本轮新增了两个脚本：
   - `huhutalk-douyin\_build.bat`（已有）：编译 + 打附属模组的 jar
   - `huhutalk-douyin\_mmbuild.bat`（本轮新增）：`gradlew jar --rerun-tasks` 编译**主模组**，
     再把 `build\libs\huhutalk-1.0.0.jar` 拷进 `huhutalk-douyin\libs\`
     > ⚠ `--rerun-tasks` 不是为了慢：**没有它，Gradle 会报 `compileJava UP-TO-DATE` 并跳过
     > 你刚改的那个文件**（本轮实测：改完 `PhoneOverlay.java` 直接跑 `compileJava` 得到
     > "BUILD SUCCESSFUL in 1s / 2 up-to-date"，等于什么都没验）。
   - 顺序：改主模组 → `_mmbuild.bat` → `_build.bat`（附属模组按新 API jar 重编）

---

## 11. 下一步建议（按优先级）

1. **人工确认**：扫码登录、缺 MCEF 降级显示、**第 2 轮 UI**（圆钮位置/大小、抽屉位置与高度、
   打字闭环、显示比率观感）。判据见 §5.4 / §5.5。
2. ~~**手册公开方法（第 3 条，唯一还需要动本体的改动）**~~：✅ **第 7 轮已做**，见 §1.3
   （最终形状和这里原本拟的一致：`public static boolean registerSection(String ownerModId,
   String title, List<ManualEntry> entries)`，没有引入事件）。
   附属侧在模组构造期注册了一节 7 条的「抖音」说明（圆钮 / 抽屉 / 侧键语义 / 显示比率 / 打字 / 兼容性）。
3. 把 §7 的 `hasModelScreen()` 双屏问题交给本体作者拍板修不修。
4. 音频：手机不在栈顶 / 不可见时 JS 静音。
5. 桌面图标换成真贴图。
6. 量化帧率影响（模型屏每帧重绘 + CEF 出帧的代价）。
7. 可选：把"浏览器式 Ctrl + +/- 调字号"作为独立入口加回来（要接受它会冻结虚拟光标）。
8. ~~**横向拖动**~~：✅ **第 9 轮竣工并人工验收通过**，见 §1.5 与
   `D:\Moddevelop\huhutalk-douyin\PLAN-horizontal-drag.md`（结项报告：四轮实测、两次失败的取证、两个踩出来的坑）。
   **这条线到此为止** —— 除非玩家提观感问题，改 `bandHeight` / `panSign` / `panScale` 三个系统属性即可。
   **已知没做**：拖动视频进度条（见 §8"拖动期间不转发 `mouseMove`"）；页面内滑块的拖拽同理。

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
