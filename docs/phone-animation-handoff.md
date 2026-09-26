# HuhuTalk 女仆「使用手机」动画 — 任务状态交接

> 交接时间点：动画已接入模组、能在游戏内播放；姿态方向已修好（实机确认）。
> **「`_raise` 反复重播」根因已定位并修复**：TLM `AnimationController` 在"链式 builder 播完自动接下一个"时
> **不会重新绑定骨骼关键帧队列**，导致 `hold` 的关键帧从未被求值、每 1.9s 用 `raise` 的关键帧把手臂放回静止再抬起。
> 已改为"两条单动画 builder + 定时交接"。详见「零、症状 B 根因」。
> 新对话请先读「零」与「四、下一步该做什么」。

---

## 零、症状 B「抬手反复重播」根因（已修复，待实机确认）

### 0.1 症状回顾与日志证据

用户观感：女仆抬起手 → 顿一会 → **再抬一次**，周期约 1.5~2s；BB 里 hold 抖动幅度可见，
游戏里**完全看不到抖动**；`lower` 在最后能正常播放。

日志（第 5 次实机诊断，`姿态` 行每 60 次调用采样一次）：

```
[Client] 姿态: ... current=huhutalk_phone_raise@8.0  RightArm.x=21.14 RightForeArm.x=55.50 ForeArm.posY=0.00
[Client] 动画切换: huhutalk_phone_raise -> huhutalk_phone_hold state=TRANSITIONING
[Client] 姿态: ... current=huhutalk_phone_hold@38.0  RightArm.x=22.20 RightForeArm.x=58.28 ForeArm.posY=0.00
[Client] 姿态: ... current=huhutalk_phone_hold@38.0  RightArm.x=25.00 RightForeArm.x=65.64 ForeArm.posY=0.00
 ... 大多数采样都是 25.00 / 65.64，但每隔几条约 1.7s 出现一次明显偏低的采样：
[Client] 姿态: ... current=huhutalk_phone_hold@38.0  RightArm.x= 9.02 RightForeArm.x=23.68 ForeArm.posY=0.00
[Client] 姿态: ... current=huhutalk_phone_hold@38.0  RightArm.x= 0.92 RightForeArm.x= 2.42 ForeArm.posY=0.00
[Client] 姿态: ... current=huhutalk_phone_hold@38.0  RightArm.x=14.52 RightForeArm.x=38.13 ForeArm.posY=0.00
```

两条决定性指纹：
1. **`RightArm.x` 与 `RightForeArm.x` 严格成比例**（9.02/25 = 0.361、23.68/65.64 = 0.361；0.92/25 = 0.037、2.42/65.64 = 0.037）。
   即两个骨骼被**同一个"从当前姿态向 0（静止）插值"的因子**缩放 —— 这就是 `raise` 关键帧的进度值。
2. **`ForeArm.posY` 永远是 0.00**。`raise` 的 position 关键帧 y 全是 0，而修正后的 `hold` 的 y 是 ±0.5。
   所以驱动骨骼的**根本不是 hold 的关键帧列表**。

且 `builder 切换`/`动画切换` 日志里**只有一次** raise→hold，没有第二条 raise —— 说明
`currentAnimation` 确实是 hold，但**骨骼队列绑的还是 raise 的关键帧**。

### 0.2 机制（TLM 的 `AnimationController` 缺陷）

`AnimationController.processCurrentAnimation()` 里，一次性动画播完时：

```java
Pair<ILoopType, Animation> peek = this.animationQueue.peek();   // 只看，不 poll
this.animationState = AnimationState.TRANSITIONING;
this.shouldResetTick = true;
this.currentAnimation = peek.getSecond();                       // 换成 hold
```

它**没有 poll 掉队列头部**，也**没有调用 `switchAnimation()`**。
而 `switchAnimation()` 才是做这件事的地方：

```java
private void switchAnimation() {
    activeBoneAnimationQueues.clear();
    for (BoneAnimation animation : this.currentAnimation.boneAnimations) {
        BoneAnimationQueue queue = boneAnimationQueues.get(animation.boneName);
        queue.animation = animation;     // ← "骨骼 → 关键帧列表"的唯一绑定点
        ...
    }
}
```

它只在 `process()` 的 TRANSITIONING 分支里被调用，触发条件是 `if (resetTick || this.isJustStarting)`。
而 `resetTick` 的来源被这一步吃掉了：

```java
double adjustedTick = adjustTick(tick);        // ← 这里已把 shouldResetTick 消费成 false
...
boolean resetTick = this.shouldResetTick;      // ← 第 246 行，恒为 false
```

只有 `setAnimation()` 在 **predicate 内**（即 adjustTick 之后、第 246 行之前）置位 `shouldResetTick` 的那一帧，
`resetTick` 才为 true —— 也就是说**只有"换 builder"才能触发 `switchAnimation()`**。

于是链式 builder（`playOnce(raise).loop(hold)`）的后果：
- `currentAnimation` = hold，但 `activeBoneAnimationQueues[*].animation` 仍是 **raise**；
- 每次 hold 循环（38 tick = 1.9s）把 tick 归零后，求值的是 `raise` 的第一帧 = **静止姿态**（`raise@0`），
  随后 8 tick 内沿 `raise` 的第一段抬起 → **每隔 1.9s 重播一次抬手**；
- `hold` 的关键帧**从未被求值** → 抖动永远看不见。

**为什么 TLM 一直没被发现**：TLM 自身所有调用点都是单条 builder
（`new AnimationBuilder().addAnimation(name, loopType)`，见 `AnimationManager:62/68`、
`GeckoChairEntity:69`、`GunGeckoAnimation:165` 等），从未用到多条目链式 builder。
我是全生态里唯一一个这么用的。

### 0.3 修复

`PhoneAnimation` 改为**两条单动画 builder + 定时交接**：

```java
RAISE = new AnimationBuilder().playOnce(ANIM_RAISE);   // 单条
HOLD  = new AnimationBuilder().loop(ANIM_HOLD);        // 单条
LOWER = new AnimationBuilder().playOnce(ANIM_LOWER);   // 单条（本来就这样）
```

按 stableId 维护阶段 `PHASE = {RAISE, HOLD}`：
- 窗口刚开启（`PHASE == null`）→ `markNeedsReload()` + 提交 `RAISE`，记录起始 `event.getAnimationTick()`；
- RAISE 阶段中，`nowTick - start >= RAISE_HANDOFF_TICKS(9.0)` 或状态已 `STOPPED` → 提交 `HOLD`
  （**不同 builder ⇒ 走重建分支 ⇒ 下一帧正常 poll + `switchAnimation()` ⇒ 关键帧正确绑定**）；
- HOLD 阶段 → 幂等续提交 `HOLD`（同 builder，不会重启；hold 的 loop 由 TLM 自己维持，不需要重绑）；
- 窗口关闭 → 提交 `LOWER`，播完 `STOPPED` → 返回 `STOP`。

**为什么是 9.0 tick 而不是等 raise 自己播完**：`setAnimation` 后要先花 `transitionLengthTicks = 2` tick 过渡，
所以 raise 自己的 tick 0 在提交后第 2 tick、播完在第 10 tick。取 9 表示"在 raise 结束那一帧之前"换 builder：
predicate 在 `processCurrentAnimation` 之前执行，因此那一帧不会走到 raise 的结束分支，
也就不会出现"骨骼队列为空 → 被 `AnimationProcessor` 的重置逻辑（`resetTickLength` 默认 1 tick）
瞬间拉回静止姿态"的一帧闪烁。（`STOPPED` 兜底分支正是为这种异常情况保留的。）

**顺带**：`PhoneAnimation.reset()` 会在进/退世界时清空所有 static 状态
（`ClientCacheEvents.onPlayerLogin/onPlayerLogout`），修掉了旧文档「遗留问题 1」
（static 状态跨世界残留导致下一局可能跳过 raise）。

### 0.4 hold 抖动幅度已放大（现在才会真正生效）

原来 ±1.25°（64.39 ↔ 66.89）和 position ±0.12 单位本来就不可见 —— 而更关键的是它**从未被求值**。
现在两个问题都修了，幅度也放大到可见：
```
RightForeArm.rotation x : -65.638 基准，抖动 -70.5 ↔ -60.5（±5°）
RightForeArm.position   : y ±0.5、z -0.75 ↔ -0.15（手上下浮动约 3 厘米）
```

### 0.5 用户提出的两个怀疑的判定

- **「分割消息导致 raise 重播」：不成立。** 分割是纯客户端显示
  （`MessageSplitter` + `ChatPage.splitSchedule` 本地逐条落屏），不发包、不碰 `PhoneChatSourceController`；
  `open()` 只在 `MaidChatHandler:71/214` 各调一次（一条消息一次）。
  实测日志也证实：单条消息、窗口全程只开一次，重播照样发生 ⇒ 与消息数/分割无关。
- **「重叠消息耦合」：不是本次病灶**（用户单条消息也复现），但它**确实是一个真实隐患**：
  `open()` 在窗口已开时静默刷新、`C2SPhoneTypingDonePacket` 只带 stableId 不区分回复，
  多条消息重叠时开/关窗会错配。已加日志，后续可改为"待显示回复计数"。
  详见「三、结论 → 遗留小问题」。
- **「hold 没被正确播放」：完全正确**，就是 0.2 的 TLM 绑定缺陷。

### 0.6 本轮诊断日志（保留，便于复验）

| 日志 | 位置 | 判定 |
|---|---|---|
| `窗口状态: active X -> Y tick=..` | `PhoneAnimation` | 窗口边沿 |
| `动画切换: X -> Y state=..` | `PhoneAnimation` | 每次动画名变化 |
| `builder 切换(第N次): … 提交 raise / raise→hold 交接` | `PhoneAnimation` | **正常每个窗口恰好 2 次**（raise、hold；lower 走 `动画切换` 日志）。多于 2 次即异常重播 |
| `姿态: state=.. current=.. RightArm.x=.. RightForeArm.x=.. ForeArm.posY=..` | `PhoneAnimation` | **决定性**：读骨骼最终角度（度）与位移。修好后应看到 `RightForeArm.x` 在 60.5↔70.5 抖动、`ForeArm.posY` 在 -0.5↔+0.5 变化、且**不再出现接近 0 的采样** |
| `收到窗口包: active=..` | `ClientPayloadSetup` | 客户端收到的广播 |
| `open 调用: wasOpen=..` | `PhoneChatSourceController` | 每次 open（含静默刷新） |
| `收到打字播完回执 / 关闭来源窗口` | `C2SPhoneTypingDonePacket` / Controller | 关窗时间轴 |

---

## 一、任务目标

给 TLM（车万女仆）的酒狐系列女仆加一个「掏手机打字」的**附加动画**：
- 只在**通过狐狐手机发送的消息**触发的「思考期 + 逐条打字显示期」播放
- **不打断基础动作**（行走 / 游泳 / 坐姿 / 骑马等）
- 动画随本模组 jar 分发，其他主机上装了本模组也能看到

---

## 二、已完成的确定成果（这些是对的，无需重做）

### 1. 三段动画资产

`src/main/resources/assets/huhutalk/animation/huhutalk_phone.animation.json`
（**合并单文件，含三个动画**；三个空壳 `_raise/_hold/_lower.animation.json` 已删除）

| 动画名 | 时长 | loop | 驱动骨骼 |
|---|---|---|---|
| `huhutalk_phone_raise` | 0.4s | once | `Head` / `RightArm` / `RightForeArm` |
| `huhutalk_phone_hold` | 1.9s | loop | 同上（Head/RightArm 为常量，仅小臂抖动） |
| `huhutalk_phone_lower` | 0.4s | once | 同上 |

**姿态数值（本次已修正；数值为"相对模型静置姿态的增量"）**：
```
Head.rotation         : 0,0,0    →  +26,+10,0        （低头 26°、朝自己右手边 10°）
RightArm.rotation     : 0,0,0    →  -25,0,-14
RightForeArm.rotation : 0,0,0    →  -65.638,-7.181,-16.924
RightForeArm 抖动     : -70.5 ↔ -60.5（"三快一停"节奏）+ position y ±0.5 / z -0.75↔-0.15
```
> ⚠️ 旧的 `Head.x=-26 / RightArm.x=+25 / RightForeArm.x=+65.638` 是**符号错**的，见「三、结论 A」。

### 2. Blockbench 工程
```
D:\mcmod\.minecraft\versions\pianchang\tlm_custom_pack\
  touhou_little_maid-1.0.0\assets\geckolib\models\entity\winefox.bbmodel
```
内含 `animation.huhutalk_phone_raise/hold/lower` 三个动画。
**注意：该工程里的数值仍是旧（符号错）的版本，尚未同步本次修正。**

### 3. 架构（已验证工作）

```
服务端 MaidChatHandler.handleChat/handleImageChat   ← 手机聊天唯一入口
   → PhoneChatSourceController.open(maid) 开窗 + 广播 active=true
客户端 PhoneChatSourceCache 收到 → PhoneAnimation.predicate 播 raise→hold
   → 客户端逐条打字显示完毕 → C2SPhoneTypingDonePacket 回执
服务端关窗 + 广播 active=false → 客户端播 lower
```

**「只有手机消息唤起」的保证**：窗口只在 `MaidChatHandler` 的两个入口开启，
其他通道（对话引导、离线消息播报、TLM 自身聊天）不经过这里，所以动画不会误触发。

**窗口兜底**：`PhoneChatSourceController.WINDOW_TICKS = 30s`，超时强制关窗。

### 4. 动画注入方式（**已实测生效**）

**不用** TLM 官方扩展点 `DefaultGeckoAnimationEvent`（实测从未触发）。
改为 **`GeckoModelLoaderMixin`** 注入 `GeckoModelLoader.registerMaidAnimations` 的 HEAD：
```java
@Inject(method = "registerMaidAnimations", at = @At("HEAD"))
private static void huhutalk$injectPhoneAnimation(ResourceLocation id, AnimationFile animationFile, CallbackInfo ci) {
    PhoneAnimationHandler.ensureAnimations(animationFile);
}
```
原理：`registerMaidAnimations(id, animationFile)` 是 TLM 给**每个** gecko 模型表补齐默认动画的必经之路
（`DEFAULT_MAID_ANIMATION_FILE.animations().forEach(...)` 补缺）。
我们的动画直接补进这个 per-model 的 `animationFile`，名字不冲突，因此对所有女仆模型生效。

**日志证据**：`Could not load animation: huhutalk_phone_*` 警告完全消失；
`run/logs/latest.log` 里 `已解析手机动画文件 ... -> [hold, lower, raise]` + 每个模型一次注入成功（约 40 行，
是"每个模型一张表"的正常结果，不是重复注入）。

### 5. ChatPage 逐条打字显示（已修，逻辑复核通过）

- 改为**绝对时间排程**（`splitSchedule` 存每条短句的绝对落屏时刻），`advanceSplitting()` 幂等
- 客户端 tick 也驱动 `advanceSplitting()`（`ClientCacheEvents.onClientTick`）
- 新增 `C2SPhoneTypingDonePacket` 回执，让窗口时长 = **真实打字时长**
- 删除了 `HuhuTalkChatCallback.onSuccess` 里的 `closePhoneSource()`；`onFailure` 仍立即关窗
- **关键是 `PhoneOverlay.getCurrentPage()` 与 `isVisible()` 解耦**：收起手机时页面仍在 `pageStack` 顶部，
  所以 tick 驱动依然推进 → 原 bug（收起即冻结、重开才倾倒）确实被修掉
- 实机日志佐证：20:03:47 开窗 → 20:04:00 关窗，共 13.2s，与"逐条打字"的真实时长量级一致

---

## 三、结论（本次排查的确定结果）

### ✅ 症状 B（`_raise` 反复重播 / 进不了 hold）——**根因已定位并修复，见「零」**

分两层，前一版只修到第一层：

1. **第一层（已修，实机确认）**：`PhoneAnimation` 把状态放在 predicate 实例字段里，
   随 `GeckoMaidEntity`（NeoForge attachment）重建而丢失 → 每帧判定"窗口刚开启" →
   每帧 `markNeedsReload()` → raise 被无限重启。改成 static 状态后，
   重播频率从"每帧"降到"每 1.9 秒一次"。
2. **第二层（本轮定位，即"每 1.9 秒一次"的真身）**：
   链式 builder `playOnce(raise).loop(hold)` 触发 TLM `AnimationController` 的缺陷——
   raise 播完时只 `peek()` 换 `currentAnimation`，**不 `poll()`、不 `switchAnimation()`**，
   于是骨骼队列永远绑着 `raise` 的关键帧：`hold` 的关键帧从未被求值（抖动看不见），
   而每 1.9 秒 hold 循环归零后重新求值 `raise@0`（静止姿态）→ **手臂掉下去再抬起来**。
   完整机制、日志证据、修复方案见「零」。

（`run/logs/latest.log` 里 `current=huhutalk_phone_raise@8.0` → `hold@38.0` 只是说明
`currentAnimation` 这个名字换成了 hold，**并不能说明 hold 的关键帧在被求值** ——
第 5 次诊断的 `姿态` 行才揭穿了这一点。）

### ✅ 症状 A（手臂朝**身后**伸）——**根因 = X/Y 旋转符号全错**，已修，实机确认

**先否掉旧线索 1（度/弧度混用）**：`RotationValue.processValue()` 明确做了度→弧度转换，
所以动画 JSON 里的度数不会被当成弧度。旧文档"25 度 ≈ 1433°"的担心不成立。

**真正的约定**（都在 TLM 源码里，可复核）：
- `geckolib3/core/molang/value/RotationValue.java`：`ret = toRadians(value)`，且 **X、Y 取负号，Z 不取**。
  每一个 rotation 通道都会走这里（`RawBoneKeyFrame.getValue(..., isRotation=true)`）。
- `geckolib3/geo/render/GeoBuilder.java:40`：`rotation.mul(-1, -1, 1)` —— 模型静置姿态用的是**同一个**翻转。
  所以「模型 JSON 的数值」与「动画 JSON 的数值」是同一套约定，可直接互相参考。
- 渲染端 `RenderUtils.rotateMatrixAroundBone` → `Quaternionf().rotateZYX(z, y, x)`，单位是**弧度**，右手系。
  结论：**render_x = -toRad(json_x)**；且 render_x>0 = 手臂向前抬 / 头后仰。
- 动画数值是**增量**：`AnimationProcessor` 里 `snapshot.rotationValueX = 值 + initialSnapshot.rotationValueX`。
  即"0,0,0 = 模型静置姿态"。

**用 TLM 自带动画校准（同一个 winefox 模型，游戏内表现是确定的：抬手臂到身前使用物品）**：
| 动画 | RightArm.rotation | RightForeArm.rotation | 动作 |
|---|---|---|---|
| `use_mainhand` | `[-45.07, -31.77, 9.3]` | `[-122.5, 0, 0]` | 抬手到脸前用物品 |
| `use_mainhand:eat` | 约 `-27 ~ -69` | 约 `-110 ~ -123.6` | 吃东西（手到嘴边） |
| `use_mainhand:spyglass` | `[-71.06-0.8*pitch, ...]` | `[-112.5, 0, 0]` | 举望远镜到眼睛 |
| `hold_mainhand:fishing` | LeftArm `[-56.73, ...]` | LeftForeArm `[-17.5,0,0]` | 持竿向前 |
| `sleep` | `[-40.9, ...]` | `[-120, 0, 0]` | 躺 |

→ **JSON 的 X 取负 = 手臂向前/向上**（`use_mainhand:spyglass` 的"手臂 x=-71 + 小臂 x=-112.5 举到眼前"
在几何上只有这一种解释）。而我们旧值写的是 **+25 / +65.638** → 手臂与手肘**向后**转，
正好就是"手臂朝身后伸"。

**头部同理**（两个独立证据）：
- `GeoReplacedEntityRenderer:189-191`：`data.headPitch = -xRot`（低头时 xRot>0 → 值为负），
  `GeckoMaidEntity.updateHead`：`head.setRotationX(headRot.x + toRad(data.headPitch))`。
  女仆的"看玩家"逻辑本身就靠这条路径，所以 **render_x<0 = 低头**。
  要低头 26° ⇒ render_x=-26 ⇒ **json_x = +26**。旧值 -26 → render +26 → 实际在**仰头** 26°。
- `data.netHeadYaw = -wrapDegrees(netHeadYaw)`，且 render_y>0 = 向左转（模型局部 +X 是女仆右侧，
  已由 `winefox.json` 里 `RightArm.pivot = [-3.25, ...]` 经 `-pivot.x` 变成渲染空间 +3.25 证实）。
  要朝右 10° ⇒ **json_y = +10**。旧值 -10 → 实际**朝左** 10°。

**附带两个小 bug（一并修了）**：
1. `_raise` 起始帧 / `_lower` 结束帧的 `RightArm.rotation` 写成了 `[0, 0, 18]`——
   18 是 `winefox.json` 里 `RightArm` 的**静置旋转**（`"rotation": [0,0,18]`，A 字站姿的手臂外张）。
   因为动画值是增量，写 18 等于 18+18=36°（手臂过度夹向身体）。已改为 `[0,0,0]`。
2. `_raise` 结束时的 position 与 `_hold` 起始不一致（`-0.5` vs `-0.6`、`-0.25` vs `-0.3`），已对齐。

**Z 通道未动**：Z 不被管线翻转，且修正 X/Y 后 `RightForeArm.z=-16.924`（小臂向内收）
与 `RightArm.z=-14`（增量）看起来是合理的握机姿态。若实机观感别扭，Z 是最该微调的通道。

### 🟡 遗留小问题（未修，建议知悉）

1. ~~**`PhoneAnimation` 的 static 状态没有世界切换重置**~~ —— **已修**：
   新增 `PhoneAnimation.reset()`，在 `ClientCacheEvents.onPlayerLogin/onPlayerLogout` 调用
   （与 `PhoneChatSourceCache.clear()` 同处），跨世界不再残留 stableId。
2. **`ClientCacheEvents.onClientTick` 只在 `getCurrentPage() instanceof ChatPage` 时推进 `advanceSplitting()`**。
   收起手机没问题（页面仍在栈顶），但玩家**切到别的页面**时推进会暂停 → 回执延后 → 手臂保持举起，
   最坏等 30s 兜底关窗。修法：改为遍历"所有持有未播完断句的 ChatPage"（或让 tick 驱动与页面栈无关）。
3. `PhoneChatSourceController.clearAll()` 只清 `OPEN`、**不广播 active=false**（靠客户端重登时的 `clear()` 兜住）。
4. `PhoneAnimationHandler.ensureAnimations` 每个模型打一行注入日志（约 40 行），属于诊断噪音。

---

## 四、下一步该做什么（建议顺序）

### 4.1 验证本次修复（只需一条消息）

跑一次 `runClient`，发**一条**消息，然后核对两件事：

1. **不再周期性重播**：手臂抬起后应一直保持，直到回复打完才放下；
   **不应**再出现"每 1.5~2 秒掉下去再抬起来"。
2. **hold 抖动可见**：抬起后手应在上下小幅点动（约 3 厘米，三快一停）。

日志核对（`grep -E "HuhuTalk|PhoneChatSource" run/logs/latest.log`）：
- `builder 切换` 应在**每个窗口恰好出现 2 次**：`窗口刚开启 → 提交 raise`、`raise→hold 交接`
  （lower 的切换看 `动画切换: hold -> huhutalk_phone_lower`）。
  多于 2 次 ⇒ 还有别的重播来源。
- `姿态` 行里 `RightForeArm.x` 应在 **60.5 ↔ 70.5** 抖动、`ForeArm.posY` 在 **-0.5 ↔ +0.5** 变化，
  且**不应再出现接近 0（如 0.92 / 2.42）的采样**。旧日志里那些接近 0 的值就是 raise 关键帧在驱动的铁证。

若仍有异常，把 `run/logs/latest.log` 的相关行发我。

### 4.2 已知的遗留隐患（不是本次病灶，确认观感后再处理）

- **多消息重叠时开/关窗会错配**：`open()` 在窗口已开时只静默刷新、
  `C2SPhoneTypingDonePacket` 只带 stableId 不区分回复，所以 reply1 打完的
  回执会关掉 reply2 还需要的窗口。修法：给窗口加"待显示回复计数"，
  或让回执带 reply token，只在计数归零时关窗。
- `ClientCacheEvents.onClientTick` 只在当前页是 `ChatPage` 时推进 `advanceSplitting()`；
  切到别的页面会暂停推进，最坏等 30s 兜底关窗。
- `PhoneChatSourceController.clearAll()` 只清 `OPEN`、不广播 `active=false`。
- `PhoneAnimationHandler.ensureAnimations` 每个模型打一行注入日志（约 40 行，诊断噪音）。

### 4.3 收尾（观感确认后）
1. 清理诊断代码（见「六、待清理的诊断代码」）。
2. 若觉得手臂"夹太紧/张太开"，只调 Z：`RightArm.z`（增量 -14，模型静置已是 +18）、`RightForeArm.z`（-16.924）。
3. 觉得抖动太快/太慢：`_hold` 的 keyframe 时间戳（0.15/0.3/0.45 + 0.35 停顿）就是节奏，改时间即可；
   幅度改 `-70.5 ↔ -60.5` 和 position 的 ±0.5。
4. 把数值同步回 `winefox.bbmodel`（可选，工程文件不参与打包）。

---

## 五、文件清单（本次任务新增/修改）

### 新增
| 文件 | 作用 |
|---|---|
| `src/main/resources/assets/huhutalk/animation/huhutalk_phone.animation.json` | 三段动画（**核心资产**） |
| `src/main/java/com/huhutalk/client/PhoneAnimationHandler.java` | 解析并注入我们的动画到动画表 |
| `src/main/java/com/huhutalk/client/PhoneAnimationRegistration.java` | 动画名常量 |
| `src/main/java/com/huhutalk/client/PhoneChatSourceCache.java` | 客户端窗口缓存（按 stableId） |
| `src/main/java/com/huhutalk/client/animation/PhoneAnimation.java` | 状态机 predicate |
| `src/main/java/com/huhutalk/core/chat/PhoneChatSourceController.java` | 服务端窗口（open/close/tick 兜底） |
| `src/main/java/com/huhutalk/mixin/GeckoMaidEntityMixin.java` | 给 GeckoMaidEntity 注入独立控制器 |
| `src/main/java/com/huhutalk/mixin/GeckoModelLoaderMixin.java` | 把动画注入每个模型的动画表 |
| `src/main/java/com/huhutalk/network/S2CPhoneChatSourcePacket.java` | 服务端→客户端窗口状态 |
| `src/main/java/com/huhutalk/network/C2SPhoneTypingDonePacket.java` | 客户端→服务端"打字播完"回执 |

### 修改
| 文件 | 改动 |
|---|---|
| `huhutalk.mixins.json` | client 区加入 `GeckoMaidEntityMixin`、`GeckoModelLoaderMixin` |
| `HuhuTalk.java` | 注册 tick/停服/登出监听 + `C2SPhoneTypingDonePacket` 注册 |
| `core/chat/MaidChatHandler.java` | `handleChat` + `handleImageChat` 两处 `PhoneChatSourceController.open(maid)` |
| `core/chat/HuhuTalkChatCallback.java` | `onSuccess` **不再**关窗；`onFailure` 关窗 |
| `phone/pages/ChatPage.java` | 逐条显示改绝对时间排程 + 播完回执 + `advanceSplitting()` 转 public |
| `client/ClientCacheEvents.java` | 进世界清 `PhoneChatSourceCache`；tick 驱动 `advanceSplitting()`；进/退世界调 `PhoneAnimation.reset()` |
| `client/network/ClientPayloadSetup.java` | 处理 `S2CPhoneChatSourcePacket`（`enqueueWork` → 主线程，无竞态） |

---

## 六、待清理的诊断代码（功能确认后删除）

1. `PhoneAnimation`：`DIAGNOSED`、`CALL_COUNT`、`LAST_ACTIVE`、`LAST_ANIM`、`SWITCH_COUNT`、
   `logSwitch()`、`currentName()`、`describeCurrent()`、`deg()` 及全部 `LOGGER.info`
   （**注意保留 `PHASE` / `RAISE_STARTED` / `reset()` 与两段式提交逻辑——那是修复本体，不是诊断**）
2. `PhoneAnimationHandler`：`merged` / `lastMergeSucceeded()` 及注入日志
3. `PhoneChatSourceController`：`open`/`close` 里的 `LOGGER.info`（注意 `open` 的日志已改成**每次**都打，
   清理时别把 `wasOpen` 逻辑一起删掉）
4. `C2SPhoneTypingDonePacket`：回执日志 + "找不到女仆"警告
5. `ClientPayloadSetup.handlePhoneChatSource`：`收到窗口包` 日志
6. `PhoneAnimationRegistration`：若不再被引用可合并掉
7. 三个空壳文件 `huhutalk_phone_{raise,hold,lower}.animation.json`（**已删除**）

---

## 七、环境与工具注意事项

- **`pwsh` 工具在本机不可用**：报 `SetNamedSecurityInfoW failed (Win32 5)`，连 `Get-Location` 都挂。
  这是宿主 ACL 故障，**不是沙箱拒绝**，弹审批也过不去（已实测）。
  → 文件操作一律用 `read`/`write`/`edit`/`grep`/`glob`；**命令行验证做不了，`runClient` 只能由人来跑**。
- **IDEA MCP**：至少 `get_file_problems` 可用（本次用它校验了动画 JSON 无语法错误）；
  `mcp__IDEA__build_project` 有效。其余名字可能 `unknown tool`。
- **TLM 源码 + 资源已解包**在 `build/maidsrc/`（1459 个文件）：
  - 源码 `build/maidsrc/com/github/tartaricacid/touhoulittlemaid/...`
  - **默认动画表** `build/maidsrc/assets/touhou_little_maid/animation/maid.animation.json`
  - **酒狐全族模型/动画** `build/maidsrc/assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets/geckolib/{models,animation}/winefox*.json`
  → 写数值前**先在这里找同类姿态校准符号**，比推矩阵快且可靠（本次就是这么定案的）。
- **实机日志在 `run/logs/latest.log`**（历史日志为 `*.log.gz`）。
  本次就是靠它确认了症状 B 已修复。诊断日志已埋好，重跑后先 `grep HuhuTalk run/logs/latest.log`。
- **Blockbench MCP**：工程已打开（`winefox`）。`risk_eval` 读关键帧要用 `kf.data_points[0].x/y/z`。
  **重要坑**：用 `risk_eval` 手工 `push` 裸对象当关键帧会缺 `Keyframe.extend()` 初始化，
  导致旋转通道不被接管；建关键帧要用 `create_animation` / `manage_keyframes` 官方工具。
- **Blockbench 只在播放/scrub 时求值骨骼姿态**：用工具设 `Timeline.time` 不会更新预览姿态。

---

## 八、设计决策记录（避免新对话重复讨论）

1. **附加动画原理**：TLM 的 `GeckoMaidEntity` 有多个独立 `AnimationController`
   （`main` 管身体基础动作、`use`/`swing`/`hold_*` 管手臂附加动作）。
   我们新增一个 `huhutalk_phone` 控制器，只驱动 3 根骨骼，因此与走路/游泳/坐姿互不干扰。
2. **TLM 运行时是"覆盖"而非"累加"**（`AnimationProcessor:87-95`）：
   非 `parallel_` 控制器直接覆盖，`parallel_` 才累加。我们走独立控制器 = 覆盖语义，
   且注册顺序在 `registerControllers` 的 TAIL（最后），所以我们的值最终生效。
3. **只有酒狐系会显示该动画**：动画只含 `Head`/`RightArm`/`RightForeArm`，
   其他模型包没有这些骨骼 → TLM 静默跳过（安全）。模型包若自带同名动画则以模型包为准。
4. **头部会被 TLM 的看向玩家逻辑叠加**（`updateHead` 是 `+=`）：
   玩家站得比女仆低时低头量会偏大。已知可接受。
5. **手机模型本体不做**（用户明确暂缓）。"手机抖动"目前编码在小臂的 position 抖动里；
   若将来要手机独立二次晃动，需在模型里加 `PhoneLocator` 骨骼（要动 19 个酒狐模型，属后期内容）。
6. **数值约定（本次新增，最重要）**：
   - 动画 JSON 的 rotation 用**度**，管线自动转弧度；
   - **X、Y 会被取负，Z 不会**（`RotationValue` / `GeoBuilder` 一致）；
   - 数值是**相对模型静置姿态的增量**，`[0,0,0]` = 静置；
   - `render_x > 0` = 手臂向前抬 / 头后仰；`render_y < 0` = 朝女仆右侧转；
   - 想写"抬手到身前"就写**负 X**，"低头"就写**正 X**。
