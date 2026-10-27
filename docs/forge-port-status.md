# HuhuTalk Forge 1.20.1 版本说明

本文件记录 **Forge 1.20.1 / 1.1.0-forge1.20.1** 的版本、检查与验证范围。
2026-10-27，用户确认忘记给女仆佩戴手机，并确认正常使用场景的功能逻辑已验收通过；死亡掉落玩家手机不妨碍已登记的救援。
第三方模组、光影与硬件组合的覆盖范围仍以实际测试记录为准。

本工程是独立的 Forge 1.20.1 工作树，原 NeoForge 1.21.1 主源码在另一工作树保持，本工程不修改它，
也不修改用户的原 pianchang 实例。

## 1. 目标版本与分发

| 组件 | 发布版要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.16 |
| Java | 17 |
| 车万女仆（TLM） | 必需，最低 1.5.3 Forge |
| JourneyMap | 可选，未安装时仅路径点相关功能不可用 |
| 模组版本 | `1.1.0-forge1.20.1` |
| 发布 jar | `build/libs/huhutalk-1.1.0-forge1.20.1.jar` |

原 pianchang 参考实例使用 TLM 1.5.0，不能直接运行本版本；需要将 TLM 升级到 1.5.3 Forge（或更高的
1.20.1 Forge 版本）。本工程不要求也不修改该实例，建议在独立 1.20.1 / Forge 47.4.16 实例中验收。

## 2. 已执行的构建与检查

以下为开发侧实际执行并保留证据的检查，详细原始日志在工作流证据目录中。

- **固定 MCP JDK 17 build**：包含 `compileJava`、普通测试源、`gametest` 源与 reobf，已通过。
  文档改动前的上一源码指纹为
  `d56dbd75606bfd5f26071def11aebf5de7632a20b91386924995befcb4765661`；本候选版最终 build 的源码指纹与
  日志以工作流证据为准（文档本身也计入指纹，因此最终指纹会因本次文档修改而不同）。
- **8 组独立 JDK 17 main 检查**实际通过：Fragments（249 项）、Codec（21 项）、Waypoint（31 项）、
  ArchiveStore（181 项）、ArchiveMedia（70 项）、ArchiveAssetSession（89 项）、ArchiveHistory、
  ArchiveTransfer。这些是直接运行的 main 检查，不是 Gradle `test` 跑完了全部 main 检查。
- **资源审计**通过：19 个 JSON、450 个中英双语 key 及其 printf 参数、3 个原生祭坛配方、loot、
  tag 均通过既有审计。
- **正式 jar 隔离**：GameTest 类、客户端 smoke 驱动和测试结构模板只存在于 `gametest` 源集，
  不进入正式 jar；最终以实际 jar 检查为准。

## 3. 已执行的真实运行验证

本节及后文的 `work/...`、`runtime/...` 均为相对本项目工作流目录
`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/` 的路径；本仓库根下没有 `work/` 或
`runtime/` 目录。

### 3.1 原生 GameTest

`work/native-gametest-run-03.log` 记录 3 个 GameTest 全部完成，输出
`All 3 required tests passed`。覆盖真实 37 物品栈与预览、1 瓶自定义 NBT/药水，以及原生
`RecipeManager` 加载 3 个祭坛配方。GameTest 只测本模组，不发真实 LLM 请求。

### 3.2 客户端隔离 smoke

`runtime/client-smoke/result.json` 为 `success=true`，8 项检查通过；5 张截图已由上游逐一看过。
本次真实完成模型屏、设置页、浮窗、后摄、自拍，并保存 2 张本地照片：

- 后摄：1280×720，393882 字节；
- 自拍：400×600，103498 字节。

对应日志 `work/native-client-smoke-run-01.log`。本次 smoke **未发送 LLM 请求**，也不点聊天发送或录音。
普通 `runClient` 不带 `-PportSmoke`，不会自动驱动或自动退出；只有显式 `-PportSmoke` 才启用开发测试源
和隔离复制世界。

## 4. 源码与构建目录

| 路径 | 内容 |
| --- | --- |
| `src/main/java` | 生产源码（客户端/服务端分离） |
| `src/main/resources` | 资源、`META-INF/mods.toml`、`pack.mcmeta`（pack_format 15）、Mixin 配置 |
| `src/test/java` | 普通测试源与可直接运行的行为 main |
| `src/gametest/java` | 原生 Forge GameTest 与客户端 smoke 驱动，仅开发运行使用 |
| `src/gametest/resources` | 测试用空结构模板，不进入正式 jar |
| `tools/` | 只读审计与机械迁移脚本 |
| `D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/runtime/` | 本项目工作流目录下的隔离运行现场与 smoke 结果（不在仓库根） |
| `D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work/` | 本项目工作流目录下的运行日志与审计记录（不在仓库根） |

## 5. 网络协议与传输边界

- 通道名 `huhutalk:main`，版本严格 `forge1201-v1`；底层为本项目基于 Forge 1.20.1 原生
  `SimpleChannel` 的 own transport 适配层，通道上只注册 `WirePacket`。
- `WirePacket` 字段：`payloadId`、`messageId`、`totalLength`、`partIndex`、`partCount`、`byte[]` 片；
  编码硬上限 32767 字节，分片 24000 字节。
- 完整业务负载上限 2 MiB，1 MB 图片（C2S 与 S2C 档案图）不降成 32 KB。
- 每发送方最多 2 个进行中分组、4 MiB 预留；全局 32 MiB；30 秒过期回收；缺片、乱序、重复片与
  元数据冲突均有明确结果。
- 方向只从 Forge `NetworkEvent.Context` 判断；服务端玩家只从 `ctx.getSender()` 取得；未知
  payloadId、错方向、无 sender 在分配缓冲前拒绝。
- 客户端/服务端边界：common 入口不引用 `ClientPayloadSetup`；该类自身以 `Dist.CLIENT`/`MOD`
  订阅者在 `FMLClientSetupEvent.enqueueWork` 中绑定 15 个 S2C handler，专用服不加载该客户端类；
  音效在 common 侧门控后委托客户端 helper 播放。

## 6. 开发运行与 IDEA

- 上游已用 `genIntellijRuns` 实际生成 4 份运行配置：`runClient`、`runServer`、
  `runGameTestServer`、`runData`。被忽略的 IDEA 运行配置 Alternative JRE 已指向
  `D:/Moddevelop/.toolchains/jdk-17.0.20.1+1`。
- 在 IDEA 打开本独立工程后，把 Project SDK 与 Gradle JVM 选为 17。
- 命令行可 `set JAVA_HOME` 指向该 JDK，或直接使用该 JDK 的 `gradlew.bat runClient`。
- 客户端 smoke 命令为 `gradlew.bat runClient -PportSmoke`；测试类与模板不会进入正式 jar。

## 7. 当前验证边界与已知现象

以下按实际验证范围记录，均不构成需要用户紧急处理的警告：

- **自动化验证范围**：smoke 未发真实 LLM 请求或验证麦克风；用户已另行确认正常使用场景的功能逻辑验收通过。
- **救援补给**：用户已确认本次现象来自忘记佩戴女仆手机；有手机／无手机、死亡丢物对照结果见排查日志。
- **JourneyMap 缺装**：未安装 JourneyMap 的路径点降级场景尚需人工确认。
- **光影组合**：Oculus 及其他光影组合未验收；已验证范围不保证所有组合兼容。
- **模型屏取景**：测试截图在 70 度姿态、默认 FOV 下沿用原模型屏比例，边缘有视野裁切；
  FOV 与 GUI 缩放下的实际观感需人工确认。本次未擅改既有几何。
- **可选 Patchouli**：测试环境未安装可选 Patchouli，TLM 自带指南书 loot 报
  `unknown patchouli:guide_book`。已归因为第三方可选内容缺失，未修改第三方 jar。
- **GL 过滤模式诊断**：启动世界前有两条 GL 过滤模式诊断日志，未持续到拍照阶段；图片真实有效，
  日志保留备查。

### 7.1 本次移植的真实范围

- 服务端档案原有行为随本次移植保留；不声称已做跨版本重写。
- MFM 的 `MaidMigrationProvider` 接入与女仆聊天检索工具仍待后续对接，本次未实现，不声称已完成
  跨版本迁移。

## 8. 历史故障记录

客户端 smoke 前两次服务器启动失败曾分别由客户端音效类和
`DistExecutor.safeReferent` 预载客户端网络类引起，均已修复。旧日志保留，不能用当时 Gradle 退出码 0
反推启动已通过；当前运行结论以 `native-client-smoke-run-01.log` 与 `result.json` 为准。

## 9. 人工验收与工作日志

- 用户勾选清单：[docs/forge-port-acceptance.md](forge-port-acceptance.md)
- 本轮移植改动工作日志：[docs/worklogs/2026-10-27-forge-1201-port.md](worklogs/2026-10-27-forge-1201-port.md)
- 旧卡顿历史记录：[docs/worklogs/2026-10-26-focus-stall.md](worklogs/2026-10-26-focus-stall.md)

用户已于 2026-10-27 确认正常使用场景的功能逻辑验收通过。验收清单保留为后续回归模板，不能据此扩大未说明的第三方组合测试范围。
