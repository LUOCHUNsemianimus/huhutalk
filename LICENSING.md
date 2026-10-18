# 授权范围 / Licensing scope

HuhuTalk uses component-specific licensing. Installing or receiving the JAR
does not make every embedded file available under the code license.
HuhuTalk 按内容分别授权，不能将整份 JAR 的所有内容统一视为 Apache 授权。

| 内容 / Component | 授权 / License |
| --- | --- |
| 自有 Java 代码、脚本、构建文件、语言 JSON 及技术配置 / Original code, scripts, build files, language JSON and technical configuration | [Apache-2.0](LICENSES/Apache-2.0.txt) |
| 罗春制作的模型、贴图、视觉动画、App 图标及对应美术源文件 / Original visual models, textures, animations, icons and editable art sources by 罗春 | [CC BY-NC-SA 4.0](LICENSES/CC-BY-NC-SA-4.0.md) |
| 手册截图 / Manual screenshots | 罗春的截图与布局贡献按 CC BY-NC-SA 4.0；画面中的第三方素材保留原权利 / CC BY-NC-SA 4.0 for the capture/layout contribution; depicted third-party material retains its own rights |
| Pixabay 提示音及 OGG 源副本 / Pixabay notification sounds and OGG source copies | 第三方例外，不改套 Apache/CC；原下载作品页和具体许可记录未找回 / Third-party exception, not relicensed; exact item/download records unavailable |
| 运行时读取的车万女仆表情包 / Touhou Little Maid emoji read at runtime | 原项目资源许可与署名 / Upstream asset license and attribution |
| NeoForged MDK 模板 / NeoForged MDK template | [保留模板 MIT 声明 / Retained MIT notice](TEMPLATE_LICENSE.txt) |

`ASSET_LICENSES.json` 列出受覆盖的美术/音频文件、SHA256、署名和来源状态。
`THIRD_PARTY_NOTICES.md` 记录第三方例外。原始创作的模型/动画 JSON 属于美术；语言、
物品挂接和方块状态等技术 JSON 仍属代码侧配置。文件级明确声明及第三方原许可优先。

`ASSET_LICENSES.json` lists art/audio paths, hashes, attribution and provenance
status. See `THIRD_PARTY_NOTICES.md` for exceptions. Visual model/animation JSON
is artwork; language, item wiring and blockstate configuration remains on the
code/configuration side. Explicit file notices and upstream licenses take precedence.

## 非商业性与原有授权 / NonCommercial and existing grants

自有美术的 CC 许可包含非商业性使用条件；代码的 Apache 许可没有因此增加非商业限制。
目前没有额外授予第三方资源的商业许可，也没有自动为平台收益分成或商业整合包开例外。
如需额外许可，应联系相应权利人；不能由 HuhuTalk 为他人的作品授予许可。

The art license includes NonCommercial terms; the Apache code license does not
gain a noncommercial restriction. No additional commercial permission for third-party
assets, platform reward schemes or commercial modpacks is granted here. Additional
permission must come from the relevant rights holder.

本次明确新版本的授权边界，不追溯撤销已合法授出的历史权限。旧版本中曾有效获得的
MIT、Apache 或其他许可仍按其原条件适用；此声明不保证此前未分项标注的美术不存在旧授权。

This clarification does not revoke rights validly granted for earlier copies.
Previously granted MIT, Apache or other permissions remain governed by their
original terms, including any valid earlier grants for artwork.

## 平台与再分发 / Platforms and redistribution

CurseForge 等平台如只有一个项目许可栏，使用 Custom，并分别描述代码、美术及第三方例外，
不要只选择 Apache 后让人误以为整包美术都可按 Apache 使用。建议平台说明：

If a platform provides one project license field, use Custom and disclose the
component scopes rather than describing the complete package as Apache-only:

> Code: Apache-2.0. Original HuhuTalk visual artwork: CC BY-NC-SA 4.0.
> Third-party assets retain their own terms; Pixabay audio is excluded from those grants.
> See LICENSE, ASSET_LICENSES.json and THIRD_PARTY_NOTICES.md for scope and provenance.

构建 JAR 随附上述声明及许可证文件，位于 `META-INF/`。音效缺失的来源记录必须如实保留，
不能将“用户确认来自 Pixabay”写成已独立完成每个文件的授权审查。

The built JAR carries these notices under `META-INF/`. Missing sound provenance
records are disclosed; a maintainer's source statement is not represented as
independent clearance of each audio file.
