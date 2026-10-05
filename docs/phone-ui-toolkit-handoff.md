# HuhuTalk 手机 UI 公共工具 · 工作日志与交接

> **面向**：接下来要**独立开发新 App**（设置 App 重做 / 相机 App / 图库 App）的对话。
> **读法**：先读本文了解现状与纪律，再按需翻阅两份 API 参考：
> [phone-app-api.md](phone-app-api.md)（App 注册）、[phone-page-skeleton.md](phone-page-skeleton.md)（页面骨架 / 控件 / 图片 / 文字 / 主题）。
> **状态**：**已实现、已编译通过、未实机验收**（验收清单见 §10）。

---

## 0. 一句话状态

**公共工具层已就绪**：App 注册 → 页面骨架 → 图片/文字工具 → 四个控件 → 主题色板，全部是公开 API。
现有 12 个内置页面**一行未改**（唯一例外是 `MainPage` 的桌面底色接到了主题上），可以放心在此基础上开新 App。

---

## 1. 这一轮做了什么

### 1.1 新增：App 注册（公开）

| 文件 | 内容 |
|---|---|
| `api/phone/PhoneApp.java` | App 描述符 + 构建器（底色 / 文字-emoji 图标 / **贴图图标** / 页面工厂 / 排序） |
| `api/phone/PhoneAppRegistry.java` | 注册表：`register` / `apps` / `generation` / `byId` |
| `api/phone/PhoneAppRegisterEvent.java` | 注册事件（**推荐入口**，免去关心加载顺序） |

### 1.2 新增：页面骨架与工具（公开）

| 文件 | 内容 |
|---|---|
| `api/phone/ui/PhonePage.java` | 页面基类：顶栏 + 返回键 + 滚动 + 裁剪 + 命中 + 主题取色 |
| `api/phone/ui/PhoneList.java` | 虚拟化行列表：**渲染与命中共用一对互逆映射** |
| `api/phone/ui/PhoneImages.java` | 画图：`draw` / `drawFit` / `drawCover` / `drawRegion` |
| `api/phone/ui/PhoneText.java` | 画字：跟随全局字体倍率的缩放绘制 + 截断 + 居中/左对齐 |
| `api/phone/ui/PhoneWidget.java` | 控件基类 |
| `api/phone/ui/PhoneSwitch.java` | 胶囊开关 |
| `api/phone/ui/PhoneStepper.java` | 竖排 ▲▼ 增减（离散档位） |
| `api/phone/ui/PhoneSlider.java` | 横向滑块（拖动 + 松手落盘） |
| `api/phone/ui/PhoneDropdown.java` | 点击展开的下拉选择 |
| `api/phone/ui/PhoneRow.java` | 一行「标签 + 右侧控件」，自动布局与高度 |

### 1.3 新增：自检

| 文件 | 内容 |
|---|---|
| `client/debug/PhoneAppSelfTest.java` | 6 个自检 App（默认关闭，见 §5） |

### 1.4 修改：既有文件（**只有两个**）

| 文件 | 改动 | 影响 |
|---|---|---|
| `phone/pages/MainPage.java` | ① 桌面 App 列表改为「内置 + 注册表」合并（按注册版本号懒合并）② 新增贴图图标渲染通路 ③ **桌面底色改为读主题** | 内置 App 行为零改动；桌面底色从硬编码 `#F0E4EA` 变成主题色 |
| `client/theme/ColorScheme.java` | 新增 7 个页面结构色字段；`pageBg` 取值由 `#FFF0F5` 改为 `#F2E8EF` | **`PhoneCasePickerPage` 的底色会跟着变**（它读的就是 `pageBg`）；其余页面不读色板，不受影响 |

---

## 2. 开工前必读：四条纪律

1. **客户端 only**。整套 API 依赖 `IPhonePage`（客户端类型）。注册走 `Dist.CLIENT` 的入口或 `PhoneAppRegisterEvent`。
2. **不动内置页面**。现有 12 个页面照旧工作。新 App 一律另开新页面；内置页面只在"因为功能原因本来就要动它"时顺手迁到骨架，**一次一个、可单独验收**。
3. **色板是默认值，不是强制**。大多数页面不覆盖 `colors()` 就自动跟着主题走；**自带视觉风格的 App（例如深色底的内容页）应当覆盖 `colors()` / `pageBackground()`，或干脆用自己的品牌色**——不要把色板当成必须遵守的规范。
4. **我们是第一作者，可以随手改公共方法**（作者原话）。发现接口别扭就直接加/减/改，不用为兼容性背包袱；改完更新本文档即可。

---

## 3. 公共 API 速查（详细签名见两份参考文档）

| 你想做的事 | 用这个 |
|---|---|
| 往桌面加一个 App | `PhoneApp.builder(id, 名称).color(..).icon(..)/.center(..).page(MyPage::new).build()` + `PhoneAppRegistry.register(...)` 或订阅 `PhoneAppRegisterEvent` |
| 写一个页面 | `extends PhonePage`，实现 `title()` + `renderContent(...)` + `onContentClick(...)` |
| 页面要滚动 | 重写 `contentHeight()`，渲染用 `scrollY(logical)`、命中用 `logicalY(local)` |
| 行式列表 | `PhoneList<T>`：`items()` / `itemHeight()` / `renderItem(...)` / `onItemClick(...)` |
| 画头像 / 照片 | `PhoneImages.drawCover(...)`；源纹理取自 `MaidAvatarManager` 或 `PhotoLibrary` |
| 画文字 | `PhoneText.draw / drawCenteredIn / drawLeftIn`（都跟随全局字体倍率） |
| 开关 / 增减 / 滑块 / 下拉 | `PhoneSwitch` / `PhoneStepper` / `PhoneSlider` / `PhoneDropdown`，用 `PhoneRow` 摆成行 |
| 取主题色 | `colors()`（`PhonePage` 内）或 `ThemeManager.getActive()` |

---

## 4. 最小完整示例：从注册到页面

```java
// ① 注册（客户端事件，无需关心加载顺序）
@EventBusSubscriber(modid = "myapp", value = Dist.CLIENT)
public final class MyAppClient {
    @SubscribeEvent
    public static void onRegister(PhoneAppRegisterEvent event) {
        event.register(PhoneApp.builder(
                        ResourceLocation.fromNamespaceAndPath("myapp", "gallery"), "图库")
                .color(0xFF6D4C41)
                .icon(ResourceLocation.fromNamespaceAndPath("myapp", "textures/icon/gallery.png"), 64, 64)
                .page(GalleryPage::new)
                .build());
    }
}

// ② 页面
public final class GalleryPage extends PhonePage {

    private int brightness = 70;

    private final PhoneRow sliderRow = PhoneRow.of(
            () -> "亮度：" + brightness + "%",
            new PhoneSlider()
                    .setValue(0.7)
                    .onChange(t -> brightness = (int) Math.round(t * 100))
                    .onCommit(t -> {/* 落盘 */}));

    private final List<PhoneRow> rows = List.of(
            PhoneRow.of("第一行纯文本"),
            sliderRow);

    @Override protected String title() { return "图库"; }

    /** 列表/设置形态的页面用略深一档的底色 */
    @Override protected int pageBackground() { return colors().pageBgList(); }

    private void layoutRows() {
        int y = PhoneText.px(6);
        for (PhoneRow row : rows) {
            row.background(colors().cardBg()).layout(font(), 0, y, width());
            y += row.height() + PhoneText.px(4);
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int ox, int oy, int w, int h, float partialTick) {
        layoutRows();
        for (PhoneRow row : rows) {
            row.render(g, ox, oy);
        }
    }

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        layoutRows();                       // 命中前再摆一次，保证和渲染用同一份布局
        for (PhoneRow row : rows) {
            if (action == GLFW.GLFW_PRESS && row.mousePressed(localX, localY, button)) return true;
            if (action == GLFW.GLFW_RELEASE && row.mouseReleased(localX, localY, button)) return true;
        }
        return false;
    }

    @Override
    protected void onContentMoved(double localX, double localY) {
        for (PhoneRow row : rows) {
            row.mouseDragged(localX, localY);   // 滑块拖动
        }
    }
}
```

---

## 5. 自检：改完怎么确认没坏

开启方式（二选一）：

```
-Dhuhutalk.debugApps=true          或环境变量       HUHUTALK_DEBUG_APPS=true
```

打开手机桌面会多出 **6 个**自检 App：

| 图标 | 验证什么 | 通过判据 |
|---|---|---|
| 自检·文字 | App 注册 + 文字/emoji 图标通路 | 能点开、右键回桌面 |
| 自检·贴图 | App 注册的**贴图图标**通路 | 图标显示为 HuhuTalk 的贴图而非文字 |
| 自检·骨架 | `PhonePage` + `PhoneList` | 40 行能滚、能点中行（行号显示在顶栏右侧）、悬停高亮 |
| 自检·滚动 | `PhonePage` 自管滚动 | 滚到任意位置都能点中行 |
| 自检·控件 | 四种控件 + `PhoneRow` + 与页面滚动共存 | 开关能拨；▲▼ 能加减且标签同步；**滑块要能按住拖动**；下拉能展开（下面的行整体下移）并选中 |
| 自检·图片 | `PhoneImages` 四种画法 | 留白 / 裁切 / 拉伸三者差异一眼可见 |

---

## 6. 主题与颜色

### 6.1 色板语义（`ColorScheme.PEARL_PINK`）

| 字段 | 值 | 用途 | 对 `pageBg` 的对比度 |
|---|---|---|---|
| `pageBg` | `#F2E8EF` | 主要页面背景（含桌面） | — |
| `pageBgList` | `#EDE1E7` | 设置 / 条目 / 列表类页面背景 | — |
| `cardBg` | `#FCF2F5` | 栏目 / 卡片填充 | — |
| `textPrimary` | `#3A2E33` | 主要文字（标题、正文） | ≈ 10.9 : 1 |
| `textSecondary` | `#6F6167` | 次要文字（说明、状态） | ≈ 4.9 : 1 |
| `textMuted` | `#8A7C82` | 弱化文字（占位、禁用） | ≈ 3.3 : 1 |
| `divider` | `#E3D5DB` | 分隔线 | — |
| `accent` | `#DE7E7E` | 主强调色（开关激活 / 滑块把手 / stepper 箭头） | — |

（对比度按 WCAG 相对亮度计算，取同色系暖深色而非纯灰，压在粉底上不发蓝。）

### 6.2 换主题

`ThemeManager.setScheme(id)` / `cycleNext()` 已就绪，内置「设置」页的「配色方案」那一行就能切。
目前只有 `PEARL_PINK` 一个预设；`ThemeManager.SCHEMES` 是个不可变列表，**追加新预设是纯增量工作**。

---

## 7. 已知边界 / 明确没做

| 项 | 说明 |
|---|---|
| **圆角图片** | `UiShapes` 只能填纯色圆角矩形，圆角贴图需要遮罩或着色器，**没做**。头像目前是方块。 |
| **滚动条** | 作者决定搁置。只提供 `scrollOffset` / `maxScroll()`，需要时自己画。 |
| **控件放进 `PhoneList` 的行里** | **需要自己接线**：列表行的 y 随滚动变化，行内控件必须每帧按行位置 `bounds(...)`。目前 `PhoneRow` 是为页面级行设计的。 |
| **多列网格列表** | `PhoneList` 只有单列。`PhoneCasePickerPage` 那种网格不支持。 |
| **吸顶分组头** | 没有。 |
| **App 启动动画** | ✅ **已开放**（本轮）。`PhoneApp.Builder.launchAnimation(boolean)`，默认开启；机制是 `PhoneOverlay.launchDesktopApp(page, slot)` —— 与「进入 HuhuTalk 内容区（底部导航）」彻底解耦（不再置 `inChatApp`）。内置 App 仍走各自的既有通路（HuhuTalk 用 `openChatApp()`，手册/设置用 `pushPage`），不受影响。详见 [phone-app-api.md](phone-app-api.md) §5.1 |
| **未读红点 / 隐藏 / 运行时注销** | 均未开放。 |

---

## 8. 后续三个 App 的落点建议

> 需求约束（作者原话）：**原功能保留不删除，只增加新入口和 App 版新布局**。
> 所以这三件事都是**新增页面**，不需要重构任何既有功能——风险很低。

### 8.1 设置 App 重做（当手册用）

- 新页 `extends PhonePage`，用 `PhoneRow` 一行行搭（开关 / stepper / 滑块 / 下拉四种控件都齐了）。
- 现成的私有实现可作参照：`SettingsPage` 的 `drawToggleRow` / `drawStepperRow` / `drawSizeRow` / `drawBrightnessRow`，`MaidSettingsPage` 的下拉。
- **注意**：`SettingsPage` 里那个"配色方案：珍珠粉 / 切换"的行，`cycleNext()` 已经接通，直接搬。
- 桌面的「设置」图标**目前指向 `ManualPage`（手册）**。作者计划：届时把这个图标改名/换图标叫「手册」，再新增一个「设置」App。**这一步是后续工作，本轮没动。**

### 8.2 相机 App —— ✅ **已完成（独立相机）**

> **已落地**
> - **公开接口** `api/phone/camera/PhoneCameraHost.java`：一个页面实现它，就自动获得
>   「透明取景框 + 擦除机壳 + 取景姿态 + 不画光标 + 吞鼠标点击 + 侧键接管」。
>   `PhoneOverlay` 里原先写死的 `instanceof ChatPage` 已全部改成接口判定（ChatPage 方法体一行未动）。
> - **公开页面** `api/phone/camera/PhoneCameraPage.java`：桌面「相机」App 的实现。
>   **只有取景态**（按下即拍 + 存相册 + 留在取景），没有"已拍成页"，拍照成功**不弹气泡**；
>   版面与聊天内相机逐项一致（含底部黑边——那条黑边是用来遮住模型没掏空的那条带子的）。
> - **注册** `client/CameraAppRegistration.java`：走**公开注册通路**（`PhoneAppRegisterEvent`）+ `.slot(1)`
>   + `.order(-100)`（保证先于附属模组拿到槽位 1）。图标暂用 📷 emoji，底色深灰 `#424242`，
>   像素图标到位后改 `.icon(...)` 即可。
> - **文档**：[phone-camera-api.md](phone-camera-api.md)（新建，含契约表、踩坑、参考实现）。

- 抓图链路：`PhoneOverlay.requestWorldCapture(Consumer<byte[]>)` + `PhotoLibrary.saveToGallery(byte[])`。
- 新页面用 `PhonePage` 的 `showTopBar()` / `renderContent` 画取景框。
  ⚠ 相机页**不要**继承 `PhonePage`：它的 `render` 是 `final` 且必定用 `pageBackground()` 铺满内容区，
  会把取景框整个盖死。相机页直接 `implements PhoneCameraHost` 自绘。

### 8.2.1 聊天内相机的「已拍成页」重做 —— ✅ **已完成**

旧毛病：`renderCameraMode` 在 `shot == true` 时**照样画透明取景框**（能看到背后的世界），
照片却只在左下角 24px 的缩略图里 —— 玩家要确认的恰恰是照片本身，结果它最小。

改成：
1. **已拍成时整块内容区铺满刚拍的照片**（`PhoneImages.drawCover`，不变形），
   底色兜底 + 底部暗带保证按钮可读；
2. 底部两颗按钮：左「重拍 <键5名>」（白底红字） / 右「发送 <键4名>」（红底白字），
   命中区与排版共用 `shotBarLayout`；
3. **判据拆分**（本次为此新增的公开 API）：`PhoneCameraHost` 上加了
   `cameraScreenTransparent()` / `cameraSideKeysActive()` / `cameraInputExclusive()`，
   默认值都 = `isCameraMode()`，所以**对既有实现零影响**。`ChatPage` 只覆盖了第一个：
   `cameraActive && cameraShot == null`。
4. `ProPhoneHandRenderer.stackForMode` 与 `PhoneOverlay.render` 的透明/擦除判据
   一并改走 `isCameraScreenTransparent()`；**取景姿态仍用 `isCameraMode()`**。

> **刻意没做**：已拍成页的"光标可点"。侧键 4 = 发送 / 侧键 5 = 重拍是玩家的肌肉记忆，
> 放开光标就得关掉侧键路由，反而更难用。命中逻辑已经写好并与排版对齐，
> 将来若要放开，只需改 `cameraSideKeysActive()` / `cameraInputExclusive()` 两行。

### 8.3 图库 App —— ✅ **已完成**

- `phone/pages/GalleryPage.java`：3 列网格（`extends PhonePage`，滚动走骨架自管模型）。
  单击进阅览；**长按 1 秒**进多选；选中态右上角「删除」批量删。
  选中视觉沿用聊天内图库页的配色（粉 `#DE7E7E` / 未选 `0x40000000` / 删除红 `#E53935`），
  另加"图片缩到 0.8 倍居中"作为更强的选中感。
- `phone/pages/GalleryViewerPage.java`：阅览页（深底 + `PhoneImages.drawFit` 完整可见），
  右上角「删除」删完直接 `popPage()`。**返回时下层网格页收到 `onOpen()` 自动刷新列表**，
  不需要额外的通知机制。
- `phone/pages/GalleryDeleteButton.java`：两颗删除按钮共用的「渲染与命中共用同一份位置」小工具。
- `client/GalleryAppRegistration.java`：走公开注册通路（`.slot(3)` + `.order(-99)`）。
- **新增公开页能力**：`IPhonePage.onLongPress(relX, relY)` —— 见
  [phone-page-skeleton.md](phone-page-skeleton.md) §4.1。默认返回 false，
  **现有页面一行未改**；判定在松开时做，不需要每 tick 计时器。
- 数据与纹理：`PhotoLibrary.listGallery()` / `getOrLoadTexture(key)` / `getTextureSize(key)` / `deleteGallery(fileName)`。
  注意 `listGallery()` **每次都读一遍目录**，所以 `GalleryPage` 只在 `onOpen()` 与删除后刷新，不每帧调。
- 作者说图库**可以不做公开方法**，所以两个页面放在 `phone/pages/` 而不是 `api/`。

#### 图库上线时修掉的两个坑（都值得记住）

1. **`onOpen()` 被绕过 → 页面"打开了但内容是空的"**。
   `PhoneOverlay` 里原先有几处裸的 `pageStack.push(...)`（`launchAppAt` / `switchTab` / 桌面入栈），
   它们**不通知 `onOpen()`**。图库的列表刷新挂在 `onOpen()` 上，于是聊天里能看到照片、
   图库页却坚持"暂无照片"——因为**它压根没去读过目录**（迷惑性极强：布局全对）。
   已统一成 `PhoneOverlay.pushTop(page)`（push + 通知），所有入栈点都走它。
   > 唯一的行为变化：`switchTab` 现在会通知新顶层页，于是切到「联系人」tab 会发一次
   > `C2SRequestMaidListPacket`（`ContactListPage.onOpen`）——这是**期望**的刷新行为。
2. **亮红文字压在品牌粉顶栏上几乎看不见**。顶栏改成 `#DE7E7E` 后，删除按钮原来的
   `#E53935` 对比度只有约 **1.7:1**。改成主题 `dangerBg`（暗红 `#B71C1C`）实心小胶囊 + 白字，
   对比度约 **6.6:1**，而且"实心胶囊"天然说明它可点。

#### 顶栏配色统一

- `PhonePage` 新增公开常量 `BRAND_TOPBAR_BG`（`#DE7E7E`）与钩子 `topBarBackground()`。
  骨架默认跟主题走（`topBarBg()`）；**主题本身也已更正**——`PEARL_PINK.topBarBg` / `navBg`
  原为偏紫的 `#F06292`，现改为 `#DE7E7E`，与内置页面硬编码的顶栏同色。
- 已应用：`GalleryPage` / `GalleryViewerPage` / `ManualPage`（手册原来是 `#F06292`）。
  注意这三个是**显式返回品牌常量**而不是依赖主题——这样以后新增配色方案时，
  它们仍与聊天/消息/设置这些硬编码顶栏的老页面保持一致。

> **为什么长按没做成"按住到 1 秒即时反馈"**：当前输入链路里，按住期间 Overlay 不向页面转发任何事件，
> PRESS/RELEASE 是松开时一起补发的。要即时反馈就得加每 tick 的计时器 + 松开时的抑制状态（约多 30 行）。
> 代价只是反馈出现在松手那一刻，作者拍板接受。
>
> **为什么不用双击**：双击必须给单击加 ~300ms 延迟（第一下会先触发"进阅览"），
> 那是对**每一次**进阅览的永久 UX 代价，比长按贵得多。

---

## 9. 改动过的既有文件与影响（回归检查用）

| 文件 | 改动 | 回归注意 |
|---|---|---|
| `MainPage.java` | App 列表 = 内置 + 注册表（按版本号懒合并，外部 App **一律追加在后面**） | 内置图标排布不变 |
| `MainPage.java` | 新增"贴图图标"渲染分支 | 只对 `iconTexture != null` 的 App 生效；内置 App 全部为 null |
| `MainPage.java` | 桌面底色改为 `ThemeManager.getActive().pageBg()` | 底色从 `#F0E4EA` 变为 `#F2E8EF`；切主题时会一起变 |
| `ColorScheme.java` | +7 字段；`pageBg` 值改为 `#F2E8EF` | **`PhoneCasePickerPage` 底色跟着变**（唯一读 `pageBg` 的既有页面） |
| 其余 11 个页面 | **未改动** | — |

### 9.1 排布改为「槽位」（本轮新增，含对外语义变更）

| 项 | 变化 |
|---|---|
| `MainPage` | **位置从「列表下标」改成「显式槽位」**：`AppInfo` 新增 `slot` / `behavior` / `unreadBadge`；新增 `SLOT_COUNT`(=9)、`SLOT_TALK`(=0) |
| `MainPage` | 6 处 `"名字".equals(app.name)` 判功能 → `AppBehavior` 枚举（`TALK` / `MANUAL` / `PLAIN`）。**从此改名、挪位都不会让红点或入口失联** |
| `MainPage` | `appIconCenterScreen(String)` 保留，新增按槽位的 `appIconCenterScreen(int)`；`PhoneOverlay.openChatApp()` 改走槽位 0，不再按名字查图标 |
| `PhoneApp` | 新增公开 `Builder.slot(int)` 与 `MAX_SLOTS`/`SLOT_AUTO`；**不调 `.slot()` 的附属模组行为完全不变** |
| **对外语义变更** | `phone-app-api.md` §5 那句「内置 App 恒定占前几个位置、外部一律追加在后面」**已改写**：现在外部可用 `.slot()` 指定任意空槽；申请的槽位被占会退让到空槽 + WARN（不会消失） |

---

## 10. 验收清单（实机待办）

开 `-Dhuhutalk.debugApps=true` 后逐条确认：

- [ ] 桌面 6 个自检图标都出现且排布正常（在内置图标之后）
- [ ] 「自检·文字」「自检·贴图」能点开、右键回桌面
- [ ] 「自检·骨架」列表能滚、滚后点哪行是哪行
- [ ] 「自检·滚动」同上
- [ ] 「自检·控件」：开关 / ▲▼ / **滑块拖动** / 下拉展开 四项都正常
- [ ] 「自检·图片」：四种画法差异明显（无变形异常、无越界）
- [ ] 桌面底色为 `#F2E8EF`；设置页切「配色方案」时桌面与骨架页一起变
- [ ] 内置「设置」→ 手册、HuhuTalk、SOS 等原有入口行为不变
- [ ] 「字体倍率」调到 0.5 与 2.0，控件与骨架布局都不溢出

---

## 11. 相关文档

| 文档 | 内容 |
|---|---|
| [phone-app-api.md](phone-app-api.md) | App 注册 API 完整参考（含附属模组依赖配置、`neoforge.mods.toml` 写法） |
| [phone-page-skeleton.md](phone-page-skeleton.md) | 页面骨架 / 两种滚动模型 / 控件 / 图片 / 文字 / 主题 |
| [douyin-webview-verification-report.md](douyin-webview-verification-report.md) | 抖音网页版验证结论（含三个必踩的坑）——做 MCEF 类 App 前必读 |
| [douyin-webview-feasibility-handoff.md](douyin-webview-feasibility-handoff.md) | 内嵌浏览器完整调研与 MCEF API 清单 |
