# HuhuTalk 识图多平台选择 · 设计交接

> **面向**：后续维护/扩展识图平台的人。
> **状态**：**已实现、已编译通过、未实机验收**（验收清单见 §7）。
> **读法**：先读 §1「为什么是这个架构」——它决定了后面所有代码的形状。

---

## 0. 一句话

设置 App 新增「识图模型」页：按平台手风琴展开，选模型、填 API Key、点「使用此平台」，
识图即走该平台；**API Key 完全复用主模组(TLM)的服务端站点配置**，本模组不另存一份。

---

## 1. 为什么是这个架构（先读这段）

最初的设计是「把 API Key 放在客户端 `settings.json`，再同步给服务端」。
查过主模组之后这个方案被**推翻**了 —— 因为 TLM 是这么做的：

| 环节 | 主模组的做法 | 证据 |
|---|---|---|
| Key 存哪 | `config/touhou_little_maid/sites/llm.json`，明文 `secret_key` 字段，**服务端**文件 | `AvailableSites.createFolder()` → `FMLPaths.CONFIGDIR` |
| 内存态 | `AvailableSites.LLM_SITES`，`Map<String, LLMSite>`，**全服单份，不按玩家** | `AvailableSites` |
| 客户端能改吗 | 能，但只是「远程编辑器」：`SaveLLMSitePacket`(C2S) → 服务端改内存 + `saveSites()` 落盘 | `SaveLLMSitePacket` |
| 权限 | `GameModeUtil.canEditSite(player)`：单人 ✅ / 局域网主机 ✅ / 专用服 **op≥2** / 其余 ❌ 静默丢弃 | `GameModeUtil` |
| 密钥怎么显示 | TLM 自己的编辑器用 `"·".repeat(len)` 掩码 | `LLMSiteEditorScreen` |

**结论：Key 只在服务端，客户端不经手。** 所以本模组：

- **不新建配置体系**，直接把识图平台映射到 TLM 的 LLM 站点；
- **写入走我们自己的包**（原因见 §3 的坑），但权限判定和落盘用的是主模组**同一个**方法。

### 1.1 白捡的好处：平台 TLM 全都已经有了

`DefaultLLMSite.addDefaultSites()` 内置了 `deepseek / aliyun / siliconflow / doubao / zhipu /
minimax / kimi / gemini / grok / openrouter / player2`，`LLMOpenAISite.Serializer.defaultSite()`
又带出 `openai`。**本模组一个都不覆盖，缺的才补**（见 §2）。

---

## 2. 平台清单

预设表在 `common/vision/VisionProviders.java`。`injectUrl != null` 的才会由本模组补建。

| 预设 | TLM 站点 id | 接口地址 | 备注 |
|---|---|---|---|
| DeepSeek（跟随主模组） | `deepseek` | TLM 自带 | **默认值**，也是所有兜底路径的终点 |
| 阿里云百炼 | `aliyun` | TLM 自带 | |
| 火山引擎方舟 | `doubao` | TLM 自带 | model 也可填推理接入点 ID |
| 智谱 Z.ai | `zhipu` | TLM 自带 | 免费 `glm-4v-flash` 不吃 base64，未列入 |
| 月之暗面 Kimi | `kimi` | TLM 自带 | 其视觉模型只支持 base64，正好匹配 |
| MiniMax | `minimax` | TLM 自带 | |
| 硅基流动 | `siliconflow` | TLM 自带 | 有免费视觉模型 |
| OpenAI | `openai` | TLM 自带 | |
| Google Gemini | `gemini` | TLM 自带 | 走官方 OpenAI 兼容端点 |
| xAI Grok | `grok` | TLM 自带 | |
| OpenRouter | `openrouter` | TLM 自带 | 站点自带 `HTTP-Referer` 头，已一并转发 |
| 百度千帆 | `huhutalk_qianfan` | `qianfan.baidubce.com/v2/chat/completions` | **本模组注入** |
| 腾讯混元 | `huhutalk_hunyuan` | `api.hunyuan.cloud.tencent.com/v1/chat/completions` | **本模组注入**；官方正迁 TokenHub |
| 阶跃星辰 | `huhutalk_stepfun` | `api.stepfun.com/v1/chat/completions` | **本模组注入** |
| 自定义（OpenAI 兼容） | `huhutalk_custom` | 玩家自填 | **本模组注入**，地址留空待填 |

### 刻意不做的

| 平台 | 为什么 |
|---|---|
| **讯飞星火** | 图片理解只有 WebSocket 专有协议 + HMAC 签名（`wss://spark-api.cn-huabei-1.xf-yun.com/v2.1/image`）；那条 OpenAI 兼容的 `spark-api-open` 口**不吃图片**。接入成本远超收益。 |
| **Anthropic Claude** | 协议不同（`x-api-key` + `anthropic-version` + `content[].source.base64`），需要独立的适配器分支。 |
| 商汤 / 百度 v1 | 无必要，千帆 v2 已是 OpenAI 兼容。 |

---

## 3. ⚠ 必须避开的坑：`SyncAISitesPacket` 会抢屏幕

TLM 自己的 `SaveLLMSitePacket` **不能直接复用**。它的配套回包
`SyncAISitesPacket.onHandle` 在客户端会：

```java
else { mc.setScreen(AIChatSettingsHubScreen.openDefault(null, llmSites, ttsSites, ...)); }
```

只要当前 screen 不是 TLM 那几个界面之一，**它就把玩家界面换成 TLM 的 AI 设置页**。
在手机里保存一次密钥，手机界面就没了。

所以本模组走自己的 `C2SVisionSiteEditPacket`，但：
- 权限判定用主模组同一个 `GameModeUtil.canEditSite`；
- 落盘用主模组同一个 `AvailableSites.saveSites()`；
- 只把「回包」换成自己的 **不抢屏** 快照包。

---

## 4. 文件地图

### 4.1 新增

| 文件 | 作用 |
|---|---|
| `common/vision/VisionProvider.java` | 一个平台的预设（纯数据，客户端/服务端共用） |
| `common/vision/VisionProviders.java` | 预设表 + 顺序 + 默认站点 |
| `core/vision/VisionSites.java` | **与 TLM 站点体系的唯一接触面**：注入预设 / 读快照 / 写密钥 / 解析识图参数 |
| `core/vision/VisionSettings.java` | 「走哪个平台、用哪个模型」的服务端会话缓存（按玩家 UUID，仿 `RescueSettings`） |
| `network/C2SRequestVisionSitesPacket.java` | 拉平台列表 |
| `network/C2SVisionSiteEditPacket.java` | 写密钥 / 接口地址（权限把关） |
| `network/C2SVisionSelectionPacket.java` | 同步选中的平台与模型（非机密，登录与改动时各推一次） |
| `network/S2CVisionSitesPacket.java` | 平台列表**非敏感**快照（**不含密钥**） |
| `client/vision/VisionSiteCache.java` | 客户端快照缓存 |
| `api/phone/ui/PhoneTextInput.java` | **公开**内联文本输入控件（可掩码） |
| `phone/PhoneTextCaptureScreen.java` | 透明键盘捕获屏（见 §5） |
| `phone/pages/SettingsAppPage.java` | 桌面「设置」App 主页（目前只有「识图模型」一栏） |
| `phone/pages/VisionModelSettingsPage.java` | 「识图模型」页（手风琴 + 下拉 + 掩码输入 + 使用此平台） |

### 4.2 改动（全部是加法）

| 文件 | 改动 | 影响 |
|---|---|---|
| `HuhuTalk.java` | +3 行包注册；+3 个事件监听（补建预设 / 登出清缓存 / 停服清缓存） | — |
| `ClientPayloadSetup.java` | +1 个 S2C 注册 | — |
| `ClientModEvents.java` | +1 个登出监听；+`pushVisionSelection()` | — |
| `HuhuTalkClientSettings.java` | +`visionSiteId` / `visionModels` / `visionCustomModelSites` 与读写（老配置缺失 → 默认 deepseek） | 老玩家行为不变 |
| `VisionService.java` | **新增重载** `describe(img, prompt, siteId, model)`；原签名保留并委托到它 | **旧行为逐字节等价** |
| `MaidChatHandler.java` | 识图发起处改取 `VisionSettings.get(player.getUUID())` | — |
| `MainPage.java` | 槽位 5「设置」：`pageFactory` 从 `null` → `SettingsAppPage::new` | 从「敬请期待」变成真页面 |

### 4.3 明确**没有**动

- 底部导航那个老设置页 `SettingsPage` —— 一行没改
- `C2SMaidImageChatPacket`（图片聊天包）—— 一个字段没加
- `VisionService.describe(byte[], String)` 旧签名 —— 保留
- TLM 站点的 `models` 字段 —— 一个都不写（识图的模型预设是本模组自己的）
- TLM 自带站点的 url —— 不覆盖；**只补建缺失的**

---

## 5. 为什么文本输入需要一个「透明屏」

手机页面走 `IPhonePage`，那套接口**没有任何键盘回调**：手机 UI 画在 GUI 层上，
键盘事件在原版里只会送给当前 `Screen`。既有页面全都是开一个 Screen 抢键盘
（聊天页 `PhoneInputScreen`、联系人页 `PhoneGroupNameInputScreen`）。

所以 `PhoneTextInput` 是「**内联的样子 + Screen 级键盘捕获**」：
- 样式内联，就在行里原地渲染，焦点态描主题强调色的边 + 末尾光标；
- 点它时开一个**一个像素都不画**的 `PhoneTextCaptureScreen`，把按键转发给控件；
  手机界面继续在底下渲染，玩家看到的就是"这一格在闪光标、我在往里打字"。
- `Enter` 提交，`Esc` 取消（回滚），左键点任意处提交。

---

## 5.1 ⚠ 踩坑：滚动页面里的控件必须用**视图坐标**

**症状（实机复现过）**：展开任一平台后，模型名栏 / API Key 栏**不跟随滚动**，
钉在屏幕上不动，而它所在的卡片正常滚动。

**原因**：`PhoneWidget` 的 `bounds(x, y, w, h)` 里那个 y，被
`PhoneWidget.render(g, originX, originY)` 直接当作 `originY + y` 来画，
而 `originY` 是**内容区顶端、不含滚动偏移**的。
页面自己画的东西走 `originY + scrollY(logical)`，所以两者一旦用不同坐标系就会错位。

一开始两边都用了**逻辑坐标**（`c.modelRowY`），于是"一致地错"：
渲染钉住、命中却还能对上——所以只看点击会以为没问题。

**规矩**（`VisionModelSettingsPage` 里已按此写死）：

| 用途 | 坐标 |
|---|---|
| 控件 `bounds(...)` | **视图坐标** `scrollY(logical)` |
| 控件命中 `mousePressed/mouseDragged` | **未滚动的本地坐标** `localY` |
| 页面自绘 / 卡片命中 / 按钮命中 | 逻辑坐标（配 `scrollY` / `logicalY`） |

两者自洽的依据：`scrollY(logicalY(localY)) == localY`。

---

## 5.2 「自定义模型」是一个**显式状态**，不是"记的模型在不在预设里"

`HuhuTalkClientSettings.visionCustomModelSites`（`Set<String>`，持久化）记录哪些平台
处于自定义模式。为什么不能只靠"记住的模型是否命中预设列表"来判断：

- 玩家点「自定义…」时可能**还没敲**模型名 → 记住的模型是空的 →
  下一次布局就会退回预设默认，**刚点开的输入框一帧后就自己收回去**；
- 反过来，输入框是每次打开页面新建的，若不从记住的模型**预填**，
  会出现"摘要显示有模型、输入框空着"的错位，此时真正发出去的模型名和摘要不一致。

所以：**自定义模式 = 显式标记**；进入自定义时把"记住的模型"与输入框**一起清空**，
使「输入框内容」恒等于「自定义模式下真正会用的模型名」。

---

## 5.3 ⚠ 踩坑：展开的下拉会把"整页滚动"一起吃掉

**症状（实机复现过）**：展开任意平台的「可用模型」栏之后，**拖拽就再也滚不动页面了**。

**原因**：手机滚页面靠的是**拖拽**，而拖拽和滚轮走的是同一条 `IPhonePage#mouseScrolled` 通路。
`PhoneDropdown.mouseScrolled` 只要处于展开态就 `return true`（它的设计意图是
"滚轮换选中项"），于是只要有一张卡的下拉开着，页面就再也收不到滚动增量。

**注意这不是本页独有的写法问题** —— 工具链的参考实现
`client/debug/PhoneAppSelfTest`「自检·控件」页也是这么写的
（原文注释："下拉展开时先让它吃掉滚轮（否则会一边选项一边滚页面）"）。
差别只在于那个自检页内容短、`maxScroll() == 0`，本来就没得滚，所以坑没暴露。

**本页的处理**：**不重写 `mouseScrolled`**，交给 `PhonePage` 的默认实现滚页面。

**代价（必须知道）**：`PhoneDropdown` 只渲染前 `VISIBLE_ROWS`(=5) 行，且没有滚动窗口，
所以下拉里**第 6 项及以后永远点不到**。为此：

| 约束 | 落实方式 |
|---|---|
| 各平台预设模型数 ≤ `VisionProviders.MAX_MODELS_PER_PROVIDER`(=4) | 给「自定义…」留出第 5 位；`VisionModelSettingsPage` 的静态块在超限时打 WARN |
| 阿里云原本有 5 个模型，已去掉被 `qwen3-vl-flash` 取代的 `qwen-vl-plus` | 见 `VisionProviders.ALIYUN` |

**给工具链的建议（未做）**：`PhoneDropdown` 需要二选一 ——
要么给展开列表加真正的滚动窗口（`selected` 跟随可见范围），
要么让它别在手机语境下吞掉滚轮（改由"光标是否落在列表矩形内"决定，
但手机拖拽的起点并不能表达意图）。在那之前，**任何需要滚动的页面都不要把滚轮转发给下拉**。

---

1. **密钥不下发**。`S2CVisionSitesPacket` 只带 `hasKey` 布尔量，不带密钥内容。
   玩家要改就重新输一遍 —— 这比"掩码显示"更彻底：密钥压根没到过客户端。
2. **清空密钥不做**。API Key 输入框留空 = 不改动（会提示"密钥留空，未做改动"）。
   刻意如此：否则一次误按回车就会把已配好的密钥清掉。
   真要清除，用主模组自己的 AI 设置界面（那里能编辑站点）。

---

## 7. 验收清单（实机待办）

单人存档下逐条确认：

- [ ] 桌面槽位 5 的「设置」图标点开进入**设置主页**（不再是「敬请期待」）
- [ ] 主页「识图模型」一行右侧显示当前平台 · 模型；点它进入识图模型页
- [ ] 识图模型页顶部显示「当前：DeepSeek（跟随主模组） · deepseek-v4-flash-vision-exp」
- [ ] 点平台行 → 展开；再点 → 收起
- [ ] **展开后滚动页面：模型下拉 / 模型名输入框 / API Key 输入框都跟着卡片一起滚**
      （不是钉在屏幕上不动 —— 这个 bug 实测出现过，见 §5.1）
- [ ] **展开任一平台的下拉后，拖拽依然能滚动页面**（不是一展开就卡死 —— 见 §5.3）
- [ ] 「可用模型」下拉能展开、能选中，且**列表里的每一项都点得到**（含「自定义…」）
- [ ] 选「自定义…」后出现模型名输入框，且该输入框**不会自己弹回预设**；
      重开页面后输入框里仍是上次填的自定义模型名
- [ ] 点 API Key 输入框 → 打开透明捕获屏（手机界面仍在渲染）；输入显示为 `••••`；
      `Enter` 提交、`Esc` 回滚、点别处提交
- [ ] 填一个真 Key 后：该平台行右侧「未配置密钥」→「已配置密钥」
      （回包到达后刷新；若没变，看客户端日志有没有 `vision_sites` 报错）
- [ ] 点「使用此平台」→ 该卡整张染主题强调色、出现「使用中」徽标、
      页顶摘要跟着变、按钮变成「✓ 识图正在使用此平台」
- [ ] 之后发一张图给女仆：日志出现 `[HuhuTalk] 发起识图: site=<平台>, model=<模型>`
- [ ] 切到「自定义（OpenAI 兼容）」：填地址 + 模型 + Key，能正常识图
- [ ] 字体倍率调到 0.5 与 2.0，页面布局不溢出、点得准
- [ ] 底部导航「设置」tab（老页面）行为完全不变
- [ ] 老存档（没有 `visionSiteId` 字段）升级后识图仍走 DeepSeek，不需要任何配置

**未实机验证的部分**：13 家平台的**真实调用**只做过接口层面的核对，
除 DeepSeek（改造前就在用）外，其余平台的模型名与端点需要拿真 Key 各跑一张图确认。
千帆的 `appid` 头非必填、混元可能已迁 TokenHub —— 这两家最可能需要现场调整。

---

## 8. 后续扩展点

| 想做什么 | 改哪里 |
|---|---|
| 加一个平台 | `VisionProviders` 里加一条 `VisionProvider`（TLM 已有该站点 → `injectUrl = null`；没有 → 填地址让它自动补建） |
| 加/改某平台的视觉模型预设 | 同上，改那条的 `models` |
| 设置 App 里再加一栏 | `SettingsAppPage.ENTRIES` 加一个 `Entry` |
| 允许玩家改某平台的接口地址 | 该条的 `editableUrl` 设为 `true` |
| 支持 Anthropic 协议 | 需要第二个适配器分支（`VisionService` 里按站点类型分派） |
| 识图提示词可配置 | 现在是 `VisionService.DEFAULT_PROMPT` 常量，加个客户端设置 + 随 `C2SVisionSelectionPacket` 一起传即可 |
