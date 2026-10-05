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

## 6. 自检：怎么确认骨架是通的

和 App 注册自检同一个开关（详见 [phone-app-api.md](phone-app-api.md) §9）：

```
-Dhuhutalk.debugApps=true     或环境变量   HUHUTALK_DEBUG_APPS=true
```

打开手机桌面会多出四个图标，其中两个专门验骨架：

| 图标 | 验证什么 |
|---|---|
| **自检·骨架** | `PhonePage` + `PhoneList`（模型 B）：顶栏由骨架画、返回键可点、40 行列表能滚、裁剪正确、**滚动后点哪行是哪行**、悬停高亮 |
| **自检·滚动** | `PhonePage` 自管滚动（模型 A）：`scrollY` 与 `logicalY` 互逆换算，滚到任意位置都点得准 |

判定：两个页面都能滚、都能点中行（点中的行号显示在顶栏右侧）、「‹ 返回」能回桌面。

---

## 7. 和现有页面的关系

**现有 12 个页面一行没改，继续照旧工作。**

- 新页面（包括附属模组的页面）**一律用骨架**；
- 现有页面**按需顺手迁**——下次因为功能原因本来就要动它时，顺手换成骨架，一次一个、可单独验收；
- 不搞大爆炸式重构：那要一次性改动 10 个能跑的页面，回归风险与验收成本都不划算。

短期内会存在「两套写法」，这是刻意的取舍。

---

## 8. 已知未做（需要就说）

| 项 | 说明 |
|---|---|
| **控件集**（按钮 / 开关 / 下拉） | 没有第二个使用方之前不做，避免过设计 |
| **滚动条** | 目前只提供 `scrollOffset` / `maxScroll()`，需要时自己画 |
| **主题语义色不全** | `ColorScheme` 只有顶栏 / 气泡 / 操作色，缺「列表行底 / 分隔线 / 次要文字 / 卡片」。骨架目前用 `pageBg` + `accentSoft` 等已有色凑，分隔线用固定半透明黑。要真正贯通主题得给 `ColorScheme` 加字段（record 构造器签名会变），等有需要再动 |
| **多列网格列表** | `PhoneList` 只有单列。`PhoneCasePickerPage` 那种网格暂不支持 |
| **吸顶分组头** | 没有 |

---

## 附：相关文件

| 文件 | 说明 |
|---|---|
| `src/main/java/com/huhutalk/api/phone/ui/PhonePage.java` | 页面骨架 |
| `src/main/java/com/huhutalk/api/phone/ui/PhoneList.java` | 虚拟化行列表 |
| `src/main/java/com/huhutalk/client/debug/PhoneAppSelfTest.java` | 自检 App 与两个骨架示例页（默认关闭） |
| [phone-app-api.md](phone-app-api.md) | App 注册 API |
