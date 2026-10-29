# 更新日志 / Changelog

## 1.1.1 — 2026-10-29

本节汇总 1.1.0 之后的手机归档、历史检索与引用改动。
This section covers the phone archive, history retrieval and quoting work since 1.1.0.

### 新增与改进 / Added and improved

- 服务端手机归档长期保存当前主人与当前女仆的手机文字、图片及双向物品传输事件；图片按内容哈希存入世界目录，传输记录包含发送、领取与丢地结果。
  Server-side phone archive retaining text, images and two-way item-transfer events for the current owner and maid; images are content-addressed in the world folder, and transfer records include sent, claimed and dropped outcomes.
- 女仆资料页新增只读手机存储页，可分页浏览归档中的文字、图片和结构化传输事件，并按需查看归档图片；本页不合并本机聊天记录，也不提供删除归档或领取物品的按钮。
  A read-only phone storage page in the maid profile pages through archived text, images and structured transfer events, loading archived images on demand. It does not merge local chat history or offer archive deletion or item claiming.
- 手机聊天支持检索当前主人与当前女仆的归档历史，并把找到的原话、图片或真实物品事件引用到女仆回复；引用显示原作者、原消息预览与原发送时间，且只挂在本次回复断句后的第一条气泡上。检索依赖模型支持工具调用，并非每次对话都会自动使用。
  Phone chat can search archived history for the current owner and maid and quote a found message, image or real item event in a maid reply. A quote shows the original author, preview and send time and appears only on the first bubble of the segmented reply. Search depends on model tool-calling support and is not used automatically in every conversation.
- 玩家随文字同发的物品会绑定到同一轮对话上下文，归档不再把物品事件与对应消息拆散；女仆发件的待领取事件也随当前回合归档。
  Items sent together with a player's text are bound to the same conversation turn, so the archive no longer separates the item event from its message; queued maid-to-player transfers are archived in the current turn as well.
- 识图设置更新豆包预设为 Seed 2.1 Pro / Lite / Turbo 与 Seed 2.0 Mini，并更新平台图标与名称显示；自定义模型仍走原输入通路。
  Vision settings update the Doubao presets to Seed 2.1 Pro / Lite / Turbo and Seed 2.0 Mini, with refreshed platform icons and names; custom models still use the existing input path.

### 修复与调整 / Fixed and adjusted

- 修复救援叙述开关：开关状态现在会同步到服务端，关闭后只停止 AI 救援叙述，药水救援本身仍照常执行。
  Fixed the rescue narrative toggle: its state now syncs to the server, and disabling it stops only the AI rescue narration while potion rescue still runs normally.
- 提供归档导出/导入基础，兼容读取旧 schema1 记录与缺少引用字段的 schema2 记录；导入按 ID 幂等并保留冲突处理。
  Archive export/import groundwork, with compatible reading of legacy schema1 records and schema2 records missing quote fields; imports are idempotent by ID and retain conflict handling.

### 升级与兼容说明 / Upgrade and compatibility

- 本发布包仅适用于 **Minecraft 1.21.1 / NeoForge**；Forge 1.20.1 分支暂未同步本轮功能。需要 Java 21、NeoForge 21.1.243 或更高的 1.21.1 对应版本，以及车万女仆（元数据最低 1.5.0）。
  This release targets **Minecraft 1.21.1 / NeoForge only**; the Forge 1.20.1 branch has not received these features yet. It requires Java 21, NeoForge 21.1.243 or newer for 1.21.1, and Touhou Little Maid (metadata minimum 1.5.0).
- 网络协议已变化（`huhutalk-phone-quote-v1`），**多人服的客户端与服务端都必须升级到 1.1.1**，不能与旧版本混用。
  The network protocol changed (`huhutalk-phone-quote-v1`); **both client and server on a multiplayer server must upgrade to 1.1.1** and cannot be mixed with older versions.
- 服务端归档不等于车万女仆完整的 AI 工作上下文，本轮未实现完整 provider；不承诺重建旧版本从未归档的数据。本机聊天清理不等于删除服务端归档。升级前请备份存档与配置。
  The server archive is not Touhou Little Maid's complete AI working context, and this round does not implement a full provider. Data never archived by older versions cannot be reconstructed. Clearing local chat does not delete the server archive. Back up worlds and configuration before upgrading.
- 传输事件历史是只读审计，不会重发物品；归档导出/导入只是基础能力，未集成 Maid File Manager（MFM）的 `.maid` provider。
  Transfer history is a read-only audit and never re-sends items. Archive export/import is groundwork only; the Maid File Manager (MFM) `.maid` provider is not integrated.

### 验证边界 / Verification scope

本轮交付以 49 项 JUnit（构建不跳过）、94 项原生链路检查、456 个自有双语键和六张上游检查截图为依据，对应本次功能的源码状态。原生检查不发起真实付费 LLM/TTS 请求，也未进行 Forge 运行实测；发布版本重新构建与资源审计由上游核对。
This delivery is supported by 49 JUnit tests (not skipped in the build), 94 native-chain checks, 456 bilingual keys and six upstream-reviewed screenshots, corresponding to the feature source state. The native checks do not make paid LLM/TTS requests and no Forge runtime test was performed; the release build and resource audit are rechecked upstream.

## 1.1.0 — 2026-10-18

本节汇总从 `v1.0.0-beta` 之后的开发改动；不补造历史正式版本日期。
This section summarizes development since `v1.0.0-beta`; no historical release dates are inferred.

### 新增与改进 / Added and improved

- 实体手持手机体验，以及手机、电话手表和 Wi-Fi 方块的新模型。
  Handheld phone experience and refreshed phone, smartwatch and Wi-Fi block models.
- 自拍前摄与实时镜像预览；保存照片为正常方向。桌面相机直接保存，聊天相机保留确认与重拍。
  Selfie camera with mirrored live preview and normally oriented saved photos. Desktop capture saves immediately; chat capture retains confirmation and retake.
- 识图平台/模型选择，玩家与女仆外貌描述，为女仆理解照片中的人物提供上下文。
  Vision provider/model selection and player/maid appearance descriptions to help contextualize people in images.
- 桌面 App 图标更新、图标布局管理及联系人分组等界面改进。
  Refreshed desktop icons, icon layout management, contact grouping and UI refinements.
- 中英文界面、系统提示和手册支持；手册小标题独立成行，功能段落间留空行。
  Chinese/English interface, system messages and manual; separate manual subheadings and spaced feature blocks.
- 设置 App 新增「显示与渲染」，迁入比例、大小、字体倍率、亮度；图标风格留在 HuhuTalk 内部设置。
  New Display & Rendering page in the Settings app for aspect ratio, size, font scale and brightness; icon style remains in HuhuTalk's internal settings.
- 新增默认关闭的「墨水屏」开关，仅调整手持手机屏幕在光影处理前/后的绘制顺序。
  E-ink Screen toggle, off by default, controlling whether the held phone screen is drawn before or after shader processing.

### 修复与调整 / Fixed and adjusted

- 优化 Iris 光影下自拍渲染开销及兼容性；修复 BSL 下手机屏幕文字重影、泛光的默认绘制路径。
  Reduced selfie rendering overhead and improved Iris compatibility; corrected the default phone-screen path for BSL text ghosting and bloom.
- 调整特殊手持位置，保持模型与离屏界面对齐，改善生存 HUD 对屏幕内容的遮挡。
  Adjusted the special holding position while keeping model and offscreen UI aligned, reducing survival HUD overlap.
- O 键独立悬浮屏不再要求主手持有 Pro 手机；状态栏支持独立光标拖动，不改变手持手机屏幕的位置。
  O no longer requires a Pro phone in the main hand; the standalone screen can be dragged by its status bar without moving the held phone screen.
- 设置 App 图标允许移动但禁止删除；新手礼包改为 Pro 手机、电话手表、Wi-Fi 方块，并更新提示。
  The Settings icon can be moved but not deleted; the starter kit now contains a Pro phone, smartwatch and Wi-Fi block with an updated notification.
- 修正手册物品配图：电话手表、Wi-Fi 方块、Pro 手机分别对应正确图片。
  Corrected manual illustrations for the smartwatch, Wi-Fi block and Pro phone.
- 发布版本元数据统一到 1.1.0，主页链接修正；明确代码采用 Apache-2.0、罗春制作的自有美术采用 CC BY-NC-SA 4.0，第三方资源保留原许可（Pixabay 音效为例外）。新增资源署名/来源清单，并将授权声明随 JAR 分发。
  Unified release metadata at 1.1.0 and corrected the homepage link; clarified Apache-2.0 for code, CC BY-NC-SA 4.0 for original artwork by 罗春, and retained original terms for third-party assets (including the Pixabay audio exception). Added asset attribution/provenance listings and bundled the license notices with the JAR.

### 升级与兼容说明 / Upgrade and compatibility

Minecraft 1.21.1、Java 21、NeoForge 与车万女仆为必需组件。升级前备份存档和配置。墨水屏开启后可能重新出现所选光影包的泛光/重影；不同光影版本效果可能不同。界面语言切换不会强制改变 AI 回复语言。

Minecraft 1.21.1, Java 21, NeoForge and Touhou Little Maid are required. Back up worlds and configuration before upgrading. Enabling E-ink Screen can reintroduce a shader pack's bloom/ghosting; results vary by pack and version. Interface language changes do not force the AI response language.

## v1.0.0-beta

仓库已有历史标签；本次不追溯重写其发布说明。
Existing historical repository tag; its release notes are not reconstructed here.
