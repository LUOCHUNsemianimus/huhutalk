# HuhuTalk 狐狐手机 3D 模型 — 交接

> **下一轮对话请直接从这里开始。**
> 现状：模型 / 贴图 / display 全部就绪，创造模式与女仆手上都能正常显示；
> 第 5 轮换贴图 + 调角度大小，第 6 轮修好「手机浮在手外」（pivot 改到手机重心），
> 第 7~8 轮按用户要求抬高 0.4 个手机的高、并沿手机宽边右→左挪 0.4 个手机的宽。
> **待用户进游戏确认；§5.3.3 有位置微调的换算表（含两套映射，别搞混）。**
> ⚠️ 本轮 Blockbench MCP 掉线，`.bbmodel` 里的 `display_settings` **没同步**成最新值，
> 下次进 BB 记得先用 `set_display_transform` 对齐（否则从 BB 导出会覆盖掉）。

---

## 零、一句话现状

| 项 | 状态 |
|---|---|
| 3D 模型 `phone_3d.json` | ✅ 已重写为独立物品模型（无 parent、0~16 标准空间、8 个 display 上下文全给） |
| 贴图 `phone_3d.png` | ✅ 128×128，用户手绘最终版 |
| `phone_smart.json` | ✅ 已指向 `huhutalk:item/phone_3d` |
| 模型能否烘焙 | ✅ 能 —— `latest.log` 无 `Unable to load model` / `Missing model` |
| 挂载链路 | ✅ 通 —— 见「四」 |
| **女仆手上显示** | ✅ **根因已找到并修好**（`ItemMaidPhone.syncClient` → true），待用户复验 |
| 物品栏图标 / 创造模式模型 | ✅ 用户已确认正常显示 |
| 玩家手上的尺寸 | ⚠️ 上一轮反馈「小得像电话手表」，已把 scale 从 0.5 提到 1.8，效果待确认 |
| ⚠️ 主手占用时手机让位 | ⚠️ 设计如此（见 §4.2）。女仆手上拿着三叉戟/工具时**手机不会显示** |

---

## 一、任务目标与方案选型

给 HuhuTalk 做狐狐手机（参考图：黄色狐耳手机壳，正面聊天屏 / 背面狐尾 + 三摄）的 **3D 模型**，用于：

1. 女仆「掏手机打字」动画（`huhutalk_phone_raise/hold/lower`）时挂在右手上；
2. 手机物品本身的模型。

三种方案对比后选 **B**：

| 方案 | 随 mod 下发 | 日后热切换 | 要改 Java |
|---|---|---|---|
| A. 塞进 `winefox.bbmodel` | ❌ | ❌ | 否 |
| **B. 独立 Java 物品模型（当前）** | ✅ | ✅ | **否** |
| C. Bedrock geo + 自绘层 | ✅ | ✅（可带骨骼动画） | 是 |

选 B 的依据（都在源码里核对过）：

1. `PhoneInHandLayer` 用 `ItemInHandRenderer.renderItem(..., THIRD_PERSON_RIGHT_HAND, ...)` 渲染一个 **ItemStack**
   → 外观 100% 由 `assets/huhutalk/models/item/*.json` 决定；
2. `RenderUtils.translateToPivotPoint` 里写的是 `pivot / 16f` → **定位器空间是方块单位**，
   而物品模型顶点也是 `from / 16` 方块，**同一把尺子**；
3. 所以手机是纯美术资源，改它不用碰 Java。

（旧文档 `phone-animation-handoff.md` §9.5.5 的 C 方案仍然保留：若将来要「运行时热切换 + 骨骼动画」，再升级。）

---

## 二、文件清单

| 文件 | 作用 | 状态 |
|---|---|---|
| `src/main/resources/assets/huhutalk/models/item/phone_3d.json` | 3D 物品模型（16 个 element） | ✅ 最新（display 见 §5.3） |
| `src/main/resources/assets/huhutalk/textures/item/phone_3d.png` | 128×128 贴图 | ✅ **第 5 轮已换成 Blockbench 现行版**（sha256 `2ce3e3f4…`） |
| `src/main/resources/assets/huhutalk/models/item/phone_smart.json` | `{"parent": "huhutalk:item/phone_3d"}` | ⚠️ 临时改动，见「7.4」 |
| `docs/blockbench/huhutalk_phone.bbmodel` | Blockbench 可编辑源 | ✅ 最新；`display_settings` 已同步成 §5.3 的值 |
| `src/main/java/com/huhutalk/client/render/PhoneInHandLayer.java` | 手机挂手渲染层 | ⚠️ **含临时诊断日志**，问题解决后要清 |
| `src/main/java/com/huhutalk/init/ItemMaidPhone.java` | 手机物品 + `IMaidBauble` | ✅ **本轮修复：补上 `syncClient` → true**（见「4.1」） |
| `src/main/java/com/huhutalk/HuhuTalkTLMExtension.java` | 注册渲染层的扩展入口 | 未改 |

`phone_watch`（青春版电话手表）**保持原样**，仍是 32×32 平面图标，本轮完全没动。

---

## 三、架构：手机是怎么被渲染的

```
[服务端] MaidChatHandler → PhoneChatSourceController.open(maid) → 广播 active=true
[客户端] PhoneChatSourceCache.isActive(stableId)
            ├─→ PhoneAnimation   播放 huhutalk_phone_raise → hold → lower（只驱动 Head/RightArm/RightForeArm）
            └─→ PhoneInHandLayer 在 RightHandLocator 上渲染 ItemStack
                 └─ 需要 PhoneHelper.getPhoneTier(maid) != null
                      └─ 需要客户端的女仆配饰栏里有手机
                           └─ 需要 ItemMaidPhone.syncClient() == true   ← 之前缺的就是这一环
[服务端] 关窗 → active=false → 客户端播 lower
```

`PhoneInHandLayer` 是 TLM `GeckoLayerMaidHeld` 的克隆（同样的 `translate(0,-0.0625,-0.1)` + `rotateX(-90)`），
挂点是模型自带的 `RightHandLocator` 骨骼 —— **不需要改任何女仆模型**。

---

## 四、已经排除的可能（都有证据）

| 嫌疑 | 结论 | 证据 |
|---|---|---|
| 模型 JSON 语法/结构坏了 | **排除** | `latest.log` 里 `Unable to load model` / `Missing model` **零条**；NeoForge 会把 `models/item/*` 全部烘焙，有错必报 |
| 贴图丢了 / 是空白 | **排除** | `src` 与 `build/resources/main` 的 `phone_3d.png` 都是 128×128、内容完整、字节一致 |
| 没打包进产物 | **排除** | `build/resources/main/assets/huhutalk/{models,textures}/item/phone_3d.*` 都在 |
| 渲染层没注册 | **排除** | `GeckoEntityMaidRenderer:51-52` 构造时遍历 `TouhouLittleMaid.EXTENSIONS` 调 `addAdditionGeckoMaidLayer` → 我们覆写的方法 → `addGeoLayerRenderer` → `addLayer`。且该层还会被复制到 YSM 渲染器（`EntityMaidRenderer:91-94`） |
| 物品用自定义渲染器绕过了模型 | **排除** | `HuhuTalkItems` / `ItemMaidPhone` 没有任何 BEWLR / `IClientItemExtensions` |
| 手机模型比手掌小、被包住 | **已定量确认，已修** | 见「5.4」 |
| **客户端根本不知道女仆戴了手机** | 🔴 **这才是真正的根因，已修** | 见「4.1」 |

### 4.1 🔴 根因：配饰栏只存在于服务端，客户端那份是空的（已修）

**证据（`动画诊断代码-第6次.txt`）** —— 这几行同时出现，互相矛盾：

```
[10:51:17] [HuhuTalk] 收到窗口包: stableId=6dbac82c-... active=true      ← 服务端认为女仆有手机（否则开不了窗口）
[10:51:17] [HuhuTalk/Hand] skip: main hand not empty = 1 minecraft:trident
[10:51:28] [HuhuTalk/Hand] skip: maid has no phone tier (PhoneHelper.getPhoneTier)   ← 但客户端说没有手机
```

全程 **没有一条 `RENDERED`**。

**因果链**（全部在源码里核对过）：

1. `PhoneHelper.getPhoneTier(maid)` 读的是 `maid.getMaidBauble().containsItem(PHONE_SMART/WATCH)`
   （`PhoneHelper.java:24-35`）。
2. `EntityMaid.maidBauble` 是**普通字段**，不走 entity data 同步；客户端那份只有收到
   `SyncBaublePackage` 才会有内容（`SyncBaublePackage.handleClient`）。
3. 而 TLM **只同步 `syncClient(...) == true` 的饰品**：
   - 全量：`MaidTrackEvent.onTrackingPlayer` → `getSyncClientBauble(maid)`
   - 增量：`BaubleContainer.BaubleSlot.setByPlayer/onShiftTakeoff`
   - `getSyncClientBauble` 内部就是 `if (entry.getValue().syncClient(maid, stack))`
     （`BaubleItemHandler.java:206-216`）
4. `IMaidBauble.syncClient` 的**默认实现返回 `false`**（`IMaidBauble.java:133-136`），
   而 `ItemMaidPhone` 当时**没有覆写**。
   → 客户端配饰栏永远是空的 → `getPhoneTier` 恒 null → `phoneOf()` 返回 `EMPTY` → 早退。

**修复**：在 `ItemMaidPhone` 里覆写

```java
@Override
public boolean syncClient(EntityMaid maid, ItemStack baubleItem) {
    return true;
}
```

**顺带说明**：`PhoneChatSourceCache` 走的是**另一条**网络通道（`ClientPayloadSetup` 的
`PhoneChatSourcePacket`），所以窗口状态能正常同步、`huhutalk_phone_*` 动画能正常播 ——
**只有配饰栏没同步**。这就是「动画在演，手上却没东西」的原因。

### 4.2 ⚠️ 主手有东西时手机让位（设计如此，未改）

`PhoneInHandLayer` 在 `!maid.getMainHandItem().isEmpty()` 时直接跳过，原因是 TLM 自带的
`GeckoLayerMaidHeld` 用的**就是同一个 `RightHandLocator`**（`GeckoLayerMaidHeld.java:43-45,86-88`），
两层会穿模。

**代价**：第 6 次日志里女仆挂着 `trident_attack` 任务、主手一直有三叉戟，
所以**即使配饰同步修好了，那个时刻手机也不会显示**。

复验时请让女仆主手空着（把三叉戟收回 / 换个不拿东西的任务），或者参考 `phone-model-handoff.md`
后面「§6 备选」里改挂 `LeftHandLocator` 的方案。

---

## 五、当前模型规格

### 5.1 几何（16 个 element，5 组）

| 组 | cube | 作用 |
|---|---|---|
| `case` | `case_core` / `case_top` / `case_bottom` | 机身三段，上下两段各窄 0.2 → 一级倒角 |
| `ear_right` / `ear_left` | `ear_r1..r3` / `ear_l1..l3` | 狐耳，每段阶梯 + z 向逐级收窄（0.36→0.34→0.32），防共面 z-fighting |
| `camera` | `camera_bump` + `lens_tl/tr/bl` | 背面摄像头凸起 + 三摄 |
| `buttons` | `btn_vol_up` / `btn_vol_dn` / `btn_power` | 侧键 |

**几何坐标已经整体平移 (+8, +5, +8)**，落在 Minecraft 标准的 **0~16 物品空间、居中于 (8,8,8)**。
（之前是以原点为中心的，那是错的 —— 对比 `camera_in_hand.json` 才发现。）

**反 z-fighting**：相邻实心块互相嵌入 0.01，不要改成共面贴合。

### 5.2 贴图与图集布局

128×128，UV 空间 0~16（Blockbench 里 `texture_width = 16`，1 UV = 8 px）。

```
x 0 ──────────────── 44        46 ──────── 83   86 ────── 117
y 0-4   case_core.up / down
y 4-39  case_core: west(0-3) south(4-24) east(25-28) north(29-49)
y 0-6   case_top:   west(52-55) south(56-73) east(74-77) north(78-94)
y 8-14  case_bottom: 同上
y 43-60 camera_bump(0-33) | lens_tl(48-79,y43-58) / lens_tr(y59-74) / lens_bl(y75-90)
y 43-87 按键 x84-95：btn_vol_up(y43-56) btn_vol_dn(y58-70) btn_power(y72-87)
y 92-104 右耳 ear_r1 x0-44 / ear_r2 x46-83 / ear_r3 x86-117
y 106-119 左耳 ear_l1 x0-44 / ear_l2 x46-83 / ear_l3 x86-117
```

耳朵两行的 **UV 偏移是 `[0 | 5.8 | 10.7]`**（不是 `[0|4.9|9.7]`）—— 左耳被改宽后原偏移会互相压到。
改耳朵尺寸时记得同步检查这里。

贴图密度：主体 8 px/单位；耳朵与镜头用 **UV 岛放大**跑到 2~4 倍密度（不靠加几何换细节）。

### 5.3 display

`display` **8 个上下文全给**（只给一项的话，父级整套 transform 不再继承，其余上下文退化成 `NO_TRANSFORM`）。

**当前值（第 8 轮定稿）**：

```
thirdperson_righthand / lefthand : rotation [-25,-30,0]  translation [1.31,1.71,3.39]  scale 1.35
firstperson_righthand / lefthand : rotation [11.74,-3.18,3.92] translation [1,4.75,-0.25] scale 1.35
ground / fixed / head / gui      : scale 1.8，translation / rotation 见 JSON（gui 带 rotation [25,148,0]）
```

| 旋钮 | 现在的值 | 含义 |
|---|---|---|
| `display.thirdperson_*.scale` | **1.35** | 手持大小。想小就 1.0、想大就 1.8 |
| `display.thirdperson_*.rotation.x` | **-25** | 手机向后倒的角度（-25 = 相对原 +35 向后倒了 60°） |
| `display.thirdperson_*.rotation.y` | **-30** | 俯视顺时针 30°（**负号 = 顺时针**，见 §5.3.2） |
| `display.thirdperson_*.translation` | **[1.31, 1.71, 3.39]** | 位置。**三个轴不正交**，改之前先看 §5.3.3 的换算表 |

> `gui`（物品栏图标）保持 1.8 没动 —— 用户已确认图标正常。

#### 5.3.1 display 的 pivot 与「手机在不在手上」（重要，别再手调 translation）

**MC 1.21.1 的物品 `display` 没有 pivot 字段**。查过源码：

- `ItemTransform.Deserializer`（`neoforge-21.1.243-merged.jar`）：`translation.mul(0.0625F)`
  → JSON 里的 translation 单位是 **1/16 方块（= 模型单位）**。
- `ItemRenderer.render` 第 121 行：`poseStack.translate(-0.5F, -0.5F, -0.5F)`
  → **旋转/缩放的默认中心恒为模型空间的 (8, 8, 8)**。

所以最终变换是（单位 = 模型单位）：

```
p' = translation + R · S · (p − 8)
```

把它反解，就能让「手机上的某个点 G」在变换后落在**物品局部原点**：

```
translation = − R · S · (G − 8)
```

**而这个「物品局部原点」就是手的握持点。** 证据（在 Blockbench 显示模式里读 `display_base.matrixWorld`）：
它的平移分量是 `(6.00, 9.8518, −8.3008)`，而手机的**世界包围盒中心**是 `(6.00, 9.875, −8.05)` —— 两者重合。
说明挂点就是原点，手机上落在原点的那个点，就是被手握住的点。

**结论：`G` 应当取手机自己的中心（中部）。** 于是：

```
G − 8   = (8, 7.815, 7.91) − 8 = (0, −0.185, −0.09)      // 手机包围盒中心
R = Rx(−25)·Ry(−30)，S = 1.35
R·S·(G−8) = (−0.06075, −0.27082, 0.01019)
translation = − 上式 = (0.06, 0.27, −0.01)  ≈ [0, 0.27, 0]
```

> ⚠️ **第 5 轮踩过的坑**：当时按用户要求「以底边为轴」向后倒 60°，我用
> `translation = P_fixed − R_new·S_new·(P−8)` 保住了**底边**，数学没错 ——
> 但底边不是重心，绕底边转 60° 会把机身甩出去 4.4 单位，结果手机**浮在手外**
> （用户实测约「1.3 个手机的高」）。第 6 轮用户要求「以手机中部的高为轴」，
> 正好就是上面 `G = 手机中心` 这一步 —— 换过去之后手机立刻回到手上。
>
> **教训：pivot 选哪个点，就决定了手机相对手的位置。想让手机长在手上，G 必须是重心。**

**想改「握在手机上的哪一段」**：把 `G` 沿手机高度轴下移 d 个单位（d>0 = 手握更低、手机更往上露），
`translation` 按下面线性关系走（已把 R·S·(0,1,0) 代入）：

```
translation.y += 1.2235 * d
translation.z -= 0.5705 * d
```

例：想让手再往下握 1 个单位（手机整体往上移）→ `translation = [0, 1.49, -0.58]`。

#### 5.3.2 欧拉角方向约定

`ItemTransform.apply` 用的是 `Quaternionf().rotationXYZ(rx, ry, rz)`，即 **R = Rx·Ry·Rz**（内旋 X→Y→Z）。

- 手机局部 **+Y = 顶部**（狐耳那端），**−Z = 背面**（后盖 `case_core.north` 在 z=7.75）。
- 「向背面倒」= +Y 转向 −Z = 绕局部 +X 转**负角**。
- 「俯视顺时针」= 从上往下看（屏幕上方为北 −Z、右为东 +X）顺时针 = 绕 +Y 转**负角**。
  （两种俯视约定——「地图式」和「面朝南俯视式」——都推出同一个结论：顺时针 = Ry 取负。）
- 因为原有旋转是纯 X 轴（ry=rz=0），叠加 Ry 可以直接由 Euler 表达：
  `Rx(−25)·Ry(−30)` ↔ `rotation: [-25, -30, 0]`。
  **如果 ry/rz 不为 0 就不能这么做**，必须老老实实做四元数/矩阵相乘。
- 左手槽：MC 在 `apply` 里对左手把 ry、rz 取反（rx 不变），所以左右槽**写成同一组值**即可，
  镜像由引擎负责。

#### 5.3.3 微调查询表（位置怎么改）

`translation` 的三个数是**手机局部轴**上的量，和屏幕上的「上下左右前后」**不正交**，
所以不能按直觉加减。有**两套**映射，别搞混：

##### (a) 手机自己的轴 → 世界（跟手机走，与渲染层无关）

要让手机**沿它自己的几何轴**平移（比如「沿手机的宽从右往左挪」），直接用显示旋转把
手机轴转进 `translation` 所在的坐标系即可：

```
Δtranslation = R_disp · (沿手机轴的位移)         // R_disp = Rx(−25)·Ry(−30)
R_disp 的三列 =  宽(几何 X) = (+0.866025, +0.211309, +0.453154)
                 高(几何 Y) = ( 0,        +0.906308, −0.422618)
                 厚(几何 Z) = (−0.5,      +0.366001, +0.784886)
```

> 这是**首选**方式：`translation` 的直接效果依赖渲染层（女仆是 `GeckoLayerMaidHeld`，
> 玩家是原版 `ItemInHandLayer`），但「沿手机自己的轴挪」两边是同一件事。

##### 三个数各自管什么（游戏里的实际效果）

| 第几个 | 字段 | 方向 | 正负含义 |
|---|---|---|---|
| 1 | `translation.x` | **沿手臂**（唯一干净的单轴） | **正 = 手肘→手掌；负 = 手掌→手肘**<br>（第 7 轮实测：Δx = −1.512 让手机「手掌→手肘」） |
| 2 | `translation.y` | 「上」与「侧」的混合 | 单独看没有干净的物理含义 |
| 3 | `translation.z` | 「上」与「侧」的混合 | 同上 |

`y`/`z` 之所以不干净，是因为它们张成的是「上」和「侧」两个方向的斜交坐标：

```
「上」 = (0, +0.382683, +0.923880)      ← 主要吃 z
「侧」 = (0, -0.923880, +0.382683)      ← 主要吃 y
（「侧」= 垂直于「手臂轴」和「上」的那个方向）
```

所以要干净的方向必须两个一起改：

| 想要 | `translation` 增量 |
|---|---|
| 纯向上 h | `y += 0.3827h`，`z += 0.9239h` |
| 纯侧向 w | `y -= 0.9239w`，`z += 0.3827w` |
| 沿手臂 d（正 = 手肘→手掌） | `x += d` |

##### (b) 世界轴 → 手机局部轴（在 Blockbench 的 player 参考模型里实测）

```
Δ世界(单位) = M · Δtranslation(单位) ,   M = Rx(67.5°)
```

> **测法**（可复现）：进显示模式 → 把 `rotation` 设成 `[0,0,0]` → `translation` 依次给
> `(16,0,0) / (0,16,0) / (0,0,16)`，每次读 `display_base` 的世界包围盒中心差。
> 三次结果正好是 M 的三列。第 6 轮曾用「读 `display_base.matrixWorld` 平移分量」反推，
> 因为算错了转置，把 z 的符号搞反过 —— **务必用三次探测，别手推。**

反解 `Δtranslation = Mᵀ · Δ世界`：

| 想要的世界位移（单位 = 1/16 方块） | `translation` 增量 |
|---|---|
| 向上 u（世界 **+Y**） | `(0, +0.38268·u, +0.92388·u)` |
| 世界 **−X** | `(−u, 0, 0)` |
| 世界 **+Z** | `(0, −0.92388·u, +0.38268·u)` |

**用「手机尺寸」当尺子**（几何 × scale 1.35）：手机的高 = **7.33**，宽 = **3.78**。

##### 第 7~8 轮实算

```
抬高 0.4 个手机的高（世界 +Y，7.33×0.4 = 2.932）
  → Δtranslation = (0, +1.122, +2.709)

右→左 0.4 个手机的宽（沿手机几何 X，3.78×0.4 = 1.512）
  → Δtranslation = 1.512 × (0.866025, 0.211309, 0.453154)
                 = (+1.309, +0.319, +0.685)

合计 = (+1.309, +1.441, +3.394)
translation = [1.31, 1.71, 3.39]
```

> ⚠️ **第 7 轮的坑（用户纠正过一次）**："向内移动 0.4 手机的宽" 被误解成
> 「朝**世界 −X**（身体中线）走」，于是用了 `(−1.512, 0, 0)` —— 游戏里手机
> **沿手臂从手掌滑向手肘**，是错的。
> 用户的本意是「**沿手机自己的宽边，从右往左**」= 上表 (a) 的用法。
> **教训：用户说「手机的宽/高」时，指的是手机自己的轴，先按 (a) 算，别自作主张换成世界轴。**
> 如果这次方向反了，把 `Δtranslation` 整体取负即可：`translation = [−1.31, 1.07, 2.02]`。

> 注意 `translation` 的直接效果依赖渲染层（见 (a) 的说明），所以 (b) 表只对
> Blockbench 的 player 参考模型严格成立。

### 5.4 「手机被手掌吞掉」这条教训（重要）

| | 尺寸（单位 = 1/16 方块） |
|---|---|
| 女仆手掌方块 `RightHand` | **3 × 3 × 3** |
| 手机几何 | 2.6 × 5.0 × 0.5（含耳 5.43 高） |
| 最初渲染尺寸（× scale 0.5） | **1.3 × 2.5 × 0.25** ← 完整落在手掌内部，完全不可见 |

**Minecraft 的手持物品是故意放大的**：剑 16 单位 × 0.85 = 13.6 单位，地图 ≈ 8.8 单位。
按「真实人手 / 手机比例」设计（5 单位）再乘 0.5，必然小到被手吞掉。
第 3 轮 scale 1.8 → 渲染成 4.68 × 9.0 × 0.9 单位；第 5 轮用户觉得偏大，× 0.75 → **1.35**
（= 3.51 × 6.75 × 0.675 单位）。

> ⚠️ **`camera_in_hand.json` 的 display 值不能照抄。** 第 3 轮把它的
> `rotation [35,0,0] / translation [0,1.75,0.25]` 原样搬了过来，只改了 scale。
> 但那个模型的几何在 **y 0~8**（重心 y≈4，远在原点下方），而手机在 **y 5.1~10.53**（重心≈原点）。
> 同一组 translation 作用在两种几何上，落点完全不同 —— 这就是第 5 轮「手机浮在手外」的远因。
> **抄 transform 时必须同时看几何重心相对 (8,8,8) 的位置。**

---

## 六、下一步

### 第 0 步：复验（已修，等确认）

```
grep "HuhuTalk/Hand" run/logs/latest.log
```

**测试条件**：女仆配饰栏里戴着手机 **+ 主手空着**（三叉戟要收起来）**+ 打开一次聊天窗口**。

| 日志行 | 判定 | 下一步 |
|---|---|---|
| `RENDERED ... bones=N SCALE=1.0` | ✅ 通了 —— 剩下的是**尺寸 / 朝向 / 位置**问题 | 调 `display.third_person_righthand.scale`（先试 2.5），或在 `PhoneInHandLayer` 里微调 `BASE_TRANSLATE_Y/Z` |
| `skip: maid has no phone tier ..., clientBaubles=[...]` | 配饰还没同步下来 | 看 `clientBaubles=` 里的内容：`[]` = 同步仍然没发生（重登一次让 `MaidTrackEvent` 全量同步，或检查 `syncClient` 是否真的编进产物） |
| `skip: main hand not empty = ...` | 女仆主手有东西 | 见「4.2」；按设计让位给 TLM 自带手持层，可考虑改挂左手 `LeftHandLocator` |
| `skip: chat window inactive` | **正常** —— 手机只在聊天窗口开着时才挂手上 | 确认测试时真的发了消息 |
| `skip: rightHandBones() empty` | 该模型没有 `RightHandLocator` | 换酒狐系模型，或改用 `RightHandLocator2` |
| `skip: locator scale is zero` | 骨骼链里有 scale = 0 的骨骼 | 查模型 |
| **一行都没有** | 层压根没被调用 | 说明女仆用的是**非 gecko 模型**（`EntityMaidRenderer.java:164` 只在 `mainInfo.isGeckoModel()` 时才走 Gecko 渲染器），或 EXTENSIONS 扫描失败 |

### 第 0.5 步：物品栏图标 —— ✅ 已确认正常

用户已反馈创造模式里模型显示正常 → 模型链全通，问题确实只在渲染层（本轮已定位）。

### 其它备选怀疑（第 0 步没结论时）

- `RightHandLocator` 拿到的骨骼层级里，第一根骨骼的 pivot / scale 是否异常；
- 手机是否被渲染在手掌**内部**（几何已放大，理论上应该露出来，但可临时把 `BASE_TRANSLATE_Y` 调到 0.5 验证）；
- TLM 的 `addLayer` 是否在 `GeckoEntityMaidRenderer` 构造完成前被调用（目前看 `GeckoLayerMaidHeld` 也是同样方式注册，应该没问题）。

---

## 七、修改指南

### 7.1 改模型 / 贴图

1. 用 Blockbench 打开 `docs/blockbench/huhutalk_phone.bbmodel`（当前 Blockbench 里也开着这个工程）。
2. 改完导出到 `src/main/resources/assets/huhutalk/models/item/phone_3d.json`。
3. **导出后必须手工检查 `"textures"` 的值** —— Blockbench 的 java_block 编码器只会写
   `命名空间:贴图名`（即 `huhutalk:phone`），**不会带 `item/` 路径**。必须是 `huhutalk:item/phone_3d`。
   （本轮是直接在 `risky_eval` 里 `JSON.stringify` 自己写文件，绕过了这个问题。）
4. 贴图另存：
   `Blockbench.writeFile(".../textures/item/phone_3d.png", {content: canvas.toDataURL('image/png'), savetype:'image'})`

> **第 5 轮实测：BB 的 `elements` 与出货 JSON 完全一致（`elementDiffs=0`），只有贴图位图是旧的。**
> 所以只改贴图时，**不要**用 BB 的导出覆盖模型 JSON —— 那会用错贴图路径、还会把
> `display` 换成 BB 的（可能过期的）值。正确姿势：
> - 贴图：`risky_eval` 里 `Texture.all[0].canvas.toDataURL('image/png')` → `Blockbench.writeFile(...)`；
> - 模型：只在真要改几何时才动，并且手工修 `textures` 路径。
>
> ⚠️ `Blockbench.writeFile` 返回 `undefined`（异步），**别用返回值判断成功**，
> 写完用 `certutil -hashfile` 核对 sha256。
>
> ⚠️ 用 `drawImage` 画到临时 canvas 再 `toDataURL` 会**拿到空白图**（`t.img` 是
> `file://` 的 `HTMLImageElement`，那时还没解码完）。直接用 `Texture.all[0].canvas`。

### 7.2 改尺寸

只改 `display.*.scale`。**不要动几何。**

### 7.3 改 UV

`case_core` 的正面是屏幕（`south`，x4-24 / y4-39），背面是后盖（`north`，x29-49）。
其余布局见「5.2」。UV 值写进 JSON 时是 `px / 8`。

### 7.4 回滚 `phone_smart.json`

目前是 `{"parent": "huhutalk:item/phone_3d"}`。要恢复原来的平面图标就换回：

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "huhutalk:item/phone_smart" }
}
```

`phone_smart.png`（32×32）一直没动过，随时可回滚。

### 7.5 清诊断日志

`PhoneInHandLayer` 里的 `LOGGER` / `lastDiag` / `lastDiagAt` / `diag()`，以及 9 处 `diag(...)` 调用，
问题确认后删掉即可 —— **但不要删早退逻辑本身**。

---

## 八、踩坑清单

### Blockbench MCP

- `risky_eval` 的代码里**不能出现 `//` 或 `/* */` 注释**，插件会直接拒绝。
- `face.texture` 要写**贴图 UUID 字符串**（`set_cube_uv` 官方工具写的也是 UUID），不是索引。
- `Project.display_settings` 里必须放 **`new DisplaySlot(slot)` 实例**（再赋 `rotation/translation/scale`）；
  放普通对象的话 java_block 编码器**不会导出 `display`**。
- `box_uv` 在这套插件路径下**不会自动生成 face uv** —— 要自己按 cross layout 算好写进 `faces[k].uv`。
- `create_project` 会切走当前工程 —— 动手前先确认 `Project.saved`。
- 截图不可靠：`set_camera_angle` / `capture_screenshot` 经常回传**旧帧或空帧**。
  可靠做法是先用 `Canvas.updateAll()` 或改贴图**弄脏场景**，再 `capture_screenshot`。
  **从 -Z 方向（看背面）基本永远拿不到帧**（试了 5 次全空）。

### Minecraft 1.21.1 物品模型

- UV 空间恒为 **0~16**，与贴图分辨率无关（128×128 时 1 UV = 8 px）。
- 几何用 **0~16 空间、居中于 (8,8,8)**，不要以原点为中心。
- 三维物品模型用**独立模型**（无 `parent`），参考
  `build/maidsrc/assets/touhou_little_maid/models/item/camera_in_hand.json`。
- 子模型一旦写了 `display`，父级整套 display **不再继承**；要么全给，要么别写。
- 手持物品的缩放是**故意放大**的，别按真实比例设计。

### 本机环境

- **`pwsh` 工具不可用**（`SetNamedSecurityInfoW failed (Win32 5)`，宿主 ACL 故障，弹审批也没用）。
  命令行走 **IDEA MCP 终端**（`mcp__IDEA__execute_terminal_command`）。
- 日志全是 `.gz`。解压最新那份：

  ```powershell
  $d='D:\Moddevelop\MDK-1.21.1-ModDevGradle-main\run\logs'
  $f=Get-ChildItem -LiteralPath $d -Filter *.gz | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $src=[IO.File]::OpenRead($f.FullName)
  $gz=New-Object IO.Compression.GZipStream($src,[IO.Compression.CompressionMode]::Decompress)
  $dst=[IO.File]::Create("$d\_newest.log"); $gz.CopyTo($dst); $dst.Close(); $gz.Close(); $src.Close()
  ```

  但 **`run/logs/latest.log` 是存在且未压缩的** —— 优先直接 `grep` 它。
- IDEA MCP 可用：`get_file_problems`、`lint_files`、`build_project`、`execute_terminal_command`。
- Blockbench 与 winefox 工程：Blockbench 里现在开着两个页签（`winefox` + `huhutalk_phone`），
  `winefox` **全程没动过**。
- 这一轮对话上下文很长了，**下一轮请精简操作、批量调用**。

---

## 九、用户的最终版配色规律（复刻 / 统一时照这个）

| 位置 | 样式 |
|---|---|
| `case_core.west` / `east`（两侧边） | `#d2780f` 深橙描边（上深下浅）+ `#eca747` 亮橙填充，顶部 5 px 一条 `#f0c22e` 黄带 |
| `btn_power` / `btn_vol_up` / `btn_vol_dn` | 外侧面：深橙边框 + 亮橙填充；其余可见面 `#d2780f` |
| `case_core.south`（正面） | 聊天界面：`#ffefec` 底 + `#54d1e8` / `#3d71ad` 气泡 |
| `case_core.north`（后盖） | 黄框 + 橙底 + 米色狐尾（`#f8e9c9`）+ 圆点 |
| 狐耳 | 橙 `#f09a22` 外圈 + 米色 `#f8e9c9` 内芯；左右**镜像** |

左右两侧是**逐像素一致**的（`east` 是 `west` 的完整拷贝）；右耳是左耳的**真镜像**
（几何按 x=0 镜像；贴图 `south/north/up/down` 水平翻转，`east↔west` 互换但**不翻转**，
因为这两面的 u 轴沿 Z）。

---

## 十、历史轮次速查

| 轮次 | 做了什么 |
|---|---|
| 1 | 建 `java_block` 工程；16 个 element 建模；128×128 贴图；导出 `phone_3d.*`；接入 `phone_smart.json` |
| 2 | 用户手改（左侧橙边、屏幕、后盖、左耳形状）；把右侧（`case_core.east`、`btn_vol_up/dn`）统一成左侧样式；7 个 cube 尺寸变更后**重排 UV + 最近邻重采样**；右耳镜像；三颗镜头贴回 bump |
| 3 | 定位「看不见」= 手机比手掌小被包住；`display` scale 0.5 → 1.8；模型改独立范式（无 parent、0~16 空间、8 个 display）；`PhoneInHandLayer` 加诊断日志 |
| 4 | 用户报「创造模式模型已正常显示，女仆手上仍不可见」；从日志 `skip: maid has no phone tier` 入手，定位到 **`ItemMaidPhone` 没覆写 `IMaidBauble.syncClient`**（默认 false）→ 客户端配饰栏为空 → `getPhoneTier` 恒 null。**修复：`syncClient` 返回 true**；顺便把该诊断行改成会 dump 客户端配饰栏内容 |
| 5 | 用户提三点：①换 BB 现行贴图 ②手机头部太朝里，要**以底边为轴向后倒 60°** ③大小调成 0.75 倍。核对后 `elementDiffs=0`（几何+UV 与 BB 完全一致，只有贴图是旧的，392 px 差异集中在 case_top 顶带）→ 只换贴图；display 按底边 pivot 反解出 `rotation[-25,0,0] translation[0,1.02,-4.4] scale 1.35`；BB 的 `display_settings` 同步成同值 |
| 6 | 用户反馈角度/大小对了，但**手机浮在手前**（约 1.3 个手机的高），还想要**俯视顺时针 30°、以手机中部的高为轴**。查 BB 显示模式 `display_base.matrixWorld` 证明「物品局部原点 = 握持点」，据此推出正确规则 `translation = −R·S·(G−8)`，`G` 取手机重心 → `translation [0,0.27,0]`；顺便把 Ry(−30) 折进 Euler（`rotation [-25,-30,0]`）。同时定位出「手机浮起来」的远因：第 3 轮照抄了 `camera_in_hand.json` 的 translation，但两个模型的重心位置差 4 单位 |
| 7 | 用户要「再高 0.4 个手机的高、再向内 0.4 个手机的宽」。用**三次探测法**在 BB 里实测出映射 `Δ世界 = Rx(67.5°)·Δtranslation`（第 6 轮手推矩阵时转置算错、把 z 符号搞反过，靠探测纠正）→ `translation = [-1.51, 1.39, 2.71]`。换算表见 §5.3.3 |
| 8 | 用户纠正：「向内移动 0.4 手机的宽」实为「**沿手机自己的宽边从右往左**」。第 7 轮按「世界 −X」做的 `(−1.512,0,0)` 在游戏里让手机**沿手臂滑向手肘**。改用手机自身轴算：`Δtranslation = R_disp·(±0.4W,0,0)` → `translation = [1.31, 1.71, 3.39]`。**顺带发现：说「手机的宽/高」时应按手机自身轴算，别自作主张换成世界轴**（§5.3.3a）。本轮 BB 的 MCP 掉线，`.bbmodel` 的 `display_settings` 没能同步 |
| 9 | 用户问「沿手臂（手肘→手掌）改哪个数、正负怎么分」。答:**第一个数 `translation.x`**，正 = 手肘→手掌、负 = 手掌→手肘（由第 7 轮实测反推）；`y`/`z` 是「上 × 侧」的斜交坐标，单独改不干净。用户自行把 `x` 翻成了 `-1.31`。本轮无代码改动，只补了 §5.3.3 的「三个数各自管什么」表 |

**未落地的备选方案**（若要继续深挖）：
把手机从「物品模型」升级为 **Bedrock geo + 自绘渲染层**（旧文档 §9.5.5），
挂点仍是 `RightHandLocator`，几何可直接复用 —— 但要走 Java，且本轮所有贴图/UV 工作需重做映射。
