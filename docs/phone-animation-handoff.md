# HuhuTalk 女仆「使用手机」动画 — 任务状态交接

> 交接时间点：动画已接入模组、能在游戏内播放；姿态方向已修好（实机确认）。
> **「`_raise` 反复重播」仍在排查中**，但已有实机日志证明"hold 确实被进入"，
> 而真正的问题是 **hold 段的抖动幅度小到看不见**（已放大）。
> 新对话请先读「三、结论」与「四、下一步该做什么」。

---

## 零、第二轮排查（最新，优先级最高）

### 0.1 姿势问题已确认修好
实机确认：抬手方向、低头方向都正确了。

### 0.2 实机日志证明 hold **确实**被进入（推翻了"压根没换 hold"）

`run/logs/latest.log`（20:19–20:20 那次带修正数值的 run，全 session 只有一个窗口）：

```
20:20:32.984 [Server] 开启来源窗口
20:20:33.084 [Client] 窗口开启→重启动画链        ← 只出现一次
20:20:33.365 [Client] current=huhutalk_phone_raise@8.0
20:20:33.743 [Client] current=huhutalk_phone_hold@38.0
   ... 之后每一条采样都是 current=huhutalk_phone_hold@38.0，state 恒为 RUNNING ...
20:20:44.018 [Server] 关闭来源窗口
```

raise 在 0.4s 内切到 hold，且 10 秒内没有任何采样退回 raise。
**所以"卡在 raise"在日志层面不成立。** 但用户的观感（"只是抬起手然后停住"）是对的，
原因是——

### 0.3 真问题：`_hold` 的抖动幅度小到不可见（已修）

原 `_hold` 抖动只有 ** forearm x: 64.39 ↔ 66.89（±1.25°）** 和
**position y: ±0.12 单位（= 0.0075 格 ≈ 7 毫米）**。
这个幅度在游戏里完全看不出来，所以 hold 播着也像"冻住了"，自然被判断为"没进 hold"。
（旧文档写"手机抖动编码在小臂 position 抖动里"是事实，但幅度根本没到可见阈值。）

**已放大**（节奏仍是原来的"三快一停"，keyframe 时间戳不变）：
```
RightForeArm.rotation x : -65.638 基准，抖动到 -70.5 ↔ -60.5（±5°）
RightForeArm.position   : y ±0.5、z -0.75 ↔ -0.15（手上下浮动约 3 厘米）
```
→ 这也是一个**自检手段**：装上这版后如果能看到手在上下小幅点动，就说明 hold 确实在播。

### 0.4 用户的怀疑 #1（消息分割导致 raise 重播）—— 结构上不成立，但同类耦合真实存在

- **分割是纯客户端显示行为**：`MessageSplitter` + `ChatPage.splitSchedule` 只在本地把一条回复拆成多段逐条落屏，
  **不发任何包**，也不碰 `PhoneChatSourceController`。
- `PhoneChatSourceController.open(maid)` 只在 `MaidChatHandler` 的两个入口各调一次（第 71 / 214 行），
  即"玩家发一条消息 = 一次 open"。所以**分割段本身不可能触发 raise**。
- **但确实存在同族的耦合**（值得后续修）：
  1. `open()` 只在"原本没开"时才广播；窗口已开时**只刷新 30s 到期时间、不广播、不打印任何日志**。
     所以"发第二条消息"在客户端是完全静默的。
  2. `C2SPhoneTypingDonePacket` **只带 stableId，不区分是哪一条回复**；
     一旦某条回复显示完，客户端就回执 → 服务端立刻关窗。
  3. 于是当两条消息重叠时：msg2 在 reply1 还在打字时到达（open 静默刷新）→
     reply1 打完 → 客户端回执 → **关窗、手臂放下**，而 reply2 还没显示；
     之后 reply2 显示完再回执时窗口早就关了（`close` 幂等，无广播）。
     反过来又可能出现"关窗后 msg3 到达 → open 广播 true → 再次 raise"。
  4. 结果就是**raise/hold/lower 的节奏和"消息到达时刻"对不上**，正是用户观察到的现象。
- 要判定这次遇到的到底是不是它，看日志里的 `open 调用: wasOpen=` / `窗口状态` / `关闭来源窗口` 时间轴即可。

### 0.5 本轮新增的诊断（下轮日志即可定案）

| 日志 | 位置 | 能判定什么 |
|---|---|---|
| `窗口状态: active false -> true` | `PhoneAnimation` | 客户端窗口边沿，与服务端开关窗对齐时间 |
| `动画切换: X -> Y state=.. active=..` | `PhoneAnimation` | 每一次 raise/hold/lower 切换；**重播一定会留下多条 raise 行** |
| `raise 链被重建(第N次): 重建前动画=.. 重建前state=.. 我们markNeedsReload=..` | `PhoneAnimation` | **最关键**。只在队列真被重建时打印；`markNeedsReload=true` ⇒ 我们自己重启；`false` ⇒ 另有来源（渲染器重建 / 控制器被重建 / 别的 needsAnimationReload） |
| `姿态: RightArm.x=.. RightForeArm.x=.. ForeArm.posY=..` | `PhoneAnimation` | 直接读骨骼最终角度（度）与位移，每 60 次调用一条。可证明 hold 抖动到底有没有被应用、幅度多大；数值可直接和 JSON 对照 |
| `收到窗口包: active=..` | `ClientPayloadSetup` | 客户端收到的窗口广播 |
| `open 调用: wasOpen=..` | `PhoneChatSourceController` | **每次** open 都打印（含静默刷新）。频繁 `wasOpen=true` ⇒ 消息在重叠到达 |
| `收到打字播完回执 / 关闭来源窗口` | `C2SPhoneTypingDonePacket` / Controller | 回执时间轴，看是否与 open 错配 |

**下一次复现后要提供的证据**：`run/logs/latest.log`（或 `grep -E "HuhuTalk|PhoneChatSource" run/logs/latest.log`），
外加"我发了几条消息、大概什么节奏"。

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
（**合并单文件，含三个动画**；另有三个空壳 `_raise/_hold/_lower.animation.json` 建议删除）

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

### ✅ 症状 B（`_raise` 反复重播 / 进不了 hold）——**已修复，有实机日志实证**

根因就是 `PhoneAnimation` 把状态放在 predicate 实例字段里，随 `GeckoMaidEntity`（NeoForge attachment）
重建而丢失 → 每帧都判定为"窗口刚开启" → 每帧 `markNeedsReload()` → `_raise` 被无限重启。
改成 static 集合 `ACTIVE_INSTANCES` 后修复。

`run/logs/latest.log`（最后一次 runClient）：
```
184: 手机动画诊断: stableId=6e4b... cacheActive=false raiseFound=true mergeOk=true calls=1
188: 手机动画启动: stableId=6e4b... raiseFound=true mergeOk=true activeCount=0
189: 窗口开启→重启动画链: stableId=6e4b... reloadFlag=true      ← 整个窗口只出现一次 ✅
191: 进度: state=RUNNING current=huhutalk_phone_raise@8.0 calls=3640
192: 进度: state=RUNNING current=huhutalk_phone_hold@38.0 calls=3680   ← 0.4s 内进入 hold ✅
193~228: state=RUNNING current=huhutalk_phone_hold@38.0 ...           ← 一直保持在 hold ✅
229: [PhoneChatSource] 关闭来源窗口: stableId=6e4b...
```
（`@8.0` / `@38.0` 是 `animation_length`（tick），不是进度；重点是动画名从 raise 变成 hold 并保持。）

**因此「线索 3：症状 A 只是症状 B 的副产品」不成立**——hold 确实在播，手臂朝后是姿态数值本身的问题。

### 🔴 症状 A（手臂朝**身后**伸）——**根因 = X/Y 旋转符号全错**，已修数值，待实机确认

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

1. **`PhoneAnimation.ACTIVE_INSTANCES` / `DIAGNOSED` 没有世界切换重置**，
   而 `PhoneChatSourceCache.clear()` 有。若玩家在动画中途退出世界，static 集合里会残留该 stableId。
   后果：下次开窗时 `markNeedsReload()` 可能不触发 → 直接续播 hold 而跳过 raise（外观问题，非功能问题）。
   修法：给 `PhoneAnimation` 加 `reset()`，在 `ClientCacheEvents.onPlayerLogin/onPlayerLogout` 里调用。
2. **`ClientCacheEvents.onClientTick` 只在 `getCurrentPage() instanceof ChatPage` 时推进 `advanceSplitting()`**。
   收起手机没问题（页面仍在栈顶），但玩家**切到别的页面**时推进会暂停 → 回执延后 → 手臂保持举起，
   最坏等 30s 兜底关窗。修法：改为遍历"所有持有未播完断句的 ChatPage"（或让 tick 驱动与页面栈无关）。
3. `PhoneChatSourceController.clearAll()` 只清 `OPEN`、**不广播 active=false**（靠客户端重登时的 `clear()` 兜住）。
4. `PhoneAnimationHandler.ensureAnimations` 每个模型打一行注入日志（约 40 行），属于诊断噪音。

---

## 四、下一步该做什么（建议顺序）

### 4.1 先确认 hold 现在"看得见"
跑一次 `runClient`，只发**一条**消息，盯住手臂：抬手 0.4s 后应该能看到**手在上下小幅点动**
（约 3 厘米，三快一停），而不是停住不动。看得到 ⇒ hold 正常，问题只剩"重播"。

### 4.2 再复现"重播"，把日志给我
建议发 3~4 条消息、故意让上一句还在逐条打字时就发下一句（这正是最可疑的重叠场景）。
然后把 `run/logs/latest.log` 里的 `HuhuTalk` / `PhoneChatSource` 行给我
（`grep -E "HuhuTalk|PhoneChatSource" run/logs/latest.log`），
并说明"我发了几条、大概什么节奏"。

**判读方法**：
- 若 `动画切换` 里出现多条 `-> huhutalk_phone_raise`，且伴随 `窗口状态 active true -> false -> true`
  或 `open 调用`/`关闭来源窗口` 频繁成对出现 → **是窗口开合抖动**（0.4 节的耦合），
  修法：给窗口加"待显示回复计数"（或让回执带 reply token，只在计数归零时关窗）。
- 若出现多条 `raise 链被重建(第N次)` 而**没有**窗口开合 → 是**动画链被自己重建**，
  看 `我们markNeedsReload=` 是 true 还是 false：
  - `true` ⇒ `ACTIVE_INSTANCES` 被误清空（可能窗口瞬时 false 一帧）；
  - `false` ⇒ 来自 `switchRenderer`（模型/渲染器重建）或控制器被重建。
- 若 `姿态` 行里 `RightForeArm.x` 在 60.5/70.5 之间变化 ⇒ hold 抖动确实在跑。

### 4.3 收尾（观感确认后）
1. 清理诊断代码（见「六、待清理的诊断代码」）。
2. 若觉得手臂"夹太紧/张太开"，只调 Z：`RightArm.z`（增量 -14，模型静置已是 +18）、`RightForeArm.z`（-16.924）。
3. 觉得抖动太快/太慢：`_hold` 的 keyframe 时间戳（0.15/0.3/0.45 + 0.35 停顿）就是节奏，改时间即可；
   幅度改 `-70.5 ↔ -60.5` 和 position 的 ±0.5。
4. 把数值同步回 `winefox.bbmodel`（可选，工程文件不参与打包）。
5. 处理「三、结论 → 遗留小问题」里的 4 条。

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
| `client/ClientCacheEvents.java` | 进世界清 `PhoneChatSourceCache`；tick 驱动 `advanceSplitting()` |
| `client/network/ClientPayloadSetup.java` | 处理 `S2CPhoneChatSourcePacket`（`enqueueWork` → 主线程，无竞态） |

---

## 六、待清理的诊断代码（功能确认后删除）

1. `PhoneAnimation`：`DIAGNOSED`、`CALL_COUNT`、`LAST_ACTIVE`、`LAST_ANIM`、`RESTART_COUNT`、
   `currentName()`、`describeCurrent()`、`deg()` 及全部 `LOGGER.info`
2. `PhoneAnimationHandler`：`merged` / `lastMergeSucceeded()` 及注入日志
3. `PhoneChatSourceController`：`open`/`close` 里的 `LOGGER.info`（注意 `open` 的日志已改成**每次**都打，
   清理时别把 `wasOpen` 逻辑一起删掉）
4. `C2SPhoneTypingDonePacket`：回执日志 + "找不到女仆"警告
5. `ClientPayloadSetup.handlePhoneChatSource`：`收到窗口包` 日志
6. `PhoneAnimationRegistration`：若不再被引用可合并掉
7. 三个空壳文件 `huhutalk_phone_{raise,hold,lower}.animation.json`（**已在本次删除**）

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
