# 光影开发环境（Iris + Sodium + Complementary Reimagined）

本项目的默认构建、发布 jar 和模组 metadata 不包含任何光影相关依赖。只有显式传入
`-PshaderDev=true` 时，才会：

- 把 Iris 作为 `localRuntime` 加入开发运行类路径；
- 把 Sodium 的原始 Maven 分发 JAR 复制到专用运行目录的 `mods/`；
- 把 Complementary Reimagined 光影 ZIP 复制到专用运行目录的 `shaderpacks/`。

该开关启用时把 client 的默认运行目录隔离为 `run-shader-dev/`，不改变默认关闭时的
`run/`、存档或 Iris 用户配置，也不会自动启用光影。关闭开关后不会选择专用目录，已准备的
Sodium 不会影响默认运行。

## 前置条件

- Windows CMD（本文命令均为 CMD 语法；项目规则不允许用 PowerShell 代替）。
- JDK 21，且 `JAVA_HOME` 指向它（本工程使用 Java 21）。
- 第一次解析 Modrinth 依赖时需要联网（`gradle.properties` 已配置代理）；之后可使用
  Gradle 缓存离线运行。

检查 Java：

```bat
set "JAVA_HOME=C:\path\to\jdk-21"
"%JAVA_HOME%\bin\java" -version
```

## 启用并运行

在项目根目录执行。第一次先在线准备 Sodium 和光影 ZIP：

```bat
cd /d <项目根目录>
gradlew.bat -PshaderDev=true prepareShaderDev
```

该任务会：

- 执行 `prepareShaderDevMods`，把 Sodium 原始分发 JAR 复制为
  `run-shader-dev/mods/sodium-shader-dev.jar`；
- 把光影 ZIP 复制为
  `run-shader-dev/shaderpacks/ComplementaryReimagined-dev.zip`。

### 投递必需的车万女仆前置

`run-shader-dev/mods/` 是新的独立目录，不会自动继承普通 `run/mods/`。本项目把车万女仆
1.5.3 作为 compileOnly 开发前置，正常开发运行目录中已有
`touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar`。在 `prepareShaderDev` 之后、首次
`runClient` 之前，用 CMD 把它投递到专用目录：

```bat
copy /Y "run\mods\touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar" "run-shader-dev\mods\"
```

这是现有必需的开发前置，不是 Iris 的依赖。若原目录中没有该文件，需先安装与当前工程匹配的
车万女仆；`prepareShaderDev` 不会自动下载全部模组，不能把新目录启动失败当成光影准备任务
应解决的缺模组问题。

同一 mod 只保留一份，不需要改文件名再复制。若自定义了 client `gameDirectory`，请把该前置
复制到那个目录的 `mods/`，而不是固定复制到 `run-shader-dev/`。重复执行准备任务只会覆盖
工具自己管理的 Sodium 固定别名和光影别名，不会删除其它模组。

**`prepareShaderDev` 只解析 Sodium 和光影 ZIP，不会解析 Iris 的 `localRuntime`。**
因此首次 `runClient` 仍建议联网，让 Gradle 拉取 Iris；成功运行一次后即可离线：

```bat
gradlew.bat -PshaderDev=true runClient
```

通过 Gradle 启动时，`runClient` 会先执行 `prepareShaderDev`（进而执行
`prepareShaderDevMods`）。**不要假设在 IDE 中直接点运行也一定会执行这个 dependsOn**；
需要 IDE 启动时，请先在 CMD 执行一次 `prepareShaderDev`，再启动 IDE 的客户端 run，或者直接
使用上面的 Gradle `runClient`。

Sodium 不在 `runtimeClasspath` 上，而是作为原始服务包装 JAR 放在专用 `mods/` 中，让 FML
在早期发现其服务与嵌套模块。进入游戏后，由你在 Iris 的光影列表里手动选择并启用光影。
首次启动生成的 Iris 配置属于 `run-shader-dev/`，不会被写入源码仓库；该目录已加入
`.gitignore`。

### 自定义运行目录

默认目录是 `run-shader-dev/`。如果外部 init script 或构建脚本通过 `set(...)` 显式设置了
client 的 `gameDirectory`，该显式值优先；`prepareShaderDev` / `prepareShaderDevMods`
会跟随实际 `gameDirectory` 写入 `mods/` 和 `shaderpacks/`，不写死盘符或用户名。

## 首次在线、之后离线

第一次至少要让 Sodium、光影 ZIP 和 Iris 都进入本机 Gradle 缓存。推荐的首次顺序：

```bat
gradlew.bat -PshaderDev=true prepareShaderDev
gradlew.bat -PshaderDev=true runClient
```

之后可以离线执行：

```bat
gradlew.bat -PshaderDev=true prepareShaderDev --offline
gradlew.bat -PshaderDev=true runClient --offline
```

若离线时报 `No cached version ... available for offline mode`：

- 缺 Sodium / 光影：先在相同参数下去掉 `--offline` 执行 `prepareShaderDev`；
- 缺 Iris：先在线执行一次 `runClient`，因为它才会解析 `localRuntime`。

不存在“`prepareShaderDev` 已预热全部运行 classpath”的保证。

默认构建不受此开关影响，仍应通过：

```bat
gradlew.bat build --offline
```

## 固定版本与覆盖

默认版本（Modrinth 版本 ID，不是语义化版本号）：

| 用途 | Maven 坐标 | 默认 ID | 投递方式 | 对应版本 |
| --- | --- | --- | --- | --- |
| Iris | `maven.modrinth:iris:<id>` | `KduFYu4t` | `localRuntime` | Iris 1.8.14-beta.1 for NeoForge 1.21.1 |
| Sodium | `maven.modrinth:sodium:<id>` | `uMOpc5uV` | `shaderDevMods` → `mods/sodium-shader-dev.jar` | Sodium 0.8.13 for NeoForge 1.21.1 |
| 光影 | `maven.modrinth:complementary-reimagined:<id>@zip` | `Bqen1mJX` | `shaderDevPacks` → `shaderpacks/ComplementaryReimagined-dev.zip` | Complementary Reimagined r5.9.3 |

可用 Gradle 属性覆盖任一 ID：

```bat
gradlew.bat -PshaderDev=true ^
  -PirisDevVersion=<Modrinth版本ID> ^
  -PsodiumDevVersion=<Modrinth版本ID> ^
  -PcomplementaryDevVersion=<Modrinth版本ID> ^
  runClient
```

版本必须同时匹配 Minecraft 1.21.1、NeoForge 和客户端环境。本项目仅验证了
**Iris 1.8.14-beta.1 与 Sodium 0.8.13 这一组组合**；不能据此认为所有 Iris 1.8.x 与
Sodium 0.8.x 都互相配套，换用旧版/新版时需查 Iris 对该 Sodium 版本的明确要求。覆盖成不匹配
的 ID 时，Gradle 可能仍能解析，但游戏会因缺少 API/类而崩溃。版本 ID 可在 Modrinth 对应
版本页的 URL 或 API 中查到，不能用 `1.8.14` 这类版本号直接代替。

## 文件与依赖边界

- Iris 只加入 `localRuntime`（`runtimeClasspath` 的开发扩展），不会进入发布 jar，不会出现在
  模组 metadata 的依赖声明里，也不会成为玩家必须安装的依赖。
- Sodium **不再**加入 `localRuntime`。它由独立配置 `shaderDevMods`（`canBeConsumed=false`、
  `canBeResolved=true`、`transitive=false`）解析，原始 JAR 被复制到专用目录的 `mods/`。
  不把它挂到 `runtimeClasspath`，是因为该分发包是服务包装 JAR，需要 FML 的原生 mods 发现来
  激活早期服务/嵌套模块；不涉及解包或改写第三方字节码。
- 光影 ZIP 使用独立配置 `shaderDevPacks`（同样 `canBeConsumed=false`、
  `canBeResolved=true`、`transitive=false`），只被 `prepareShaderDev` 作为复制来源，不挂入
  `runtimeClasspath`，因此不会被当作模组加载。
- Sodium 故意重命名为固定的开发别名 `sodium-shader-dev.jar`，光影重命名为
  `ComplementaryReimagined-dev.zip`，避免版本覆盖后文件名仍声称旧版本。
- 两个准备任务只覆盖目标目录中的同名别名文件；**不会删除 `mods/` 中任何其它文件，也不做
  通用清理**，不会修改 `options.txt`、Iris 配置或存档。
- 启用后的开发运行仅用于客户端。Iris 与 Sodium 都是 client-only 模组，不要把这个配置用于
  `runServer` 或发布服务端环境。

## 常见问题

- `prepareShaderDev` 报 404：ID、项目名或 `@zip` 写法有误。光影必须带 `@zip`；写成 `@jar`
  会找不到资源包产物。
- Sodium 没有加载：确认执行过 `prepareShaderDev`，并检查实际 `gameDirectory` 下的
  `mods/sodium-shader-dev.jar`。默认位置是 `run-shader-dev/mods/`，不是原来的 `run/mods/`。
- 启动后光影列表里没有：检查 `build.gradle` / 外部 init script 是否修改了 client 的
  `gameDirectory`；`prepareShaderDev` 会跟随该目录写入 `shaderpacks/`。
- 第一次离线启动失败且缺 Iris：先在线执行一次 `runClient`；`prepareShaderDev` 不解析 Iris。
- 默认 `build` 失败但未加 `-PshaderDev=true`：说明问题不在本开关范围内，按普通构建错误排查。
