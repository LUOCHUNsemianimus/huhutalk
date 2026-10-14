# HuhuTalk 手机 UI（公共工具 + 页面迁移）· 工作日志与交接

> **面向**：接手继续做 HuhuTalk 手机 UI 的对话（加新 App / 继续迁移页面 / 排查模型屏问题）。
> **读法**：先读 **§0 / §0.1**（当前状态与下一步），再按需翻阅两份 API 参考：
> [phone-app-api.md](phone-app-api.md)（App 注册）、[phone-page-skeleton.md](phone-page-skeleton.md)（页面骨架 / 控件 / 图片 / 文字 / 主题）。
> **状态**：公共工具层**已完成**；**12 个内置页面的硬编码改造已完成（6 批，IDE 全项目编译通过）**；
> **尚未实机验收** —— 清单见 §0.1。§1 是详细工作日志，可跳过。

---

## 0. 一句话状态

**两层都就绪**：

1. **公共工具层**（上一轮）：App 注册 → 页面骨架 → 图片/文字工具 → 四个控件 → 主题色板，全部是公开 API；
2. **页面迁移**（本轮，6 批）：原本「抄了一百遍」的硬编码页面已改用这套公开 API 绘制。

收尾指标：

| 项 | 结果 |
|---|---|
| `UiFont` 直接调用 | 从 **421 处** → **0**（全部页面、全部 `api/phone/**` 类都已改用 `PhoneText.px/scale`；只剩 `PhoneText` 自己的转发实现） |
| `UiShapes`（已弃用壳） | 主模组内**已无调用点**，约 30 条弃用告警清零（壳本身保留给第三方附属模组） |
| 样板副本 | `drawScaledText` 9→1、`navH()` 8→1（原来是 4 种公式）、`textH` 5→0、`truncate` 4→1、`hit` 3→1、手绘顶栏 ~10→0、无 `try/finally` 的 scissor 配对 7+→0 |
| 「画在一处、点在另一处」 | 三对互逆映射（`scrollY`/`logicalY`、`itemY`/`indexAt`、`cellX+cellY`/`indexAt`）使其在**结构上不可能** |
| 死代码 | 删除 `MaidListPage` / `MaidSettingsPage`（−428 行） |
| 顺带修的真 bug | `PhoneList.render` 曾把 `viewY` 加了两遍（`viewY != 0` 时整列行下移；既有用法都是 `setViewport(0,0,..)`，所以一直没暴露） |

> ⚠ **行数并没有"极大减重"**：删了 428 行死代码，但 10 个被迁移的页面净 **+87 行** ——
> 因为省下的样板换成了每个文件 30~120 行的说明性注释，且"调用公开 API"比内联一行算术略长。
> **可执行代码是减少的，注释让总数持平。** 想缩小 diff 就把新增注释削一半。

---

## 0.1 现在该做什么

### ① 待实机验收（本机跑不了游戏，只能做 IDE 级编译校验）

开 `-Dhuhutalk.debugApps=true` 后逐条确认：

| # | 看什么 | 期望 |
|---|---|---|
| 1 | 顶栏高度 | 所有页面统一「一行字 + 8px」≈ **17px**（本轮从骨架原先的 ≈25px 统一过来 —— 那个 0.08 比例抄的是两个已删的死页面） |
| 2 | 设置页 | 行高统一 **28px**（`PhoneRow.rowHeight`）；开关/▲▼/滑块三种控件等高对齐；**滑块要能按住拖动**；右侧「切换 / 选壳」胶囊可点 |
| 3 | 联系人页 | 点分组标题折叠/展开后**点哪一行是哪一行**（可变行高是本轮新能力，`PhoneList.itemHeight(int)`） |
| 4 | 手册页 | 三级返回（正文→条目→栏目→退出）与**各级滚动位置记忆**；⚠ `ManualPage` 新增了 `onBack()` 覆写，删掉它会导致点「返回」直接退出手册 |
| 5 | 传文件页 | 上下两个网格（背包 5×36 可滚 / 队列 5×2）、点格入队与移回；滚轮只在背包区生效 |
| 6 | 消息列表页 | 条目命中、未读红点、头像；滚轮与点击在**同帧内列表变化**时不应错行（已用「帧内缓存 + 点击前刷新」处理） |
| 7 | 聊天页 | **行为应与改动前完全一致**（本轮只换 helper，不继承 `PhonePage`）——含相机取景 / 拍照 |
| 8 | 桌面 + 图库 | 图标排布、启动动画、红点；图库三列网格与长按多选 |

### ② 未提交的改动

批次 4~6 尚未提交（`git status` 里 10 个文件 + 本文档）。批次 0~3 已被作者提交为
`a348762 重构主模组p1` / `b4cc604 重构主模组p2`。

### ③ 相机黑屏 bug —— **已修复，且与本次重构无关**

实机验收时发现的「相机页直接画了个黑底」，根因在**上一轮「修图标描边」加的那步 alpha 钉底**：

- `PhoneOverlay.render` 的离屏分支末尾用 `colorMask(false,false,false,true)` 把手机矩形内的 alpha 钉成 255
  （目的是让图标描边不再被"没画满的 alpha"污染）；
- 但它写成 `if (external)`，**没有排除相机取景态**。取景框能透出世界，靠的正是
  「取景区在离屏靶里保持 `(0,0,0,0)`」—— 被钉成 `(0,0,0,255)` 后，屏幕四边形一贴就是**不透明纯黑**；
- 症状"只有模型屏坏、悬浮窗正常"完全吻合：`if (external)` 只在模型屏出图那条路上为真；
- **修法**：`if (external && !cameraTransparent)`。

完整复盘见 [phone-model-screen-handoff.md](phone-model-screen-handoff.md) §5.8-38b 与
[camera-black-screen-handoff.md](camera-black-screen-handoff.md)。

> **为什么能确定与本轮重构无关**（这条方法论值得留着）：相机链路上的**全部**改动都用
> `git diff` 逐行核对过，只有 `UiShapes.`→`PhoneShapes.` 与 `UiFont.`→`PhoneText.` 两种**改名**；
> 而 `PhoneText.px/scale` 的方法体就是 `UiFont.sh/sc` 的转发（逐字节相同），`UiShapes` 是纯转发壳。
> `ChatPage` 还用 `git diff -G"cameraActive|cameraShot|…"` 验证过**相机代码一行未动**。
> 教训：**"改动之后出问题"不等于"改动导致的"** —— 先按"唯一变量"把两边都排除掉，再动手查。

### ④ 传文件页「点不动 + 物品压在光标上」—— **已修复**（本轮继承收尾时实机反馈）

#### bug 1：整页格子点不动 —— **判据与被委托方的契约不一致**（批次 4 的回归）

`FileTransferPage.onContentClick` 判的是 `action == GLFW_RELEASE`（旧实现自己手写命中时的判据，
迁移时为了"不改既有手感"照抄了下来），**但命中已经交给 `PhoneGrid`**，
而 `PhoneGrid.mouseClicked` 的契约是**按下即触发**：

```java
// PhoneGrid.java
public final boolean mouseClicked(double localX, double localY, int button, int action) {
    if (action != GLFW.GLFW_PRESS || !acceptsButton(button)) return false;   // ← RELEASE 在这里被吞掉
```

于是 `onCellClick` 永远不触发，两个网格全部点不动（**顶栏返回键照常可用**，因为它走
`PhonePage` 的 PRESS 分支 —— 这个"一半好一半坏"的现象正是定位的抓手）。

**修法**：判据改成 `GLFW_PRESS`。**手感零变化**，因为
`PhoneOverlay.onPhoneSideButton` 在轻点结束时是**连着补发 PRESS + RELEASE** 的
（同一个 tick、中间没有渲染），而真实鼠标左右键根本不会走到页面（只有玩家绑定的手机侧键进这条通路）。

**旁证**：全项目扫一遍 `onContentClick`，**只有 `FileTransferPage` 一个页面判 RELEASE**，
其余 12 处（`PhoneCasePickerPage` / `GalleryPage` / `ContactListPage` / `MessageListPage` /
`ManualPage` / `MaidInfoPage` / `SettingsAppPage` / `ComingSoonPage` / `MainPage` / `VisionModelSettingsPage` /
`SettingsPage` / `GalleryDeleteButton`）全部判 PRESS。

> **通用教训**：「哪一刻算点击」和「点到哪一格」是**同一件事的两半**，不能一半留在页面里、一半交给控件。
> 谁做命中，谁定义 action 语义。

#### bug 2：物品图标压在独立光标上 —— **z 深度**，不是绘制顺序

GUI 的深度测试是开着的，`RenderType.gui()` 用的是 **`LEQUAL_DEPTH_TEST`**
（1.21.1 源 `RenderType.java` L698-707），而：

| 画什么 | z | 依据 |
|---|---|---|
| `fill` / `drawString` / `blit`（页面、光标、文字……） | **0** | `GuiGraphics.fill(...)` 默认 z=0 |
| **物品图标** | **150** | `GuiGraphics.renderItem` 里硬编码 `pose.translate(x+8, y+8, 150 + …)`（源 L1284） |
| 原版物品角标（数量/耐久） | 200 | `GuiGraphics.renderItemDecorations` 的 `translate(0,0,200f)` |

z 越大离摄像机越近 ⇒ 物品先把 150 写进深度缓冲，之后 z=0 的 `fill` **比较 LEQUAL 一律失败**
⇒ 物品压在光标上。原版给角标用 200 正是同一个理由。

**修法**（两处，都在最上层元素上显式抬 z）：
- `PhoneOverlay.renderCursor`：整个光标 `pushPose / translate(0,0,300) / popPose` ——
  取 300 是为了同时高过物品本体（150）与原版角标（200）。**因为是改在光标自己身上，
  `ChatPage` 底部扩展栏里那个"传文件队列"预览（`ChatPage:1419`）也一并修好了。**
- `FileTransferPage.drawSlot` 的数量角标：`translate(0,0,200)`（照抄原版做法）。

> **通用教训**：手机 UI 里凡是"必须在最上层"的东西（光标、气泡、角标），
> **都要自己声明 z**，不能指望"后画 = 在上层" —— 深度测试会让后画的 z=0 元素沉到先画的 z=150 之下。
> 以及：`ChatPage` / `GalleryPage` 之类**同时画物品与 z=0 覆盖层**的地方，以后新增覆盖层时记得这条。

### ⑤ 「新建分组」从自绘弹窗改成内联输入 —— **已完成**

**问题**：`MaidInfoPage` 分组设置里点「＋ 新建分组」会开
`setScreen(new PhoneGroupNameInputScreen(this))` —— 那个屏**自己画一个居中对话框**
（半透明遮罩 + 白框 + 输入框 + 取消/确定）。这与手机页面的设计语言相悖：
手机屏本身就是一整块内容区，弹窗会把它整块盖掉（连同状态栏、水印栏一起），
看起来像一个和手机无关的原版界面。

**改法**：改用公开控件 `PhoneTextInput` 的**内联**形态，长在分组列表最后一行。
点它只开一个**完全透明**的 `PhoneTextCaptureScreen` 抢键盘 —— 与设置 App 的
API Key / 模型名 / 接口地址三个框**同一条通路**（`VisionModelSettingsPage` 早就这么做了）。
编辑期手机界面照常渲染在底下，玩家看到的是"这一格在闪、我直接往里打字"。

| 项 | 变化 |
|---|---|
| `MaidInfoPage` | 分组设置末行 = 内联输入框（占位「＋ 输入新分组名，回车确定」，`maxLength(16)`）；`GroupSlot` 去掉 `isNewGroup` 分量；新增 `commitNewGroup(String)` 承担原弹窗的校验与落盘；输入框底下垫一层 `pageBgList()`（输入框自身底色就是 `cardBg`，直接压在卡片上看不见） |
| `PhoneTextInput` | **新增公开 `startEditing()`** —— 给"页面自己管热区"的场合用（框比行矮时，页面按行矩形判定完直接调它，不必凑控件的内部几何） |
| `PhoneGroupNameInputScreen` | **删除**（全工程仅此一处引用；它也是 `UiShapes` 改名那轮的 11 个文件之一） |
| 行为差异 | 校验失败不再"拒绝关闭弹窗"，而是发提示 + **把已输入的字留在框里**，玩家再点一下接着改。⚠ 捕获屏的 `finish()` 是「先 `endEdit`、后 `setScreen(null)`」，所以 **`onCommit` 里不能重开捕获屏**（会被紧接着关掉） |

文档：`phone-page-skeleton.md` §8.1 把控件表从「四个」更新为「五个」，新增 §8.1.1
（`PhoneTextInput` 的坐标规矩 / `startEditing` / `onCommit` 的坑）。

### ⑥ 离屏绘制批处理 —— **已回退**（踩到的坑比优化值钱，务必读完）

**做了**：`PhoneOverlay.renderExternal` 整段包 `graphics.drawManaged(...)`，把模型屏的 draw call
从「O(绘制调用数)」压到「O(RenderType 切换数)」（圆角矩形逐像素行 `fill`，一个 200px 高的矩形
= 200 次 `endBatch`）。

**回退原因（实机现象：手机独立光标消失）**：

`drawManaged` 把 `GuiGraphics` 置为 `managed=true`，于是 `fill` / `blit` / `drawString`
**不再立刻 flush**。而本模组的代码里有若干处"**在绘制前后改 RenderSystem 全局状态**"的写法 ——
它们在旧的 unmanaged 语义下是**隐式安全**的（因为每次绘制都已立刻落盘），批处理化之后
那个窗口里攒着的几何会在**状态已经改掉之后**才被刷出：

```java
// PhoneOverlay：离屏靶 alpha 钉底
RenderSystem.colorMask(false, false, false, true);   // ← 关掉 RGB 写
graphics.flush();     // ⚠ 这句原注释是"先把上面所有绘制的批处理落盘"
                      //    unmanaged 时它恒为空操作；managed 时它真的开始刷 ——
                      //    于是尚未落盘的几何被"只写 alpha"地刷了出去
fillRoundedRect(..., 0xFFFFFFFF);
graphics.flush();
RenderSystem.colorMask(true, true, true, true);
```

**光标是整帧最后一批 `gui()` 填充**，正好落在这个窗口里 ⇒ RGB 一个像素都没写、只写了 alpha
⇒ 光标看不见。同理还有 `renderPageTranslated`（`setShaderColor(1,1,1,alpha)` → `page.render` →
立刻恢复 `(1,1,1,1)` + `disableBlend()`）：批处理化后转场/启动动画的**淡入 alpha 会丢**。

> **通用教训（"批处理化"这类改动的检查表）**：
> 只要把 `GuiGraphics` 从 unmanaged 改成 managed（或反过来），就必须把每一处
> 「改 RenderSystem 状态 → 绘制 → 还原状态」的代码改成
> 「**改状态 → 绘制 → 显式 `graphics.flush()` → 还原状态**」。
> `RenderType` 自己会重设混合/深度（所以 `disableBlend` 通常无害），
> 但 **`colorMask` / `setShaderColor`（ColorModulator）这类"着色器 uniform + 光栅化掩码"是全局的，
> RenderType 不会替你恢复** —— 这两类才是真凶。
> 本项目里的全部窗口只有两处：`PhoneOverlay` 的 alpha 钉底、`PhoneOverlay.renderPageTranslated`。

**当前状态**：两个文件已 `git checkout b1bbbae -- …` 回退到批处理之前（编译绿）。
要重新启用，先修上面两处窗口，并且**必须在模型屏与悬浮窗两条路上都实测**。

### ⑦ 分组管理（联系人设置页）—— 已完成一半，A/B/E 待定位

**已落地**：

| 项 | 内容 |
|---|---|
| **排序**（D） | `MaidInfoPage.groupOptions()` 改成**自定义分组在上、四个默认状态分组在下**。理由：默认分组（在线/离线/已关机/死亡）不可删，可删的排上面，✕ 就集中在列表上半段。顺手过滤掉"名字撞上默认分组"的残留项（`addCustomGroup` 已拦，但老设置文件可能有） |
| **删除分组**（C） | 自定义分组行右端画红色「✕」，点它删除。判据只有 `isDeletableGroup()` 一处（渲染画不画 ✕ 与命中响不响应共用），✕ 的矩形也由 `deleteMarkX/deleteMarkW` 一处产出 —— 不会"画在一处、点在另一处" |

**⚠ 删除必须连带把女仆收回默认分组**（否则会造出幽灵分组）：

女仆分组是「手动分组优先，否则按状态」。**只删分组名**的话，原本手动分到该组的女仆，
其手动分组字段还指着那个已经不存在的名字，`MaidGroupHelper.resolveGroup` 会照样返回它 ⇒
**联系人列表里照样有一个分组标题，而分组选择列表里没有它**（列表只认 4 个默认名 + 已登记的自定义名）
⇒ 「联系人列表里还在、但选不中」。`deleteGroup()` 因此遍历一次手动分组映射把指向本组的条目清空。

> 顺带发现：`HuhuTalkClientSettings.isKnownGroupName()` **全工程零调用点**（死代码），
> 以及 `isDefaultGroupName` 原本是 private —— 已改为 public，作为"可不可删"的唯一判据来源。

#### ⑦b **「最后一个分组变成灰色圆角方框」= 坐标系混用**（A/B 两个报告其实是同一个 bug）

**现象**（实机截图）：分组列表最后一行**本该显示分组名，却显示成一个灰色圆角方框**；
点那个方框**仍然会正确切到那一行对应的分组**。

**根因**：`renderNewGroupRow` 里那条"给输入框垫的对比底色"用**内容坐标**去画了，而
`PhoneShapes.fillRoundedRect` / `g.fill` / `PhoneText.draw` 收的是**屏幕坐标**：

```java
// ✗ 修复前：漏了 originX/originY ⇒ 整条底色上移了约一行（= 内容原点到顶栏的距离）
PhoneShapes.fillRoundedRect(g, inX - px(2), inY - px(2), inW + px(4), inH + px(4), 6, c.pageBgList());
groupNewInput.bounds(inX, inY, inW, inH);            // ✓ 控件是内容坐标
groupNewInput.render(g, originX, originY);           // ✓ 它内部自己加 origin
```

于是灰条**正好盖住分组列表最后一行的文字**，而命中判定用的是 `slot.y()`（本来就是那一行）
⇒ "看到的是灰框、点下去却是那个分组"。

**这正是 A 与 B 两条报告的来源**（换排序把它换了受害者）：

| 报告 | 当时的列表顺序 | 被灰条盖住的 |
|---|---|---|
| **B**「新建分组没持久化 / 新加的分组没法选中」 | 旧顺序（自定义分组在**最后**） | **最新建的那个自定义分组** ⇒ 看起来没保存、也"没法点"（看不见）。联系人列表照常显示它，所以"联系人列表里还在" |
| **A**「死亡分组变成灰色圆角方框」 | 新顺序（自定义在上、默认在下） | 最后一个槽位 = **死亡** |

**修法**：底色条补上 `originX/originY`（见该方法的 javadoc）。

> **通用教训（已写进 `MaidInfoPage.renderNewGroupRow` 的 javadoc）**：
> 同一个方法里混用"页面自绘（屏幕坐标）"与"控件 `bounds`（内容坐标）"时，
> **宁可把 `originX/originY` 写在每一行上**。这类错误不会崩、不会报错，
> 只会让某个东西"挪到旁边一行"，而且**看起来像另一个完全无关的 bug**
> （本次它伪装成"新建分组没持久化"和"某分组渲染坏了"两条）。
> 判据：画完问一句"这个 y 是屏幕坐标还是内容坐标？"

**E（联系人列表点击收起）**：**已经实现**，不是待办 ——
`ContactListPage.collapsedGroups` + `onItemClick` 切换 + `refreshRows()` → `PhoneList.invalidateLayout()`，
分组标题右侧画 ▾/▸ 箭头。若实机点不动，那是另一个问题（输入通路），需要具体现象。

---

## 1. 都做了什么（详细工作日志）

> **§1.1–1.5 是上一轮**（搭公共工具层）；**§1.6–1.18 是这一轮**（把 12 个内置页面迁到公开 API）。
> 只想看结论与下一步的话，读完 §0 / §0.1 就够了，本节是归档用的细节。

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
| `api/phone/ui/PhoneShapes.java` | 画圆角：`fillRoundedRect` / `fillRoundedRectFrame` / `fillRoundedRectFrameTiled`（**本轮从 `com.huhutalk.phone.UiShapes` 提升而来**，见 §1.5） |
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

### 1.5 补充（识图多平台那一轮）：`UiShapes` 提升为公开件

**问题**：公开工具链自己全都在用圆角矩形 —— `PhoneRow` / `PhoneDropdown` / `PhoneSlider` /
`PhoneSwitch` / `PhoneTextInput` 五个公开控件，外加 `api/phone/camera/PhoneCameraPage` ——
但实现住在**非公开**的 `com.huhutalk.phone.UiShapes` 里。
于是"只用 `api/phone/ui` 写页面"这条标准在**圆角矩形**这一项上天然做不到。

**处理**：
| 项 | 变化 |
|---|---|
| `api/phone/ui/PhoneShapes.java` | **新增**，成为实现的正式归属（公开包自洽） |
| `phone/UiShapes.java` | 变成**兼容转发壳**，标 `@Deprecated`；签名一字未改，所以既有页面**一行都不用改** |
| 公开包内 6 个文件 | 改用 `PhoneShapes`（`PhoneRow` / `PhoneDropdown` / `PhoneSlider` / `PhoneSwitch` / `PhoneTextInput` 同包免 import；`PhoneCameraPage` 改 import） |
| 10 个老页面 | **当时未改动**，仍走 `UiShapes` 壳（代价：每个调用点多一条 `已弃用` 告警 —— 这正是"该迁了"的信号）。**本轮已全部迁完**，主模组内 `UiShapes` 已无调用点 |

**✅ 已处理（批次 5 收尾）**：`PhoneCameraPage`、`GalleryPage`、`GalleryDeleteButton` 原先都直接用了
非公开的 `UiFont`（`sh` / `sc`）—— `PhoneText.px(int)` 与 `PhoneText.scale(float)` 是它们的公开等价物
（逐字节相同的换算），已全部换掉。**现在 `api/phone/**` 与 `pages/**` 都不再依赖 `com.huhutalk.phone.UiFont`**，
只剩 `PhoneOverlay`（框架自身，非页面）与 `PhoneText` 自己的转发实现还引用它。

### 1.6 本轮（迁移现有硬编码页面）· 批次 0：补齐公开 API

**背景**：作者决定推翻上一轮的「现有页面按需顺手迁」策略，改为**分批全量迁移**。
理由是把重复量量化之后太吓人：9 份 `drawScaledText`、8 份 `navH()`（**4 种不同公式**）、
5 份 `textH`、4 份 `truncate`、3 份 `hit`、7+ 处**没有 `try/finally`** 的 `enableScissor` 配对、
421 处裸 `UiFont.sh/sc/uiScale` 调用。

动手前先补齐骨架覆盖不到的部分（**全部是纯新增，既有页面零改动**）：

| 新增 | 内容 | 为什么必须有 |
|---|---|---|
| `PhoneText` +4 个重载 | `draw` / `drawShaded` / `drawCenteredIn` / `width` 的 `FormattedCharSequence` 版 | emoji 与 `Component.getVisualOrderText()` 拿到的不是 `String`；`ChatPage` 与 `PhoneOverlay` 各抄了一份 |
| `PhoneList.itemHeight(int)` | 第 i 行的行高（默认转发到 `itemHeight()`），内部改走**行高前缀和 + 二分** | `ContactListPage` 是「分组标题 18px + 条目 N px」的混排行高。`itemHeight()` 由 `abstract` 放宽为带默认实现（源码/二进制都向后兼容） |
| `PhoneList.invalidateLayout()` | 声明行高已变，前缀和下次查询时重建 | 折叠/展开分组会改行高，不声明的话命中算的还是旧值 |
| `PhoneList.separatorColor(int)` | 按行决定分隔线色 | 分组标题下面要**不画**线 |
| `PhoneGrid<T>`（新类） | 列数固定、格子等大的虚拟化网格 | `PhoneCasePickerPage`(4 列) / `FileTransferPage`(5 列) / 图库(3 列)。**没有**做成 `PhoneList.columns(int)`：网格命中是整除取余 O(1)，列表是前缀和二分，两套数学 |
| `PhoneLayout`（新类） | `hit(px,py,x,y,w,h)` / `topShadow` / `horizontalShadow` | `PhonePage.hit` 是 `protected`，`ChatPage`（相机宿主）/ `MainPage`（桌面）/ 相机页都用不到，各自抄了一份 |
| `PhonePage.bottomInset()` | 内容区底部预留高度，只影响 `maxScroll()` | 底部 Dock 是**悬浮**的（`getBottomNavHeight()` 恒为 0），既有页面各自写 `maxScroll + 0 + sh(30)` 兜遮挡；收成一处 |
| `PhonePage.hit` | 改为转发 `PhoneLayout.hit` | 消除最后一份矩形命中的重复 |

**顺手修掉的一个真 bug**：`PhoneList.render` 里是 `int y = top + itemY(i)`，而
`top = originY + viewY`、`itemY(i)` 里**又加了一次 `viewY`** —— `viewY != 0` 时整列行会下移
一个 `viewY`。既有用法都是 `setViewport(0, 0, ..)` 所以一直没暴露。已改为
`originY + itemY(i)`（`itemY` 给的是内容坐标，只需再加内容区屏幕原点）。

**⚠ `PhoneLayout.hit` 与 `PhonePage.hit` 参数顺序不同**（前者「先点后矩形」、后者「先矩形后点」）：
前者刻意对齐 `ChatPage` / `MaidInfoPage` / `SettingsPage` 三个现存私有副本的顺序，
这样迁过去时调用点一个字都不用改。

**编译状态**：批次 0 已通过 IDE 全项目编译（`build_project` → `isSuccess: true, problems: []`）。
后续批次见 [phone-page-skeleton.md](phone-page-skeleton.md) §11.1 的进度表。

### 1.7 批次 0 的追加项：顶栏高度统一（**一个打破既有观感的改动**）

动手迁页面时发现：`PhonePage` 的顶栏高度公式 `max(height * 0.08, lineHeight + 4)`（默认 **≈25px**）
抄的是 `MaidListPage` / `MaidSettingsPage` —— 而那正是本轮要删掉的<b>两个死页面</b>。
活着的内置页面用的都是另一套：`lineHeight * UiFont.sc(1.0f) + UiFont.sh(8)`（默认 **17px**）。

| 页面 | 顶栏公式 | 高度 |
|---|---|---|
| 聊天 / 消息 / 联系人 / 设置 / 女仆信息 / 手机壳选择 | `lineHeight*sc(1.0f) + sh(8)` | 17px |
| `MaidListPage` / `MaidSettingsPage`（**已删**） | `h * 0.08` | 27px ← 骨架抄的来源 |
| `PhonePage`（改动前） | `max(h*0.08, lineHeight+4)` | ≈25px |

**作者拍板统一到 17px**（内置页现高）。已迁移到骨架的 4 个页面
（`SettingsAppPage` / `VisionModelSettingsPage` / `GalleryPage` / `GalleryViewerPage`）
顶栏因此变矮 8px、内容整体上移 —— 因为它们全都用 `contentTop()` / `topBarHeight()` 动态定位
（`GalleryDeleteButton.hit(...)` 是把 `topBarHeight()` 当参数传进去的），
**没有任何一页需要校正布局常量**。

顺带修了一个同路径上的真 bug：`renderTopBar` 算标题垂直居中用的是 `font.lineHeight`（1 倍行高），
字体倍率调到 2.0 时顶栏变高而文字仍按 9px 居中 → 标题明显偏下。
现已改用新增的 `PhonePage.topBarLineHeight()`（随全局字体倍率），
并且返回键与标题的绘制也换成 `PhoneText.draw`（此前用 `drawString`，不受字体倍率影响）。

### 1.8 批次 1：删死代码 + 两个页面迁骨架 + `UiShapes` 全量改名

| 项 | 变化 |
|---|---|
| `MaidListPage.java`（176 行）、`MaidSettingsPage.java`（252 行） | **删除**。全工程（含两个附属模组）无任何 `new`，顶栏还是老的深灰 `#333333` + 1 倍字号 |
| `ComingSoonPage.java` | 22 行裸 `IPhonePage` → `extends PhonePage`。旧实现**连页面底色都没画**（桌面底色直接透过来）、文案钉在硬编码 `(110, 160)`、命中框是 `220×340` 魔数。现在底色/顶栏/返回键由骨架给，文案用 `PhoneText.drawCenteredIn` 真正居中并跟随字体倍率 |
| `PhoneCasePickerPage.java` | 189 → 约 200 行但**删掉了全部样板**：手绘顶栏、手写 `navH()`、手写 `drawScaledText`、手写 scissor 配对、以及**渲染与命中各算一遍**的网格坐标。网格改用新的 `PhoneGrid`。⚠ 滚轮速度从 ≈40px/单位变成统一的 10px/单位（与 `PhoneList` 一致）<br>**保留了「点已选中的方块 → 恢复默认」这条既有交互** |
| `UiShapes` → `PhoneShapes` | 11 个文件、约 60 处调用点全部改名（`PhoneOverlay` / `PhoneGroupNameInputScreen` / `SettingsPage` / `ManualPage` / `MainPage` / `MaidInfoPage` / `GalleryPage` / `GalleryDeleteButton` / `FileTransferPage` / `ChatPage` / `PhoneCaseRenderer`）。**主模组内已无 `UiShapes` 调用点**，约 30 条弃用告警清零 |
| `phone/UiShapes.java` | **保留**（未删除）：它是 `public` 类，第三方附属模组可能引用。javadoc 已改为「主模组已无调用点，仅为兼容保留」 |

**编译状态**：批次 1 已通过 IDE 全项目编译（`isSuccess: true, problems: []`）。

### 1.9 公开 API 的默认行为变化汇总（附属模组回归检查用）

| 变化 | 影响 |
|---|---|
| `PhonePage.topBarHeight()`：≈25px → 17px | 凡是继承 `PhonePage` 的页面（含**附属模组的页面**）内容区整体上移 8px |
| `PhonePage.renderTopBar` 用 `PhoneText.draw` + `topBarLineHeight()` | 标题与返回键现在**跟随全局字体倍率**缩放（此前是固定 1 倍） |
| `PhoneList.itemHeight()`：`abstract` → 带默认实现 | 向后兼容；只覆盖 `itemHeight()` 的既有子类行为不变 |
| `PhoneList.render` 修 `viewY` 重复计数 | `viewY != 0` 的调用方才看得出差别；`setViewport(0,0,..)` 行为不变 |

### 1.10 批次 2：`SettingsPage` 改用骨架 + 控件（**724 → 约 570 行，净删约 150 行样板**）

旧实现 724 行里真正的设置内容只有 16 行，其余是样板：手绘顶栏与 SOS 胶囊、
`navH()`/`rowH()`/`textH`/`contH`/`btnW`/`truncateUi`/`drawScaledText` 七个私有工具、
手写开关（胶囊 + 圆点）、手写滑块（轨道 + 把手）、手写 stepper（▲▼ + 命中）、
手写 scissor 配对，以及**渲染与命中各算一遍**每个行坐标
（`Layout` 里 5 个分组每行的 y，在 `mouseClicked` 里用 `hit(relX, ly, rectX, L.xxxRow[i], rectW, rowH())` 再写一遍）。

现在：`PhoneRow` 负责「标签 + 右侧控件」的布局与命中，`PhoneSwitch` / `PhoneStepper` / `PhoneSlider`
负责三种控件，页面里只剩一个 `CapsuleButton`（「切换 / 选壳」小胶囊，约 45 行）。
**两个 `dragging` 布尔量消失**——谁在被拖动由 `PhoneRow` 代管，落盘时机由 `PhoneSlider.onCommit` 承担。

**刻意接受的差异**（实机验收时重点看这几条）：

| 项 | 旧 | 新 |
|---|---|---|
| 页面底色 | 硬编码 `#F0E4EA` | 主题 `pageBgList` `#EDE1E7` |
| 分组标题色 | 硬编码 `#555555` | 主题 `textMuted` `#8A7C82` |
| 行高 | 固定 `sh(24)` | `PhoneRow` 按「文字高 + 控件高 + 留白」自动算（约 19~22px） |
| 每字耗时 / 单句上限 | 无限 `+50 / -50` | **离散档位** 50ms / 100ms 一档（上限 3000 / 10000ms），默认值 500 / 2400 都落在网格上 |
| 内容裁剪下界 | 裁到「手机底 − 导航高 − sh(4)」 | 只裁到手机底（`bottomInset()` 改为预留滚动余量，与消息/联系人页一致） |

**编译状态**：批次 2 已通过 IDE 全项目编译与告警检查（`problems: []`）。

### 1.11 批次 2 追加：`PhoneRow.rowHeight(int)` —— 统一行高

作者实机看过之后反馈：**设置页"有点窄"，每一行高矮不一**，希望恢复成旧版那种"所有行等高"的观感。

**根因**：`PhoneRow` 默认按内容自动算行高，于是同一种「标签 + 控件」形态因为控件不同而得到 4 种行高：

| 行类型 | 自动行高（默认字体倍率） |
|---|---|
| 开关行 | 19px（`max(标签9, 开关8) + 上下留白10`） |
| 胶囊按钮行 | 19px |
| stepper 行 | 28px（`max(9, 18) + 10`） |
| 滑块行（`STACKED`） | 35px（`5 + 9 + 3 + 13 + 5`） |

旧设置页是**所有行等高 `sh(24)`**。

**新增公开件**：`PhoneRow.rowHeight(int basePx)` —— 强制行高（随字体倍率缩放），并定义了固定行高下三种控件的摆法：

- `INLINE_RIGHT`（开关 / stepper / 按钮）：**标签与控件一起在行内垂直居中**；
- `STACKED`（滑块 / 下拉）：**标签贴顶、控件贴底**（与旧设置页「标签在上、滑块在下」一致，
  也保证 3px 的轨道不会横穿标签文字）。

`layout()` 里还加了一个**下界兜底**：`max(请求值, 标签高 + 控件高 + 留白)`。
没有它的话，字体倍率调到 0.5 时固定像素的行高会先撑不住（滑块高度是固定像素、不随倍率缩放），
轨道就会切进标签里。

**设置页用 28 而不是旧的 24**：`STACKED` 行的无碰撞下界是
`上留白 5 + 标签 9 + 滑块控件 13 = 27`（控件贴底），24 会让轨道正好穿过标签文字。

**编译状态**：通过 IDE 全项目编译与告警检查（`problems: []`）。

### 1.12 批次 3：三个列表/卡片页迁到骨架

| 页面 | 行数 | 关键改动 |
|---|---|---|
| `MessageListPage` | 368 → 约 300 | `PhonePage` + `PhoneList<String>`（固定行高）。**「第 i 行画在哪」原先在 `render` 与 `mouseClicked` 里各算一遍**，现在只有 `itemY`/`indexAt` 一对互逆映射 |
| `ContactListPage` | 381 → 约 340 | `PhonePage` + `PhoneList<Row>` 的**可变行高**（`itemHeight(int)`）—— 分组标题 18px / 女仆条目 N px，折叠时调 `invalidateLayout()` |
| `MaidInfoPage` | 681 → 706 | `PhonePage` 模型 A（自管滚动）。删掉整个 `render` override（手绘顶栏 + `enableScissor` 配对）、`navH`/`textH`/`hit`/`drawScaledText`、8 个硬编码色常量、16 处 `UiFont`、以及**算了两次**的确认框几何（改成一个 `ConfirmLayout` record） |

**新增包私有件** `pages/PlayerHeader.java`：消息页与联系人页顶栏左侧的
「玩家头颅正面 + 玩家 ID」原本各写了一遍（19 行逐字相同，IDE 的重复代码检查当场报出）。

**踩到并处理掉的坑：`PhoneList` 的 `items()` 必须在帧内稳定。**
`ChatHistoryStorage.getConversationIds()` **每次调用都新建一份 List**，
而 `PhoneList.mouseClicked` 会先 `indexAt(...)`（读一次 items）再 `items().get(index)`（再读一次）——
两次之间列表若变了就会越界或点错行。两个列表页都改成「缓存一份、每帧刷新、点击前再刷新」。
`ContactListPage` 的 `buildRows()` 更重（遍历全部女仆 + 排序），缓存同时省掉了每帧多次重建。

**编译状态**：批次 3 已通过 IDE 全项目编译与告警检查（`problems: []`）。

### 1.13 批次 4：`ManualPage` / `FileTransferPage` / `MainPage`

| 页面 | 改动 |
|---|---|
| `ManualPage` | 迁到 `PhonePage` 模型 A。⚠ **对外 API 一字未动**：`registerSection` / `externalSections` / `ManualEntry` / `ManualSection` 四个符号的签名、嵌套位置、`synchronized (ManualPage.class)` 纪律、注册表快照语义全部保持，附属模组 `huhutalk-douyin` 无需重编 |
| `FileTransferPage` | 255 行裸 `IPhonePage` → `PhonePage` + **两个 `PhoneGrid`**（背包 5×36 可滚 / 队列 5×2 固定）。删掉 `cols/margin/gap/cell/gridTotal/gridX0` 七个几何方法 + `backpackIndexAt`/`queueIndexAt` 两个命中方法 —— 旧实现里渲染用 `x = x0 + col * rowH` 算一遍、命中用 `col = (relX - gridX0) / rowH` 再算一遍<br>⚠ **本批引入过一个回归**：把点击判据从旧实现的 `GLFW_RELEASE` 照抄下来，而 `PhoneGrid` 只认 `GLFW_PRESS` ⇒ 整页格子点不动。**已修**，见 §0.1-④ |
| `MainPage` | **只换 helper**（桌面启动器：无顶栏、无滚动、有自己的槽位命中与启动动画，不适合继承骨架）。`drawScaledText`/`drawScaledTextShaded` 两个私有副本 → `PhoneText.draw`/`drawShaded`；5 处 `UiFont.sc` → `PhoneText.scale` |

### 1.14 批次 5：`ChatPage` 只换 helper（2956 → 2937 行）

**为什么不继承 `PhonePage`**（见 [phone-page-skeleton.md](phone-page-skeleton.md) §11.2）：
`PhonePage.render` 是 `final` 且必定用不透明底色铺满内容区 —— 与相机取景需要的**透明**直接冲突；
它只认**一个** `scrollOffset`，而聊天页有三套独立滚动（消息 / 表情 / 图片画廊）；
底栏是输入框 + 5 图标扩展栏 + 多个浮层，`contentTop()` 那一刀切不进来。

**这一批做了什么**：
- `drawScaledText` 的 **String 与 `FormattedCharSequence` 两个重载**（31 处调用点）→ `PhoneText.draw`
  （批次 0 专门为它加的 `FormattedCharSequence` 重载正好派上用场）；
- `drawTopShadow` → `PhoneLayout.topShadow`；
- 顶栏标题的内联截断（5 行手写算术）→ `PhoneText.truncate`
  （唯一差异：极端情况下旧实现会截成空串，现在会显示「…」）；
- **约 150 处 `UiFont.sh/sc/uiScale` → `PhoneText.px/scale`**，`UiFont` 依赖清零。

**刻意没做**：ChatPage 的私有 `hit(...)` **保留原样，没有换成 `PhoneLayout.hit`**。
两者**边界语义不同**：私有副本是**闭区间**（`x <= rx + rw`），公开件是**半开区间**（`x < rx + rw`）。
本页命中区密集相邻（消息气泡 / 图库格子 / 表情格 / 工具瓦片 / 相机构图按钮），
边界像素归谁所有会实际影响点击行为，而聊天页没有自动化测试兜底 —— 换语义属于"改行为"，不是"去重复"。
方法体里已写好结论与替换方法（参数顺序一致，换一行即可），留给实机验收后决定。

**编译状态**：批次 4 / 5 已通过 IDE 全项目编译（`problems: []`）。

### 1.15 `ManualPage` 的两个必要细节（复核结论）

子代理在迁移时做了两个**必须保留**的决定：

1. **新增 `onBack()` 覆写**：`if (!onBackPressed()) PhoneOverlay.popPage();`
   旧实现在顶栏返回键的命中分支里写的就是这一句；而 `PhonePage.onBack()` 的默认实现是**无条件
   `popPage()`**。不覆写的话，在「二级列表 / 正文」级别点「返回」会直接退出手册，而不是回退一级 ——
   这是**行为保持**，不是加功能。右键返回仍由 `PhoneOverlay` 直接调 `onBackPressed()`。
2. 构造函数上加了 `@SuppressWarnings("TextBlockMigration")`：那 5 条告警来自**逐字复制的用户可见文案**
   （IDE 的文本块建议只看字面量），而改文案被禁止，所以只能抑制。字符串一字未改，删掉抑制也安全。

四个公开符号已逐字复核未变；`synchronized (ManualPage.class)`、构造期快照、OWNER 语义与顺序全部原样。

### 1.16 批次 6（可选，只做了低风险子集）：`PhoneOverlay`

**做了**：
- 私有的 `drawScaledText(g, Font, FormattedCharSequence, ...)`（与批次 0 新增的
  `PhoneText.draw` 重载**逐字节相同**）→ 删除，4 处调用点改用 `PhoneText.draw`；
- 约 50 处 `UiFont.sh/sc/uiScale` → `PhoneText.px/scale`。

**刻意没做**：`PhoneOverlay` 的私有 `fillRoundedRect(g, x, y, w, h, topR, bottomR, color)`。
它的签名是**上下圆角半径分别指定**，而 `PhoneShapes.fillRoundedRect` 是**统一半径** ——
两者不是同一个图元，替换会改变手机壳/状态栏/水印栏的圆角形状。
（真要对齐，得给 `PhoneShapes` 加一个 `fillRoundedRectV(g, x, y, w, h, topR, bottomR, color)`
公开重载 —— 属于新增 API，留待需要时再做。）

### 1.17 `UiFont` 的最终收敛状态

```
grep "UiFont\." src/  →  只剩 PhoneText 自己（public 壳的实现）与几处注释
```
**所有页面、所有 `api/phone/**` 类都不再直接调用 `UiFont`** —— 一律走 `PhoneText.px/scale`。
`com.huhutalk.phone.UiFont` 现在是纯粹的内部实现细节。

### 1.18 全量迁移的收尾验收

| 项 | 结果 |
|---|---|
| 全项目编译 | `build_project` → `{"isSuccess":true,"problems":[]}` |
| 12 个页面 | 8 个迁到 `PhonePage`；`ChatPage`/`MainPage` 按设计只换 helper；`PhoneCasePickerPage`/`ComingSoonPage` 已迁 |
| 死代码 | 删除 `MaidListPage`/`MaidSettingsPage`（−428 行） |
| `PhoneList` 的 `viewY` 重复计数 bug | 已修（`viewY != 0` 时整列行会下移，既有用法都是 `setViewport(0,0,..)` 所以从未暴露） |
| 新增公开件 | `PhoneGrid`、`PhoneLayout`、`PhoneText` 的 4 个 `FormattedCharSequence` 重载、`PhoneList.itemHeight(int)/invalidateLayout/separatorColor(int)`、`PhonePage.bottomInset`、`PhoneRow.rowHeight` |

**⚠ 仍需作者实机验收**（本机 shell 沙箱不可用，无法启动游戏）：
开 `-Dhuhutalk.debugApps=true`，逐页确认 —— 顶栏统一到 17px 后的观感、设置页 28px 统一行高与滑块拖动、
联系人页分组折叠后点得准、手册三级返回与滚动记忆、传文件页两个网格、
以及聊天页（helper 替换后行为应完全不变）。

---

## 2. 开工前必读：四条纪律

1. **客户端 only**。整套 API 依赖 `IPhonePage`（客户端类型）。注册走 `Dist.CLIENT` 的入口或 `PhoneAppRegisterEvent`。
2. ~~**不动内置页面**~~ → **已改为「分批全量迁移」**。原有 12 个页面不再"按需顺手迁"，而是按
   [phone-page-skeleton.md](phone-page-skeleton.md) §11.1 的 6 个批次推平；每批独立编译、独立验收。
   **但 `ChatPage` 与 `MainPage` 不继承 `PhonePage`**（原因见该文档 §11.2），它们只换 helper。
3. **色板是默认值，不是强制**。大多数页面不覆盖 `colors()` 就自动跟着主题走；**自带视觉风格的 App（例如深色底的内容页）应当覆盖 `colors()` / `pageBackground()`，或干脆用自己的品牌色**——不要把色板当成必须遵守的规范。
4. **我们是第一作者，可以随手改公共方法**（作者原话）。发现接口别扭就直接加/减/改，不用为兼容性背包袱；改完更新本文档即可。

---

## 3. 公共 API 速查（详细签名见两份参考文档）

| 你想做的事 | 用这个 |
|---|---|
| 往桌面加一个 App | `PhoneApp.builder(id, 名称).color(..).icon(..)/.center(..).page(MyPage::new).build()` + `PhoneAppRegistry.register(...)` 或订阅 `PhoneAppRegisterEvent` |
| 写一个页面 | `extends PhonePage`，实现 `title()` + `renderContent(...)` + `onContentClick(...)` |
| 页面要滚动 | 重写 `contentHeight()`，渲染用 `scrollY(logical)`、命中用 `logicalY(local)` |
| 行式列表 | `PhoneList<T>`：`items()` / `itemHeight()` / `renderItem(...)` / `onItemClick(...)`；混排行高覆盖 `itemHeight(int)` + 改完调 `invalidateLayout()` |
| 多列网格 | `PhoneGrid<T>`：`items()` / `columns()` / `cellWidth()` / `cellHeight()` / `renderCell(...)` / `onCellClick(...)` |
| 矩形命中 / 细阴影 | `PhoneLayout.hit(px, py, x, y, w, h)` / `PhoneLayout.topShadow(...)`（**不继承 `PhonePage` 的页面**也能用） |
| 给底部悬浮 Dock 留白 | `PhonePage.bottomInset()`（只影响 `maxScroll()`，默认 0 = 不留白） |
| 画头像 / 照片 | `PhoneImages.drawCover(...)`；源纹理取自 `MaidAvatarManager` 或 `PhotoLibrary` |
| 画圆角矩形 / 胶囊 | `PhoneShapes.fillRoundedRect(...)`（**不要**再用 `com.huhutalk.phone.UiShapes`，它已 `@Deprecated`） |
| 画文字 | `PhoneText.draw / drawCenteredIn / drawLeftIn`（都跟随全局字体倍率） |
| 开关 / 增减 / 滑块 / 下拉 | `PhoneSwitch` / `PhoneStepper` / `PhoneSlider` / `PhoneDropdown`，用 `PhoneRow` 摆成行 |
| 想让一页里所有行等高 | `PhoneRow.rowHeight(int basePx)`。默认按内容自动算，一页里会出现 4 种行高；固定后：`INLINE_RIGHT` 控件行内居中、`STACKED`（滑块/下拉）标签贴顶控件贴底。⚠ 滑块行最低要 27px，否则轨道会切过标签 |
| 文字是 emoji / `Component` 而不是 `String` | `PhoneText.draw / drawShaded / drawCenteredIn / width` 的 `FormattedCharSequence` 重载（`getVisualOrderText()` 拿到的就是它） |
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
| **控件放进 `PhoneList` / `PhoneGrid` 的行里** | **需要自己接线**：行的 y 随滚动变化，行内控件必须每帧按行位置 `bounds(...)`。目前 `PhoneRow` 是为页面级行设计的。 |
| **吸顶分组头** | 没有。`PhoneList` 的可变行高能画分组标题行，但它会跟着滚走。 |
| **网格横向滚动** | `PhoneGrid` 只纵向滚（与 `PhoneList` 一致）。 |
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
  **（上一轮当时）现有页面一行未改**；判定在松开时做，不需要每 tick 计时器。
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

> 下面这张表 + §9.1 是**上一轮**（搭工具层）的记录；**§9.2 起是本轮**（批次 0~6）的记录。

| 文件 | 改动 | 回归注意 |
|---|---|---|
| `MainPage.java` | App 列表 = 内置 + 注册表（按版本号懒合并，外部 App **一律追加在后面**） | 内置图标排布不变 |
| `MainPage.java` | 新增"贴图图标"渲染分支 | 只对 `iconTexture != null` 的 App 生效；内置 App 全部为 null |
| `MainPage.java` | 桌面底色改为 `ThemeManager.getActive().pageBg()` | 底色从 `#F0E4EA` 变为 `#F2E8EF`；切主题时会一起变 |
| `ColorScheme.java` | +7 字段；`pageBg` 值改为 `#F2E8EF` | **`PhoneCasePickerPage` 底色跟着变**（唯一读 `pageBg` 的既有页面） |
| 其余 11 个页面 | **（上一轮当时）未改动** | 本轮已全部迁移，见 §1.6–1.18 与 §9.2 |

### 9.2 批次 0：公开件本身的变化（回归检查用）

| 文件 | 改动 | 回归注意 |
|---|---|---|
| `PhoneText.java` | **+4 个 `FormattedCharSequence` 重载**（`draw` / `drawShaded` / `drawCenteredIn` / `width`） | 纯新增。既有 `String` 重载一字未改 |
| `PhoneLayout.java` | **新增**（`hit` / `topShadow` / `horizontalShadow`） | 纯新增，还没有调用点 |
| `PhoneGrid.java` | **新增**（多列虚拟化网格） | 纯新增，还没有调用点 |
| `PhoneList.java` | ① `itemHeight()` 由 `abstract` 放宽为**带默认实现**（返回 0）② **+`itemHeight(int)`** ③ **+`invalidateLayout()`** ④ **+`separatorColor(int)`** ⑤ 内部改走**行高前缀和 + 二分** ⑥ **修 `render` 的 `viewY` 重复计数 bug** | 既有子类（`PhoneAppSelfTest` 里的自检页）只覆盖 `itemHeight()`，行为不变。**⑥ 是行为修正**：`viewY != 0` 时原先整列行会下移 `viewY`；既有用法都是 `setViewport(0, 0, ..)`，所以实机看不出差别 |
| `PhonePage.java` | ① **+`bottomInset()`**（默认 0，扣在 `contentViewHeight()` 上）② `contentViewHeight()` 现在减去 `bottomInset()` ③ `hit()` 改为转发 `PhoneLayout.hit` | ①默认 0 → 已在用的 4 个骨架页（设置 App / 识图模型 / 图库 / 图库阅览）行为**逐字节不变**；③参数顺序未变 |

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
| **[camera-black-screen-handoff.md](camera-black-screen-handoff.md)** | **✅ 已修复**的相机黑屏 bug 复盘：根因（alpha 钉底漏排除取景态）、排除重构嫌疑的完整证据链、二分实验步骤。**保留作记录**，也示范了一次"先排除再动手"的排查过程 |
| [phone-camera-api.md](phone-camera-api.md) | 相机契约（`PhoneCameraHost` 四个判据 / 取景透明 / 侧键路由） |
| [douyin-webview-verification-report.md](douyin-webview-verification-report.md) | 抖音网页版验证结论（含三个必踩的坑）——做 MCEF 类 App 前必读 |
| [douyin-webview-feasibility-handoff.md](douyin-webview-feasibility-handoff.md) | 内嵌浏览器完整调研与 MCEF API 清单 |
