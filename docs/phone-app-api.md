# HuhuTalk 手机桌面 App 注册 API

> **面向**：想让自己的附属模组在 HuhuTalk 手机桌面上多一个 App 的开发者。
> **状态**：已实现、已编译通过（IDE 构建零错误）；**尚未实机验收**——验收方法见 §9。
> **代码位置**：`src/main/java/com/huhutalk/api/phone/`

---

## 0. 一句话

调用 `PhoneAppRegistry.register(...)`，**或者**订阅 `PhoneAppRegisterEvent`，
HuhuTalk 的桌面上就会出现你的图标。不需要改 HuhuTalk 任何源码，不需要 mixin。

---

## 1. 30 秒上手

```java
package com.example.mymod;

import com.huhutalk.api.phone.PhoneApp;
import com.huhutalk.api.phone.PhoneAppRegisterEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "mymod", value = Dist.CLIENT)
public final class MyModPhoneApps {

    @SubscribeEvent
    public static void onRegisterPhoneApps(PhoneAppRegisterEvent event) {
        event.register(
                PhoneApp.builder(ResourceLocation.fromNamespaceAndPath("mymod", "douyin"), "抖音")
                        .color(0x161823)                                                     // 图标底色
                        .icon(ResourceLocation.fromNamespaceAndPath(
                                "mymod", "textures/icon/douyin.png"), 256, 256)               // 中心贴图
                        .page(DouyinPage::new)                                                // 点开进哪个页面
                        .build());
    }
}
```

`DouyinPage` 就是你自己实现的 `com.huhutalk.phone.IPhonePage`（接口本来就是 public）。

---

## 2. 注册时机：两种都行，推荐用事件

| 方式 | 写法 | 什么时候用 |
|---|---|---|
| **事件（推荐）** | `@SubscribeEvent` 监听 `PhoneAppRegisterEvent` | 默认选它。**不需要关心加载顺序** |
| 直接调用 | `PhoneAppRegistry.register(app)` | 想在自己的 mod 构造器 / 客户端 setup 里立刻注册 |

**事件什么时候触发？** HuhuTalk 在**第一次需要桌面 App 列表时**（也就是玩家第一次打开手机桌面）
广播一次。那一刻所有模组的构造器都已经跑完，所以你的监听器一定已经注册好了——
这就是"用事件就不必担心顺序"的原因。

事件之后**仍然可以**随时 `register(...)`：桌面每帧检查一次注册版本号，最迟下一帧就会多出图标。
所以即使注册晚了也不会出现"注册成功但图标不出现"的哑失败。

**重复 id**：同一个 `ResourceLocation` 只生效一次。后一次注册会被忽略并打一条 `WARN`，**不会抛异常**——
附属模组的问题不该让玩家的游戏崩掉。

---

## 3. `PhoneApp` 构建器参考

```java
PhoneApp.builder(ResourceLocation id, String displayName)
```

| 方法 | 默认值 | 说明 |
|---|---|---|
| `.color(int rgb)` | `0xFF3F51B5` | 图标底色。可给 `0xRRGGBB` 或 ARGB；alpha 最终强制不透明 |
| `.center(String text)` | `""` | 图标中心用文字 / emoji（白色、1 倍字号） |
| `.center(String text, float scale, int color)` | `1.0f` / 白 | 自定义字号倍率与颜色。emoji 建议 `2.0f` 起步 |
| `.icon(ResourceLocation tex, int srcW, int srcH)` | — | 中心用贴图，撑满方块 |
| `.icon(ResourceLocation tex, int srcW, int srcH, float fill)` | `fill = 1.0f` | 同上，`fill < 1` 时四周留白 |
| `.page(Supplier<? extends IPhonePage>)` | `null` | 点开进入的页面。**不设则点开显示内置的「敬请期待」页** |
| `.order(int)` | `0` | 排序权重（小的在前），仅影响外部 App 之间的顺序 |

`build()` 只在以下情况抛 `IllegalArgumentException`（都是为了让你早点发现问题）：
`id == null`、`displayName` 为 null / 空白、声明了贴图却没给合法尺寸。

---

## 4. 图标中心画什么

两种画法**二选一**，都设时**贴图优先**：

- **贴图**：`.icon(tex, srcW, srcH)`。
  建议给**正方形**贴图；非正方形会自动按较长边贴合、短边留白，**不会拉伸变形**。
- **文字 / emoji**：`.center("⚙")` 或 `.center("🦊", 2.25f, 0xFFFFFFFF)`。
- 两者都不设：就是一个纯色圆角方块（也不难看）。

贴图路径写法与 MC 资源包一致，例如贴图文件在
`src/main/resources/assets/mymod/textures/icon/douyin.png` →
`ResourceLocation.fromNamespaceAndPath("mymod", "textures/icon/douyin.png")`。

---

## 5. 桌面上会发生什么

| 事项 | 行为 |
|---|---|
| **排布** | 内置 App（HuhuTalk / 狐狐打人 / 狐狐打车 / SOS / 设置…）**恒定占前几个位置**，你的 App 一律**追加在后面**，按 `.order()` 稳定排序 |
| **会不会挤掉内置图标** | 不会。装不装你的模组，既有图标的排布完全不变 |
| **网格容量** | 每行 3 个，行数随手机长宽比（20:9→5 行、9:16→4 行、4:3→3 行）。**超出可见容量的图标会排在可视区之外**（与内置 App 同样处理）——注册超过 2 个 App 时请留意 |
| **点击** | 走通用「从右侧滑入」转场（与内置「设置」一致） |
| **右键** | 返回桌面（桌面是页面栈底，你的页面是压在它上面的子页） |
| **没有** | 底部导航栏、未读红点、启动动画——这些目前是内置 App 专有，详见 §10 |

---

## 6. 页面侧：`IPhonePage` 的钩子

你的页面实现 `com.huhutalk.phone.IPhonePage`。**所有坐标都是相对「手机内容区左上角」**（已扣除顶部状态栏）：

| 方法 | 用途 |
|---|---|
| `render(GuiGraphics, int baseX, int baseY, float partialTick)` | 每帧渲染。`baseX/baseY` 是内容区左上角的屏幕坐标 |
| `mouseClicked(double relX, double relY, int button, int action)` | 点击。`action` 是 `GLFW_PRESS(1)` / `GLFW_RELEASE(0)` |
| `mouseScrolled(double relX, double relY, double scrollDelta)` | 滚轮。**正值 = 手势往下/往右** |
| `mouseMoved(double relX, double relY)` | 移动（可做悬停高亮） |
| `dragAxisHorizontal(double relX, double relY)` | 「按住拖动」的主轴**是不是横向**（默认纵向）。横滑的画廊类面板才需要重写 |
| `onBackPressed()` | 右键返回。返回 `true` = 你已消费（做多级返回）；`false` = 交给上层默认弹页 |
| `onOpen()` / `onClose()` | 页面入栈 / 出栈 |

手机尺寸用 `PhoneOverlay.getPhoneWidth()` / `getPhoneHeight()` 拿。

> 💡 **对网页类 App 的关键**：`mouseClicked` + `mouseScrolled` + `dragAxisHorizontal`
> 就是"用一个自绘光标操作网页"的全部入口——把手机局部坐标换算成浏览器像素坐标，
> 交给浏览器的输入接口即可（详见 §11）。

### 6.1 别从零手绘：用页面骨架

直接用 `IPhonePage` 意味着顶栏、返回键、滚动、裁剪、命中判定都得自己写一遍——
而且极易掉进「渲染和命中各算一遍布局」的坑（内置页面里已经发生过十几次）。

**新页面建议继承 `PhonePage`**（可选层，现有页面一行未改）：

```java
public final class DouyinPage extends PhonePage {
    @Override protected String title() { return "抖音"; }
    @Override protected void renderContent(GuiGraphics g, int ox, int oy, int w, int h, float pt) { ... }
    @Override protected boolean onContentClick(double x, double y, int button, int action) { ... }
}
```

顶栏、返回键、滚动、裁剪、命中与主题取色都由骨架提供。
详见 **[phone-page-skeleton.md](phone-page-skeleton.md)**。

---

## 7. 依赖配置

### 7.1 Gradle

HuhuTalk 没有发布到公共 Maven，用构建产物直接做编译期依赖即可：

```groovy
dependencies {
    // 把 build/libs/huhutalk-<version>.jar 放到你的 libs/ 下
    compileOnly files("libs/huhutalk-1.0.0.jar")
}
```

`compileOnly` 就够了——运行时玩家会自己装 HuhuTalk，你的 jar 里**不要**打包它。
（若 HuhuTalk 侧执行过 `publishToMavenLocal` 或本地 `repo/` 发布，也可以走本地 Maven，
但 `files(...)` 是最省事的方式。）

### 7.2 `neoforge.mods.toml`

```toml
[[dependencies.mymod]]
    modId = "huhutalk"
    type = "required"
    versionRange = "[1.0.0,)"
    ordering = "AFTER"
    side = "CLIENT"
```

- `type = "required"`：没装 HuhuTalk 就不该加载你的模组（你的页面依赖它的接口）。
- `ordering = "AFTER"`：确保 HuhuTalk 先初始化。
- `side = "CLIENT"`：桌面是纯客户端的东西。

---

## 8. 约束与注意事项

1. **仅客户端**。桌面只在客户端存在；在服务端调用 `register(...)` 会被拒绝并打 `WARN`。
   用 `Dist.CLIENT` 的入口 / `@EventBusSubscriber(value = Dist.CLIENT)`。
2. **`id` 全局唯一**，用你自己的 mod id 作命名空间。
3. **`displayName` 尽量别和内置 App 重名**（`HuhuTalk`、`设置`、`SOS`、`狐狐打人`、`狐狐打车`、`狐狐三十分`）。
   内置 App 的启动动画是按**名字**查图标的，重名会让内置那个被优先命中。
4. **`page()` 每次点开都会调用一次**，直接写 `MyPage::new` 即可，不需要自己缓存实例。
5. **别在 `render` 里做重活**。桌面每帧渲染；你的页面在栈顶时才渲染，但仍应保持轻量。
6. 注册表是**静态、进程级**的：不会因为存档切换而清空（App 定义本来就与存档无关）。

---

## 9. 自检：怎么确认 API 是通的

HuhuTalk 内置了一个自检 App，**默认关闭**，用下面任一方式打开：

| 方式 | 写法 |
|---|---|
| 系统属性 | `-Dhuhutalk.debugApps=true` |
| 环境变量 | `HUHUTALK_DEBUG_APPS=true`（IDE 运行配置里改这个更方便） |

然后：

1. 启动客户端，打开手机桌面；
2. 应多出 **6 个**图标：
   **「自检·文字」**（文字/emoji 图标通路）、**「自检·贴图」**（贴图图标通路）、
   **「自检·骨架」**（`PhonePage` + `PhoneList`）、**「自检·滚动」**（`PhonePage` 自管滚动）、
   **「自检·控件」**（四种控件 + `PhoneRow`）、**「自检·图片」**（`PhoneImages` 四种画法）；
3. 六个都点得开、右键都能返回桌面；滚动页、控件页、图片页各自的表现符合预期 → 全链路正常；
4. 日志里会有一行 `[HuhuTalk/PhoneApp] 已注册桌面 App：...`（每个 App 一条）。

各页的详细判据见 [phone-page-skeleton.md](phone-page-skeleton.md) §10。

实现见 `src/main/java/com/huhutalk/client/debug/PhoneAppSelfTest.java`。

> 为什么正式代码里留这个：附属模组接入时最常见的失败是「注册了但图标不出现」，
> 而这类问题的日志往往不可读。留一个开箱可复现的对照物，比事后猜快得多。

---

## 10. 目前**不支持**的能力（以及怎么推进）

| 能力 | 现状 | 说明 |
|---|---|---|
| **启动动画** | ❌ 未开放 | 内置 App 有一套「底色色块放大淡出」的启动动画，但它与「进入 HuhuTalk 内容区（显示底部导航）」耦合（`PhoneOverlay.launchApp` 会置 `inChatApp = true`）。对外开放需要拆出一个不碰底部导航的重载 |
| **未读红点 / 角标** | ❌ 未开放 | 目前只有 HuhuTalk 图标有未读红点、设置图标有首次未看红点 |
| **隐藏 / 条件显示** | ❌ 未开放 | `AppInfo.hidden` 是内置用的；外部 App 无条件显示 |
| **插入到内置图标之前** | ❌ 不支持 | 内置 App 恒定在前，这是刻意的（保证装不装附属模组都不改变内置排布） |
| **运行时注销** | ❌ 不支持 | 只能注册。注销需要同时处理图标消失时的排布与动画，暂不做 |

以上任何一条需要的话直接提，都是加一个方法的事，不影响现有调用方。

---

## 11. 给「抖音网页版 App」附属模组的具体建议

> 可行性已验证通过（见 [douyin-webview-verification-report.md](douyin-webview-verification-report.md)）。
> 下面是把验证结论接到本 API 上的落点。

1. **MCEF 放在你的附属模组里，不要进 HuhuTalk 本体。**
   玩家不想要抖音功能时，HuhuTalk 本体必须**零依赖**。用 `compileOnly` + `ModList.get().isLoaded("mcef")`，
   缺 MCEF 时你的页面显示"需要安装 MCEF"而不是崩。

2. **页面 = 浏览器宿主。** 在 `onOpen()` 建 browser、`onClose()` 关它；
   `render()` 里把浏览器纹理画到内容区（**不要**走 `TextureManager` / `AbstractTexture`，
   直接 `RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID())` + Tesselator，
   原因见验证报告 §3.1）。

3. **输入桥接就是 `IPhonePage` 的三个钩子**：
   - `mouseMoved(relX, relY)` → 换算成浏览器像素坐标 → `sendMouseMove`
   - `mouseClicked(...)` → `sendMousePress` / `sendMouseRelease`（每次先 `setFocus(true)`）
   - `mouseScrolled(...)` / `mouseMoved` 的拖动 → `sendMouseWheel`（**刷视频就靠这条**）；
     若要做"按住上滑"手势，重写 `dragAxisHorizontal(...)` 返回 `false`（纵向主轴）

4. **必须覆写 `setCursorChangeListener`**（验证报告 / 交接文档 §3.5）：
   MCEF 会为了网页里的按钮去调 GLFW 改系统光标、甚至解除 MC 的鼠标捕获。
   HuhuTalk 用的是自绘独立光标，必须把它改成 no-op（或在自绘光标上换形状）。

5. **分辨率与显示尺寸解耦**：浏览器 `resize()` 给个较大的分辨率（例如按内容区宽高比给 720p 级），
   再缩到手机内容区显示，否则文字发糊（验证结论里的"分辨率偏低"就是这条）。

6. **强杀残留**：`cef_server` 是独立进程，任务管理器强杀 JVM 会残留约 425 MB。
   在 `onClose()` 里正常 `browser.close()` 就能干净退出；建议再加一层兜底。

---

## 附：相关文件

| 文件 | 说明 |
|---|---|
| `src/main/java/com/huhutalk/api/phone/PhoneApp.java` | App 描述符 + 构建器 |
| `src/main/java/com/huhutalk/api/phone/PhoneAppRegistry.java` | 注册表 |
| `src/main/java/com/huhutalk/api/phone/PhoneAppRegisterEvent.java` | 注册事件（推荐入口） |
| `src/main/java/com/huhutalk/api/phone/ui/PhonePage.java` | **页面骨架**（顶栏/返回/滚动/裁剪/命中/主题） |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneList.java` | **虚拟化行列表**（渲染与命中共用单一映射） |
| `src/main/java/com/huhutalk/phone/pages/MainPage.java` | 桌面：内置 + 外部 App 合并、排布、渲染、命中 |
| `src/main/java/com/huhutalk/client/debug/PhoneAppSelfTest.java` | 自检 App 与骨架示例页（默认关闭） |
| [phone-page-skeleton.md](phone-page-skeleton.md) | **页面骨架文档** |
| [douyin-webview-verification-report.md](douyin-webview-verification-report.md) | 抖音验证结论（含三个必踩的坑） |
| [douyin-webview-feasibility-handoff.md](douyin-webview-feasibility-handoff.md) | 完整技术调研与 API 清单 |
