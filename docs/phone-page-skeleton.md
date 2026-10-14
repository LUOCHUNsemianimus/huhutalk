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
| `int topBarBackground()` | `colors().topBarBg()` | 顶栏底色。想和内置页面（聊天/消息/设置）统一就用 `PhonePage.BRAND_TOPBAR_BG`（`#DE7E7E`） |
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

### 4.1 `onOpen()` 什么时候会被调用（踩过坑）

`onOpen()` 是页面做初始化的地方（拉列表、重置状态、重roll 内容）。它**只在两种情况**被调：

1. **页面被压入栈顶**时；
2. **它上面那层被弹出、重新露出它**时。

⚠ **别把初始化写在构造函数里就以为稳了**：构造函数只在 `new` 的那一刻跑，而"重新露出"那次不会重新 `new`。
图库 App 第一版就是这么踩的——列表刷新写在 `onOpen()` 里，但当时 `PhoneOverlay` 有几处
**裸 `pageStack.push(...)`** 绕过了这个通知，于是"页面能打开、布局也对，内容永远是空的"。

现已统一成 `PhoneOverlay.pushTop(page)`（push + 通知），所有入栈点都走它。
**如果你是 HuhuTalk 的维护者：别再写裸的 `pageStack.push(...)`。**

### 4.2 长按（`IPhonePage.onLongPress`）

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
| `int itemHeight()` | **固定**行高（像素，> 0）。混排行高改覆盖 `itemHeight(int)` |
| `int itemHeight(int index)` | 第 i 行的行高。默认转发到 `itemHeight()`。**混排行高覆盖它** |
| `void renderItem(g, index, item, x, y, w, h, hovered)` | 画一行。**坐标是屏幕坐标**，直接画 |
| `void onItemClick(int index, T item)` | 行被点击（按下即触发） |
| `int separatorColor()` | 行分隔线颜色，alpha=0 则不画。默认 `0x22000000` |
| `int separatorColor(int index)` | 第 i 行下方的分隔线色。默认转发。分组标题下想不画线就返回 `0` |
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
| `invalidateLayout()` | **声明行高变了**。行数变化会自动检测；行高随数据变（折叠分组）必须手动调 |

`itemY` 与 `indexAt` **严格互逆**，是本类存在的意义。滚动之后依然点得准。

> **可变行高怎么实现的**：各行的 `itemHeight(i)` 累成一份**前缀和**，`itemY` 读它、`indexAt` 对它二分。
> 所以"画在一处、点在另一处"在混排行高下同样不可能。零高度的行会被二分自动跳过（点不到）。

### 5.1 混排行高（分组标题 + 条目）

```java
@Override protected int itemHeight() { return 40; }            // 只作占位，内部走下面那个
@Override protected int itemHeight(int i) {
    return rows.get(i).isHeader() ? 18 : 40;
}

// 点击分组标题 → 折叠。⚠ 改完数据必须 invalidateLayout()
@Override protected void onItemClick(int i, Row r) {
    if (r.isHeader()) {
        r.toggle();
        invalidateLayout();
        return;
    }
    PhoneOverlay.pushPage(new MaidInfoPage(r.stableId(), r.name()));
}
```

---

## 5.2 `PhoneGrid<T>`：多列网格

`PhoneList` 是单列、行高可变的「列表」；`PhoneGrid` 是**列数固定、格子等大**的「网格」。
两者是同一条纪律的两个形状，不是替代关系：

| 形态 | 用哪个 | 例子 |
|---|---|---|
| 单列 / 行高可变 / 带分组 | `PhoneList` | 消息列表、联系人、设置条目 |
| 多列 / 格子等大 | **`PhoneGrid`** | 手机壳方块（4 列）、传文件背包格（5 列）、图库缩略图（3 列） |

不把列数做成 `PhoneList` 的参数，是因为两套命中数学不同：网格是**整除取余**（O(1)），
列表是**前缀和二分**（可变行高）。

### 子类实现

| 方法 | 说明 |
|---|---|
| `List<T> items()` | 数据源，按行优先展开（第 i 格 = 第 `i / columns()` 行、第 `i % columns()` 列） |
| `int columns()` | 列数，> 0 |
| `int cellWidth()` / `int cellHeight()` | 格子尺寸，> 0 |
| `int gapX()` / `int gapY()` | 列/行间距。默认 0 |
| `int padLeft()` / `int padTop()` | 网格整体相对视口的缩进。默认 0 |
| `void renderCell(g, index, item, x, y, w, h, hovered)` | 画一格。**坐标是屏幕坐标** |
| `void onCellClick(int index, T item)` | 格子被点击（按下即触发） |
| `boolean acceptsButton(int button)` | 默认仅左键 |

### 现成能力

| 方法 | 说明 |
|---|---|
| `setViewport(x, y, w, h)` | 视口（**内容坐标**） |
| `render(g, originX, originY)` | 绘制可见格并裁剪 |
| `mouseClicked(...)` / `mouseScrolled(delta)` / `mouseMoved(...)` | 命中 / 滚动 / 悬停 |
| `cellX(int index)` / `cellY(int index)` | **第 i 格的内容坐标（渲染用）** |
| `indexAt(localX, localY)` | **内容坐标点在哪一格（命中用）** |
| `itemCount()` / `rowCount()` / `totalHeight()` / `maxScroll()` | 尺寸 |
| `scrollOffset()` / `setScrollOffset(int)` / `scrollBy(delta)` / `invalidateLayout()` | 滚动状态 |

> **落在间距里不算命中**：`indexAt` 在 `gapX/gapY` 区间返回 `-1`。
> 否则"点空白也选中"会让多选网格（图库批量删）误触。

> ⚠ **`mouseClicked` 的契约是「按下即触发」**（`action == GLFW_PRESS`，与既有页面一致）。
> 页面**不要**自己再加一层 `action` 判据再转发 —— 只能在 PRESS 时转，也只能转 PRESS。
> 曾经有页面（`FileTransferPage`）照抄旧实现判了 `GLFW_RELEASE` 再转发给 `PhoneGrid`，
> 结果被 `PhoneGrid` 直接吞掉，**整页格子点不动**（返回键却正常，因为那是 `PhonePage` 自己的 PRESS 分支）。
> 真要在"松开那一刻"生效，就该改这里是 PRESS 的这条契约，而不是在页面里两头各判一套。

### ⚠ 格子里的**物品图标会压住 z=0 的覆盖层**（深度测试）

`GuiGraphics.renderItem` 把物品画在 **z=150**（原版硬编码），`fill` / `drawString` / `blit`
默认 **z=0**，而 GUI 的 `RenderType.gui()` 是 **`LEQUAL_DEPTH_TEST`**。
z 越大越靠近摄像机 ⇒ **后画的 z=0 元素会被先画的 z=150 物品判掉**（"后画=在上层"在这里不成立）。

所以凡是"必须在物品之上"的东西，都要显式抬 z：

| 想画什么 | 建议 z | 例子 |
|---|---|---|
| 物品本体 / 格子底 | 0 / 150 | `renderCell` 里的 `g.renderItem(...)` |
| 压在物品上的角标（数量、耐久） | **200** | `FileTransferPage.drawSlot`；原版 `renderItemDecorations` 同值 |
| 页面级最上层（独立光标、气泡） | **≥ 300** | `PhoneOverlay.renderCursor` |

写法就是 `g.pose().pushPose(); g.pose().translate(0f, 0f, 300f); …; g.pose().popPose();`。

---

## 5.3 `PhoneLayout`：给**不继承 `PhonePage`** 的页面用

`PhonePage.hit(...)` 是 `protected`，只有骨架子类用得到。这几类页面必须自绘、拿不到骨架：
`ChatPage`（相机宿主，取景要透明，不能继承 `PhonePage`）、`MainPage`（桌面，无顶栏）、
实现 `PhoneCameraHost` 的相机页。它们各自抄了一份 `hit` / `drawTopShadow`。

| 方法 | 说明 |
|---|---|
| `hit(px, py, x, y, w, h)` | 矩形命中（半开区间）。**⚠ 参数顺序是「先点、后矩形」** |
| `topShadow(g, x, y, w)` | 一条 2 层递减的细水平阴影，用于分区视觉分隔 |
| `horizontalShadow(g, x, y, w, layers)` | 可调层数版 |

> ⚠ **`PhoneLayout.hit` 与 `PhonePage.hit` 参数顺序不同**：
> `PhonePage.hit(x, y, w, h, px, py)` 是「先矩形、后点」（它只是 `PhoneLayout.hit` 的转发壳）；
> `PhoneLayout.hit(px, py, x, y, w, h)` 是「先点、后矩形」——与既有页面里三个私有副本
> （`ChatPage` / `MaidInfoPage` / `SettingsPage`）的顺序一致，所以迁过去时调用点一个字都不用改。

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

### 7.1 `FormattedCharSequence` 重载

`draw` / `drawShaded` / `drawCenteredIn` / `width` 各有一个 `FormattedCharSequence` 重载。
**什么时候必须用它**：emoji、`Component.literal(..).getVisualOrderText()`、已排版的富文本
——它们都不是 `String`。换算与 `String` 版逐字节一致（`1/scale` 反算坐标）。

> 在此之前 `ChatPage` 的气泡与 `PhoneOverlay` 的状态栏 / Dock 各抄了一份这个重载（两处私有副本）。

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

#### 8.2.1 统一行高：`rowHeight(int)`

默认行高是**按内容自动算**的，于是同一种「标签 + 控件」形态会因为控件不同而得到不同高度：
开关 8px、胶囊按钮 8px、stepper 18px、滑块 13px 且要另起一行 —— 一页里能出现 **4 种行高**。
内置「设置」页原来所有行等高（`sh(24)`），四种控件混在一起看着是齐的；
迁到 `PhoneRow` 之后如果放任自动算，页面就会"每行都不一样高"。

```java
private static final int ROW_H = 28;

private static PhoneRow row(String label, PhoneWidget widget) {
    return PhoneRow.of(label, widget).pad(12).rowHeight(ROW_H);
}
```

固定行高之后三种控件的摆法（这是本方法真正的价值）：

| 控件 | `layoutHint` | 固定行高下的摆法 |
|---|---|---|
| 开关 / stepper / 按钮 | `INLINE_RIGHT` | **标签与控件一起在行内垂直居中** |
| 滑块 / 下拉 | `STACKED` | **标签贴顶、控件贴底** —— 与旧设置页「标签在上、滑块在下」观感一致，也保证滑块轨道不会横穿标签文字 |

⚠ **别设得太矮**：`STACKED` 行要求
`固定行高 ≥ 上留白 + 标签高 + 控件高`，否则轨道会切过标签文字。
默认字体倍率下滑块（控件高 13px + 上下留白 5px）最低需要 **27px**——
所以内置设置页用 28 而不是它原本的 24。`layout()` 里有一个按
「标签 + 控件 + 留白」算出的**下界**兜底，字体倍率调小时也不会被挤坏。

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
| `topBarBg` | `#DE7E7E` | 顶栏 / 底部导航背景（= `PhonePage.BRAND_TOPBAR_BG`） |
| `topBarText` | `#FFFFFF` | 顶栏文字 |
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

### 11.1 现行策略：**分批全量迁移**（已推翻上一轮的"按需顺手迁"）

上一轮的结论是「现有 12 个页面一行不改，按需顺手迁，不搞大爆炸式重构」。
**作者已改主意**：硬编码页面的重复量太大（9 份 `drawScaledText`、8 份 `navH()` 且 4 种公式、
5 份 `textH`、4 份 `truncate`、3 份 `hit`、7+ 处无 `try/finally` 的 `enableScissor` 配对），
继续"顺手迁"意味着这套重复要再存在很久。现在按 **6 个批次**推进，每批独立编译、独立验收：

| 批次 | 内容 | 状态 |
|---|---|---|
| 0 | 扩公开 API：`PhoneText` 的 `FormattedCharSequence` 重载、`PhoneList` 可变行高、`PhoneGrid`、`PhoneLayout`、`PhonePage.bottomInset()`；**并把顶栏高度统一到内置页真实公式（17px）** | ✅ 完成 |
| 1 | 删死代码 `MaidListPage` / `MaidSettingsPage`（−428 行）；`ComingSoonPage` / `PhoneCasePickerPage` 迁骨架；全工程 `UiShapes` → `PhoneShapes`（11 文件 ≈60 处） | ✅ 完成 |
| 2 | `SettingsPage` → `PhonePage` + `PhoneRow` + 四种控件（单点收益最大） | ✅ 完成 |
| 3 | `MessageListPage` / `ContactListPage` / `MaidInfoPage` 迁骨架 | ✅ 完成 |
| 4 | `ManualPage`（保持对外 API 签名）/ `FileTransferPage` 迁骨架；`MainPage` 只换 helper | ✅ 完成 |
| 5 | `ChatPage` **只换 helper**（它是相机宿主，不继承 `PhonePage`） | ✅ 完成 |

### 11.2 哪些页面**不**继承 `PhonePage`（不是漏了，是刻意的）

| 页面 | 为什么不能继承 | 怎么办 |
|---|---|---|
| `ChatPage` | ① `PhonePage.render` 是 `final` 且**必定**用 `pageBackground()` 铺满内容区，与相机取景需要的**透明**冲突；② `PhonePage` 只认**一个** `scrollOffset`，而聊天页有**三套**独立滚动（消息 / 表情 / 图片画廊）；③ 底栏是输入框 + 5 图标扩展栏 + 多个浮层，`contentTop()` 那一刀切不进来 | 保持 `implements PhoneCameraHost`，只把 `drawScaledText` / `drawTopShadow` / `hit` / 内联截断换成 `PhoneText` / `PhoneLayout` / `PhoneText.truncate` |
| `MainPage` | 桌面启动器：无顶栏、无滚动、有自己的槽位命中与启动动画 | 只换 helper |
| `PhoneCameraPage` | 相机取景必须自绘，骨架会盖死取景框（见 §12 与 `phone-camera-api.md`） | 只换 helper |

### 11.3 配色：统一走主题色

迁移后页面底色从硬编码的 `#F5EBF2` / `#F5EBEE` / `#F0E4EA` 变成主题的
`pageBg`(`#F2E8EF`) / `pageBgList`(`#EDE1E7`)，**有轻微色差，这是接受的**——
这样以后加配色方案时这些页面会一起变，不会出现"两台不同的手机"。
顶栏仍显式返回 `PhonePage.BRAND_TOPBAR_BG`，与聊天/消息这些页面保持一致。

---

## 12. 已知未做（需要就说）

| 项 | 说明 |
|---|---|
| **圆角图片** | `PhoneShapes`（`api/phone/ui`，旧名 `com.huhutalk.phone.UiShapes` 已 `@Deprecated`）只能填纯色圆角矩形；圆角贴图要遮罩或自定义着色器。头像目前是方块 |
| **滚动条** | 已决定搁置。只提供 `scrollOffset` / `maxScroll()`，需要时自己画 |
| **控件放进 `PhoneList` / `PhoneGrid` 行内** | 需自己接线：行的 y 随滚动变化，行内控件要每帧按行位置 `bounds(...)`；`PhoneRow` 是为页面级行设计的 |
| **⚠ 滚动页里的控件坐标** | `PhoneWidget.render` 只在 `bounds` 的 y 上加**内容区原点、不含滚动偏移**。所以滚动页里必须 `bounds(x, scrollY(logical), ...)`（**视图坐标**），命中则传未滚动的 `localY` —— 依据 `scrollY(logicalY(localY)) == localY`。用逻辑坐标会"渲染钉在屏幕上、命中却还对得上"（实测踩过）。`PhoneRow` 同理：`row.layout(font, 0, scrollY(y), w)` + `row.mousePressed(localX, localY, btn)` |
| **吸顶分组头** | 没有。`PhoneList` 的可变行高能画分组标题行，但它会跟着滚走 |
| **文本输入框** | 页面内没有可用的输入控件。需要打字时既有做法是借用真 `Screen`（见 `PhoneInputScreen`，输入法可用） |
| **网格的横向滚动** | `PhoneGrid` 只纵向滚（与 `PhoneList` 一致） |

---

## 附：相关文件

| 文件 | 说明 |
|---|---|
| `src/main/java/com/huhutalk/api/phone/ui/PhonePage.java` | 页面骨架 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneList.java` | 虚拟化行列表（固定/可变行高） |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneGrid.java` | 虚拟化多列网格 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneLayout.java` | 给不继承骨架的页面用的命中 / 阴影小工具 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneRow.java` | 标签 + 控件行 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneSwitch.java` 等 4 个 | 开关 / stepper / 滑块 / 下拉 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneImages.java` | 画图 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneText.java` | 画字（含 `FormattedCharSequence` 重载） |
| `src/main/java/com/huhutalk/client/debug/PhoneAppSelfTest.java` | 6 个自检 App 与示例页（默认关闭） |
| [phone-app-api.md](phone-app-api.md) | App 注册 API |
| [phone-ui-toolkit-handoff.md](phone-ui-toolkit-handoff.md) | **工作日志与交接**（先读这个） |
