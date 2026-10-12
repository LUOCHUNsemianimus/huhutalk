# 手册返回键 / 手册文案 Word 流水线 / 开机提示 / 开局礼包 —— 交接

> 2026-10-12 一轮需求（4 条）的落地记录。全部改在主模组 `MDK-1.21.1-ModDevGradle-main`。
> 编译：`gradlew jar` 通过（走 `HuhuTalk-Demo\_mmbuild.bat` 或 IDEA 终端，见文末环境说明）。

---

## 1. 手册返回键（`ManualPage`）

### 根因
`ManualPage.mouseClicked` 里：

```java
if (hitHeaderBack(w, relX, relY)) {
    onBackPressed();      // ← 返回值被丢掉
    return true;          // ← 无条件吞掉这次点击
}
```

`onBackPressed()` 在**一级列表**（手册最外层）会返回 `false`，注释里写的是"交给上层 `popPage` 回桌面"
—— 但那是**右键**通路的行为（`PhoneOverlay.handleMouseClicked` 会看返回值）。
左键点顶栏「‹ 返回」时返回 `false` 没人接，于是**点击被吞掉、什么也不发生**：玩家看到的就是"返回键是坏的"。

### 修法（接上公共方法）
```java
if (hitHeaderBack(w, relX, relY)) {
    if (!onBackPressed()) {
        PhoneOverlay.popPage();   // 公共通路：与 api 层 PhonePage#onBack() 的默认行为一致
    }
    return true;
}
```

> 没有把 `ManualPage` 改成继承 `api` 层 `PhonePage`：那套模板只维护**一个**滚动偏移，
> 而手册有"一级列表 / 二级列表 / 正文"三套独立滚动状态；为了修一个返回键去做这个重构不划算。
> 现在只在"最外层再往回"这一处接上公共通路。

---

## 2. 手册文案 ⇄ Word（`tools/manual_docx.py`）

手册正文是硬编码在 `ManualPage` 构造函数里的 Java 字符串字面量（11 节 / 42 条），
需求方要在 Word 里改，所以做了一条**往返流水线**（沿用之前"救援提示词 Word 草案"的做法，python-docx）。

```bat
cd /d D:\Moddevelop\MDK-1.21.1-ModDevGradle-main
python tools\manual_docx.py export                rem → docs\manual-entries.docx（给需求方改）
python tools\manual_docx.py import --dry-run      rem 看改动清单 + diff，不写盘
python tools\manual_docx.py import                rem 写回 ManualPage.java（先备份 .java.<时间戳>.bak）
python tools\manual_docx.py import --allow-delete  rem 文档里被删掉的条目才真的删
python tools\manual_docx.py selftest              rem 在临时副本上跑通整条链路（不动真文件）
```

### 往返约定（导出的 Word 首段也写着同一份）
1. **方括号编号不能改**：`[2]` = 大栏目、`[2-3]` = 第 2 节第 3 条。标题与正文随你改。
2. 正文里**一个自然段 = 一行**（对应模组里的 `\n`）。
3. 新增条目：编号写 `[新]` 或不写编号 → 追加到所在大栏目末尾（重名会被"按标题认领"，所以**同一份文档回写两次是幂等的**）。
4. 删除条目：整段删掉 + `--allow-delete`；不加则保留并在清单里告警。
5. 大栏目顺序 = 手册首页顺序；条目顺序 = 点进去后的列表顺序（整块上下移动即可）。

### 两个关键实现选择（都是踩过才写的）
* **只改该改的语句**：没变化的 `sections.add(sec(...))` 整段原样搬回去（连它原来的字符串拼接换行都保留），
  所以 diff 只落在真正编辑过的条目上 —— 而不是把 42 条全部重排版。
* **写盘前自校验**：生成新源码后立刻重新解析一遍，把"解析结果"和"我想要的内容"逐条比对，
  不一致就**拒绝写盘**。这条自校验在开发时真的抓到过一个 bug（见下）。
* ⚠ **删除条目必须显式把所属语句标脏**。第一版忘了这行，`render_stmt` 走"内容没变 → 原文搬回"的分支，
  **被删掉的条目会复活**。自校验就是在这里报的 `预期 11 节 / 42 条，实际 11 节 / 43 条`。
* ⚠ **不要用 `style.name == "Heading 1"` 判标题**：Word 另存后可能变成 `heading 1`（小写）或 styleId `Heading1`。
  现在 `_heading_level()` 三种写法都认。

### 自检覆盖
`selftest` 在临时副本上：导出 → 用 python-docx 模拟编辑（改正文含 `\` 与 `"`、改大栏目名、新增一条、删一条）
→ 回写 → 校验 7 项（含转义还原、条目增删、其余条目原样）→ 再回写一次确认**零变化**。当前全绿。

---

## 3. 开机聊天栏提示（`ClientCacheEvents.onPlayerLogin`）

旧文案：`[HuhuTalk] 按下 Alt 释放鼠标操作狐狐Talk！` —— **早就不成立了**：
"按住 Alt 释放系统鼠标"那条通路已拆除（见 `MouseHandlerMixin` 的类注释）。
现在是**独立光标**：鼠标左右键完整留给游戏，手机只吃玩家绑定的两个功能键。

新文案（键名**动态取当前绑定**，默认就是鼠标侧键 4/5）：

```
[HuhuTalk] 掏出狐狐Talk手机后低头，按「鼠标按键4」「鼠标按键5」（两个玩家绑定的功能键）即刻使用。
```

实现：`ClientModEvents.PHONE_CLICK / PHONE_BACK` 的 `getTranslatedKeyMessage()`，
玩家在"按键设置"里改过之后这条提示也依然准确。

---

## 4. 开局礼包：每个新档发两部手机（`core/event/PlayerStarterKit`）

* 时机：`PlayerEvent.PlayerLoggedInEvent`（服务端侧；客户端同名事件被 `instanceof ServerPlayer` 挡掉）。
* 内容：`huhutalk:phone_smart`（Pro）+ `huhutalk:phone_watch`（电话手表）各一件，塞进背包；**塞不下就掉在脚下**。
* **每档每玩家一次**：标记写在 `SavedData`（名字 `huhutalk_starter_kit`，存主世界维度数据存储）
  —— 与联系人簿 / WiFi 注册表 / 离线队列同一套写法。多人存档里第二个玩家照样能领到自己的那份。
* 先占标记再发东西：即便发东西那一步抛异常，也不会出现"同一个档反复发"。
* 发完给玩家一条聊天提示（"放进女仆饰品栏即可远程聊天"）。
* 注册处：`HuhuTalk` 构造函数里 `NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, PlayerStarterKit::onPlayerLoggedIn);`

> 想重测"开局发放"：删掉存档里的 `data/huhutalk_starter_kit.dat` 即可（或换个新档）。

---

## 5. 验收清单

| # | 做什么 | 期望 |
|---|---|---|
| 1 | 打开手机手册 → 进任意大栏目 → 点右上/左上「‹ 返回」 | 逐级退回；**在一级列表再点一次会回桌面**（本次修的就是这条） |
| 2 | 右键（默认鼠标侧键 5）在手册里连按 | 行为与上面一致（这条本来就正常） |
| 3 | 进世界看聊天栏提示 | 出现新文案，且键名与你在按键设置里绑定的**一致** |
| 4 | 新建一个存档进世界 | 背包里出现 Pro 手机 + 电话手表各一件，并收到提示 |
| 5 | 退出重进同一个存档 | **不再重复发放** |
| 6 | 背包塞满再试（新档） | 两部手机会掉在脚下，不会丢 |
| 7 | `python tools\manual_docx.py export` 后用 Word 打开 docx | **10 节 / 34 条**都在，中文正常；带图的条目末尾有 `【配图：图N】` 一行 |
| 8 | 手机手册里逐条翻带图的条目（如 紧急关闭 / 图库 / 拍照 / 传文件） | 正文下方出现对应配图；竖屏长图（图9/图10）**不会**把正文挤没（高度封顶 62%） |
| 9 | 桌面看四个 App 图标 | 设置 / 图库 / 相机 三张是真贴图（狐狐像素图），手册仍是 📖 emoji；点开各自的启动动画色块色不变 |
| 10 | 删掉 `.minecraft/huhutalk/manual/` 整个目录再看手册配图 | 图照旧显示（走 jar 内兜底）；往该目录放同名 `manual_fig_N.png` 则可覆盖自带图 |

---

## 5.1 第二轮：手册文案改写（2026-10-12，需求方在 Word 上改完）

需求方的改法（写在 Word 里的"给ds："标记）与我的落地：

| 需求方写的 | 落地 |
|---|---|
| `[2-4] 紧急关闭` 正文里"——写给deepseek…括号里这行字不进mod" | **指令标记一律不进正文**；正文只保留"（见图1）"，并把图挂到该条目 |
| `[3-2]（给ds：3-8/3-9和这里合并为备用屏幕选项，被我删除序号的即为合并到上一级）` | 原 屏幕大小 + 屏幕比例 + 顶部导航栏 + 亮度滑块 + 手机壳 → **一条「备用屏幕选项（只能调节配用屏幕）」** |
| `[3-4]（给ds：这三条合为一条）` | 原 消息免打扰 + 女仆悄悄话 + 沉浸式聊天 → **一条「聊天设置」** |
| `[5]（给ds：这个大栏目降级为小栏目，点进去直接是内容）` | 「联系人设置」不再是独立大栏目，作为**「联系人」最后一条**，四条内容并进它 |
| `[3-11] 切屏与消息动画` 正文被清空，另加了两条无编号条目 | 原 打字耗时（→消息动画）+ 动画（→切屏动画）→ **一条「切屏与消息动画」** |

**合并约定**（需求方拍板）：合并进来的每条原文各占一行，**开头加「原标题：」**，
于是读者仍知道每句在说哪个选项。例：

```
亮度滑块：“手机壳也可以调暗。”
手机壳：可以选择你背包内的任何完整方块，但是，有点不好看。
```

**顺手改掉的两处笔误**（需求方授权，其它一个字没动）：
1. `[2-2]` "手机需要按住使用已绑定的功能键位使用" → "手机需要用已绑定的功能键位来操作"（原句"使用"重复）；
2. `[5] 删除联系人` "但是如果女仆只是丢失了而被删除联系人，下次他拿起手机会被重新录入联系人"
   → "但是如果女仆只是因为丢失（区块未加载）而被删除联系人，下次她拿起手机会被重新录入联系人"（他→她、句子理顺）。

> 还有几处 `他/她`（如 `切屏与消息动画` 里的"抢在他发完之前"）属于**上一版就有的**、这轮没点名，
> 所以按"照抄原文"处理了 —— 想一起改成"她"说一声。

## 5.2 手册配图（17 张）怎么进模组的

1. 需求方把图放在 `D:\Moddevelop\模组配图\图1.png … 图17.png`（另有 设置/图库/相机 三张 App 图标）。
2. `python tools\import_manual_assets.py` 把它们**改成 ASCII 文件名**拷进资源目录：
   * 图N.png → `src/main/resources/assets/huhutalk/manual/manual_fig_N.png`
   * 设置/图库/相机.png → `src/main/resources/assets/huhutalk/textures/icon/{settings,gallery,camera}.png`
   （脚本幂等：内容一样就跳过；顺手校验是不是真 PNG —— MC 的 `NativeImage` 只认 PNG/JPG。）
3. 代码侧：`ManualPage.entry(title, body, fig(N))` 挂图；
   `PhotoLibrary.readBytes` 新增**最后一道兜底**：磁盘（图库/聊天图/手册目录）都没找到时，
   去资源管理器取 `huhutalk:manual/<key>.png`。于是**图随 jar 分发**，
   玩家不用往 `.minecraft/huhutalk/manual/` 拷东西；想覆盖就放同名文件（磁盘优先）。
4. `ManualPage.renderBody` 给配图加了**高度封顶**（内容区可视高度的 62%，超出按比例缩宽、水平居中）：
   手册里图9/图10 是 798×1340 这种竖屏截图，不封顶会把正文挤成一条缝。
5. Word 往返也带上配图了：导出时每条带图条目末尾写 `【配图：图N】`；
   回写时读这一行（改成别的编号＝换图，写成 `【配图：无】`＝去掉，整行删掉＝保持原样）。

## 5.3 桌面 App 图标（设置 / 图库 / 相机）

| App | 位置 | 改动 |
|---|---|---|
| 设置 | `MainPage.buildApps()` 槽位 5 | 用 15 参构造器挂 `textures/icon/settings.png`（256×256，`iconFill=1`）；仍是"敬请期待"占位页 |
| 图库 | `GalleryAppRegistration` 槽位 3 | `.center("🖼️")` → `.icon(textures/icon/gallery.png, 256, 256)` |
| 相机 | `CameraAppRegistration` 槽位 1 | `.center("📷")` → `.icon(textures/icon/camera.png, 256, 256)`（原来那条 TODO 注释就是等这张图） |

手册（槽位 2）**还是 📖 emoji**：需求方这轮画的三张是 设置/图库/相机，手册图标以后再说。
三个 App 的 `color` 全部保留 —— 它是启动动画"色块放大淡出"用的颜色，也是贴图缺失时的兜底。

---

## 6. 环境备注（本机，沿用既有交接文档）

* `pwsh` 沙箱在本机是坏的（`SetNamedSecurityInfoW Win32 5`）→ 命令一律走 IDEA 终端的 `execute_terminal_command`；
  带引号的命令要落成 `.bat`（终端会吃掉嵌套引号）。
* `JAVA_HOME=C:\Users\10217\.jdks\temurin-21.0.11`；建 jar 用 `gradlew jar --offline --rerun-tasks`
  （不加 `--rerun-tasks` 会跳过刚改的文件）。
* Python 3.11 + python-docx 1.2.0 已装（`python` 在 PATH 里）。
