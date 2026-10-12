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

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = pathlib.Path(r"D:\Moddevelop\模组配图")
MANUAL_DIR = ROOT / "src" / "main" / "resources" / "assets" / "huhutalk" / "manual"
ICON_DIR = ROOT / "src" / "main" / "resources" / "assets" / "huhutalk" / "textures" / "icon"

FIGS_AND_ICONS_DOC = """（脚本说明）"""

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


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not SRC.is_dir():
        print(f"ERROR: 找不到素材目录 {SRC}", file=sys.stderr)
        return 2

    print(f"素材目录：{SRC}")
    print(f"手册配图 -> {MANUAL_DIR.relative_to(ROOT)}")
    for name, dst_name in sorted(FIGURES.items(), key=lambda kv: int(kv[0][1:-4])):
        print("  " + copy_one(SRC / name, MANUAL_DIR / dst_name, args.dry_run))
    print(f"App 图标 -> {ICON_DIR.relative_to(ROOT)}")
    for name, dst_name in ICONS.items():
        print("  " + copy_one(SRC / name, ICON_DIR / dst_name, args.dry_run))
    return 0


if __name__ == "__main__":
    sys.exit(main())
