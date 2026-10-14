# 「形象描述」让女仆认出自己 · 调研与实施交接

> **面向**：下一条对话来实现这个功能。
> **状态**：**已实施完成，编译通过，待实机验收**。调研结论见 §1~§13；**实际改了什么、怎么改的见 §14**。
> **前置阅读**：[phone-camera-api.md](phone-camera-api.md)（相机）、
> [phone-page-skeleton.md](phone-page-skeleton.md) §8.1.1（`PhoneTextInput` / 透明捕获屏）、
> [phone-ui-toolkit-handoff.md](phone-ui-toolkit-handoff.md)（页面与控件现状）。

---

## 0. 需求（作者原话）

> 我们的模组开放了识图模型链路，加上之前的相机功能，可以给女仆发照片，但是目前女仆认不出自己，
> 所以我打算做个新填写栏位，一个放置于管理视觉模型页的顶部。另一个放在联系人设置页。
>
> **①「你的样貌」**：位于视觉模型页顶部，点击可以填写一段关于自己的外貌描述，
> 在识图的时候发送给视觉模型，让他可以判断画面中的人物（如果有）是玩家，并且还能看出玩家状态。
>
> **②「女仆的样貌」**：位于联系人设置页，放在发消息到删除联系那一大栏目的下部，
> 名为"女仆的样貌"，在这个栏目填写女仆的外貌，在调取视觉模型时，
> 我们根据玩家发送该图片的窗口传送对应女仆的外貌提示词，
> 让使用体验上达到"女仆可以认出自己"的功能。

---

## 1. 一句话结论

**可行，而且改动面可控 —— 整条识图链路只有「一个」提示词组装点。**
不解除默认提示词里那句"不要猜测人物身份"，喂多少外貌描述都会被模型忽略（见 §2）。

---

## 2. 直接病因（务必先看这条）

`core/vision/VisionService.java:53-56`：

```java
/** 识图系统提示词：要求她描述画面但不得臆测人物身份 */
public static final String DEFAULT_PROMPT =
        "这是主人刚发给你的一张我的世界图片。请仔细观察画面，尽量具体地描述其中发生了什么、有哪些东西。"
                + "但是不要猜测其中人物的身份，仅描述外貌、特征";
```

**它在明确禁止识别人物身份。** 所以实现的第一件事不是加字段，而是**在有人设信息时换一套提示词**。

---

## 3. 现状链路（已逐行核实）

```
ChatPage.sendImageChat()                       ChatPage.java:2322-2370
   │  ← 这里知道 stableId（哪个女仆的窗口），这就是需求里"按窗口取女仆样貌"的落点
   └─ C2SMaidImageChatPacket(stableId, userText, 图片字节, bubbleMute, chatBannerMute)
        │                                          C2SMaidImageChatPacket.java:19-20
        │                                          MAX_IMAGE_BYTES = 1_000_000（帧长约 2MB）
        └─ MaidChatHandler.handleImageChat(ServerPlayer, stableId, userText, imageData, …)
             │                                     MaidChatHandler.java:162-248
             ├─ VisionSettings.get(player.getUUID())        ← 站点/模型：服务端按 UUID 会话缓存
             ├─ VisionService.describe(imageData, VisionService.DEFAULT_PROMPT, siteId, model)
             │                                     ★ 全工程唯一的 describe 调用点（:213）
             └─ buildImageMessages(manager, player, desc, userText)
                                                   MaidChatHandler.java:251-279
                   └─ desc 作为「本次调用的临时 SYSTEM 消息」喂给聊天 LLM（:265-272），不写历史
```

**关键事实**：

| 事实 | 依据 |
|---|---|
| 识图调用在**服务端** | `MaidChatHandler:212-214` |
| `describe` 全工程**只有一个**生产调用点 | `grep VisionService.describe` → 仅 `MaidChatHandler:213` |
| 提示词是 `describe` 的第一个入参，可任意拼 | `VisionService.describe(imagePng, prompt, siteId, modelId)` |
| 识图**结果**（描述文本）才是女仆"看到的画面" | `buildImageMessages` 把 desc 塞进 SYSTEM 消息 |
| `ChatPage` 在发送时知道 `stableId` | `sendImageChat()` 里直接用了 `stableId` |

---

## 4. 推荐数据流（含一条**硬约束**）

> ### ⚠ 硬约束：`StreamCodec.composite` 在 1.21.1 **最多 6 个分量**
>
> `net/minecraft/network/codec/StreamCodec.java` 只有 `composite` 的 1~6 分量重载（已读源码确认）。
> `C2SMaidImageChatPacket` 现在正好用 **5** 个（stableId / userText / imageData / 两个布尔）
> ⇒ **图片包最多只能再加 1 个字符串字段。**
> 这个约束直接决定了下面的拆分方案（不是设计洁癖，是编解码器的上限）。

| 数据 | 归属 | 存哪 | 怎么到服务端 |
|---|---|---|---|
| **① 你的样貌** | 全局、每玩家 | `HuhuTalkClientSettings` 新增 `playerAppearance`（本地 JSON） | **扩展现有 `C2SVisionSelectionPacket`（2 字段 → 3，仍在 6 以内）**，服务端存进 `VisionSettings` 的 per-UUID `Selection`（`VisionSettings.java:40-47, 75-85`）。它和站点/模型是同一种东西——"这个玩家的识图偏好" |
| **② 女仆的样貌** | 每女仆 | `HuhuTalkClientSettings` 新增 `maidAppearance: Map<stableId, String>`（与现有 `maidGroups` 同型同处，`:87` / `setMaidGroup` `:468`） | **发送时按窗口取**：`ChatPage.sendImageChat` 里 `stableId` 已知，把它塞进 `C2SMaidImageChatPacket`（5 → **6，正好卡满**） |

**为什么不把玩家样貌也塞图片包**：它是 per-player 的全局设置，每张图都带一遍是浪费，
而且会把 6 个分量用光（以后再也加不了字段）。

**服务端组装**（`MaidChatHandler.handleImageChat` 内，调用 `describe` 之前）：

```java
String prompt = VisionService.DEFAULT_PROMPT;                     // 无人设时【逐字不变】
String playerLook = visionSel.playerAppearance();                  // 来自 VisionSettings
String maidLook   = maidAppearanceFromPacket;
if (!playerLook.isBlank() || !maidLook.isBlank()) {
    prompt = 识别人物版提示词 + 玩家段 + 女仆段;                    // 见 §5
}
```

`buildImageMessages` 目前只收 `desc`，**建议把两段人设也一并传进去**（可选但效果更好）：
让女仆在聊天里也知道"画面里那个人是主人 / 那个女仆是你自己"，
否则她只能从识图描述里间接读到身份。

---

## 5. 提示词怎么拼（这是功能成败的关键）

必须**两套**，且"无人设"时逐字保持现在的 `DEFAULT_PROMPT`（老玩家行为零变化——这条是本项目一贯纪律）：

```text
【无人设】= 现有 DEFAULT_PROMPT（一字不改）

【有人设】= 基础描述要求
           + "画面中可能出现的人物身份参考如下，请据此判断画面中的人物是谁："
           + "- 主人（玩家）：<你的样貌>"
           + "- 你自己（女仆）：<女仆的样貌>"
           + "如果画面中的人物与上述描述相符，请在描述里明确写出他是谁（例如"画面里是你自己"）；"
           + "如果都不相符或画面里没有人，就只描述外貌，不要臆测身份。"
```

三处细节，缺一不可：

1. **必须显式解除**"不要猜测其中人物的身份"——不能只追加信息；
2. **要求模型在描述里直接点名身份**（"画面里是你自己"），因为女仆只有这段描述可看；
3. 空值必须跳过对应段落（只有一方填了就别提另一方），否则会诱导模型硬套。

---

## 6. UI 落位（坐标已核实）

| 栏位 | 落点 | 说明 |
|---|---|---|
| **① 你的样貌** | `VisionModelSettingsPage` 顶部 | `layout()` 起点 `:241`（`y = scaled(10)`），紧接着是摘要卡 `:245-247`。插一张自己的卡即可——该页所有 y 都是相对累加的，`laidOutHeight` 自动跟着长。**建议插在摘要卡之后、平台列表之前**（摘要卡是这页的"标题"，压到它上面会削弱页面识别） |
| **② 女仆的样貌** | `MaidInfoPage` 管理大矩形下部 | 管理组 4 行是 `L.cardY[0..3]`，大矩形 `manageRectY/H` 是算出来的（`cardY[0]-rectPad` ~ `cardY[3]+cardH()+rectPad`）⇒ **新增第 5 行插在 `cardY[3]` 之后，矩形自动包住它，不用改那两行公式**。⚠ 作者原话是"发消息到删除联系那一大栏目的下部"，但那一栏实际含 4 项（发消息/删除记录/删除联系人/**更新头像**），需确认插在 `cardY[3]` 之后还是 `cardY[2]`/`cardY[3]` 之间 |

---

## 7. ⚠ 主要成本项：现有 `PhoneTextInput` **编不了"一段描述"**

- `PhoneTextInput` 是**单行**控件，渲染走 `PhoneText.drawLeftIn`；而 `PhoneText` 整个类
  **只有 `truncate`，没有 wrap**（`PhoneText.java:160`；13 个公开方法已逐个看过）。
- 手机内容区宽约 110~137 GUI 像素，一个 CJK 字约 9px ⇒ **单行只能看到约 11 个字**。
  编辑 60~200 字的描述 = 盲打 + 只能看见尾巴。

两条路，**需要作者拍板**：

| 方案 | 内容 | 代价 |
|---|---|---|
| **A（省）** | 沿用 `PhoneTextInput`，`maxLength` 收到 **60 字**左右，并给它加"光标跟随横向滚动"（否则看不到正在打的字） | 约 30 行；60 字对"外貌描述"偏紧 |
| **B（推荐）** | 新增公开控件 **`PhoneTextArea`**：多行、`Font.split` 自动换行、约 4~6 行可见、`maxLength(200)`，键盘仍走**同一个透明捕获屏** `PhoneTextCaptureScreen` | 约 150~200 行新控件 + 两处接线；**两个栏位共用**，也是公开工具链的第 6 个控件 |

**实现 `PhoneTextArea` 的现成抓手**（都是已验证的既有件）：
- 键盘捕获：照抄 `PhoneTextInput` + `PhoneTextCaptureScreen`（含 `beginEdit`/`updateEditingText`/`endEdit`
  与 `startEditing()` 那套"页面自己管热区"的入口，见 `phone-page-skeleton.md` §8.1.1）；
- 换行：`Font.split(FormattedText, width)`（CJK 按字宽断行，不用自己写算法）；
- 光标/焦点态：照抄 `PhoneTextInput` 的 accent 描边；
- ⚠ 坐标规矩与 `PhoneTextInput` 完全一样：`bounds` 用**内容坐标**（`scrollY(logical)`），
  `render(g, originX, originY)` 会自己加原点；**页面自绘的 `fill` / `PhoneText.draw` 用屏幕坐标**。
  （写错的表现见 `phone-ui-toolkit-handoff.md` §0.1-⑦b：一个灰条整体上移一行，把别的行盖住。）

---

## 8. 持久化（已核实，加字段天然兼容）

`HuhuTalkClientSettings` 是**扁平 JSON**（`run/huhutalk/settings.json`，原子写入）：

| 项 | 位置 |
|---|---|
| `maidGroups`（每女仆 → 分组名） | 字段 `:87`、`setMaidGroup` `:468`、`getMaidGroups` `:233` |
| 加载 | `load()` `:483`；`maidGroups` 恢复在 `:523-526`，`customGroupNames` 在 `:527-534` |
| 落盘 | `save()` `:568`，`SettingsData` 在 `:606` 附近 |
| 兼容性 | **无版本号**，Gson 忽略未知字段、缺失字段为 null ⇒ 加两个字段天然向后兼容（与 `screenBrightness`/`phoneCursorSensitivity` 同款写法：可空类型 + 缺失时取默认） |

新增：
```java
String playerAppearance;                 // ① 可空 = 未填
Map<String, String> maidAppearance;      // ② stableId -> 描述（缺失/null 视为未填）
```
配套：`getPlayerAppearance()/setPlayerAppearance(v)`、`getMaidAppearance(id)/setMaidAppearance(id, v)`
（空串 = 删除该条目，与 `setMaidGroup` 同一语义）。

---

## 9. 待作者拍板的 5 件事（**已全部拍板，见 §14**）

1. **控件形态**：方案 A（单行 60 字）还是 **B（新 `PhoneTextArea` 多行 200 字，推荐）**？
2. **① 的精确位置**：摘要卡**之上**还是**之下**（我推荐之下）？
3. **② 的精确位置**：管理组第 5 行（更新头像之后），还是插在「删除联系人」和「更新头像」之间？
4. **② 存哪**：客户端本地（与 `maidGroups` 一致、最省，换客户端就没了）
   **还是**服务端跟女仆走（多机一致，但要动 `EntityMaid` 的持久化数据 + 同步包，明显更贵）？
5. **"还能看出玩家状态"怎么理解**：
   - 若指"模型自己从画面里看出来"（受伤/手持物品/姿势）→ 提示词里加一句要求即可，**零额外成本**；
   - 若指"把主人的实时状态也告诉女仆"（血量/饥饿/药水效果/手持物）→
     **服务端此刻正好握着 `ServerPlayer`**（`handleImageChat` 的第一个参数），
     可以直接读出来拼进提示词，**不需要任何新字段、不需要新包**。建议顺手做，效果最好。

---

## 10. 风险清单

| 风险 | 说明 |
|---|---|
| **不能改老行为** | 两个栏位都没填时，必须逐字走现在的 `DEFAULT_PROMPT`，否则所有老玩家的识图描述措辞会变 |
| **包上限** | 图片包只能再加 **1** 个字段（composite 上限 6）；女仆样貌字符串**必须设长度上限**（建议 ≤200 字符 ≈ 600 字节）。以后还想加字段就得手写 `StreamCodec.of(...)`（`C2SVisionSiteEditPacket.java:44` 有现成例子） |
| **token 成本** | 两段描述每次识图都进提示词；200 字 ≈ 400 token，可接受但要设上限 |
| **提示词注入** | 文本由玩家本人填写、经服务端转发，不是外部输入，风险可接受；仍建议做长度与换行规整 |
| **失败路径** | `onVisionError` / 降采样失败退化纯文字（`ChatPage:2352-2360`）两条路不受影响——改动只在提示词与包字段 |
| **UI 的坐标坑** | 新增栏位的 `bounds`（内容坐标）与页面自绘（屏幕坐标）混用时极易差一行，见 §7 末尾那条引用 |

---

## 11. 验收清单（实机）

- [ ] 两个栏位都不填：识图行为与改动前**逐字一致**（描述措辞不变）
- [ ] 只填①：画面里有玩家时，识图描述能点名"这是主人/玩家"
- [ ] 只填②：女仆自拍/让别人拍她时，描述能点名"这是你自己"
- [ ] 两个都填：画面里同时有玩家与女仆时能分别指名
- [ ] 画面里没有人时不硬套（不出现"这个人是主人"这种幻觉）
- [ ] 从**不同女仆的聊天窗口**发同一张图，带的是**各自女仆**的样貌（②的核心验收点）
- [ ] 描述长度上限生效（不会因为超长把提示词撑爆）
- [ ] 关掉游戏再进：两个栏位的内容都还在
- [ ] 悬浮窗与模型屏两条路上，新栏位的渲染/命中都正确（尤其滚动后点位不偏）

---

## 12. 关键文件与行号索引

| 关注点 | 位置 |
|---|---|
| 默认识图提示词（**直接病因**） | `core/vision/VisionService.java:53-56` |
| 识图调用（**唯一**调用点） | `core/chat/MaidChatHandler.java:213` |
| 识图描述注入聊天上下文 | `core/chat/MaidChatHandler.java:251-279`（临时 SYSTEM 消息 `:265-272`） |
| 图片聊天包（5 个字段，上限 6） | `network/C2SMaidImageChatPacket.java:19-40`，`MAX_IMAGE_BYTES :22` |
| 客户端发送（知道 `stableId`） | `phone/pages/ChatPage.java:2322-2370` |
| 站点/模型选择包（2 字段，可扩到 3） | `network/C2SVisionSelectionPacket.java:30-41` |
| 服务端 per-UUID 选择缓存 | `core/vision/VisionSettings.java:40-47`、`Selection :75-85` |
| 客户端设置持久化 | `client/settings/HuhuTalkClientSettings.java`（`maidGroups :87`、`setMaidGroup :468`、`load :483`、`save :568`、`SettingsData :606`） |
| ① 落点页 | `phone/pages/VisionModelSettingsPage.java:232-247`（`layout()` 与摘要卡）；输入框范例见 `placeControls :345-365` / `dispatchText :671-677` |
| ② 落点页 | `phone/pages/MaidInfoPage.java`（管理组 `cardY[0..3]` 与 `manageRectY/H`） |
| 单行输入控件 + 透明捕获屏 | `api/phone/ui/PhoneTextInput.java`、`phone/PhoneTextCaptureScreen.java` |
| 文字工具（**没有 wrap**，只有 truncate） | `api/phone/ui/PhoneText.java:160` |
| 新控件要注意的坐标坑 | `MaidInfoPage.renderNewGroupRow` 的 javadoc + `phone-ui-toolkit-handoff.md` §0.1-⑦b |

---

## 13. 环境提醒

- `pwsh` 在本机**默认不可用**（沙箱 ACL：`SetNamedSecurityInfoW failed (Win32 5)`）。
  编译校验走 IDEA MCP 的 `build_project`（本文所有"已核实"都是这么验的）。
- IDEA MCP 偶尔 `server is disconnected`，会自行恢复；**兜底编译**：
  IDE 终端里 `cmd /c "set JAVA_HOME=C:\Users\10217\.jdks\ms-21.0.12&& gradlew.bat compileJava --offline --console=plain"`
  （JDK 路径可用 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 查到）。
- ⚠ **改了 Java 必须重启客户端**，`F3+T` 只重载资源、不重载 class。

---

## 14. 实施记录（本轮已完成）

### 14.1 拍板结果（对应 §9 的 5 问）

| # | 问题 | 决定 |
|---|---|---|
| 1 | 控件形态 | **B：新增 `PhoneTextArea` 多行控件**，200 字、4~5 行可见 |
| 2 | ① 的精确位置 | **摘要卡之下、平台列表之前** |
| 3 | ② 的精确位置 | **管理组第 5 行（「更新头像」之后）**，点它展开下方内联输入框 |
| 4 | ② 存哪 | **客户端本地**（与 `maidGroups` 同文件同写法） |
| 5 | "看出玩家状态" | 作者澄清：**只在提示词里要求"描述玩家正在做什么"**（看天、放方块…），**不做**实时状态注入 |

### 14.2 新增文件

| 文件 | 作用 |
|---|---|
| `common/vision/VisionAppearance.java` | 两段描述共用的上限（`MAX_CHARS = 200`）与 `clean()`（压空白 + 截断）。放 `common` 是因为**客户端与服务端都要过一遍**，上限定两处必然漂移 |
| `api/phone/ui/PhoneTextEditable.java` | 「能被透明捕获屏编辑」的控件契约（`value` / `maxLengthValue` / `beginEdit` / `updateEditingText(text, cursorPos)` / `endEdit` / `focused`） |
| `api/phone/ui/PhoneTextArea.java` | 多行内联输入控件：自写按字宽折行（`Font.split` 不给"每行首字符索引"，没法把光标映射到行）、编辑期**窗口跟着光标走**、非编辑期显示开头 |

### 14.3 改动文件

| 文件 | 改动 |
|---|---|
| `PhoneTextInput.java` | `implements PhoneTextEditable`；`updateEditingText(String)` → `(String, int)`（单行忽略 cursorPos） |
| `PhoneTextCaptureScreen.java` | 字段类型 `PhoneTextInput` → `PhoneTextEditable`，**单行/多行共用一个捕获屏**；转发时补传 `box.getCursorPosition()` |
| `HuhuTalkClientSettings.java` | 新增 `playerAppearance`（String）与 `maidAppearance`（Map）；配套读写方法（**空串 = 删除条目**，与 `setMaidGroup` 同语义）；`load`/`save`/`SettingsData` 各加两处，**可空类型 + 缺失取默认**，与 `screenBrightness` 同款、老配置天然兼容 |
| `C2SVisionSelectionPacket.java` | 2 → **3** 字段，加 `playerAppearance`（离上限 6 还很远） |
| `VisionSettings.java` | `Selection` 加 `playerAppearance`；`set(...)` 多一个入参，**服务端再 clean 一遍**（客户端可被改包） |
| `C2SMaidImageChatPacket.java` | 5 → **6** 字段（正好卡满 composite 上限），加 `maidAppearance` |
| `VisionService.java` | 新增 **`buildPrompt(playerLook, maidLook)`**：两段皆空时**逐字返回 `DEFAULT_PROMPT`**；有人设时换成显式解禁 + 要求点名身份 + 要求描述玩家动作的一套提示词 |
| `MaidChatHandler.java` | `handleImageChat` 多收一个 `maidAppearance`；识图前算 `playerLook`/`maidLook` 并交给 `buildPrompt`；新增 `identityNote(...)` 传给 `buildImageMessages`，让女仆在**聊天里**也知道谁是谁（两段皆空时返回空串 ⇒ 拼出的系统消息与改造前**逐字一致**） |
| `ChatPage.java` | `sendImageChat` 发图时按 **`stableId`（当前窗口）** 取女仆样貌塞进包 —— 这是"不同窗口带各自女仆样貌"的落点 |
| `ClientModEvents.java` | `pushVisionSelection()` 一并推 `playerAppearance` |
| `VisionModelSettingsPage.java` | 摘要卡之下新增「你的样貌」卡（标题 + 提示 + `PhoneTextArea`）；`dispatchText` 形参放宽到 `PhoneWidget`（本页现在同时有单行/多行两种控件）；`onOpen` 重新对齐输入框内容 |
| `MaidInfoPage.java` | 管理组 4 行 → **5 行**（`cardY` 扩到 5，"👗 女仆的样貌"，右端显示「已填写/未填写」+ ▲▼）；点行展开下方的 `PhoneTextArea`，`manageRectH` 由 `manageBottom` 算出、矩形自动包住 —— **`rectY/H` 的公式本身没改** |

### 14.4 刻意保持的"零变化"保证

- 两段描述都为空 ⇒ `VisionService.buildPrompt` 返回的**就是** `DEFAULT_PROMPT` 那个对象；
- `identityNote` 返回 `""` ⇒ 图片系统消息字符串与改造前逐字相同；
- 两个新包字段默认为空串 ⇒ 老客户端/新服务端、新客户端/老服务端的组合都不会崩。**⚠ 但这是协议改动：客户端与服务端必须一起升级**（`StreamCodec` 分量数不同，混用会在解码时抛错）。

### 14.5 验证情况

- IDEA `build_project`：**通过**（0 error / 0 problem）。
- IDEA `lint_files`：新代码引入的告警已清（`Math.clamp`、`isEmpty`、无用字段与无用方法）；余下均为本文件历史遗留告警。
- **尚未实机验证** —— §11 的验收清单全部待跑（其中"两个都不填时逐字一致"是最该先跑的一条）。

### 14.6 下一轮可做（未做，不在本次范围）

- 若嫌 200 字不够：只改 `VisionAppearance.MAX_CHARS` 一处即可（客户端控件与服务端裁剪都引它）。
- `PhoneTextArea` 目前没有"点击框内定位光标"（真光标由 `EditBox` 管，视觉光标只做提示），与 `PhoneTextInput` 现状一致。

