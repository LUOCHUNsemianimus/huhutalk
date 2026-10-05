# HuhuTalk 手机页面骨架（`PhonePage` / `PhoneList`）

> **面向**：想给 HuhuTalk 手机写页面的开发者（包括我们自己写新页面）。
> **状态**：已实现、已编译通过；**尚未实机验收**——验收方法见 §6。
> **代码位置**：`src/main/java/com/huhutalk/api/phone/ui/`
> **前置阅读**：[phone-app-api.md](phone-app-api.md)（App 注册）

---

## 0. 一句话

写手机页面时不必再从零手绘。继承 `PhonePage` 拿到「顶栏 + 返回键 + 滚动 + 裁剪 + 命中 + 主题取色」，
列表用 `PhoneList` 拿到「虚拟化 + 悬停 + 点击命中」。你只写内容。

---

## 1. 它解决的具体问题

在此之前每个页面都直接 `implements IPhonePage`，于是同一套逻辑被复制了十几份。
其中**最要命的**不是行数，而是这个：

```java
// 渲染里算一遍
int listTop = baseY + navH;
int itemY = listTop + i * ITEM_HEIGHT - scrollOffset;
...
// 点击里又算一遍（另一处代码、另一次机会写错）
int listTop = navH;
int itemY = listTop + i * ITEM_HEIGHT - scrollOffset;
```

"第 i 行画在哪"和"点在哪算第 i 行"是两处独立计算，靠人肉保持同步。
一旦不同步就是「点不准」，而且极难定位。

`PhonePage` / `PhoneList` 把这类计算收敛成**一对互逆方法**（`scrollY`/`logicalY`、`itemY`/`indexAt`），
渲染和命中都从这一对走，**结构上不可能不同步**。

---

## 2. 坐标系约定（必须先懂这个）

| 名称 | 原点 | 谁用 |
|---|---|---|
| **手机坐标** `(relX, relY)` | 手机**内容区**左上角（系统状态栏已扣掉） | `IPhonePage` 传给页面的原始坐标 |
| **内容坐标** `(localX, localY)` | 顶栏**下方**的内容区左上角 | `PhonePage` 的内容钩子；`localY = relY - contentTop()` |

`renderContent` 收到的 `(originX, originY)` 是**内容区左上角的屏幕坐标**，
所以 `renderItem` 那类回调里可以直接 `originX + 内容坐标` 得到屏幕坐标去画。

---

## 3. 两种滚动模型——**选一个，别混用**

### 模型 A：页面自管滚动（适合混合排版）

适合「分组标题 + 卡片 + 行」这类用不上统一行高列表的页面
（内置的 `SettingsPage` / `MaidInfoPage` / `ContactListPage` 都是这种形态）。

```java
public final class MyScrollPage extends PhonePage {
    @Override protected String title() { return "我的页面"; }
    @Override protected int contentHeight() { return TOTAL_ROWS * ROW_H; }   // ← 只有它比视口高才会滚

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        for (int i = 0; i < TOTAL_ROWS; i++) {
            int y = scrollY(i * ROW_H);          // ★ 逻辑 y → 内容坐标
            if (y + ROW_H < 0 || y > h) continue; // 视口外跳过
            g.drawString(font(), "第 " + i + " 行", originX + 8, originY + y + 6, 0xFFFFFFFF);
        }
    }

    @Override
    protected boolean onContentClick(double localX, double localY, int button, int action) {
        if (action != GLFW.GLFW_PRESS) return false;
        int index = logicalY(localY) / ROW_H;    // ★ 内容坐标 → 逻辑 y
        if (index < 0 || index >= TOTAL_ROWS) return false;
        if (!hit(0, scrollY(index * ROW_H), width(), ROW_H, localX, localY)) return false;
        // ... 处理这一行
        return true;
    }
}
```
滚轮、裁剪、上下边界钳制**骨架都做好了**，你只要实现 `contentHeight()`。

### 模型 B：`PhoneList` 自管滚动（适合统一行高的列表）

```java
public final class MyListPage extends PhonePage {
    private final PhoneList<Foo> list = new PhoneList<>() {
        @Override protected List<Foo> items() { return myItems; }
        @Override protected int itemHeight() { return 24; }
        @Override protected void renderItem(GuiGraphics g, int i, Foo f, int x, int y, int w, int h, boolean hovered) {
            if (hovered) g.fill(x, y, x + w, y + h, 0x22FFFFFF);
            g.drawString(font(), f.name(), x + 8, y + 4, 0xFFFFFFFF);
        }
        @Override protected void onItemClick(int i, Foo f) { PhoneOverlay.pushPage(new FooPage(f)); }
    };

    @Override protected String title() { return "我的列表"; }

    @Override
    protected void renderContent(GuiGraphics g, int originX, int originY, int w, int h, float partialTick) {
        list.setViewport(0, 0, w, h);   // 布局只写这一处
        list.render(g, originX, originY);
    }

    @Override protected boolean onContentClick(double x, double y, int button, int action) {
        return list.mouseClicked(x, y, button, action);
    }

    @Override protected void onContentMoved(double x, double y) {
        list.mouseMoved(x, y);          // 悬停高亮需要它
    }

    @Override public boolean mouseScrolled(double relX, double relY, double delta) {
        if (relY < contentTop()) return false;   // 顶栏不滚
        return list.mouseScrolled(delta);
    }
}
```

> ⚠️ **别混用**：用了 `PhoneList` 就让 `contentHeight()` 保持默认的 `0`。
> 否则页面的 `scrollOffset` 和列表的 `scrollOffset` 会各滚各的，变成双重滚动。

---

## 4. `PhonePage` 参考

### 子类覆盖（都有默认实现，按需重写）

| 方法 | 默认 | 说明 |
|---|---|---|
| `String title()` | `""` | 顶栏居中标题。空串则不画 |
| `boolean showTopBar()` | `true` | 关掉后内容区从手机内容区原点开始 |
| `boolean showBackButton()` | `true` | 是否画「‹ 返回」 |
| `void onBack()` | `PhoneOverlay.popPage()` | 点返回键做什么 |
| `void renderTopBarExtras(g, baseX, baseY, w, barH, textY)` | 空 | 顶栏右侧的额外内容（计数、按钮…） |
| `int contentHeight()` | `0` | 内容总高；≤ 视口高时不滚。**用 PhoneList 时保持 0** |
| `double scrollSpeed()` | `10` | 1 单位滚轮 = 多少逻辑像素 |
| `void renderContent(g, originX, originY, w, h, partialTick)` | 空 | 内容绘制。origin 是内容区左上角的**屏幕**坐标 |
| `boolean onContentClick(localX, localY, button, action)` | `false` | 内容区点击，**内容坐标** |
| `boolean onTopBarClick(relX, relY, button, action)` | `false` | 顶栏点击拦截，在返回键判定**之前** |
| `void onContentMoved(localX, localY)` | 空 | 内容区鼠标移动（悬停用） |

### 现成能力

| 方法 | 说明 |
|---|---|
| `width()` / `height()` | 手机内容区尺寸 |
| `colors()` | 当前配色方案（`ColorScheme`，来自 `ThemeManager`） |
| `font()` | 常用字体 |
| `topBarHeight()` / `contentTop()` / `contentViewHeight()` | 顶栏与内容区几何 |
| `scrollOffset()` / `setScrollOffset(int)` / `maxScroll()` | 滚动状态（自动钳制） |
| `scrollY(int logicalY)` | 逻辑 y → 内容坐标 y（**渲染用**） |
| `logicalY(double localY)` | 内容坐标 y → 逻辑 y（**命中用**，与上者互逆） |
| `hit(x, y, w, h, px, py)` | 矩形命中测试 |

> `render` 与 `mouseClicked` 是 **final** 的——它们把顺序钉死了（背景 → 顶栏 → 裁剪后的内容；
> 顶栏 → 内容），这正是消除重复的关键。需要顶栏上的自定义按钮请用 `onTopBarClick`。
> 如果你需要完全不同的结构，直接 `implements IPhonePage` 就行，本类只是**可选**的便利层。

### 4.1 长按（`IPhonePage.onLongPress`）

按住 ≥ `PhoneOverlay.LONG_PRESS_MS`（**1000ms**）、期间没有拖动成滚动，松开时会先问页面：

```java
@Override
public boolean onLongPress(double relX, double relY) {   // 返回 true = 已消费
    // ⚠ 坐标是**手机内容区**坐标（顶栏在里面）；要复用 onContentClick 那套布局，
    //   先减 contentTop() 换成内容坐标
    int index = indexAt(relX, relY - contentTop());
    if (index < 0) return false;      // 没命中 → 返回 false，上层照常补一次普通点击
    ...
    return true;                       // 消费掉 → 不再补普通点击
}
```

几条要点：

| 事项 | 说明 |
|---|---|
| **默认不启用** | `IPhonePage` 默认返回 `false`，所以**现有页面一行不用改**：按住 1 秒再松开，行为与以前完全一致（就是一次普通点击） |
| **判定在松开时** | 按住期间 `PhoneOverlay` 不向页面转发任何事件，左键的 PRESS/RELEASE 本来就是松开时一起补发的。所以这里只需比较时长，**不需要每 tick 的计时器**，也不会有"长按已触发就别再补轻点"那套状态 |
| **代价** | 选中/触发反馈出现在**松手那一刻**，而不是按住第 1 秒 |
| **"没有拖动"怎么判** | `PhoneOverlay` 内部已判好：位移超过阈值就升级为滚动，那种情况下**根本不会有点击**，本钩子自然也不会被调用 |
| **坐标系** | 与 `mouseClicked` 一致（手机内容区原点）。这是最容易踩的坑——见上面代码里的 `contentTop()` |
| **参考实现** | `GalleryPage`（长按进入多选） |

> 相机那套"长按侧键 = 存相册"是**另一条**通路（`PhoneCameraHost.cameraSaveAction`，阈值 350ms、按住期间就触发），
> 与本钩子互不相干：相机界面根本不走独立光标。

---

## 5. `PhoneList<T>` 参考

### 子类实现

| 方法 | 说明 |
|---|---|
| `List<T> items()` | 数据源 |
| `int itemHeight()` | 行高（像素，> 0） |
| `void renderItem(g, index, item, x, y, w, h, hovered)` | 画一行。**坐标是屏幕坐标**，直接画 |
| `void onItemClick(int index, T item)` | 行被点击（按下即触发） |
| `int separatorColor()` | 行分隔线颜色，alpha=0 则不画。默认 `0x22000000` |
| `boolean acceptsButton(int button)` | 哪些键算「点击行」。默认仅左键（右键在手机里是「返回」，不挡掉会顺带点开一行） |

### 现成能力

| 方法 | 说明 |
|---|---|
| `setViewport(x, y, w, h)` | 视口（**内容坐标**），在 `renderContent` 里按布局调一次 |
| `render(g, originX, originY)` | 绘制可见行并裁剪 |
| `mouseClicked(localX, localY, button, action)` | 命中 |
| `mouseScrolled(delta)` / `scrollBy(delta)` | 滚动 |
| `mouseMoved(localX, localY)` | 更新悬停行 |
| `itemY(int index)` | **第 i 行的内容坐标 Y（渲染用）** |
| `indexAt(localX, localY)` | **内容坐标点在哪一行（命中用）** |
| `maxScroll()` / `scrollOffset()` / `setScrollOffset(int)` | 滚动状态 |
| `itemCount()` / `totalHeight()` | 尺寸 |

`itemY` 与 `indexAt` **严格互逆**，是本类存在的意义。滚动之后依然点得准。

---

## 6. 画图：`PhoneImages`

### 6.1 图片句柄统一是 `ResourceLocation`

本模组里所有"运行期产生的图"都已经统一成这个形式，所以**没有再引入第二套图片类型**：

| 来源 | 拿纹理 | 拿尺寸 |
|---|---|---|
| 女仆头像 | `MaidAvatarManager.getOrLoadTexture(stableId)` | `MaidAvatarManager.getTextureSize(stableId)` |
| 图库照片 | `PhotoLibrary.getOrLoadTexture(key)` | `PhotoLibrary.getTextureSize(key)` |
| 玩家皮肤 | `((AbstractClientPlayer) p).getSkin().texture()` | 固定 64×64，头颅正面是 8×8 子区域 |
| 资源包里的图 | `ResourceLocation.fromNamespaceAndPath("mymod", "textures/icon/x.png")` | 你的贴图尺寸 |

### 6.2 四种画法

| 方法 | 行为 | 典型场景 |
|---|---|---|
| `draw(g, tex, x, y, w, h, srcW, srcH)` | **拉伸**铺满（宽高比不符会变形） | 尺寸天生匹配 |
| `drawFit(...)` | 按宽高比缩放到**完整可见**，四周留白（contain） | 照片预览、说明插图 |
| `drawCover(...)` | 按宽高比铺满并**居中裁切**（cover） | **头像**——非正方形照片变方不变形 |
| `drawRegion(g, tex, x, y, w, h, u, v, uW, vH, texW, texH)` | 只画贴图的一个子区域 | 皮肤头颅、雪碧图、九宫格 |

```java
// 女仆头像：先取纹理与尺寸，再用 cover 画进 30×30
ResourceLocation tex = MaidAvatarManager.getOrLoadTexture(stableId);
int[] size = MaidAvatarManager.getTextureSize(stableId);
if (tex != null && size != null) {
    PhoneImages.drawCover(g, tex, x, y, 30, 30, size[0], size[1]);
}

// 玩家皮肤的头颅正面
PhoneImages.drawRegion(g, skin, x, y, 30, 30, 8, 8, 8, 8, 64, 64);
```

> ⚠️ **圆角图片没做**。要圆角贴图得遮罩或自定义着色器，目前头像都是方块。

---

## 7. 画字：`PhoneText`

手机 UI 的字号要跟着玩家的「字体倍率」设置走，而 `GuiGraphics` 只能按 1 倍画。
既有页面每处都自己抄了一遍 `pushPose / scale / popPose` 的换算（被复制了十几遍），这里收成一个入口。

| 方法 | 说明 |
|---|---|
| `scale(float relative)` | 相对倍率 × 全局字体倍率，直接传给下面的绘制方法 |
| `px(int pixels)` | 把固定像素量（行高、留白）按全局倍率放大 |
| `width(font, text, scale)` / `height(font, scale)` | 量文字占多大，**不要自己拿 `font.width` 乘**，容易漏掉全局倍率 |
| `draw(...)` / `drawShaded(...)` | 任意倍率绘制 |
| `drawCenteredIn(g, font, text, x, y, w, h, color, scale)` | 在矩形内水平+垂直居中 |
| `drawLeftIn(g, font, text, x, y, w, h, padLeft, color, scale)` | 垂直居中、左对齐；**超出宽度自动截断补「…」** |
| `truncate(font, text, maxWidth, scale)` | 单独要截断时用 |

---

## 8. 控件与 `PhoneRow`

### 8.1 四个控件

| 控件 | 形态 | 关键行为 |
|---|---|---|
| `PhoneSwitch` | 胶囊开关 | 点击即切换并回调 `onChange`；`setValue` 不触发回调 |
| `PhoneStepper` | 竖排 ▲▼ | **离散档位** `steps(...)`，`setValue` 自动吸附最近档位；到顶/到底仍消费点击，不穿透 |
| `PhoneSlider` | 轨道 + 把手 | 值恒为 `0.0~1.0`；**`onChange` 拖动中实时、`onCommit` 松手落盘**——不需要自己写 `dragging` 布尔量 |
| `PhoneDropdown` | 点击展开 | 展开时**画在自己矩形之外**，见下方警告；展开时吃掉滚轮 |

四个控件都默认从主题取色（`accent` / `neutralBg` / `cardBg` / `textPrimary`），也都能用 `colors(...)` 覆盖。

> ⚠️ **`PhoneDropdown` 展开时会画到矩形外**：高度是 `extraHeight()`。
> `PhoneRow` 已经把它算进行高（后续行会自动下移），所以**用 `PhoneRow` 摆就没问题**；
> 直接裸用的话要自己预留高度，并且注意它的渲染顺序。

### 8.2 `PhoneRow`：一行「标签 + 右侧控件」

两种排布由控件的 `layoutHint()` 决定：

- `INLINE_RIGHT`（开关、stepper）：标签在左、控件右对齐
- `STACKED`（滑块、下拉）：标签占一行、控件占满下一行

```java
private final List<PhoneRow> rows = List.of(
        PhoneRow.of("开启某功能", new PhoneSwitch().on(v0).onChange(v -> save(v))),
        PhoneRow.of(() -> "数值：" + stepper.display(), stepper),
        PhoneRow.of(() -> "亮度：" + pct + "%", slider),
        PhoneRow.of("选择项", dropdown),
        PhoneRow.of(() -> "状态：" + status));   // 纯文本行

/** 唯一布局处：行高自动算（含下拉展开），控件矩形也在这里定 */
private void layoutRows() {
    int y = PhoneText.px(6);
    for (PhoneRow row : rows) {
        row.background(colors().cardBg()).layout(font(), 0, y, width());
        y += row.height() + PhoneText.px(4);
    }
}
```

**三态接线**（行代管拖动，页面不用维护"谁在被拖"）：

```java
@Override protected boolean onContentClick(double x, double y, int button, int action) {
    layoutRows();                       // 命中前再摆一次，与渲染共用同一份布局
    for (PhoneRow row : rows) {
        if (action == GLFW.GLFW_PRESS   && row.mousePressed(x, y, button))  return true;
        if (action == GLFW.GLFW_RELEASE && row.mouseReleased(x, y, button)) return true;
    }
    return false;
}

@Override protected void onContentMoved(double x, double y) {
    for (PhoneRow row : rows) row.mouseDragged(x, y);
}

@Override public boolean mouseScrolled(double relX, double relY, double delta) {
    if (relY < contentTop()) return false;
    layoutRows();
    for (PhoneRow row : rows) {
        if (row.mouseScrolled(delta)) return true;   // 下拉展开时先让它吃掉
    }
    return super.mouseScrolled(relX, relY, delta);
}
```

> ⚠️ **控件放进 `PhoneList` 的行里需要自己接线**：列表行的 y 随滚动变化，
> 行内控件必须每帧按行位置 `bounds(...)`。`PhoneRow` 目前是为**页面级行**设计的。

---

## 9. 主题与颜色

### 9.1 色板是默认值，不是强制

`PhonePage.colors()`（返回 `ColorScheme`）与 `pageBackground()` 都是**可覆盖的普通方法**，不是 final：

- **大多数页面不覆盖** → 自动跟着主题走，换主题整体变色；
- **自带视觉风格的 App**（例如深色底的内容页）→ 覆盖 `colors()` / `pageBackground()`，或干脆用自己的品牌色。
  色板负责"让默认页面跟着主题走"，**不负责规定所有 App 长什么样**。

### 9.2 页面结构语义色（`ColorScheme.PEARL_PINK`）

| 字段 | 值 | 用途 |
|---|---|---|
| `pageBg` | `#F2E8EF` | 主要页面背景（含桌面） |
| `pageBgList` | `#EDE1E7` | 设置 / 条目 / 列表类页面背景 |
| `cardBg` | `#FCF2F5` | 栏目 / 卡片填充 |
| `textPrimary` | `#3A2E33` | 主要文字（对 `pageBg` 对比度 ≈ 10.9 : 1） |
| `textSecondary` | `#6F6167` | 次要文字（≈ 4.9 : 1） |
| `textMuted` | `#8A7C82` | 弱化文字（≈ 3.3 : 1） |
| `divider` | `#E3D5DB` | 分隔线 |
| `accent` | `#DE7E7E` | 主强调色（开关激活 / 滑块把手 / stepper 箭头） |

### 9.3 换主题

`ThemeManager.setScheme(id)` / `cycleNext()` 已就绪，内置「设置」页的「配色方案」行就能切。
目前只有 `PEARL_PINK` 一个预设，追加新预设是纯增量工作。

---

## 10. 自检：怎么确认骨架是通的

和 App 注册自检同一个开关（详见 [phone-app-api.md](phone-app-api.md) §9）：

```
-Dhuhutalk.debugApps=true     或环境变量   HUHUTALK_DEBUG_APPS=true
```

打开手机桌面会多出 **6 个**图标（App 注册 3 个 + 骨架/控件/图片 3 个 + 滚动 1 个，共 6 个自检 App）：

| 图标 | 验证什么 |
|---|---|
| **自检·文字** | App 注册的文字/emoji 图标通路 |
| **自检·贴图** | App 注册的贴图图标通路 |
| **自检·骨架** | `PhonePage` + `PhoneList`（模型 B）：顶栏由骨架画、返回键可点、40 行列表能滚、裁剪正确、**滚动后点哪行是哪行**、悬停高亮 |
| **自检·滚动** | `PhonePage` 自管滚动（模型 A）：`scrollY` 与 `logicalY` 互逆换算，滚到任意位置都点得准 |
| **自检·控件** | 四种控件 + `PhoneRow`，且与页面滚动共存 |
| **自检·图片** | `PhoneImages` 四种画法（留白 / 裁切 / 拉伸差异） |

判定：都能点开、右键回桌面；两个滚动页都能滚能点；控件页开关/▲▼/**滑块拖动**/下拉展开都正常。

---

## 11. 和现有页面的关系

**现有 12 个页面一行没改，继续照旧工作。**（唯一例外：`MainPage` 的桌面底色接到了主题上。）

- 新页面（包括附属模组的页面）**一律用骨架**；
- 现有页面**按需顺手迁**——下次因为功能原因本来就要动它时，顺手换成骨架，一次一个、可单独验收；
- 不搞大爆炸式重构：那要一次性改动 10 个能跑的页面，回归风险与验收成本都不划算。

短期内会存在「两套写法」，这是刻意的取舍。

---

## 12. 已知未做（需要就说）

| 项 | 说明 |
|---|---|
| **圆角图片** | `UiShapes` 只能填纯色圆角矩形；圆角贴图要遮罩或自定义着色器。头像目前是方块 |
| **滚动条** | 已决定搁置。只提供 `scrollOffset` / `maxScroll()`，需要时自己画 |
| **控件放进 `PhoneList` 行内** | 需自己接线：列表行的 y 随滚动变化，行内控件要每帧按行位置 `bounds(...)`；`PhoneRow` 是为页面级行设计的 |
| **多列网格列表** | `PhoneList` 只有单列。`PhoneCasePickerPage` 那种网格暂不支持 |
| **吸顶分组头** | 没有 |
| **文本输入框** | 页面内没有可用的输入控件。需要打字时既有做法是借用真 `Screen`（见 `PhoneInputScreen`，输入法可用） |

---

## 附：相关文件

| 文件 | 说明 |
|---|---|
| `src/main/java/com/huhutalk/api/phone/ui/PhonePage.java` | 页面骨架 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneList.java` | 虚拟化行列表 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneRow.java` | 标签 + 控件行 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneSwitch.java` 等 4 个 | 开关 / stepper / 滑块 / 下拉 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneImages.java` | 画图 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneText.java` | 画字 |
| `src/main/java/com/huhutalk/client/debug/PhoneAppSelfTest.java` | 6 个自检 App 与示例页（默认关闭） |
| [phone-app-api.md](phone-app-api.md) | App 注册 API |
| [phone-ui-toolkit-handoff.md](phone-ui-toolkit-handoff.md) | **工作日志与交接**（先读这个） |
