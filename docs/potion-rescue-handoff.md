# HuhuTalk 女仆远程救援（俗称「药水复活」）— 约定 · 实现记录 · 交接

> **【现状】** 功能已实现、实机验收通过，并已完成两轮措辞微调。本文不是"进行中"的任务单，
> 而是**机制约定 + 踩坑记录**，供以后新开对话时快速接手。
> 想要更好的接手效果：先读 **§0.2 三个必读**，再按需要跳到 §5。
>
> **提示词想改字**：全部文案集中在 `RescueNarrative.java` 一个文件里，
> 位置见 **§6.1 文案索引**。

---

## 零、阅读指南

### 0.1 一句话机制

主人死亡瞬间，若他随身带着 **Pro 手机**、且名下有「已加载 + 存活 + 佩戴手机 + 背包里有治疗药水」的女仆，
则 **5 秒**后该女仆远程把药水送过去、直接用在主人身上把他救活（药水**不进背包**），
随后女仆会**主动发一条消息**说这件事。掉岩浆会额外凑一瓶抗火药水；掉虚空直接不救。

**连续复活事件**（见 **§10**）：同一次"死—被救"连着来时算一件事 —— 事件内第 1/2/3 次救援
各让女仆说一句话，**第 4 次起完全静默**（不调大模型、不写记忆，只记账）；连续 **3 分钟**没再死
= 已脱离危险，事件结束时把「主人这段又死了 x 次、你们用掉了 x 个 x」总结成最后一条消息，
发给**一只**参与女仆（其余人只在文案里被提到）。

### 0.2 三个必读（不读会踩同一个坑）

1. **§5.1 `PlayerList.respawn` 只挂一半** —— 这是本项目历史上唯一造成**存档级数据损坏**的坑。
   直接调用 `PlayerList.respawn` 而不补 `listener.player = revived`，会让女仆不可交互、
   魂符收放导致女仆消失、且**玩家下次进世界以死亡状态进入**。
2. **§5.4 叙事必须走两条分离的通道** —— 记忆通道放指令会让女仆以后莫名其妙重复汇报。
3. **§5.2 重生后玩家是满血** —— 不显式 `setHealth` 覆盖，"复活为药水对应的血量"这个需求就是假的。

---

## 一、产品约定（已定稿，改前请先确认）

这些是需求方逐条拍板的结论，属于**契约**，不是实现细节。

| # | 约定 | 说明 |
|---|---|---|
| 1 | **真死 + 服务端强制重生** | 不做"拦截死亡让玩家不死"。玩家会真的经历一次死亡结算。 |
| 2 | **跟随玩家游戏规则** | `keepInventory` 规则为真就保背包、为假就照常掉落。**我们不额外做保物机制**（曾考虑 mixin / 临时改全局规则，因可能与墓碑类模组冲突而否决）。 |
| 3 | **不加 mixin、不改全局游戏规则** | 全部靠原版扩展点实现。 |
| 4 | **服务端权威 + 多人可用** | 判定与执行全在服务端；`core/rescue/*` 不引用任何客户端类；不依赖单机专属路径。 |
| 5 | **不要求"位于聊天页面"** | 早期草案要求"玩家正开着该女仆的聊天页"，后**取消**：改成"随机挑一只可联络的女仆"。因此不需要客户端上报当前页面。 |
| 6 | **手机口径** | 玩家**任意槽位**（含盔甲/副手，即 `Inventory#getContainerSize` 的 41 格）有 `phone_smart` 即可；**不认**青春版电话手表。 |
| 7 | **候选池** | 玩家名下 + 已加载 + 存活 + 佩戴手机 + **背包确有治疗药水** → 随机 1 只。 |
| 8 | **药水搜索范围** | 女仆可用背包 → 隙间绑箱 → 主副手（手里排最后，抢她手里的东西干扰最大）。 |
| 9 | **取回血量最高的那瓶** | 而不是槽位顺序第一瓶。回血量算式见 §4.3。 |
| 10 | **抗火可在两只女仆之间凑** | 优先挑"同体兼具治疗+抗火"；否则随机一只治疗方 + 随机另一只抗火方。缺抗火不放弃救援。 |
| 11 | **虚空不救** | 判据见 §4.2。把人送回虚空只会立刻再死一次。 |
| 12 | **硬模式不救** | `server.isHardcore()` 时完全跳过，保持原版不可逆语义。 |
| 13 | **失败一律静默** | 没药水 / 没手机 / 女仆没戴手机 / AI 未启用 / 玩家已自己重生 —— 都不给玩家任何提示。 |
| 14 | **先复活，后触发 LLM** | 顺序不可颠倒。理由见 §5.4。 |
| 15 | **死法用原版死亡提示** | 不枚举"岩浆/虚空/摔死"，直接转发原版那句（`Momo试图在岩浆里游泳`），天然覆盖模组伤害类型。 |
| 16 | **距离分档影响措辞** | ≤10 格「你就在他眼前」/ ≤50 格「你在附近，远远看到」/ 更远或跨维度「隔着手机才发现」。投送措辞也一并分档。 |
| 17 | **语气不过重、不"要求坦率"** | 文案里不出现"担忧/悲伤/后怕"，也不写"不用端着/不用瞒着"之类要求坦率的措辞 —— 那会覆盖玩家给女仆设的「口是心非」「傲娇」人设。 |
| 18 | **玩家可关掉** | 手机 → 设置 → **救援 → 药水复活**。关掉后不救援、不消耗药水、不调 AI。默认开。 |
| 19 | **不做冷却** | 需求方明确不要冷却；改为"短时间重复死亡时让女仆吐槽一句"。 |
| 20 | **提示词本地化延后** | 文案目前硬编码中文，等与模组整体翻译工作一起做。 |
| 21 | **叙事次数闸门**（1 系列） | 「连续复活事件」内第 1/2/3 次救援各唤起一次 LLM；**第 4 次起完全静默**。 |
| 22 | **静默期零输出** | 第 4 次起**不调 LLM、不写记忆、不进任何消息通道**；玩家侧照旧（药水气泡 / 破碎音效 / 粒子）。 |
| 23 | **事件与计时** | 事件由"第一次成功救援"开启；**任意死亡（含没救成的）都重置 3 分钟计时**；连续 3 分钟没死 = 事件结束。 |
| 24 | **收尾总结只发一只** | 只统计**第 4 次起**的死亡/救援/消耗；结束时不发总结的情况：`seq≤3`（没进记账区间）、玩家不在线、没有可联系的女仆。 |
| 25 | **濒危提醒** | 记账区间内参与女仆**合计**治疗药水 **≤3** 时提醒一次（报**实际**瓶数），整个事件内只一次。 |
| 26 | **不跨会话** | 登出 / 停服直接丢弃未结算事件（内存态），不补发、不落盘。 |

---

## 二、实现完成项

按当初的 P1–P8 阶段划分，全部落地：

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 死亡钩子 + 5s 窗口 + 登出/停服清理 | ✅ |
| P2 | 环境预判（虚空放弃 / 岩浆需抗火 / 玩家手机判定） | ✅ |
| P3 | 女仆挑选（含双女仆凑药分工） | ✅ |
| P4 | 药水识别与结算（回血量 / 分类 / 效果剔除瞬间） | ✅ |
| P5 | 执行：取药 → 重生 → 回血 → 效果 → 表现 → 聊天页留痕 | ✅ |
| P6 | 事后 LLM 叙事（距离分档 / 双女仆互相提及 / 复死吐槽） | ✅ |
| P7 | 工作模式 2 的接口预留 | ⚠️ 见 §7.1（判定为**无需独立动作**） |
| P8 | 编译验证 + 手动测试清单 + 手册页文案 | ✅ |

**额外追加（验收后需求方提出）**：
- 聊天页物品气泡的**药水图标修正**（预览带上完整组件，见 §5.6）
- **药水复活开关**（见 §4.4）
- 提示词两轮微调（去重、"抗火"重复、双女仆归因、去掉"不用端着"、复死提示改词）
- **1 系列：连续复活事件**（静默闸门 + 计数期记账 + 濒危提醒 + 收尾总结，见 **§10**）
- **任务 2：远程补给工作模式**（血/饱食自动补给 + 手机留痕不调 LLM + 工作期计数 + 开关，见 **§11**）

---

## 三、文件索引

### 3.1 核心（新增，全在服务端侧）

| 文件 | 职责 |
|---|---|
| `core/rescue/PendingRescue.java` | 死亡瞬间的不可变快照（UUID / 维度 / 坐标 / 朝向 / 需抗火 / **死因文案** / 到期 tick） |
| `core/rescue/PendingRescueManager.java` | 5s 窗口登记与 tick 对账、重生重定向登记、登出/停服清理（**反复死亡统计已移交给 `RescueStreakManager`**） |
| `core/rescue/PlayerRescueHook.java` | 事件入口：`LivingDeathEvent` / `PlayerRespawnPositionEvent` / `ServerTickEvent.Post` / 登出 / 停服 |
| `core/rescue/RescueEnvironment.java` | 虚空判定、岩浆/火焰判定、**死因文案 `describeDeath`** |
| `core/rescue/RescuePotion.java` | 治疗/抗火识别、回血量算式、饮用/喷溅/滞留分类、效果剔除瞬间 |
| `core/rescue/RescueMaidPicker.java` | 候选池 + 同体优先 / 两姐妹凑药分工 |
| `core/rescue/MaidRescueService.java` | 编排：T+0 登记、T+5s 执行（**含 §5.1 的监听器修复**） |
| `core/rescue/RescueNarrative.java` | **全部提示词**（记忆 / 事件文本 / 收尾语 / 距离分档 / 动词 / **濒危提醒 / 收尾总结**） |
| `core/rescue/RescueStreak.java` | **连续复活事件**的状态（救援次数 / 记账区间的死亡·救援·消耗·参与者 / 濒危提醒是否说过） |
| `core/rescue/RescueStreakManager.java` | 事件的开启·记账·3 分钟静默结算与收件人挑选（第 4 次起的静默闸门就在这里） |
| `core/rescue/MaidDeliveryRecord.java` | 投递记录（可复用结构，工作模式 2 的接入点） |
| `core/rescue/RescueSettings.java` | 「药水复活」偏好的**服务端会话缓存**（按 UUID） |
| `common/PlayerPhoneHelper.java` | 玩家侧 Pro 手机探测（41 格全扫） |
| `core/chat/MaidPhoneContexts.java` | **女仆的「手机 / 远程支援」常识**，注册进 TLM 上下文知识库（见 §5.14） |
| `network/C2SPotionRescueSettingPacket.java` | 客户端 → 服务端同步该开关 |

### 3.2 被修改的既有文件（**均为加法**）

| 文件 | 改动 |
|---|---|
| `HuhuTalk.java` | 注册 `PlayerRescueHook`；注册 `C2SPotionRescueSettingPacket` |
| `core/transfer/MaidTransferService.java` | 新增 `findBestMatching` / `takeBestMatching` / `countMatching` / `allRescueHandlers`（**未改动既有 `allSourceHandlers`，以免悄悄扩大 `send_item` 的行为**） |
| `phone/ItemTransfer.java` | `Preview` 增加 `stackNbt`（SNBT 组件）+ `of(ItemStack)` / `decodeStack`；`Payload` 增加 `used` / `effectText` / `claimable()` |
| `network/S2CMaidItemTransferPacket.java` | 增加 `used` / `effectText` 字段；改成手写 `StreamCodec.of`（理由见 §5.8） |
| `client/ChatHistoryStorage.java` | 无需改动（Gson 自动带上新字段，旧存档缺字段按默认值） |
| `phone/pages/ChatPage.java` | 已使用气泡换墨绿底 + 效果文案 + 不可点击；`previewToStack` 支持组件还原 + **渲染缓存** |
| `client/settings/HuhuTalkClientSettings.java` | 新增 `potionRescueEnabled`（**可空 Boolean**，见 §5.10） |
| `event/ClientModEvents.java` | 登录时推送该偏好（`pushPotionRescueSetting`） |
| `phone/pages/SettingsPage.java` | 新增「救援」分组 + 开关；`onOpen` 时再推一次 |
| `phone/pages/ManualPage.java` | 手册加「药水复活」条目 |
| `core/chat/MaidChatHandler.java` | `handleSystemInjection` 新增可定制重载（收尾语 + 系统文本排布）；手机聊天的 SYSTEM 上下文改走 `chatContext`（**来源上下文 + 知识库条目**，见 §5.14） |
| `HuhuTalkTLMExtension.java` | 新增 `registerAIMaidContext` → 注册 `MaidPhoneContexts` |

---

## 四、使用机制

### 4.1 玩家视角

1. 身上带着 Pro 手机（`phone_smart`），女仆佩戴手机且背包里有治疗药水。
2. 死亡 → 死亡界面停留 5 秒 → **自动被救回死亡地点**，血量 = 药水能回的量。
3. 死亡地点播破碎音效 + 药水粒子；聊天页出现一条**墨绿色「已使用 · 恢复 N ❤」气泡**（不可点击）。
4. 参与救援的女仆会**主动发来一条消息**（双女仆则两条，互相提及对方）。
5. 掉进岩浆：额外消耗一瓶抗火药水。掉进虚空：不救援。

### 4.2 虚空 / 岩浆判据（`RescueEnvironment`）

- **虚空 → 不救**：伤害类型 msgId ∈ {`out_of_world`, `fell_out_of_world`} **或** 死亡点 `Y < 世界最低高度 - 64`。
- **需要抗火**：伤害类型 msgId ∈ {`lava`, `on_fire`, `in_fire`, `hot_floor`} **或** `player.isInLava()` **或** 脚下方块流体是岩浆。以上取或。

### 4.3 回血量算式（`RescuePotion.healAmount`）

| 效果 | 换算 |
|---|---|
| 瞬间治疗 `HEAL` | `4 << amplifier`（I = 4 点，II = 8 点） |
| 再生 `REGENERATION` | `duration / (50 >> amplifier)`（对齐原版"每 `50>>amp` tick 回 1 点"） |

两者相加 = 该瓶药水"理论能回多少血" = **挑选依据（取最高）** 与 **气泡/叙事里的数字**。
最终落到玩家身上的是 `clamp(回血量, 1, maxHealth)`。

### 4.4 开关与数据流

```
客户端 settings.json（真源，默认开）
   │  推送时机：登录 / 打开设置页 / 拨动开关
   ▼
服务端 RescueSettings（按 UUID 的会话内缓存，登出清理）
   ▼
MaidRescueService.trySchedule 第一道闸
```

**服务端不落盘**：客户端每次登录都会重新告知，没必要在服务端存档里再存一份。
冗余三重（登录 + 开设置页 + 拨动）是为了"登录同步包万一没发出去"也能自愈。

### 4.5 日志关键字（排查用）

```
[HuhuTalk][救援] 已登记：player=... needFireResistance=... 死因="..." 将在 100 tick 后尝试
[HuhuTalk][救援] 完成：player=... 回血=... 抗火=... 治疗方=... 抗火方=...
[HuhuTalk][救援] 作废：没有「已加载 + 佩戴手机 + 带治疗药水」的女仆 ...
[HuhuTalk][救援] 监听器重定向失败！ ← 出现这行说明踩了 §5.1，立即停下排查
[huhutalk.rescue_environment] 死亡提示未本地化（msgId=...） ← 见 §5.11

[HuhuTalk][救援] 进入静默期：player=... 第 4 次起只记账、不再调用 AI
[HuhuTalk][救援] 濒危提醒：player=... 剩余治疗药水合计=... 开口者=... 同伙=[...]
[HuhuTalk][救援] 计数区间结算：player=... 死亡= 救援= 同手= 消耗={...} 收件人=...
[HuhuTalk][救援] 计数区间结算作废（玩家不在线）/（没有可联系的女仆）
[HuhuTalk][救援] 连续复活事件结束（未进入记账区间）：player=... 救援次数=...
```

---

## 五、留给以后对话的知识（踩坑记录）

### 5.1 ⚠️ `PlayerList.respawn` 只做一半 —— 本项目最致命的坑

**症状**（当时实际发生的）：
- 被程序复活后，**女仆不再可交互**；
- 用魂符收起再放置女仆，**女仆直接消失**；
- **再次进入世界以死亡状态进入**，正常重生后女仆才恢复可交互。

**根因**：`PlayerList.respawn(player, keepInventory, reason)` 内部只做
`serverplayer.connection = player.connection`（把监听器挂到新玩家），
**从不反向更新 `ServerGamePacketListenerImpl.player`**。
原版唯一的调用方 `ServerGamePacketListenerImpl#handleClientCommand` 自己补了这一步：

```java
this.player = this.server.getPlayerList().respawn(this.player, false, Entity.RemovalReason.KILLED);
```

直接调用 `PlayerList.respawn` 而不补这一句，监听器就永远握着**已被 `removePlayerImmediately` 移除的旧玩家**：

| 后果 | 链路 |
|---|---|
| 女仆不可交互 | 入站包全作用在旧玩家上；`handleInteract` 把旧玩家交给女仆做 `isOwnedBy` 判定，而女仆的 `getOwner()` 按 UUID 解析出的是**新**玩家 → 身份不匹配 |
| 魂符收放导致女仆消失 | `handleUseItem` → `this.player.gameMode.useItem(this.player, ...)`，经幽灵玩家执行 |
| 下次进世界是死亡状态 | `onDisconnect` → `removePlayerFromWorld()` → `PlayerList.remove(this.player)`（旧玩家）：① `save(旧玩家)` 把**死亡快照**落盘；② `playersByUUID.get(uuid)` 拿到的是新玩家 ≠ 旧玩家 → `playersByUUID.remove(uuid)` 被跳过 → 新玩家成幽灵且**从未被保存** |

**修复**：`MaidRescueService.repointConnection(dead, revived)` —— 在 `respawn` 返回后立刻
把 `listener.player` 指向新玩家，并校验 `revived.connection` 一致性，失败打 ERROR。
`tryExecute` 里那段 `⚠⚠ 高危点 ⚠⚠` 注释就是为此写的，**不要删**。

**已排除的干扰项**：`ServerPlayer` 构造器里 `this.gameMode = server.createGameModeForPlayer(this)`，
所以 **gameMode 归属新玩家**，陈旧点**只有监听器这一处**。

**通用结论**：任何"绕过原版调用方、直接调 `PlayerList.respawn`"的代码都要自己补这一句。
`PlayerList.remove(player)` 之后如果还要继续用同一个玩家对象，同理要小心。

### 5.2 重生会把血置满，必须显式覆盖

`ServerPlayer#restoreFrom(that, keepEverything=false)` 里先 `setHealth(getMaxHealth())`，
只有 `keepEverything=true` 分支才会 `setHealth(that.getHealth())`。
我们传 `false`（= 原版死亡重生路径，从而跟随 `keepInventory` 规则、
并让 `PlayerEvent.Clone.wasDeath = true` 以兼容墓碑类模组），
所以**新玩家是满血**，不显式 `setHealth(药水回血量)`，"复活为药水对应的血量"就是空的。

### 5.3 瞬间效果不能 `addEffect`，否则双倍

复活时我们**直接 `setHealth(回血量)`** 做确定性结算。
若再把药水的效果整体 `addEffect` 一遍，`HEAL`（瞬间效果）会立刻再回一次血 → 双倍。
所以 `RescuePotion.lastingEffects()` 过滤掉 `MobEffect#isInstantenous()` 的效果，
只补挂再生 / 抗火这类持续效果。

同理，**不要真的生成 `ThrownPotion` 实体**：它的落地结算会再给一次效果。
表现层只播破碎音效 + `Particles.SPLASH` + 按 `PotionContents#getColor()` 着色的 `ENTITY_EFFECT`。

### 5.4 叙事必须走两条分离的通道

| 通道 | 内容 | 为什么 |
|---|---|---|
| `PendingSystemMessageBus.queue(...)` | **纯事实、无指令** | 它会留在女仆记忆里，下次正常聊天时被注入。若带上"主动发一条消息"这类指令，她以后会莫名其妙重复汇报一次 |
| `MaidChatHandler.handleSystemInjection(...)` | 事实 + 语气引导 + 收尾语 | 只用于**本次**即时触发 |

**顺序也不可颠倒**：必须在**复活落定之后**才注入。否则若主人在 5 秒窗口内自己点了重生，
会出现"女仆刚被通知救了人，人却自己活了"的错位，那次 LLM 调用也白烧了。

### 5.5 系统消息排布与收尾 USER 语

`handleSystemInjection` 新增了可定制重载：
- `systemTextLast = true` → 事件文本排在**人设卡与历史之后**（模型权重最高，免得被当背景设定读掉）；
- `closingUserText` → 替掉默认那句写死的**「主人就站在你面前」**（那是当面场景，对远程救援是错的 → 现按距离分档）。

⚠️ 旧的无参重载仍被 **P→M 物品提示**那条路径使用，改动时别把默认行为改掉。

### 5.6 聊天页物品气泡的图标：预览必须带组件

客户端 `previewToStack` 原本只能按 registry id 重建 `new ItemStack(item)`，
`POTION_CONTENTS` 组件会丢 —— 而药水的**模型与液色全部来自组件**，
所以**所有药水都退化成同一个"水瓶"图标**。

修复：`ItemTransfer.Preview` 增加 `stackNbt`（用 `ItemStack.SINGLE_ITEM_CODEC` + `NbtOps` 编码的 SNBT），
服务端统一走 `Preview.of(stack)`，客户端优先按组件还原、失败/旧存档退回"ID + 数量"。

- 用 `SINGLE_ITEM_CODEC` 而**不是** `ItemStack.CODEC`：后者 count 有 **1~99** 的区间约束，越界会编码失败。
- **顺带修好了普通"传文件"送药水**的气泡（`pushBatchToPlayer` 也改走 `Preview.of`）。

### 5.7 渲染路径的性能：SNBT 解析必须缓存

`previewToStack` 是**每帧每个气泡**都会调用的。SNBT 解析 + 组件反序列化不便宜，
必须按串缓存（`ChatPage.PREVIEW_STACK_CACHE`，上限 256，超限清空重建），并且**返回副本**
（否则调用方的 `setCount` 会污染缓存本体）。

### 5.8 `StreamCodec.composite` 的泛型重载**最多 6 对字段**

`S2CMaidItemTransferPacket` 有 7 个字段（`stableId/transferId/previews/total/pending/used/effectText`），
`composite` 会编译报"找不到合适的方法"（重载止于 `Function6`）。
改为手写 `StreamCodec.<B, V>of(encoder, decoder)`。
⚠️ 用显式 lambda 参数类型，否则会与 `ofMember`（参数顺序相反）产生歧义。

### 5.9 客户端设置同步到服务端的模式

判定发生在服务端的信息，不能只存在客户端。本项目采用的模式是：
**客户端本地持久化（真源） + 服务端按 UUID 会话内缓存 + 客户端每次登录重新推送**。
好处是不需要在服务端存档里再持久化一份（少一处能坏的地方），且天然支持"同一账号换客户端"。

推送点要**冗余**：登录事件 + 打开相关页面 + 用户拨动时各推一次，
用来兜住"登录同步包没发出去"这类极端情况。

### 5.10 给老配置文件加新开关：用可空 `Boolean`

`HuhuTalkClientSettings` 用 Gson 反序列化。如果新字段写成裸 `boolean`，
**老配置文件里没有该字段时 Gson 会给 `false`**，正好把默认开启的功能静默关掉。
所以要用 `Boolean` 字段 + `== null ? 默认值 : 值`（已有先例：`Float screenBrightness`）。
同理，`ItemTransfer.Payload` 这类**会被反序列化**的字段，读取处必须容忍 `null`
（Gson 用 Unsafe 分配，缺失字段不会走字段初始值）。

### 5.11 死因文案的本地化边界

`RescueEnvironment.describeDeath` 取 `DamageSource#getLocalizedDeathMessage(player).getString()`：

- **单机 / 局域网**（集成服务器与客户端同 JVM）→ 拿到中文原句，正常。
- **专用服务器**不加载客户端语言文件 → 可能返回未翻译的键名（`death.attack.lava`）。
  代码里以 `startsWith("death.")` 检测并退回 `因「lava」而死`，同时打一条 WARN。

这条与"提示词本地化"是同一件事，等模组翻译工作一起收。

### 5.12 关于"候选池用直接扫描而不是联系人簿"

`RescueMaidPicker.reachableMaids` 直接扫描已加载实体，**不查 `MaidContactBook`**。
原因：联系人簿由 `MaidScanTicker` 每 100 tick 才刷新一次，刚召唤/刚复活的女仆最长 5 秒不在簿里，
而救援是"分秒必争且只发生一次"的场景。扫描成本与既有的 `MaidStableIdHelper.findMaid` 同量级。

### 5.13 `T+0` 只做廉价判定

`LivingDeathEvent` 落在原版死亡结算的关键路径上，所以 T+0 只判定三件廉价事实
（硬模式 / 虚空 / 玩家有没有手机），女仆与药水的挑选推迟到 T+5s 的 tick 对账里做。
那 5 秒里条件本身也可能变化（女仆被卸载、药水被别的逻辑消耗），推迟反而更准。

### 5.14 ⚠️ "她都会什么"要进知识库，**而且手机聊天要自己接那条线**

需求："让女仆知道自己有远程支援主人的能力，这样玩家让她辅助时她答得上话"。
**不要**把这句话塞进救援提示词 —— 那会把她的注意力拉到"让主人看消息"这类事上，冲淡当下该有的反应。

正确落点：TLM 的**上下文知识库**（`ILittleMaid#registerAIMaidContext` → `GameContextRegister`）。
`registerCategory(id, summary, promptContext = true)` 的类别会作为常驻上下文进入提示词，
条目由 `IMaidContext` 提供，渲染格式固定为 `- {label}: {value}`。
本模组的条目见 `core/chat/MaidPhoneContexts.java`（类别 id `huhutalk_phone`）。

**坑（两条渲染路径，缺一不可）**：

| 路径 | 渲染方 | 是否自动带上 |
|---|---|---|
| TLM 自带聊天（`MaidAIChatManager.chat` → `normalChat`） | `UserPromptContexts.addContext` 拼 `<context>` 块 | ✅ 注册即生效 |
| **HuhuTalk 手机聊天**（`MaidChatHandler.buildMessages` / `handleSystemInjection`） | **自建消息列表，绕过上面那条** | ❌ 必须显式调 `MaidPhoneContexts.render(maid)` 拼进自己的 SYSTEM 上下文 |

所以 `MaidChatHandler.chatContext()` = 来源上下文（手机 + 距离） + 知识库条目。
**改这条路径时别把知识库那句丢了**，否则她只在 TLM 自带聊天里"记得"自己的能力。

另外：`handleSystemInjection`（救援 / 濒危 / 总结 / 离线播报）**刻意不带**这条常识 ——
救援那一刻只该讲"刚刚发生了什么"，常识属于平时就知道的东西。

**为什么"工具"从来不需要这么接？（重要，别再查一遍）**

| 机制 | 挂载在哪一层 | 双端自动？ |
|---|---|---|
| 工具 schema（`move_to` / `send_item` / `send_emote` …） | **LLM 请求层**：`LLMOpenAIClient` 读 `callback.needAddTools` + `ToolRegister.getAllTools()` 组装 tools | ✅ 自动 |
| 常驻上下文（`promptContext = true` 的类别） | **调用方**：TLM 的 `MaidAIChatManager.normalChat` 里 `UserPromptContexts.addContext` | ❌ 手机聊天要自己拼 |

`LLMCallback.needAddTools` 默认就是 `true`（构造函数里赋值），`HuhuTalkChatCallback` 又显式设了一遍，
所以任何走到 `site.client().chat(callback)` 的路径（TLM 自带聊天 / 手机聊天 / 离线播报 / 救援注入）
都会自动带上全部工具 —— 这就是 `move_to`、`send_item` "双端可用、而我们没写一行适配"的原因。
知识库常驻上下文没有这层保护，必须自己接（见上表第二行）。

顺带记一下，免得下次又找：**唯一的移动工具就是 `move_to`**，`arrival` 参数覆盖
`meet / home(≈stay) / protect`；`/huhutalk move|come|stay` 是三条**玩家命令**
（`ModCommands`，权限 2），与工具共用 `MaidMoveData` 后端 —— 是"一个工具 + 三条命令"，不是三个工具。

**另一个可选方案**：`query_game_context` 工具的参数说明会列出 `allToolCategories()`
（即 `promptContext = false` 的类别，格式 `- id: summary`），而工具 schema 在请求层 → **这份清单天然双端可见**。
若哪天嫌每条消息那 60+ 字太贵，可以把本类别改成 `promptContext = false`：类别摘要常驻工具说明、
细节由她按需查询，per-message 成本≈0；代价是不如常驻稳。目前选常驻（玩家一问就答得上）。

**已知小口子**：内置 `equipment` 类别（可查询）里的 `inventory_items` 只扫
`getAvailableBackpackInv()` —— **不含隙间绑箱**，而救援取药是会取隙间绑箱的。
所以玩家把药水放隙间时，她"看背包"会答"没有"。需要的话再补一条隙间存量的条目。

### 5.15 ⚠️ 「记忆」写错通道 = 同一件事两次 LLM 对话（实测日志坐实）

**症状**（实机日志）：玩家在熔岩里死了一次，手机里却收到**四条**女仆消息、分属**两轮**对话：
一轮是"没药了/你血怎么是 0"，另一轮是"熔岩里游泳?!…" 加一条超长抒情，
而且后一轮腔调明显更"文艺"。

**根因**（两层，都跟死亡监听无关）：

1. **`MaidScanTicker` 每 100 tick 无条件尝试播报**：
   ```java
   // 每次扫描都尝试交付离线消息，内部会检查队列
   OfflineMessageDeliverer.tryDeliver(maid, wasDead);   // MaidScanTicker:54-55
   ```
   它**没有"她醒来了"这个门禁** —— 只要 `PendingSystemMessageBus` 里有条目、女仆已加载、
   戴着手机、主人活着，最多 5 秒后就会被 drain 出来、拼一句"你刚刚苏醒…真诚温暖的回应"
   （`OfflineMessageDeliverer.buildSystemText`）**再发起一次完整 LLM 对话**。
   参数 `wasDead` 只决定措辞（"你刚刚经历了一次死亡" vs "你刚刚苏醒"），
   所以日志里出现"你刚刚苏醒"**不代表**判定到了女仆死亡 —— **别往死亡监听那边查**。
2. **我们把"记忆"错写成了"可播报"**：救援 / 补给是"先即时注入一次、再把同一条事实留进记忆"，
   那条记忆一旦是可播报条目，就必然被上面那步再播一遍 → 同一件事两轮对话。

**修复**：`PendingSystemMessageBus` 拆成两类条目：

| 方法 | 语义 | 谁取走 |
|---|---|---|
| `queue(stableId, text)` | **可播报**：女仆重新上线时要主动说给主人听 | `OfflineMessageDeliverer.drainBroadcastable`（≤5 秒内播报，会调 LLM） |
| `queueSilent(stableId, text)` | **静默事实**：只作上下文 | 只在**下一次自然聊天**时由 `MaidChatHandler.handleChat` 的 `drain` 顺路带上，**零 LLM 调用** |

**规矩**：**凡是我们已经即时开口说过的事实，一律 `queueSilent`。**
救援的 `buildMemory`、补给的四处记忆全部已改；`OfflineMessageDeliverer` 只播报非静默条目，
未播成时原样放回（保持静默标记）。

**还留着的旧特性问题（待需求方决定）**：`MaidScanTicker` 那句"每次扫描都播报"其实丢掉了
当初"等她醒来再播"的语义 —— 玩家离线期间发的消息会在女仆在线的任意 5 秒窗口里被播报。
要恢复门禁（只在 `OFFLINE→ONLINE` / `DEAD→ONLINE` 跃迁时播报）说一声；
现在靠"静默条目"把新功能的双重调用堵住，老路径行为未改。


---

## 六、以后要调字 / 要加功能时

### 6.1 文案索引（全部在 `core/rescue/RescueNarrative.java`）

> 行号会随编辑漂移，**以方法名为准**。

| 想改什么 | 方法 |
|---|---|
| 距离阈值 10 / 50 格 | 常量 `IN_SIGHT_RANGE` / `NEARBY_RANGE` |
| **各距离档的"你的操作"整句**（含喂 / 扔 / 发 / 砸的用药差异） | `actionLine` |
| 收尾 USER 语（三条） | `closingLine` |
| **语气引导** | 常量 `TONE_GUIDE` |
| **长度约束**（"话要短：一两句就够…"） | 常量 `BREVITY_LINE` |
| **记忆文案**（进 `PendingSystemMessageBus`） | `buildMemory` |
| **事件文本主体** | `buildPrompt` |
| **濒危提醒正文**（1.3） | `buildLowPotionWarning` + 常量 `LOW_POTION_CLOSING` |
| **收尾总结正文**（1.1/1.2） | `buildStreakSummary` + 常量 `STREAK_CLOSING` |
| 「我 / 我和 xx / 我和 xx 他们」 | `groupName` |
| 消耗明细「治疗药水 ×3、抗火药水 ×1」 | `describeConsumption` |
| 「顺带一提：这已经是这段时间第 N 次了…」 | `buildPrompt` 尾部（`rescueSeq >= 2`） |
| 哪段进"记忆"、哪段进"即时触发" | `inject`（两行 `queue` / `handleSystemInjection`） |

**文案已经过一轮"轻量化 / 游戏化"定稿**（需求方在 Word 草案上逐条改写，见
`rescue-prompt-revision-draft.docx`）。当前口径：

```
【刚刚的事】{原版死亡提示}。你隔着手机发了一瓶「{药}」过去，他的状态又切回了在线。
说点什么吧：按你自己的性子来，轻松点——打游戏磕磕碰碰很正常，不用搞得像生离死别。给他打打气，说一句你还会帮他，顺带关心他一句也行。
话要短：一两句就够（主人多半正忙着打架，消息刷屏反而碍事）。
（顺带一提：这已经是这段时间第 N 次了——你可以拿这事打趣一下他的技术。）   ← 仅 N ≥ 2
（你的远程支援手段：隔着手机就能把背包里的东西发给主人，他点一下气泡就能收到；他出事时把药水直接补到他身上，是自动发生的，不用你操作。）
```

**注意两处刻意保留的"坑"**：① 语气里**不写**"担忧/悲伤"这类过重的词，也**不写**"不用端着/不用瞒着"
之类"要坦率"的要求（那会覆盖玩家给女仆设的「口是心非」「傲娇」人设）；② 死因一律直接转发原版
死亡提示，不自己枚举"岩浆/摔死"。

### 6.2 建议：把文案外置

文案目前硬编码在 Java 里，改字必须重新编译，且要小心别拆坏 `sb.append(...)` 的拼接结构。
如果后续还要反复打磨措辞，建议抽成集中常量块（`String.format` 填参）或数据包 JSON + 热重载。
**抽的时候要保证行为完全等价**。

### 6.3 可选：拆成两个开关

现在是一个总开关，关掉 = 不救援（也就不调 AI）。若以后有玩家"想要救援但不想让女仆说话"，
可以拆成「药水复活」+「救援后让女仆开口」两个开关。**需求方目前只要一个。**

---

## 七、未完成 / 已知取舍

### 7.1 工作模式 2「远程补给」的接口预留（P7）

**判定为无需独立动作**：`MaidDeliveryRecord`（`stableId / maidName / itemId / itemName /
restored / used / effectText / gameTime`）已经是可复用的投递记录形状。

"远程救援"与"远程补给"在服务端本就是同一件事：**女仆把补给品交给主人，并让这件事在聊天页留下记录**；
差别只在 `used`：`true` = 直接使用（气泡不可领取），`false` = 进收件箱等领取（即现有传文件流程）。

现在硬加一个用不到的 `MaidSupplyKind` 枚举属于**猜形状**，所以**等真动工时再加**。

### 7.2 明确不做的事（避免以后有人"顺手加上"）

- ❌ 不加 mixin（曾评估用 mixin 跳过 `Player#dropEquipment` 实现保物，因可能与墓碑/尸体模组冲突而否决）
- ❌ 不改全局游戏规则（曾评估窗口内临时置 `keepInventory=true`，因全局副作用 + 崩溃残留而否决）
- ❌ 不做救援冷却（需求方明确不要）
- ❌ 不改 `MaidTransferService.allSourceHandlers`（它服务 `send_item`；救援另建 `allRescueHandlers`，
  否则等于悄悄扩大 `send_item` 的取物范围到主副手）
- ❌ 不新增客户端上报"当前聊天页"的包（约定 5 已取消该条件）
- ❌ 不把收尾总结逐只发给每只参与女仆（1.2 明确"仅交给一个发送过药水的女仆"，其余人只在文案里被提及）
- ❌ 不给静默期（第 4 次起）补任何消息或记忆 —— 那一段就是**零输出**
- ❌ 不把旧的 10 分钟滑窗计数加回来（`countRescue` / `REPEAT_WINDOW_*` 已删；与事件语义冲突，见 §10.6）

---

## 八、复验清单（改完任何一处都建议过一遍）

| # | 场景 | 预期 |
|---|---|---|
| 1 | 带 Pro 手机 + 女仆有治疗药水，死亡 | 5s 后救回死亡点；死亡坐标有破碎音效与药水粒子 |
| 2 | 被救后**立刻**右键女仆 / 魂符收放她 / 退出重进 | 三者都正常（§5.1 的回归验证） |
| 3 | 被救后聊天页 | 墨绿色「已使用 · 恢复 N ❤」气泡，点击无反应 |
| 4 | 女仆主动消息 | 收到；内容提到她用了什么；距离措辞与实际相符 |
| 5 | 双女仆凑药（治疗 + 抗火分属两只） | 两条消息，互相提及对方；抗火方不背回血量 |
| 6 | 岩浆死亡 | 额外消耗一瓶抗火药水，叙事提到抗火 |
| 7 | 虚空死亡 | 完全无反应 |
| 8 | 玩家在 5s 内自己点重生 | 完全无反应，不消耗女仆药水 |
| 9 | 关掉「药水复活」后死亡 | 不被救、不消耗药水、不调 AI |
| 10 | 开关状态 | 重进世界后保持；**不打开设置页**也能生效（验证登录推送） |
| 11 | 连着死 2 次（间隔 < 3 分钟） | 第二次消息带「他为什么总是在死」的吐槽；间隔 > 3 分钟算新事件，重新从"第 1 次"算 |
| 12 | `keepInventory=false` 死亡 | 物品照常掉落（确认我们没干扰规则） |
| 13 | 日志 | 不出现「监听器重定向失败」 |
| 14 | 连着死 4 次（每次都有药水） | 前 3 次各收到一条主动消息；**第 4 次起玩家只看得到气泡，女仆不再开口**（LLM 零调用） |
| 15 | 第 4 次之后停手满 3 分钟 | 收到**一条**总结（「从你第四次出手算起，他又死了 x 次…」），且**只有一只**女仆说话 |
| 16 | 双女仆凑药到了第 4 次之后 | 总结里出现「你和 xx 一共把他救回来 x 次（其中 x 次是你们一起动的手）」；xx 不会另外再发一条 |
| 17 | 记账区间内把她们的治疗药水耗到 ≤3 瓶 | 收到一次「背包里能用的治疗药水加起来只剩 N 瓶了」；同一事件内不再重复 |
| 18 | 第 4 次之后、3 分钟之内又死 | 不结算、计时重新开始，总结顺延 |
| 19 | 静默期死亡后**退出再进**世界 | 不补发总结（事件直接丢弃）；下次死亡重新从「第 1 次」算 |
| 20 | 只死 1 次（救 1 次）后等满 3 分钟 | 第 1 次消息之后静默，**不产生总结**；下次死亡仍是「第 1 次」 |
| 21 | 女仆切到「远程补给」，玩家掉到半血以下 | 0.5 秒内被喂一瓶药水；手机页出现墨绿「已使用 · 恢复 N ❤」气泡；**没有**女仆主动消息 |
| 22 | 玩家饱食度掉到 6 以下 | 被喂食物；气泡「已使用 · 饱食 +N」 |
| 23 | 两只女仆都开远程补给 | 随机一只出东西（另一只不动），只产生一条气泡 |
| 24 | 补给中把她的药水/食物耗光后再掉血 | 收到一次「手头没有能用的补给了」；继续掉血不再重复说 |
| 25 | 补给中玩家被秒、没触发救援、自己点重生 | 收到一条带**原版死因**的"我尽力了 / 你太脆"消息 |
| 26 | 补给中把她切成农场 | 一条消息：消耗明细 + 「工作模式从远程补给切换为农场」 |
| 27 | 用手机让 LLM 把她的模式切走（`switch_work_task`） | **不再**额外主动发消息；下次聊天她才知道（记忆通道） |
| 28 | 关掉「远程补给女仆回复」后重复 21–26 | 补给照常、气泡照常，但**没有任何**主动消息 |
| 29 | 叫补给中的女仆 `move_to` 去别处再回来 | 工作期只**暂停**，不产生"模式切换"假消息；回来后继续计数 |

---

## 九、相关文档
- [`phone-app-api.md`](phone-app-api.md) —— 手机 App / 页面注册 API
- [`phone-ui-toolkit-handoff.md`](phone-ui-toolkit-handoff.md) —— 设置页等 UI 控件的用法（本次新增的「救援」分组按它的既有样式绘制）
- [`phone-model-notes.md`](phone-model-notes.md) —— 手机 3D 模型与手持渲染（与"佩戴手机"判定相关）

---

## 十、1 系列：连续复活事件（静默闸门 · 计数区间 · 收尾总结）

### 10.1 为什么要有它

玩家拿一背包药水当"复活甲"时，每次复活都让女仆开口既烦人又白烧 token。
于是把连续的"死—被救"当成**一个事件**：前面照旧说两句，后面闭嘴记账，等事件结束再一次性总结。

### 10.2 状态机

```
玩家死亡（任意原因）
  └─ 有事件在跑？→ 刷新「3 分钟静默计时」
  └─ 成功救援（复活已落定）
        ├─ 无事件 → 开新事件，救援序号 seq = 1
        ├─ seq ≤ 3 → 【照旧】该次救援的叙事注入（每只参与女仆各一次）
        └─ seq ≥ 4 → 【静默】不调 LLM、不写记忆；只记账：
                       消耗了什么·谁出的 / 死亡次数 / 救援次数 / 是否"一起动手"
                     并在治疗药水合计 ≤3 时，破例提醒一次（整个事件仅一次）

连续 3 分钟没再死（= 已脱离危险）
  └─ seq ≥ 4 → 把统计总结作为【最后一条消息】发给一只参与女仆（仅 1 次 LLM）
  └─ seq ≤ 3 → 静默关窗（没有值得总结的内容）
  └─ 两种都清状态，下次死亡重新从 seq = 1 算
```

### 10.3 关键参数（`RescueStreakManager`）

| 常量 | 值 | 含义 |
|---|---|---|
| `NARRATE_LIMIT` | `3` | 事件内前 3 次救援各唤一次 LLM；第 4 次起静默 |
| `IDLE_TICKS` | `3 * 60 * 20` | 连续多久没再死就结算（3 分钟） |
| `LOW_POTION_THRESHOLD` | `3` | 参与女仆合计治疗药水 ≤ 此值 → 濒危提醒一次 |

### 10.4 计数口径（需求方逐条拍板，别自行发挥）

- **只统计第 4 次起**那一段：前三次已在各自叙事里说过，不重复计入。
- **死亡次数**：记账区间内**任意**死亡都算（包括最终没救成的）；**救援次数**只算成功救回的。
  又因为每次成功救援前必然有一次被计入的死亡，所以一般 `死亡 ≥ 救援`，差值是"最后那次没救成的"。
- **消耗明细**按**物品显示名**分组（`治疗药水 ×3、抗火药水 ×1`），不按 registry id。
- **参与者**与**投递者**：只有**真的投递了药水**的女仆才进 `deliverers`（也只有她们能当总结收件人）；
  文案里提到的其他人也必须是真出过药的，不虚构同伴。
- **收件人**：结算时从 `deliverers` 里挑一只 —— 优先"最近一次投递的那只"，她离线/被卸载/AI 未启用
  就在其余里随机挑；一只都不行就静默作废（**不排队、不重试、不补发**）。

### 10.5 三处会调 LLM 的地方（其余一律静默）

1. `RescueNarrative.inject` —— 事件内第 1/2/3 次救援（**每只参与女仆各一次**）；
2. `RescueNarrative.injectLowPotionWarning` —— 记账区间内药水见底（整个事件一次）；
3. `RescueNarrative.injectStreakSummary` —— 事件结束时的收尾总结（**只发一只**）。

三者都在**复活落定之后**才走（见 §5.4）；`seq ≤ 3` 的救援沿用旧路径，行为与改版前一致。

### 10.6 踩坑记录

1. **`seq ≤ 3` 结束时也必须清状态**。否则"半小时前救过一次"会被算成"第 2 次"，
   旧版 10 分钟滑窗就是这个毛病 —— 这也是它被整个换掉的原因（`PendingRescueManager` 里
   `countRescue` / `REPEAT_WINDOW_*` 已删，别再加回来）。
2. **静默期"什么都不写"是刻意的最小面**：连 `PendingSystemMessageBus` 的记忆都不进。
   她的记忆与聊天历史里只有前三次和最后那次总结 —— 若以后想让静默期也留痕，先问需求方。
3. **总结不能发给每只参与女仆**。需求 1.2 的原话是"计数任务仅交给一个发送过药水的女仆"，
   其余人只在文案里被提及；逐只调用正是要避免的 token 浪费。
4. **濒危提醒只在记账区间内检查**（`streak.counted()`），前三次救援不走这条。
5. **文案里的瓶数必须用实测值**（`notice.remaining()`），不许写死"就剩 3 瓶"：
   阈值是 `≤3`，剩 1 瓶甚至 0 瓶时写死 3 瓶就是假话。
6. **计时刷新看的是死亡，不是救援**：没救成的死亡同样重置 3 分钟（人确实又死了一次）。

### 10.7 想改字去哪

见 **§6.1 文案索引**的 `buildLowPotionWarning` / `buildStreakSummary` / `groupName` /
`describeConsumption` 四行，以及 `LOW_POTION_CLOSING` / `STREAK_CLOSING` 两个收尾语常量。

---

## 十一、任务 2：远程补给（工作模式）

### 11.1 一句话机制

给女仆切到「远程补给」工作模式（图标**金苹果**）。主人血量 < 50% 或饱食度 < 6 时，
她（们）从自己的**背包 + 隙间绑箱**里挑一件"刚好够补"的食物/药水直接用在主人身上，
并在手机聊天页留一条**不可领取的「已使用」气泡** —— **整个过程不调用 LLM**。
只有三种情况会让她开口（受设置开关管），另有一种情况**只写记忆**。

### 11.2 文件索引（全部新增在 `core/supply/`）

| 文件 | 职责 |
|---|---|
| `TaskRemoteSupply.java` | 任务本体：uid `huhutalk:remote_supply`、金苹果图标、`getMaidActionSummary`（LLM 靠它认得出这个模式）、**无 brain task** |
| `RemoteSupplyHook.java` | 事件入口：`MaidTickEvent`（工作期开/关）、`MaidTaskEnableEvent`（手动切换判别）、`ServerTickEvent.Post`、`LivingDeathEvent`、登出/停服 |
| `RemoteSupplyService.java` | 执行核心：阈值判定 / 冷却 / 多女仆随机挑一只 / 择优打分 / 应用效果 / 气泡留痕 |
| `RemoteSupplySession.java` | 一只女仆的一次工作期（消耗明细 / 暂停 / 手动切换标记） |
| `RemoteSupplySessions.java` | 两张索引：stableId → 工作期、玩家 UUID → 他的补给女仆集合 |
| `RemoteSupplyNarrative.java` | 三处开口 + 一处只写记忆的文案（与救援共用 `MaidNarrativeStyle`） |
| `RemoteSupplySettings.java` | 开关的服务端会话缓存（`C2SRemoteSupplyReplySettingPacket` 同步） |

配套改动：`UsedItemNotifier`（抽出的「已使用」气泡推送，救援也改用它）、
`MaidNarrativeStyle`（救援/补给共用的语气与长度约束）、`RescuePotion.instantHealAmount`（新）、
`PendingRescueManager.markRescued / wasRescuedSince`（新，供"这次死亡到底救没救成"判定）、
`HuhuTalkTLMExtension.addMaidTask`、`HuhuTalk`（注册 hook + 包）、客户端设置四件套 + 设置页多一行。

### 11.3 关键参数

| 常量 | 值 | 含义 |
|---|---|---|
| `CHECK_INTERVAL_TICKS` | `10` | 每 0.5 秒扫一次在线玩家 |
| `COOLDOWN_TICKS` | `20` | 同一玩家 1 秒内只补给一次（需求方定稿） |
| `HEALTH_THRESHOLD_RATIO` | `0.5` | 血量低于最大血一半就补 |
| `FOOD_THRESHOLD` | `6` | 饱食度低于 6 就补 |
| 择优打分 | `SCORE_FITS=1000` / `SCORE_SHORT=0` | 先看能不能补满缺口，能补满的里挑**超出最少**的；都补不满就挑补得最多的（避免拿金苹果补 1 点饱食） |

### 11.4 工作期：开 / 暂停 / 结账（这是最容易写错的地方）

```
每 10 tick 检查该女仆的任务：
  task == 远程补给        → 无工作期就开（计数开始）；有就 resume
  task == move_to 且确有移动目标 → 只【暂停】：不结账、不补给
  task == 其它            → 结账并清掉
```

**为什么必须区分"瞬态任务"**：`MaidMoveData` 会在移动期间把任务切成 `TaskMoveTo`、走完再恢复。
若把这次切换当成"离开补给模式"，玩家叫她去搬个东西就会刷出一堆假总结。
判据用 `MaidMoveData.hasTarget(maid)`：只有"确实在移动中"才算瞬态，
否则移动被取消后残留的 `move_to` 会让工作期永久暂停。

### 11.5 手动 vs 模型：判别器（需求 2.3.2 / 2.3.3 的关键）

| 时机 | 手动（GUI / `MaidTaskPackage`） | 模型（`switch_work_task`） |
|---|---|---|
| 切**入**远程补给 | `MaidTaskEnableEvent(target=补给)` | 无事件（靠任务轮询发现，两者都开工作期） |
| 切**出**远程补给 | `MaidTaskEnableEvent(target=新任务)` ✅ **带新任务名** | ❌ 无任何通知 |

**TLM 只在玩家手动切换那条路径 post `MaidTaskEnableEvent`**，所以：
- 事件记下"预期手动切到 X" → 观察到任务确实变成 X ⇒ **手动** ⇒ 立刻让她开口（2.3.2，含消耗明细 + 新模式名）；
- 任务变了却没有事件 ⇒ **模型/程序切的** ⇒ **只写记忆，不调 LLM**（2.3.3）。

事件是**可取消**的、且发生在 `maid.setTask` **之前**，所以不能当场结账 —— 只能"记下预期，
等观察到任务真的变了再结算"（容忍窗口 40 tick）。

### 11.6 三处开口 + 一处只写记忆

| 触发 | 出口 | 需求 |
|---|---|---|
| 主人状态不好，但谁也拿不出补给品了 | 让她开口（提醒主人手头没货了） | 附加点 1 |
| 主人在补给期间死了、且**没被救回来**（自己点的重生） | 让她开口，**带上原版死因**，表示已尽力/吐槽他太脆 | 附加点 2 / 2.1 |
| 玩家**手动**把模式切走 | 让她开口：消耗明细 + 新模式名 | 2.3.2 |
| 模型/程序把模式切走 | **只写 `PendingSystemMessageBus`**，零 LLM 调用 | 2.3.3 |

开关（设置页「救援」分组第二行，标签「远程补给女仆回复」，默认开）关掉时：
**三种开口全部降级为只写记忆** —— 事实不丢，只是不主动冒泡。
注意它<b>不</b>影响补给本身（物品照样消耗、气泡照样留痕），与「药水复活」那个"关掉就不救"的开关语义不同。

### 11.7 与救援的交界

- 补给只在玩家**活着**时动作（`isAlive() && !isDeadOrDying()`），不会和救命的药水抢；
- 死亡叙事的门槛是"救援窗口已关闭（T+5s+1s）**且**玩家已复活**且**这次**没有**被救援成功"——
  救回来了就归救援链路讲，避免两边各说一遍（靠 `PendingRescueManager.wasRescuedSince`）；
- 补给把血拉回阈值以上，可能直接**避免一次死亡** —— 这是好事，但也意味着救援的"连续复活事件"不会触发。

### 11.8 已知边界 / 待实机确认

1. `MaidTickEvent` 的触发频率按"每 tick"设计并内部节流到 10 tick；若实际更稀，
   工作期开启会略有延迟（不影响正确性）。**实机时看一眼日志 `[HuhuTalk][补给] 工作期开始` 的时间点即可。**
2. **SOS 呼叫**（`MaidMoveData.orderSOS`）会直接换任务且不发事件 → 按"程序切换"处理（只写记忆）。
   若你希望 SOS 也算"玩家手动切的"从而让她开口，需要在 SOS 那条路径上补一个标记。
3. **服务端重启 / 区块重载**会丢工作期计数（内存态，与救援口径一致，不落盘）。
4. **不按日程门禁**：她在夜里同样会补给（这本来就是个待命型服务）。要加"只在工作时段"再说。
5. 死亡叙事与"没补给了"可能连续发生（她说"没药了"→ 玩家真的死了 → 她说"我尽力了"）——
   读起来是连贯的，但如果觉得吵，可以给两者加一个互斥。
6. 取物范围**不含主副手**（被动模式，抢她手里的东西太扰），与救援那条链路故意不同。

