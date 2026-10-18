# 更新日志 / Changelog

## 1.1.0 — 待发布 / Pending release

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
- 发布版本元数据统一到 1.1.0；主页链接修正，许可证标识与仓库现有 Apache-2.0 LICENSE 对齐。
  Unified release metadata at 1.1.0; corrected the homepage link and aligned the license identifier with the existing Apache-2.0 LICENSE.

### 升级与兼容说明 / Upgrade and compatibility

Minecraft 1.21.1、Java 21、NeoForge 与车万女仆为必需组件。升级前备份存档和配置。墨水屏开启后可能重新出现所选光影包的泛光/重影；不同光影版本效果可能不同。界面语言切换不会强制改变 AI 回复语言。

Minecraft 1.21.1, Java 21, NeoForge and Touhou Little Maid are required. Back up worlds and configuration before upgrading. Enabling E-ink Screen can reintroduce a shader pack's bloom/ghosting; results vary by pack and version. Interface language changes do not force the AI response language.

## v1.0.0-beta

仓库已有历史标签；本次不追溯重写其发布说明。
Existing historical repository tag; its release notes are not reconstructed here.
