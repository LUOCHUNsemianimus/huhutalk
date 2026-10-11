# HuhuTalk 狐狐手机 —— 3D 模型与手持渲染 · 经验总结

> **本文是历史经验记录,不是待办清单。** 记录"做过什么、踩过什么坑、为什么最后是这么做的"。
> 没有"下一步""待确认"这类状态 —— 这个任务**已经完结并实机验收**。
>
> 覆盖范围:物品模型 / 贴图 / `display` 数值 / 手持渲染层 / 配饰同步 / 与 TLM 手持层的互斥。
> 上一阶段(女仆「掏手机打字」动画本身)见 [`phone-animation-handoff.md`](phone-animation-handoff.md)。

---

## 0. 一句话结论

狐狸手机(黄色狐耳手机壳)是**纯资源侧**的 Java 版 3D 物品模型:女仆抬手打字时挂在右手
`RightHandLocator` 骨骼上,窗口关闭即消失。**不需要改动任何女仆模型**。
Java 侧只多了三样东西:一个渲染层、一个 mixin、一处配饰同步声明。

---

## 1. 最终形态

| 文件 | 作用 | 状态 |
|---|---|---|
| `assets/huhutalk/models/item/phone_3d.json` | 3D 物品模型(16 element / 无 parent) | 定稿 |
| `assets/huhutalk/textures/item/phone_3d.png` | 128×128 贴图 | 定稿，sha256 `2ce3e3f4…` |
| `assets/huhutalk/models/item/phone_smart.json` | `{"parent": "huhutalk:item/phone_3d"}` | 定稿 |
| `assets/huhutalk/animation/huhutalk_phone.animation.json` | raise / hold / lower 三段动画 | 定稿（上一阶段） |
| `client/render/PhoneInHandLayer.java` | 手机挂手渲染层 | 定稿，内含**未清理的诊断日志** |
| `mixin/GeckoLayerMaidHeldMixin.java` | 用手机时压掉右手手持物 | 定稿 |
| `init/ItemMaidPhone.java` | 手机物品 + `IMaidBauble` | 定稿，`syncClient` → true、`isFoil` → true、`stacksTo(1)` |
| `docs/blockbench/huhutalk_phone.bbmodel` | Blockbench 可编辑源 | 定稿 |
| `assets/huhutalk/models/item/phone_watch_3d.json` | **青春版电话手表** 3D 物品模型（20 element / 无 parent） | 定稿，见 §11 |
| `assets/huhutalk/textures/item/phone_watch_3d.png` | 手表 128×128 贴图 | 定稿，sha256 `eda108ce…` |

> `phone_watch`（青春版电话手表）**原先**是 32×32 平面图标，现已换成独立 3D 模型。
> 它与手机是两套几何/贴图，但共用同一套 `display` 范式、同一条挂手渲染链路，
> Java 侧连物品类都是同一个。踩过的坑与结论见 §11。

### 几何

16 个 cube、5 个组:`case` / `ear_right` / `ear_left` / `camera` / `buttons`。
包围盒 **x 6.6–9.4, y 5.1–10.53, z 7.57–8.25**,重心 `(8, 7.815, 7.91)`
(刻意贴近 `(8,8,8)`,原因见 §3)。相邻实心块互相嵌入 0.01 防 z-fighting —— **不要改成共面贴合**。

### display 最终值

```
thirdperson_righthand / lefthand : rotation [-25,-30,0]  translation [-1.31,1.71,0.25]  scale 1.35
firstperson_righthand / lefthand : rotation [11.74,-3.18,3.92] translation [1,4.75,-0.25] scale 1.35
ground                           : scale 0.6   ← 掉落实体专用，必须单独调小
fixed / head / gui               : scale 1.8（gui 带 rotation [25,148,0]，head 带 translation [0,15,0]）
```

> ⚠ **`ground` 和 `fixed` 不是一回事，别一起给 1.8。**
> `ground` 是**掉在地上/扔出去**的那个实体用的；`fixed` 是**物品展示框**用的。
> 渲染高度(方块) = 模型 y 跨度(单位) ÷ 16 × scale：
> 手机几何高 5.43 单位，1.8 → 0.61 方块（勉强能接受）；手表高 7.6 单位，1.8 → **0.86 方块**，
> 比一整块方块还高，实机观感就是"地上躺了块砖头"。手表现用 **0.6 → 0.29 方块**。
> 这两个数只影响资源，改完 **F3+T** 就能看，不用重启、不用重编。

`thirdperson` 的 `translation` 是**用户在实机里手调**的最终结果(我按公式算出的
`[1.31, 1.71, 3.39]` 只作为初值)。**改之前先读 §4,那三个数不正交。**

`display` 的 8 个上下文**必须全给**:只写一项的话父级整套 transform 不再继承,
其余上下文会退化成 `NO_TRANSFORM`。

---

## 2. 十轮时间线:症状 → 真因 → 修法

| 轮 | 症状 | 真因 | 修法 |
|---|---|---|---|
| 1–2 | 建模 / 贴图 / 接进 `phone_smart.json` | —— | 16 element + 128×128 贴图 |
| 3 | 女仆抬手时手上什么都没有 | 手机比手掌小,被手吞掉(scale 0.5 → 渲染 1.3×2.5×0.25,整个落在 3×3×3 的手掌内部) | scale → 1.8,模型改成独立范式(无 parent、0~16 空间、8 个 display) |
| 4 | 创造模式模型正常,女仆手上仍不可见 | **`ItemMaidPhone` 没覆写 `IMaidBauble.syncClient`**(默认 false)→ 客户端配饰栏是空的 → 客户端 `getPhoneTier` 恒 null | `syncClient` 返回 true(§5) |
| 5 | 贴图有瑕疵 / 头部太朝里 / 太大 | 出货贴图是旧版(392 px 差异集中在 case_top 顶带);几何+UV 与 BB **完全一致**(`elementDiffs=0`) | 换贴图;绕**底边**后倒 60°;scale ×0.75 → 1.35 |
| 6 | 手机浮在手外(约 1.3 个手机的高) | **pivot 选错**:绕底边转 60° 会把机身甩出去 4.4 单位(底边不是重心) | pivot 移到手机重心;顺带加俯视顺时针 30°(§3、§4) |
| 7 | 需要"再高 0.4 个手机的高、向内 0.4 个手机的宽" | 把"向内"误解成"朝世界 −X" | 用世界轴映射算(§4b) |
| 8 | 用户纠正:是"沿手机自己的宽边从右往左" | `translation` 的直接效果要先过一遍 `R_disp`,**手机自身的轴 ≠ 世界轴** | 改用 `ΔT = R_disp·(位移)`(§4a) |
| 9 | "用手机时隐藏主手物品的功能掉了" | **这个功能从来没存在过** —— `PhoneInHandLayer` 一直有"主手非空则跳过"的早退 | 见 §6;改成"窗口开着 → 手持物让位给手机" |
| 10 | —— | —— | 收拢本文档 |

**十轮里最贵的两个教训**:

1. **第 4 轮**:服务端一切正常(能开窗口、能发消息、动画在演),客户端却什么都不知道 ——
   因为"配饰栏"和"窗口状态"走的是**两条完全不同的网络通道**。
2. **第 6 轮**:数学没错但结果错 —— 公式用对了,却把"绕哪个点转"这个前提搞错了。

---

## 3. 核心知识:MC 1.21.1 物品 `display` 的数学

查过反编译源码(`build/moddev/artifacts/neoforge-21.1.243-merged.jar`,TLM 1.5.3):

- `ItemTransform.Deserializer`:`translation.mul(0.0625F)`
  → **JSON 里的 translation 单位是 1/16 方块(模型单位)**。
- `ItemRenderer.render:121`:`poseStack.translate(-0.5F, -0.5F, -0.5F)`
  → **旋转/缩放的默认中心恒为模型空间的 (8, 8, 8)**。
- `ItemTransform.apply` 的顺序是 `translate → rotate → scale`。
- **1.21.1 的 `display` 没有 pivot 字段**(NeoForge 额外提供了 `right_rotation`,但也没有 pivot)。

于是最终变换是(单位 = 模型单位):

```
p' = translation + R · S · (p − 8)
```

### 3.1 让手机上的某个点「落在挂点上」

**物品的局部原点就是手的握持点。** 证据:在 Blockbench 显示模式里读
`display_base.matrixWorld`,它的平移分量 `(6.00, 9.8518, −8.3008)` 与手机世界包围盒中心
`(6.00, 9.875, −8.05)` 重合。

所以想让手机上的点 `G` 落在握点上:

```
translation = − R · S · (G − 8)
```

**`G` 取手机重心**(`(8, 7.815, 7.91)`)时,手机就是"长在手上"的。
这也解释了第 5 轮为什么浮起来:当时按用户要求"以底边为轴",`G` 变成了底边 `(8, 5.1, 8)`,
绕它转 60° 会把机身甩出去 **4.4 单位**。

> **一句话:想让手机长在手上,`G` 必须是重心。pivot 选哪个点,就决定了手机相对手的位置。**

### 3.2 三个绝对值

| 量 | 值 |
|---|---|
| 女仆手掌方块 `RightHand` | 3 × 3 × 3 |
| 手机几何 | 2.80(宽) × 5.43(高) × 0.68(厚) |
| 渲染尺寸(× scale 1.35) | **3.78 × 7.33 × 0.92** |
| 对照:剑 | 16 × 0.85 = 13.6 单位;地图 ≈ 8.8 单位 |

**Minecraft 的手持物品是故意放大的。** 按"真实人手 / 手机比例"设计(5 单位)再乘 0.5,
必然小到被手吞掉 —— 这就是第 3 轮的病根。

### 3.3 ⚠️ `camera_in_hand.json` 不能照抄

第 3 轮把 TLM 自带 `camera_in_hand.json` 的
`rotation [35,0,0] / translation [0,1.75,0.25]` 原样搬了过来,只改了 scale。
但**那个模型的几何在 y 0~8(重心 y≈4,远在原点下方),手机在 y 5.1~10.53(重心≈原点)**。
同一组 translation 作用在两种几何上,落点完全不同 —— 这是第 5 轮"手机浮在手外"的远因。

> **抄别人的 transform 时,必须同时看几何重心相对 (8,8,8) 的位置。**

---

## 4. 核心知识:改 `translation` 时,两套映射别搞混

`translation` 的三个数是**手机局部轴**上的量,和屏幕上的"上下左右前后"不正交。
有两种改法,含义完全不同。

### 4a. 跟着**手机自己的轴**走(首选,与渲染层无关)

```
Δtranslation = R_disp · (沿手机轴的位移)          // R_disp = Rx(−25)·Ry(−30)
```

`R_disp` 的三列 = 手机三个几何轴在 `translation` 坐标系里的方向:

| 手机自己的轴 | 方向向量 | 挪 1 个对应尺寸要加 |
|---|---|---|
| 宽(几何 X,→ 左边缘/音量键那侧) | `(+0.866025, +0.211309, +0.453154)` | `(+3.27, +0.80, +1.71)` |
| 高(几何 Y,→ 顶部狐耳) | `(0, +0.906308, −0.422618)` | `(0, +6.64, −3.10)` |
| 厚(几何 Z,→ 屏幕法线) | `(−0.5, +0.366001, +0.784886)` | `(−0.46, +0.34, +0.72)` |

**为什么首选这个**:`translation` 的直接效果依赖渲染层(女仆走 TLM 的 `GeckoLayerMaidHeld`,
玩家走原版 `ItemInHandLayer`,基准变换差了 `rotateY(180)`),
但"沿手机自己的轴挪"两边是同一件事。

### 4b. 跟着**世界轴**走(只在 Blockbench 的 player 参考模型里严格成立)

```
Δ世界(单位) = M · Δtranslation(单位) ,   M = Rx(67.5°)
```

反解 `Δtranslation = Mᵀ · Δ世界`:

| 想要的世界位移 | `translation` 增量 |
|---|---|
| 向上 u(世界 +Y) | `(0, +0.38268u, +0.92388u)` |
| 世界 −X | `(−u, 0, 0)` |
| 世界 +Z | `(0, −0.92388u, +0.38268u)` |

### 4c. 三个数各自管什么(游戏里实测)

| 第几个 | 字段 | 方向 | 正负 |
|---|---|---|---|
| 1 | `translation.x` | **沿手臂**(唯一干净的单轴) | **正 = 手肘→手掌;负 = 手掌→手肘** |
| 2 | `translation.y` | "上"与"侧"的混合 | 单独看没有干净的物理含义 |
| 3 | `translation.z` | "上"与"侧"的混合 | 同上 |

`y`/`z` 是"上"和"侧"的斜交坐标:

```
「上」 = (0, +0.382683, +0.923880)      ← 主要吃 z
「侧」 = (0, −0.923880, +0.382683)      ← 主要吃 y
```

要干净的方向必须两个一起改:

| 想要 | 增量 |
|---|---|
| 纯向上 h | `y += 0.3827h`,`z += 0.9239h` |
| 纯侧向 w | `y −= 0.9239w`,`z += 0.3827w` |
| 沿手臂 d(正 = 手肘→手掌) | `x += d` |

### 4d. ⚠️ 教训:用户说"手机的宽/高",指的是**手机自己的轴**

第 7 轮把"向内移动 0.4 手机的宽"理解成"朝**世界** −X(身体中线)走",
用了 `(−1.512, 0, 0)` —— 游戏里手机**沿手臂从手掌滑向手肘**,是错的。
用户的真实意思是"沿手机自己的宽边,从右往左"。

> **先按 4a 算,别自作主张换成世界轴。**

### 4e. 欧拉角方向约定

`ItemTransform.apply` 用 `Quaternionf().rotationXYZ(rx, ry, rz)`,即 **R = Rx·Ry·Rz**(内旋 X→Y→Z)。

- 手机局部 **+Y = 顶部**(狐耳那端),**−Z = 背面**(后盖 `case_core.north` 在 z=7.75)。
- "向背面倒" = +Y 转向 −Z = 绕局部 +X 转**负角**。
- "俯视顺时针" = 绕 +Y 转**负角**
  (两种俯视约定 —— 地图式(上=北 −Z,右=东 +X)和面朝南俯视式 —— 都推出同一结论)。
- 因为原有旋转是纯 X 轴(ry=rz=0),叠加 Ry 可以直接由 Euler 表达:
  `Rx(−25)·Ry(−30)` ↔ `rotation: [-25,-30,0]`。
  **ry/rz 不为 0 时就不能这么做**,必须做四元数/矩阵相乘。
- **左手槽**:MC 在 `apply` 里对左手把 ry、rz 取反、translation.x 取反(rx 与 y,z 不变),
  所以左右槽**写成同一组值**即可,镜像由引擎负责。

---

## 5. 核心知识:女仆配饰的客户端同步

**症状**:服务端一切正常(日志里窗口 `active=true`、动画在演、能发消息),
但客户端渲染层报 `skip: maid has no phone tier` —— **动画在演,手上却没东西**。

**因果链**(全部在 TLM 源码里核对过):

1. `PhoneHelper.getPhoneTier(maid)` 读 `maid.getMaidBauble().containsItem(...)`。
2. `EntityMaid.maidBauble` 是普通字段,**不走 entity data 同步**;
   客户端那份只有收到 `SyncBaublePackage` 才有内容。
3. TLM 只同步 `syncClient(...) == true` 的饰品(`BaubleItemHandler.getSyncClientBauble:211`):
   玩家开始追踪女仆时全量同步(`MaidTrackEvent`),戴上/取下时增量同步(`BaubleContainer.BaubleSlot`)。
4. `IMaidBauble.syncClient` 的**默认实现返回 `false`**(`IMaidBauble.java:133-136`),
   而 `ItemMaidPhone` 当时没覆写。

**修法**(`ItemMaidPhone`):

```java
@Override
public boolean syncClient(EntityMaid maid, ItemStack baubleItem) {
    return true;
}
```

> **注意**:窗口状态走的是**另一条**通道(`PhoneChatSourcePacket`),
> 所以"窗口开着"不等于"客户端知道她有手机"。两条通道要分别确认。
> 另:配饰的体积/数量代价极小(一个 `Int2ObjectSortedMap<ItemStack>`),可以放心同步。

---

## 6. 核心知识:手机与 TLM 手持层的互斥

### 6.1 为什么必须二选一

手机(`PhoneInHandLayer`)和女仆真正拿的东西(TLM 的 `GeckoLayerMaidHeld`)用的是
**同一根 `RightHandLocator` 骨骼**(`GeckoLayerMaidHeld.java:43-45, 86-88`),
同时渲染必然穿模。

### 6.2 让位方向的选择

**第 3 轮的做法**(被推翻):"主手非空 → 手机层跳过"。
后果:女仆只要拿着工具/武器就永远看不到手机 —— 而她在战斗中恰恰最容易错过消息。

**最终做法**:**"窗口开着 → 手持物让位给手机"**。

| 文件 | 做法 |
|---|---|
| `PhoneInHandLayer` | 删掉"主手非空就跳过"的早退;新增 `public static boolean isUsingPhone(Entity)` |
| `GeckoLayerMaidHeldMixin` | `@Inject` 到 `GeckoLayerMaidHeld.renderArmWithItem` 的 HEAD,`arm == RIGHT && isUsingPhone(...)` 时 `ci.cancel()` |
| `huhutalk.mixins.json` | `client` 列表加 `"GeckoLayerMaidHeldMixin"` |

要点:

- **只压右手**(`arm == RIGHT`),副手/左手的东西照常显示。
- `renderArmWithItem` 内部还会遍历 `extraRightHandBones()` 再画一遍同一件物品;
  取消整个方法正好一次解决。
- **判据与手机层共用** `isUsingPhone`(窗口开着 + 客户端知道她戴着手机),
  所以不会出现"手持物藏了、手机没画"的空手帧。窗口一关条件当帧变假,手持物立刻恢复。
- 副作用(已知并接受):窗口开着的那段时间,女仆右手上的物品在**客户端**看不见
  (物品实际还在)。如需收紧(例如只在 raise/hold 段隐藏),改 `isUsingPhone` 即可。

### 6.3 ⚠️ 教训:项目里**从来没有**"隐藏主手物品"的代码

用户报"这个功能掉了"时,**先确认它是否真的存在过**。排查覆盖了:

- Java 里 `getMainHandItem` 只出现在那一条早退里;
- 三段动画只有 `Head`/`RightArm`/`RightForeArm` 的 rotation/position,**没有 scale=0 之类的显示开关**;
- 6 个 mixin 没有一个碰手持渲染;
- 动画触发条件只看 `PhoneChatSourceCache.isActive`,不看手上拿什么。

**git 历史是决定性证据**:`73174f6`("动画修好了,女仆手上没手机")那个版本里,
同样的 `if (!maid.getMainHandItem().isEmpty()) return;` 一字不差地躺着;
而 git log 里连着三条提交信息写着「**去掉主手**」
(`8837589`/`6980260`/`381e895`)—— 那是**在游戏里手动把主手物品拿走**,不是代码行为。
所以"以前能用"是错觉。

> 提交信息里的人工作业(手动清空主手)会被后人误读成功能。**排查"功能丢了"之前,先证明它存在过。**

### 6.4 ⚠️ 教训:mixin 签名是**运行时**校验的

第 9 轮第一次写 mixin 时签名写错了 —— 漏了第 3 个参数 `ILocationModel`,
还把泛型 `T` 写成了 `LivingEntity`。**`build_project` 通过并不代表注入点对**,
编译只检查 Java 语法,Mixin 在**启动时**才按描述符匹配。

目标方法的真实签名(**别漏第 3 个参数,别把 `T` 写成 `LivingEntity`**):

```java
protected void renderArmWithItem(T livingEntity, ItemStack itemStack, ILocationModel geoModel,
        ItemDisplayContext displayContext, HumanoidArm arm, PoseStack poseStack,
        MultiBufferSource bufferSource, int light)
```

泛型 `T extends Mob` 在字节码里**擦除成 `Mob`**,所以处理器第一个参数必须是 `Mob`。
改 mixin 后**必须重启游戏**看 `latest.log` 里有没有注入失败。

---

## 7. 方法论:这些坑是怎么被找到的

| 做法 | 为什么有效 |
|---|---|
| **先看日志里"哪一条早退"** | 渲染层埋了 `diag()`,每条早退路径一句。第 4 轮靠 `skip: maid has no phone tier` 一眼定位 |
| **诊断行要 dump 出上下文** | 把"配饰栏内容"打进日志,就能区分"栏是空的"和"同步了但物品不对" |
| **别手推矩阵,用三次探测** | 第 6 轮手推转置算错、把 z 符号搞反过。**探测法**:显示模式 → `rotation` 设 `[0,0,0]` → `translation` 依次给 `(16,0,0)/(0,16,0)/(0,0,16)` → 每次读 `display_base` 的世界包围盒中心差,三次结果就是 M 的三列 |
| **用"已知可用"的资源做参照** | TLM 的 `camera_in_hand.json` 是能用的三维手持模型 —— 但**必须同时比对几何重心**(§3.3) |
| **git log 当证据** | §6.3 |
| **"功能丢了"先证明它存在过** | §6.3 |

---

## 8. Blockbench 操作坑

- **`Blockbench.writeFile` 返回 `undefined`(异步)** —— 别用返回值判断成功,写完用
  `certutil -hashfile` 核对 sha256。
- **用 `drawImage` 画到临时 canvas 再 `toDataURL` 会拿到空白图**
  (`t.img` 是 `file://` 的 `HTMLImageElement`,那时还没解码完)。
  直接用 `Texture.all[0].canvas`。
- **`risky_eval` 的代码里不能出现 `//` 或 `/* */` 注释** —— 插件会直接拒绝。
- **`face.texture` 要写贴图 UUID 字符串**,不是索引。
- **java_block 编码器不写 `item/` 路径**:它只写 `huhutalk:phone`,必须手工改成
  `huhutalk:item/phone_3d`(**改了贴图就别用 BB 导出覆盖模型 JSON**,见下)。
- **`Project.display_settings` 里必须放 `new DisplaySlot(slot)` 实例**,放普通对象不会导出 `display`。
- **`box_uv` 在这套插件路径下不会自动生成 face uv**,要自己按 cross layout 算好写进 `faces[k].uv`。
- **`create_project` 会切走当前工程** —— 动手前先确认 `Project.saved`。
- **截图不可靠**:`set_camera_angle` / `capture_screenshot` 经常回传旧帧或空帧,
  同一张图会以相同 sha256 重复返回。可靠做法是**先弄脏场景**(`Canvas.updateAll()` 或改贴图)再截。
  从 −Z 方向看背面基本永远拿不到帧。
- **offscreen view 不渲染显示模式的预览** —— 量偏移要用 `view: "active"`,
  或者直接读 `display_base.matrixWorld`。
- **`set_camera_angle` 的 `zoom` 对透视相机不一定生效** —— 量完之后记得把用户的视口缩放还原。

**只换贴图时的正确姿势**(第 5 轮实测:BB 的 `elements` 与出货 JSON 完全一致,`elementDiffs=0`):

- 贴图:`risky_eval` 里 `Texture.all[0].canvas.toDataURL('image/png')` → `Blockbench.writeFile(...)`;
- 模型:**只在真要改几何时才动**,并且手工修 `textures` 路径。
  用 BB 导出覆盖会让 `display` 退回 BB 里那份(可能过期的)值。

> `display` 的值记得**在 BB 的 `Project.display_settings` 里同步一份**,
> 否则哪天从 BB 重新导出会把调好的 display 覆盖掉。

---

## 9. 环境 / 工具

- **`pwsh` 工具不可用**(宿主 ACL 故障)。命令行走 **IDEA MCP 终端**
  (`mcp__IDEA__execute_terminal_command`)。
- **IDEA 终端跑的是 Windows PowerShell 5.1**:
  - 不支持 `&&` / `||`,要改用 `;`;
  - `del /q` 会被解析成 `Remove-Item`,用 `Remove-Item -Force` 或 `executeInShell: false` 直接跑单个程序;
  - 需要管道的命令用 `powershell -NoProfile -Command "..."`(**注意 `$` 会被外层吞掉**,
    复杂脚本先写成 `.ps1` 再 `-File` 执行)。
- 日志优先看 **`run/logs/latest.log`**(未压缩)。归档的都是 `.gz`,用
  `System.IO.Compression.GZipStream` 解最新一份。
- IDEA MCP 可用:`get_file_problems`、`lint_files`、`build_project`、`execute_terminal_command`。
- **日志诊断代码仍未清理**:`PhoneInHandLayer` 的 `LOGGER`/`lastDiag`/`diag()`,
  以及 `PhoneAnimation` 里的 `PHASE`/`DIAGNOSED`/`CALL_COUNT` 等一组诊断。
  功能已验收,随时可删 —— **但不要删早退逻辑本身**。

---

## 10. 附:最终参考值速查

```
模型        phone_3d.json   16 element，bbox x6.6-9.4 y5.1-10.53 z7.57-8.25
贴图        phone_3d.png    128×128，UV 空间 0~16（1 UV = 8 px）
渲染尺寸    几何 × 1.35     = 3.78 × 7.33 × 0.92 单位
重心        (8, 7.815, 7.91)
display 旋转原点 恒为 (8, 8, 8)
thirdperson rotation [-25,-30,0]   translation [-1.31,1.71,0.25]   scale 1.35
R_disp      Rx(-25)·Ry(-30)
            宽 → (+0.866025, +0.211309, +0.453154)
            高 → ( 0,        +0.906308, -0.422618)
            厚 → (-0.5,      +0.366001, +0.784886)
M(世界映射) Rx(67.5°)
握点公式    translation = -R · S · (G - 8)      // G = 手机重心
沿手臂正向  translation.x += d                  // 正 = 手肘→手掌
```

---

## 11. 附:青春版电话手表(`phone_watch`)的 3D 化

青春版手表原本是 32×32 平面图标,现已换成独立 3D 模型。它与手机**共用同一条挂手渲染链路**
(`PhoneInHandLayer` → `ItemInHandRenderer`)和同一个物品类 `ItemMaidPhone`,
但几何 / 贴图 / 模型 JSON 是另一套。

### 11.1 交付物

| 文件 | 说明 |
|---|---|
| `assets/huhutalk/models/item/phone_watch_3d.json` | 20 element / 120 面,自带全套 `display` |
| `assets/huhutalk/textures/item/phone_watch_3d.png` | 128×128 RGBA,sha256 `eda108ce…` |
| `assets/huhutalk/models/item/phone_watch.json` | `{"parent": "huhutalk:item/phone_watch_3d"}` |
| `D:\Moddevelop\build_watch_all.js` | **唯一权威生成器**:一次产出贴图 PNG + 模组模型 JSON + `.bbmodel` |
| `tools/check_watch_faces.js` | 120 面 UV/alpha 校验(可重跑;放 `tools/` 而不是 `build/`,因为 `gradlew clean` 会把 `build/` 清掉) |

几何:机身 3.0×3.0×0.4 的十字主体 + 4 个 45° 钻石切角 = 八边形;双狐耳 45° 斜置;
上下表带各两节;背面传感器台 + 像素狐尾贴花。
bbox `x 6.25–9.75 / y 4.3–11.9 / z 7.58–8.19`,y 跨度 **7.6 单位**。

### 11.2 ⚠️ 坑一:`135°` 让**整个模型**解析失败,表现为"一个黑方块"

```
Failed to load model huhutalk:models/item/phone_watch_3d.json
com.google.gson.JsonParseException: Invalid rotation 135.0 found, only -45/-22.5/0/22.5/45 allowed
	at BlockElement$Deserializer.getAngle(BlockElement.java:115)
```

`getAngle` 只放行 `±45 / ±22.5 / 0`。Blockbench 编辑器允许 135°,原版解析器不允许 ——
**§8.1 早就写了这条,但生成器硬编码了 135**。只要有一个角度非法,整个 `elements` 解析失败,
烘焙退化成 missing model,渲染成那个占位立方体(观感 = 一整块黑方块)。

后面紧跟着的 `Unable to load model: … java.io.FileNotFoundException` 是**二次报错**,极具误导性:
文件其实被找到并读进去了(否则走不到 `getAngle`)。**排查时别只 grep 含 `model` 的行** ——
`Invalid rotation …` 这条消息里一个 "model"/"phone" 字样都没有,会被过滤掉,于是一整轮都在追不存在的路径问题。

修法:`135 → 45`、`-135 → -45`。**几何等价**:那两个切角 cube 的 xy 截面是 0.5×0.5 正方形,
旋转轴 Z、旋转原点恰好是自身中心;正方形绕中心转 90° 映射到自身,而 135 = 45 + 90。

### 11.3 ⚠️ 坑二:生成器写死 `axis: 'z'`,吞掉了表带的 22.5° X 倾斜

```js
// 旧代码 —— defs[].rot 是 Blockbench 风格的三元组 [x,y,z],这里却只取 rot[2] 且写死 Z 轴
if (d.rot) el.rotation = { angle: d.rot[2], axis: 'z', origin: d.org };
```

于是 `strap_top_tip`(`rot [22.5,0,0]`)与 `strap_buckle_tip`(`rot [-22.5,0,0]`)在模组 JSON 里
变成了 `"angle": 0` —— **实机表带尾节是平的,而 Blockbench 源里是 22.5° 弧**。
`.bbmodel` 走的是 `el.rotation = d.rot`,所以只有模组 JSON 错;正因为两边不一致,
这个 bug 一直没被注意到。修法见 §11.5。

### 11.4 `display` 与"掉在地上特别大"

`display` 沿用手机的范式,但 **`ground` 必须单独调小**(手机几何高 5.43 单位,手表 7.6 单位):

| 上下文 | scale | 实机渲染高度 |
|---|---|---|
| `thirdperson_*` | 1.35 | 0.64 方块(手持,故意放大) |
| `ground` | **0.6** | **0.29 方块** |
| `fixed` / `head` / `gui` | 1.8 | 0.86 方块 |

> **渲染高度(方块) = 模型 y 跨度(单位) ÷ 16 × scale。**
> 手表跨 7.6 单位 ⇒ scale 1.8 时是 **0.855 方块**,掉在地上比一整块方块还高,看着像块砖头。
> 手机跨 5.43 单位 ⇒ 同样 1.8 时是 0.61 方块,所以"手机没事、手表特别大"。
> **`ground`(掉落实体)和 `fixed`(物品展示框)是两个独立上下文,别一起给同一个数。**
> 这个数纯资源,改完 **F3+T** 就能看,不用重启、不用重编。

### 11.5 生成器现在的守卫(别再绕过)

```js
function rotSpec(rot) {                       // 三元组 → {axis, angle}
  if (!rot) return null;
  for (let i = 0; i < 3; i++) if (rot[i]) return { axis: ['x', 'y', 'z'][i], angle: rot[i] };
  return null;
}
const LEGAL_ANGLES = new Set([-45, -22.5, 0, 22.5, 45]);
defs.forEach(d => {
  if (!d.rot) return;
  if (d.rot.filter(a => a).length > 1) throw new Error('...must have exactly one non-zero axis...');
  d.rot.forEach(a => { if (!LEGAL_ANGLES.has(a)) throw new Error('...illegal java_block rotation angle...'); });
});
```

**非法角度现在会在生成阶段直接抛错**,不会再产出"装不上、实机变黑方块"的模型。

### 11.6 120 面 UV/alpha 校验

```
node tools/check_watch_faces.js
```

结论:20 元素 / 120 面 / **非法旋转角 0**;120 面用到的 12 个图集区域里 **11 个 `minA=255`**。
唯一带透明的是狐尾贴花区域 `[32,88,60,116]`,只被 **1 个面**用到
(`back_sensor_plate` 的 `north` 面)—— 那是**故意的透明底贴花**:
非 BlockItem 的物品走 `ItemBlockRenderTypes.getRenderType()` → `Sheets.translucentItemSheet()`,
`alpha=0` 的含义是"透出后面的表壳",**不会渲染成黑色**。
所以"贴图透明导致黑块"这个假设对这块表不成立,不需要拿醒目底色去试。

### 11.7 物品属性:配饰 = 不堆叠 + 附魔光效

```java
// HuhuTalkItems —— 配饰本体,各自只有一件
new Item.Properties().stacksTo(1)
```

```java
// ItemMaidPhone —— 附魔光效恒开
@Override public boolean isFoil(@NotNull ItemStack stack) { return true; }
```

**为什么覆写 `isFoil` 而不是用 `DataComponents.ENCHANTMENT_GLINT_OVERRIDE`:**
`ItemStack.hasFoil()` 的判定是
`get(GLINT_OVERRIDE) != null ? 它 : item.isFoil(stack)`。
覆写后面这半个分支**不往物品栈上写组件**,存档里已存在的旧栈也同样发光,而且不会让
`getRarity()` 升级(那是 `isEnchanted()`,看的是真实的 ENCHANTMENTS 组件)。

三条渲染通路都读同一个 `hasFoil()`,因此自动全覆盖:

| 通路 | 位置 |
|---|---|
| 女仆手上 | `PhoneInHandLayer` → 原版 `ItemInHandRenderer.renderItem` |
| 玩家第一人称离屏屏幕 | `ProPhoneHandRenderer#renderPhoneBody`,自己拼 `getFoilBufferDirect(..., foil)` |
| 地面掉落物 / GUI / 展示框 | 原版 `ItemRenderer` |

> 相机模式的"透明取景"会临时换成 `PHONE_CAMERA` 栈,那**不是** `ItemMaidPhone`,
> 所以取景框本身不会发光 —— 如果实机发现取景时也在闪,说明换栈那条路径没走到。
