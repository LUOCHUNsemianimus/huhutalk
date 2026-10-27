# 2026-10-27：Forge 补给与死亡救援验收排查记录

- **日期**：2026-10-27（北京时间）
- **范围**：Forge 1.20.1 候选版的「活人远程补给 / 死亡救援」验收问题排查。
- **状态**：排查结束。用户于 2026-10-27 确认忘记给女仆佩戴手机，并确认正常使用场景的功能逻辑验收通过。原排查过程与当时尚待确认的事项保留在下文；本次未修改生产补给／救援逻辑。

## 问题

用户在实际验收中反馈：远程补给任务可以选中，但女仆背包里有药水时不会替玩家使用；死亡后等待
超过 10 秒仍未复活。图片识别、发物工具、移动工具已可用，保持「先复活再 LLM」的原设计。

## 实际证据

### 用户存档只读检查

- 证据：`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work/user-save-supply-inspection-01.jsonl`
  与只读脚本 `D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work/inspect_supply_save.py`。
- 用户 pianchang 存档「新的世界」对应女仆（stableId `31d30e78-4757-45c5-9de4-1cd3fdbb63b3`）的 NBT：
  `MaidTask=huhutalk:remote_supply`，`MaidInventory` 12–23 槽共 12 瓶 `strong_healing`，
  `MaidBaubleInventory.Items=[]`（配饰栏为空）。
- 用户日志：12:29 补给工作期开始、死亡已登记；12:30:15 提示没有合格女仆并作废本次救援。
- 只读检查未读取 AI 历史或凭据，未修改用户存档。

### 隔离客户端三运行

三运行复用同一隔离世界 `run/saves/port-smoke`；测试专用设置、隔离世界路径校验在运行前生效，
未修改用户实例或存档。结果文件与日志均在
`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work/`。

| 运行 | 命令 | 结果 | 关键观测 |
| --- | --- | --- | --- |
| 01 手机正例 | `gradlew.bat runClient -PsupplyRescueSmoke` | `status=PASS` | 女仆佩戴 Pro；health 6→10、药水 3→2；food 2→7、面包 3→0（可能含女仆自食干扰，不将全部消耗归因于玩家补给）；死亡登记并在约 100 tick 后按死亡维度/坐标/视角复活，新实例 8 HP，床位登记保留 |
| 02 掉落死亡正例 | `gradlew.bat runClient -PsupplyRescueSmoke -PsupplyRescueDropInventory` | `status=PASS` | `keepInventory=false` 下补给/死亡救援三阶段仍通过；药水只扣 1，pending 与 redirect 清理正确 |
| 03 无女仆手机负例 | `gradlew.bat runClient -PsupplyRescueSmoke -PsupplyRescueNoMaidPhone` | `status=FAIL`（预期对照） | 女仆配饰为空、`maidPhoneTier=null`；health 6→6、药水 3→3；food 2→2、面包 3→3；死亡事件与 pending 登记到达，但约 140 tick 后仍处于死亡，药水未扣；owner/task/药水解析正常 |

- 结果：`supply-rescue-result-01.json`、`supply-rescue-result-02-drop.json`、
  `supply-rescue-result-03-no-phone.json`。
- 日志：`supply-rescue-runtime-01.log`、`supply-rescue-runtime-02-drop.log`、
  `supply-rescue-runtime-03-no-phone.log`。
- **负例的 `status=FAIL` 是保留原正例断言的预期对照**，不是测试框架报错，也不能把 Gradle
  exit 0 当作行为通过。它说明缺手机时供给与救援被执行门控，而不是证明已有生产缺陷被修复。

### 产物对比

- 证据：`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work/supply-rescue-artifact-comparison.json`。
- 重建 jar 的 479 个非 manifest 条目与用户正在验收的候选 jar 逐个 SHA-256 完全一致，差异列表为空，
  gametest/smoke 类未进入正式 jar。
- 因此这次调查不需要用户更换业务代码 jar 来获得补给/救援；问题定位在当前存档的女仆手机前提，
  而不是要求用户升级到另一份实现。

## 结论与处理

1. **当前存档满足「女仆没有佩戴手机」这一解释**：`PhoneHelper` 以女仆配饰栏是否存在任意
   HuhuTalk 手机为联络前提；配饰为空时，补给和死亡救援都不会执行。这与负例 03 的观测一致，也与
   用户存档 NBT 一致。
2. **不取消手机门控**：门控是既有设计（手机是联络与远程能力的入口），本次不修改、不绕过。
   建议用户先给该女仆的配饰栏戴上手机（任意型号均可被联络；建议 Pro，青春版仅在 Wi-Fi 覆盖内
   保持区块加载），再按验收清单复测补给与死亡救援。
3. **调查当时的边界**：下述疑问在调查时尚未确认；用户随后已明确是忘记佩戴手机。
   因此只能说「当前存档缺手机足以解释本次现象」，不能说「手机遗失 bug 已排除」，也不能说
   「生产逻辑已修复」。若补戴手机后仍复现，应重新采集日志与 NBT，按新现象另开排查。

## 本次变更边界

- 本次排查相关代码变更只在测试工程与构建网络路由：`-PsupplyRescueNoMaidPhone` 测试开关、
  gametest 驱动中「无手机负例」的佩戴条件、以及 `gradle.properties` 的代理注释/两站直连；
  另更新验收清单与本记录。
- 未修改 `src/main` 生产 Java 或资源，未修改用户 pianchang 实例、存档、配置或凭据，
  未发送真实 LLM 请求。
- 构建参数中 TLS 证书校验始终开启，依赖版本与既有检查未变；仅把已验证可直连的
  `libraries.minecraft.net`、`maven.minecraftforge.net` 列入 `nonProxyHosts`，其余仓库继续走代理。

## 测试命令与证据根路径

```bat
gradlew.bat build --offline --console=plain
gradlew.bat runClient -PsupplyRescueSmoke
gradlew.bat runClient -PsupplyRescueSmoke -PsupplyRescueDropInventory
gradlew.bat runClient -PsupplyRescueSmoke -PsupplyRescueNoMaidPhone
```

- 本工程工作流根：`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/`
- 运行结果、日志与 NBT 证据：`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work/`
- 隔离运行现场：`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/runtime/supply-rescue-smoke/`
- 固定构建证据：`D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/runs/` 下对应任务的
  `build-NN.log` 与 `evidence.json`。

## 未确认项

- 用户验收前是否曾给该女仆佩戴手机、之后是否发生丢失；现有证据不能回答。
- 用户真实存档中的手机物品、TLM 配饰同步或客户端显示是否存在独立问题；本次未复现、未验证。
- 完整真人流程（真实 LLM、麦克风、实际救援叙事）仍需用户按验收清单人工确认，本文不代替验收。

## 用户最终确认（2026-10-27）

用户确认确实忘记佩戴女仆手机，认可死亡掉落玩家手机不影响已登记的复活；确认正常使用场景功能逻辑验收通过，并授权提交、推送 Git 及创建 GitHub Release。模组网站由用户自行上传。
