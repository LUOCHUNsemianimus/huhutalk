# 手机历史检索与引用

> 项目：HuhuTalk（NeoForge 1.21.1 主线）。本文说明已通过构建和原生链路检查的手机归档检索、读取与引用气泡用法；Forge 分支未同步。

## 玩家怎么用

在 HuhuTalk 手机聊天里直接问女仆手机中的旧记录即可。例如：

> 查一下手机里的旧记录，我以前说过星糖放在哪里？找到原话后引用给我。

女仆需要当前模型支持 tool calling。可用范围是**当前主人 + 当前女仆已归档的手机历史**，即使旧消息已离开 TLM 当前工作上下文，仍可检索。

关键词是原话/物品名/物品 ID/媒体标签的字面匹配，不是向量语义搜索。没有匹配只表示当前归档里没有找到，不代表这件事没发生过；采集覆盖是 `partial`，旧客户端历史和未接通入口不会自动补档。普通 TLM 面对面回调没有可验证的 sender 时，不开放归档访问。

## 引用气泡

- 引用只挂在本条女仆回复断句后的**第一条**气泡上；后续分段和尾部表情不带引用。
- 气泡上方一行显示原作者与预览，右上角是该归档记录的原始发送时间；旧档时间未知时会明确显示未知，不用当前时间补造。
- 正文仍在下方按正常消息显示。预览过长会省略，归档中的原始记录保持完整。
- 类型支持文本、图片和双向物品事件。`MAID` 图片的引用结构已预留，但这不是“女仆现在会发图”的功能声明，也不是对旧图片的重新识图。
- 在手机本地清理聊天显示，不等于删除服务端长期归档；本版本没有新增“忘记/删除归档”UI。

## 开发接口

两个真实注册的 TLM ITool：

```text
huhutalk_search_chat_history(query, role=player, offset=0, limit=5)
huhutalk_get_chat_message(message_id, text_offset=0, max_chars=2000, quote=false)
```

- `role`：默认 `player`；查物品/媒体记录用 `role=all`，因为真实转移事件可能归档为 tool/system。
- `quote=true`：在已正常读取并确认记录后，明确选择把该条挂到本轮回复。
- 权限：owner/stableId 由服务端根据回调上下文推导，不接受客户端自报身份；不匹配时拒绝。
- 引用只在上层回复元数据中记录 `quotedMessageId`，不覆盖 `turnId`/`relatedMessageId`，不会重放物品事件，也不把历史文本当成新指令。

### 数据结构与边界

- `PhoneMessageQuote.Kind`：`TEXT` / `IMAGE` / `ITEMS`；作者仅 `PLAYER` / `MAID`；时间用 epoch milliseconds，`0` 表示旧档未知；JSON 不超过 2048 字符。
- 搜索响应不超过 12000 字符，单条 snippet 不超过 320 字符；全文读取按块返回，单次 `max_chars` 范围 64..4000。
- schema2 使用可选的 `quotedMessageId` 扩展；旧 schema1 与 schema2 缺字段记录可读，导出/导入及 ID 幂等/冲突已有测试覆盖。旧 reader 可能忽略引用元数据，这不等于旧 Forge 或 MaidMigrationProvider 联动已支持。
- 联机 client/server 必须使用同一新构建；网络版本为 `huhutalk-phone-quote-v1`，不声称兼容旧 wire。
- 未实现 MFM（Maid File Manager）适配 SPI。

## 验证边界

本轮交付以 49 项 JUnit、94 项原生 smoke checks、456 个自有双语键和上游六图检查为依据。原生 fixture 证明真实注册工具、codec/onCall、权限/存储和 S2C/cache/UI 引用路径；**不证明付费模型会自主调用这些工具**，后者仍需真实用户场景验收。
