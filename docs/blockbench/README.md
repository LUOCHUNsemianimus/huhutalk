# Blockbench 源文件

| 文件 | 说明 |
|---|---|
| `huhutalk_phone.bbmodel` | 狐狐手机 3D 物品模型（Blockbench 可编辑源文件） |

修改后重新导出到：

- 模型：`src/main/resources/assets/huhutalk/models/item/phone_3d.json`（Java Block/Item 编码器）
- 贴图：`src/main/resources/assets/huhutalk/textures/item/phone_3d.png`

进游戏前记得 `./gradlew build` 或直接 `runClient`。

> ⚠️ **只换贴图时不要用 BB 导出覆盖模型 JSON** —— BB 的 java_block 编码器不会写 `item/`
> 贴图路径，而且会用 `Project.display_settings` 里那份（可能过期的）`display` 覆盖掉调好的值。
> 可靠的贴图导出方式与其它 Blockbench 坑，见 [`../phone-model-notes.md`](../phone-model-notes.md)
> 的「Blockbench 操作坑」。随手记：`Blockbench.writeFile` 返回 `undefined`（异步），
> 写完请用 `certutil -hashfile` 核对。
