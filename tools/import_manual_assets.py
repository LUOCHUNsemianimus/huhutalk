#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「模组配图」里的素材导入模组资源目录（幂等：内容一样就跳过）。

来源（需求方给的文件夹，文件名就是它的用途）：
    D:\\Moddevelop\\模组配图\\图1.png … 图17.png   ->  assets/huhutalk/manual/manual_fig_N.png   （手机手册的配图）
    D:\\Moddevelop\\模组配图\\设置.png               ->  assets/huhutalk/textures/icon/settings.png （桌面「设置」App 图标）
    D:\\Moddevelop\\模组配图\\图库.png               ->  assets/huhutalk/textures/icon/gallery.png  （桌面「图库」App 图标）
    D:\\Moddevelop\\模组配图\\相机.png               ->  assets/huhutalk/textures/icon/camera.png   （桌面「相机」App 图标）

为什么要统一改成 ASCII 文件名：这两个目录里的名字会被写进代码（imageKey / ResourceLocation），
带中文的路径在资源加载与大小写敏感性上都更容易出意外，而玩家侧完全看不到这些文件名。

用法：
    python tools/import_manual_assets.py            # 复制/覆盖
    python tools/import_manual_assets.py --dry-run  # 只看会做什么
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import struct
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import sprite_alpha  # noqa: E402  （同目录工具：颜色外扩 / 降采样 / PNG 读写）

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = pathlib.Path(r"D:\Moddevelop\模组配图")
MANUAL_DIR = ROOT / "src" / "main" / "resources" / "assets" / "huhutalk" / "manual"
ICON_DIR = ROOT / "src" / "main" / "resources" / "assets" / "huhutalk" / "textures" / "icon"

# 图标要走"精灵图处理"：颜色外扩 + 降采样，并附一个 .mcmeta 让 GPU 走线性过滤。
# 为什么必须这么干（2026-10-12 读 Minecraft 源码 + 离线模拟定案）：
#   * 手机上每格图标实际只有 ~28 真实像素，而素材是 256px；
#   * SimpleTexture 在没有 .mcmeta 时是 **最近邻（GL_NEAREST）+ 不生成 mipmap**，
#     最近邻只挑一个像素、不做平均 → PS 里柔和的半透明边缘被抽成一圈浅色硬边
#     （浅底图标如「设置」#EEEEEE 上就是"白描边"）；
#   * 素材透明区的 RGB 是纯白（26376 个像素），一旦启用线性过滤就会渗进边缘，
#     所以**先做颜色外扩**（把真实颜色扩散进透明区、alpha 保持 0）再开过滤。
ICON_TARGET = 96
ICON_MCMETA = '{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n'

FIGURES = {f"图{i}.png": f"manual_fig_{i}.png" for i in range(1, 18)}
ICONS = {
    "设置.png": "settings.png",
    "图库.png": "gallery.png",
    "相机.png": "camera.png",
    # 桌面「手册」的图标（槽位 2）**还没画**：以后把 手册.png 丢进「模组配图」，
    # 这个脚本会自动把它拷成 textures/icon/manual.png；
    # 然后 MainPage.buildApps() 里把「手册」那格的 📖 换成
    # ResourceLocation("huhutalk", "textures/icon/manual.png")（一行改动）。
    "手册.png": "manual.png",
}
# 同一张图的多个候选源（「设置2.png」是需求方改过的调整版）。
# 取**修改时间最新**的那个：免得改了新版，却因为脚本只认旧文件名而静默用回旧图。
ALIASES = {
    "设置.png": ["设置2.png", "设置试图补救.png", "设置修补1.png"],
    "图库.png": ["图库修补1.png"],
    "相机.png": ["相机修补1.png"],
}
# 允许"暂时没有"的素材：缺了只提示、不当错误（避免脚本一跑就一片红）。
OPTIONAL = {"手册.png"}


def png_size(path: pathlib.Path) -> tuple[int, int] | None:
    """读 PNG 的 IHDR；不是 PNG 返回 None（顺手当一道格式校验）。"""
    head = path.read_bytes()[:24]
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def copy_one(src: pathlib.Path, dst: pathlib.Path, dry: bool) -> str:
    if not src.exists():
        return f"MISSING  {src.name}"
    size = png_size(src)
    if size is None:
        return f"NOT_PNG  {src.name}（Minecraft 的 NativeImage 只认 PNG/JPG，先转格式）"
    if dst.exists() and dst.read_bytes() == src.read_bytes():
        return f"same     {dst.relative_to(ROOT)}  ({size[0]}x{size[1]})"
    if not dry:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    action = "would-cp" if dry else "copied  "
    return f"{action} {src.name} -> {dst.relative_to(ROOT)}  ({size[0]}x{size[1]}, {src.stat().st_size / 1024:.1f} KiB)"


def copy_icon(src: pathlib.Path, dst: pathlib.Path, dry: bool) -> str:
    """图标专用：颜色外扩 + 降采样 + 写 .mcmeta（详见文件头 ICON_* 注释）。"""
    if not src.exists():
        return (f"SKIP     {src.name}（还没提供）" if src.name in OPTIONAL
                else f"MISSING  {src.name}")
    size = png_size(src)
    if size is None:
        return f"NOT_PNG  {src.name}（Minecraft 的 NativeImage 只认 PNG/JPG，先转格式）"
    if dry:
        return (f"would-fix {src.name} -> {dst.relative_to(ROOT)}"
                f"（颜色外扩 + {ICON_TARGET}px + .mcmeta blur/clamp）")
    w, h, ch, data = sprite_alpha.read_png(src)
    px = sprite_alpha.to_rgba(w, h, ch, data)
    if ch == 3:
        return f"NO_ALPHA {src.name}（没有透明通道：先去 PS 导出带透明的 PNG-24）"
    # 调色板导出常常没透明度（Piskel「8 位图片」/ PS 索引色）：透明区会变成实心白。
    # 这种图直接上就是手机屏上一个白方块 —— 检测到"几乎没有透明像素"就自动抠背景。
    n_trans = sum(1 for i in range(w * h) if px[i * 4 + 3] == 0)
    keyed = 0
    if n_trans < w * h * 0.02:
        keyed = sprite_alpha.key_background(px, w, h, (255, 255, 255), 0)
    n_bleed = sprite_alpha.decontaminate(px, w, h, 250)
    nw, nh, out = sprite_alpha.downsample(px, w, h, ICON_TARGET)
    # 降采样会把"整块全透明"的输出像素写成 (0,0,0,0)，等于把上面的处理抹掉 →
    # 再处理一次：保证**最终这张 96px 图**里，透明区和半透明区的 RGB 都是内容色。
    n_bleed += sprite_alpha.decontaminate(out, nw, nh, 250)
    dst.parent.mkdir(parents=True, exist_ok=True)
    sprite_alpha.write_png(dst, nw, nh, bytes(out))
    # .mcmeta 与贴图同名同目录：Minecraft 读到它才会启用线性过滤（blur）与边缘钳制（clamp）
    dst.with_name(dst.name + ".mcmeta").write_text(ICON_MCMETA, encoding="utf-8")
    return (f"fixed    {src.name} -> {dst.relative_to(ROOT)}"
            f"  ({w}x{h} -> {nw}x{nh}"
            + (f"，抠背景 {keyed} px" if keyed else "")
            + f"，边缘去污 {n_bleed} px，+ .mcmeta)")


def pick_source(name: str) -> pathlib.Path:
    """在候选源里选**修改时间最新**的那个；都不存在就返回原名（让下游报 MISSING/SKIP）。"""
    cands = [SRC / name] + [SRC / a for a in ALIASES.get(name, [])]
    existing = [p for p in cands if p.exists()]
    if not existing:
        return cands[0]
    best = max(existing, key=lambda p: p.stat().st_mtime)
    return best


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not SRC.is_dir():
        print(f"ERROR: 找不到素材目录 {SRC}", file=sys.stderr)
        return 2

    print(f"素材目录：{SRC}")
    print(f"手册配图 -> {MANUAL_DIR.relative_to(ROOT)}（原样拷贝：截图不透明，不需要精灵图处理）")
    for name, dst_name in sorted(FIGURES.items(), key=lambda kv: int(kv[0][1:-4])):
        print("  " + copy_one(SRC / name, MANUAL_DIR / dst_name, args.dry_run))
    print(f"App 图标 -> {ICON_DIR.relative_to(ROOT)}（颜色外扩 + {ICON_TARGET}px + .mcmeta）")
    for name, dst_name in ICONS.items():
        src = pick_source(name)
        note = "" if src.name == name else f"（候选源里 {src.name} 最新）"
        print("  " + copy_icon(src, ICON_DIR / dst_name, args.dry_run) + note)
    return 0


if __name__ == "__main__":
    sys.exit(main())
