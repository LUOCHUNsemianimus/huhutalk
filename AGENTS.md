# HuhuTalk Forge 1.20.1 移植工程 工作流入口

本仓库项目ID：`huhutalk-forge-1201`，分支`port/forge-1.20.1`。遵守 `D:/Moddevelop/AGENTS.md` 与 `D:/Moddevelop/.workflow/README.md` 的多模组纪律。
使用 `python D:/Moddevelop/.workflow/project.py huhutalk-forge-1201 workflow init <任务卡绝对路径>`，下游模块使用同入口的 `module run/continue/close/status`。
本项目工作文件在 `D:/Moddevelop/.workflow/projects/huhutalk-forge-1201/work`，证据在该项目 `runs`，隔离运行在 `runtime`。
目标为Minecraft1.20.1 / Forge47.4.16 / Java17 / 车万女仆1.5.3 Forge。不得更改NeoForge主工程或用户原pianchang实例；当前继承源码仍待分步迁移，不能因为版本目录名就声称构建已适配。
不得从其他模组复制完整会话历史，跨模组不得续派同一会话。没有Git HEAD的目录先保留现场，不能假造构建证据。
