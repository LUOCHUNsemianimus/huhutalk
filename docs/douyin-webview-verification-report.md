# 抖音网页版 App · 可行性验证「返还交接日志」

> **方向**：新工作区 → HuhuTalk 本体工作区（回填）。
> **性质**：本文是 §7 验收表的**真机人工确认版**，覆盖此前的自动探针结论。
> **结论**：**验收全部通过，技术路径成立，模组可制作。**
> 完整技术细节（三个关键坑、API 核实、环境坑）仍在
> `docs/douyin-webview-feasibility-handoff.md`，本文不重复，只给结论与交接要点。

---

## 0. 一句话

**抖音网页版在 MCEF 的离屏 Chromium 里完全可用：能加载、能刷视频、能登录、能输入。**
不需要任何特殊处理，不需要安卓模拟器，不需要逆向 App。

---

## 1. 验收结果（真人实机，全部通过）

| 编号 | 项目 | 结果 | 说明 |
|---|---|---|---|
| A | 首页能加载、不被风控 | ✅ **通过** | 跳 `www.douyin.com` → `/jingxuan`，完整视频信息流；**无验证码、无滑块** |
| B | **视频能播放** | ✅ **通过** | 真人点击后**有画面有声音**，可**上下滑动刷视频** |
| C | 登录可用 | ✅ **通过** | 二维码正常显示；**手机扫码登录成功**；键盘输入也可用 |
| D | 稳定性 | ✅ **通过** | 优雅退出零残留（见 §3.3） |
| 附1 | 编解码器 | ✅ **H.264 + HEVC + AAC 全支持** | 印证 §3.1 的调研结论 |
| 附2 | 对照组 | ✅ 裸 H.264/AAC MP4 正常播放，`currentTime` 逐秒推进 | 证明环境无问题 |
| 附3 | 移动 UA | ⏸ 未测（已实现按键切换） | 桌面版已够用，非阻塞 |
| 附4 | 性能影响 | ⏸ 未量化 | 粗测 JVM ≈1.2 GB / CEF ≈700 MB，帧率未测 |
| 附5 | 小矩形可读性 | ✅ **通过** | 240×480 下文字可读 |
| 附6 | 网络环境 | ✅ **不需要加速器** | 全程未开代理/加速器，MCEF 原生库下载成功、抖音正常访问 |
| 附7 | 系统权限弹窗 | ✅ **只弹一次** | 首次下完 MCEF 原生库后弹一次防火墙权限，之后不再弹 |

**遗留的非阻塞项（明确不影响可行性结论）**：
- **分辨率偏低** —— 真人反馈"有点低"。技术上完全可解（提高 `resize()` 分辨率 / 用移动 UA 走移动版布局），
  属于调优，不是可行性问题。
- **帧率影响未量化** —— 建议阶段 2 集成分辨率调好后一并测。

---

## 2. 对之前几处结论的**修正**（重要，别沿用旧结论）

本次真机验证推翻了几个"自动探针得出的结论"：

| 旧结论（自动探针） | 真机结果 | 为什么会错 |
|---|---|---|
| ❌ "视频点不动、`video.src` 恒为空、`currentTime` 不推进" | ✅ **点击后能正常播放** | 当时**画面根本没渲染出来**（`TextureManager` bug），点击坐标是盲猜的 |
| ⚠️ "优雅退出从未触发，D 项存疑" | ✅ **优雅退出零残留** | 当时**还没加 30 秒心跳日志**，`exitSeconds` 其实生效了，只是日志里看不到过程 |
| ⚠️ "二维码需要程序点击『登录』按钮才弹" | ✅ **抖音自己就会弹** | 不需要我们做任何按键，那套 `L` 键逻辑可以删掉 |

**教训**：当渲染链路本身有问题时，**不要**用"页面逻辑探针"的结论去判断交互可行性——
你测的不是交互，是你那个看不见的视口。

---

## 3. 交接给 HuhuTalk 工作区的三组硬事实

> 这三组是**实施时一定会撞上**的，全部经实测确认。

### 3.1 渲染：**不要**用 `TextureManager`（本次最大的坑）

文档 §3.3 当初建议"把 `getTextureID()` 包一层 `AbstractTexture` 注册进 `TextureManager`"——
**这条建议是错的**，会让你卡上几小时。真相：

```java
public void register(ResourceLocation path, AbstractTexture texture) {
    texture = this.loadTexture(path, texture);   // 非 SimpleTexture → 回退并触发全资源重载
```

对 CEF 的纹理走这条路，会在**渲染线程上重载整个资源栈**，纹理永远注册不成，画面永远空白，
而且日志飘红但**没有一条直接指向原因**。

**正确做法 = 照抄 MCEF 自带的 `example/ExampleScreen`**（可直接 `javap` 自带 jar，不用读博客）：

```java
int glTextureId = browser.getRenderer().getTextureID();
RenderSystem.setShaderTexture(0, glTextureId);        // 直接喂裸 GL 纹理 id
RenderSystem.setShader(GameRenderer::getPositionTexShader);
// Tesselator QUADS + POSITION_TEX，4 顶点
BufferUploader.drawWithShader(buffer.buildOrThrow());
```

**不需要 `ResourceLocation`，不需要 `AbstractTexture`，不需要注册。**

### 3.2 三个必须知道的配置

**① CEF 必须走 CPU 离屏**，否则 `MCEFRenderer.onPaint`（用 `glTexImage2D`）**永不触发**：

```
--disable-gpu --disable-gpu-compositing
--autoplay-policy=no-user-gesture-required
--disable-background-timer-throttling --disable-backgrounding-occluded-windows
--disable-renderer-backgrounding
```

在 **mod 构造函数**里设 `System.setProperty("org.cef.commandLineArgs", ...)`（实测早于 CEF 初始化生效）。

**② 纹理方向**：CEF 缓冲区的行序**与 MC 的 V 轴一致**，
UV 直接 `(0,0)..(1,1)` 即可；**额外加 V 翻转会导致整页旋转 180°**（上下颠倒 + 左右镜像）。

**③ 探针/JS 注入时机**：browser 创建时停在 `about:blank`，
此阶段注入的脚本会被第一次真实导航**静默丢弃**。等 `getURL()` 非 `about:` 再注入。

### 3.3 退出与残留

| 场景 | 结果 |
|---|---|
| 正常"退出游戏"（`Minecraft.stop()`） | ✅ **零残留**：`screen removed` → `browser closed` → 退出码 0，`cef` 进程 = 0 |
| **强制杀 JVM**（任务管理器 / `Stop-Process -Force`） | ⚠️ **残留 6 个 `cef_server` ≈425 MB** |

`cef_server` 是**独立进程，杀 JVM 带不走它**。玩家用任务管理器强杀 MC 时可能触发——
集成时值得考虑加一个兜底清理。

---

## 4. 分发与环境：三件好事

1. **防火墙权限只弹一次** ✅
   首次运行下载 MCEF 原生库后弹过**一次**系统防火墙权限请求，**之后不再弹出**。
   → 对玩家体验影响有限，但**安装说明里值得提一句**，避免玩家以为出了问题。

2. **不需要加速器** ✅
   本次验证**全程未开加速器/代理**，MCEF 原生库下载成功（约 45 s），
   抖音页面也正常访问。
   → **原文档 §4.4 关于"必须国内直连、注意代理"的担忧可以放宽**。
   （注意：这是**本机网络环境**下的结论，不代表所有地区/运营商。）

3. **首次下载很快** ✅
   100–200 MB 的原生库本机约 **45 s** 完成，开发环境落在 `build/mcef-libraries/`。

### 4.1 仍需注意的一条

`cef_server` 是**独立进程**，强杀 MC 会留下残留（§3.3）。开发期反复重启要留意；
玩家侧若用任务管理器强杀游戏也可能触发，集成时值得考虑兜底清理。

---

## 5. 给 HuhuTalk 工作区的下一步建议

> **✅ 已落地**：下方这套"先做最小验证、再做完整集成"的路线已经执行完毕，
> 附属模组建成在独立工程 `D:\Moddevelop\huhutalk-douyin\`。
> 过程、证据与本轮新踩的坑见 **[douyin-addon-handoff.md](douyin-addon-handoff.md)**。
> 本体侧的唯一改动是新增 `PhoneOverlay.requestRedraw()`（+19 行，纯新增）。

**阶段 3 的第一步不要做完整虚拟光标集成**，先做半天的最小验证：

> 用 `PhoneOverlay` 已有的光标坐标 → 调 `browser.sendMousePress/sendMouseRelease`
> → 观察抖音 `video.currentTime` 是否推进。

理由：真机已证明**点击可用**，而且这个"真鼠标事件进 CEF"（trusted user gesture）
正是 HuhuTalk 相对所有现成浏览器 mod 的**唯一结构性优势**（§3.6）。
先用最小成本确认这一条走通，再投入完整阶段 2/3。

**其余非阻塞调优项**（按优先级）：
1. 提高浏览器 `resize()` 分辨率 / 或改用移动 UA 走移动版布局 → 解决"分辨率偏低"
2. 量化帧率影响
3. 移动 UA 下的表现差异（代码里已实现，按键 `1..4`）

---

## 6. 复用资产

| 资产 | 位置 | 说明 |
|---|---|---|
| Spike 模组源码 | `src/main/java/com/douyinspike/` | 可直接作为附属模组的种子 |
| 构建好的 jar | [build/libs/douyin_spike-1.0.0.jar](../build/libs/douyin_spike-1.0.0.jar) | 丢进 `run/mods` 即可复现 |
| JS 探针脚本 | `src/main/resources/douyin-spike/probe.js` | 编解码器 / 页面状态 / 视频状态 / 二维码检测 |
| 完整技术细节 | [douyin-webview-feasibility-handoff.md](douyin-webview-feasibility-handoff.md) | §7 验收表、§7.0 三个关键坑、§8 后续路线 |
| 探针解析脚本 | [parse-probes.ps1](../parse-probes.ps1) | 从日志提取 `DYS_PROBE` 载荷 |

**关键约束仍然有效**：Player 不想要抖音功能时，HuhuTalk 本体**不能**被迫依赖 MCEF。
终态仍是"MCEF 放进独立附属模组，本体零依赖"。
