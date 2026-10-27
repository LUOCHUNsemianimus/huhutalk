# HuhuTalk（狐狐 Talk）

> **Forge 1.20.1 移植分支提示**：本分支 `port/forge-1.20.1` 是独立候选版，目标为 Minecraft 1.20.1 /
> Forge 47.4.16 / Java 17 / 车万女仆 1.5.3 Forge（最低 1.5.3）。候选版说明见
> [docs/forge-port-status.md](docs/forge-port-status.md)，人工验收清单见
> [docs/forge-port-acceptance.md](docs/forge-port-acceptance.md)，本轮工作日志见
> [docs/worklogs/2026-10-27-forge-1201-port.md](docs/worklogs/2026-10-27-forge-1201-port.md)。
> 原 NeoForge 1.21.1 工程与 pianchang 实例未改动；下方正文保留原 1.21.1 发布说明。

[English](README.en.md) · [更新日志 / Changelog](CHANGELOG.md) · [1.1.0 发布说明](docs/releases/1.1.0.md)

为 **Minecraft 1.21.1 / NeoForge** 上的 **Touhou Little Maid（车万女仆）** 提供手机式远程聊天与摄影体验。当前版本：**1.1.0**。

## 功能

- 可手持的手机物品与专用手机界面，更新的手机、电话手表及 Wi-Fi 方块模型。
- 私密聊天、表情包、语音输入、图片消息、联系人管理与自然语言指令。
- 后摄与自拍：自拍实时预览左右镜像，保存照片恢复正常方向。桌面相机拍摄后直接保存，聊天内相机保留确认、发送与重拍流程。
- 可选择识图平台与模型，并填写玩家、女仆外貌描述，为图片理解提供身份线索；识别结果取决于模型能力与配置。
- 新桌面图标与布局管理；设置 App 图标可移动、不可删除。
- 按 O 唤出独立悬浮屏，无需持有手机；保留视角控制，以手机独立光标和绑定确认键拖动状态栏调整位置。
- 设置 App →「显示与渲染」集中管理屏幕比例、大小、字体倍率、亮度及「墨水屏」。HuhuTalk 内部设置保留图标风格等选项。
- 中文、英文界面与手册，随 Minecraft 语言切换；AI 回复语言由模型及对话配置决定。

## 安装与要求

| 组件 | 要求 |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge | 21.1.243 或更高的 1.21.1 对应版本 |
| Touhou Little Maid | 必需，元数据最低 1.5.0；开发验收使用 1.5.3 NeoForge 1.21.1 |
| JourneyMap | 可选，用于路径点相关功能 |
| Iris / 光影包 | 可选，不随本模组分发 |

安装上述必需组件，将 `huhutalk-1.1.0.jar` 放入所用实例的 `mods` 文件夹。联网服务器与客户端需要匹配的模组版本。升级已有世界前请备份存档及配置；本模组不能脱离车万女仆单独使用。

进入游戏后先配置车万女仆的聊天模型服务，再按需在桌面「设置」App 配置识图。平台密钥配置权限沿用车万女仆的服务端规则。每个世界首次进入的礼包包含 Pro 版手机、电话手表与 Wi-Fi 方块。操作细节见手机内「手册」App，键位可在 Minecraft 控制设置中修改。

## 光影与墨水屏

「墨水屏」**默认关闭**：手持手机屏幕在光影后处理完成后绘制，保留界面原色与稳定亮度。开启后在光影处理前绘制，参与光影效果，可能出现光影包自身的泛光或重影。它只改变渲染顺序，不模拟黑白、低刷新或独立的环境亮度；独立悬浮屏不受此开关影响。

本阶段针对 Iris 1.8.14-beta.1、Complementary Reimagined r5.9.3 与 BSL 10.1.8 做过开发环境验证。其他加载器、光影版本和配置仍需实测；不保证所有组合兼容。详见[光影兼容说明](docs/phone-screen-shader-compat.md)。

## 模型服务与数据

聊天、识图及语音功能依赖配置的外部服务，可能产生费用。发送图片进行识图时，图片及相关提示会交给所选模型服务处理；请按自己的数据需求选择平台。手册和界面英文化不强制改变 AI 回复语言。

## 从源码构建

使用 Java 21，在仓库根目录运行：

```bat
gradlew.bat build
```

产物位于 `build/libs/huhutalk-1.1.0.jar`。依赖已缓存时可加 `--offline --console=plain`。仓库 `gradle.properties` 含开发者本地代理配置，请按自己的网络环境调整。光影开发依赖仅在显式 `-PshaderDev=true` 时启用，见[光影开发环境](docs/shader-dev.md)，不会打包进模组。

## 反馈与协议

请通过 [GitHub Issues](https://github.com/LUOCHUNsemianimus/huhutalk/issues) 提供问题表现、模组/加载器版本，以及相关光影名称和版本。提交日志前移除 API 密钥等私人信息。

代码采用 [Apache-2.0](LICENSES/Apache-2.0.txt)；罗春制作的自有美术采用 [CC BY-NC-SA 4.0](LICENSES/CC-BY-NC-SA-4.0.md)。第三方资源保留原许可，Pixabay 音效不改套上述协议；具体来源记录未找回。见[授权范围](LICENSING.md)、[第三方资源声明](THIRD_PARTY_NOTICES.md)。由罗春使用 AI 工作流协助开发；感谢车万女仆及相关项目、资源作者与测试玩家。
