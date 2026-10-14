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

# ⚠ 图标流水线在 2026-10 的实机复查后**改回「原图直上」**，别再改回去：
#   * 旧流水线（去污 → 降采样到 96px → 再去污 → .mcmeta）的依据是
#     "手机上每格图标实际只有 ~28 真实像素"。**这个前提是错的**：
#     28 是 **GUI 逻辑像素**，屏幕上真正占的是 **设备像素 = 逻辑 × GUI Scale**。
#     按排版公式重算（iconW = (phoneWidth - 4*margin)/3，见 MainPage.appRect）：
#       1080p 窗口 → 图标 112~201 设备像素；1440p → 160~268；480p → 54~90。
#     也就是说**正常情况下图标一直是"被放大显示"的**，把 256 降到 96 等于白丢 4/5 的像素，
#     放大后又把 96 网格上的硬台阶一并放大 —— 这就是实机看到的"锯齿/毛边"。
#   * 于是：**原图分辨率直上**（ICON_TARGET = 0），像素一个都不动，只补 .mcmeta。
#   * .mcmeta 必须留：没有它 SimpleTexture 走 **最近邻（GL_NEAREST）+ 不生成 mipmap**，
#     只挑一个像素、不做平均 → 柔和的半透明边缘被抽成一圈硬边。
#   * 去污/颜色外扩（把透明区的白 RGB 换成内容色）**仍然是防白边的那一半**：
#     线性过滤对 RGBA 是各通道独立插值的，透明区的纯白会被插进轮廓。
#     在 256 原图上实测这一步无视觉代价（与不做相比 MAE 0.94 → 0.94），
#     所以留成开关：实机若看到白边，把 ICON_BLEED 改 True 重跑即可（不必降采样）。
ICON_TARGET = 0        # 0 = 不降采样（用原图分辨率）；>0 = 降到该长边（旧行为是 96）
# 2026-10 实机验收：原图直上时**确实有一圈淡淡的白边**（但不扎眼），
# 说明"透明区纯白 RGB 被线性过滤插进轮廓"这一条是真的 —— 所以去污这半步要留下。
# 它把非全不透明像素的 RGB 换成最近实心像素的颜色，**alpha 一个不动**，
# 实测与原图相比 MAE 0.94 → 0.94（无视觉代价）。
ICON_BLEED = True      # True = 只清理透明区 RGB（不降采样时也单独生效）
ICON_MCMETA = '{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n'

FIGURES = {f"图{i}.png": f"manual_fig_{i}.png" for i in range(1, 18)}

# 目标图标 -> **候选源，按顺序取第一个存在的**。
#
# 为什么不再按"修改时间最新"自动挑：那是自作主张。需求方换回旧版时，
# 脚本会静默挑到磁盘上更新的那个（已经踩过一次），而且看不出挑了谁。
# 现在谁生效由这张表说了算，改一行就能切版本，日志里也会写明用的是哪个文件。
#
# 已废弃的变体（留在素材目录里备查，但不参与导入，因为渲染器的 alpha 问题已修复，
# 它们当初只是为了绕开那个 bug 而把边"缩 1 像素"）：
#     设置2.png、设置试图补救.png、设置修补1.png、图库修补1.png、相机修补1.png
ICON_SOURCES = {
    "settings.png": ["设置.png", "设置2.png"],
    "gallery.png": ["图库.png"],
    "camera.png": ["相机.png"],
    # 桌面「手册」的图标（槽位 2）**还没画**：以后把 手册.png 丢进「模组配图」，
    # 这个脚本会自动把它拷成 textures/icon/manual.png；
    # 然后 MainPage.buildApps() 里把「手册」那格的 📖 换成
    # ResourceLocation("huhutalk", "textures/icon/manual.png")（一行改动）。
    "manual.png": ["手册.png"],
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
    """图标专用。

    三种模式（由 {@link ICON_TARGET} / {@link ICON_BLEED} 决定）：
      * 默认（ICON_TARGET=0, ICON_BLEED=False）：**原图直上**，逐字节拷贝 + 写 .mcmeta；
      * ICON_BLEED=True：只把透明区的 RGB 换成内容色，分辨率不动，再写 .mcmeta；
      * ICON_TARGET>0：旧的降采样路径（去污 → 降采样 → 再去污 + .mcmeta）。
    """
    if not src.exists():
        return (f"SKIP     {src.name}（还没提供）" if src.name in OPTIONAL
                else f"MISSING  {src.name}")
    size = png_size(src)
    if size is None:
        return f"NOT_PNG  {src.name}（Minecraft 的 NativeImage 只认 PNG/JPG，先转格式）"
    if dry:
        mode = ("原图直上" if ICON_TARGET <= 0 and not ICON_BLEED
                else ("只清理透明区 RGB" if ICON_TARGET <= 0
                      else f"颜色外扩 + {ICON_TARGET}px"))
        return (f"would-fix {src.name} -> {dst.relative_to(ROOT)}"
                f"（{mode} + .mcmeta blur/clamp）")
    w, h, ch, data = sprite_alpha.read_png(src)
    if ch == 3:
        return f"NO_ALPHA {src.name}（没有透明通道：先去 PS 导出带透明的 PNG-24）"

    # ── 模式 1：原图直上（连像素都不碰） ─────────────────────────────
    if ICON_TARGET <= 0 and not ICON_BLEED:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        dst.with_name(dst.name + ".mcmeta").write_text(ICON_MCMETA, encoding="utf-8")
        return (f"copied   {src.name} -> {dst.relative_to(ROOT)}"
                f"  ({w}x{h} 原图直上 + .mcmeta)")

    px = sprite_alpha.to_rgba(w, h, ch, data)
    # 调色板导出常常没透明度（Piskel「8 位图片」/ PS 索引色）：透明区会变成实心白。
    # 这种图直接上就是手机屏上一个白方块 —— 检测到"几乎没有透明像素"就自动抠背景。
    n_trans = sum(1 for i in range(w * h) if px[i * 4 + 3] == 0)
    keyed = 0
    if n_trans < w * h * 0.02:
        keyed = sprite_alpha.key_background(px, w, h, (255, 255, 255), 0)

    if ICON_TARGET <= 0:
        # ── 模式 2：只清理透明区 RGB，分辨率不动 ─────────────────────
        n_bleed = sprite_alpha.decontaminate(px, w, h, 250)
        nw, nh, out = w, h, px
    else:
        # ── 模式 3：旧路径（降采样）────────────────────────────────
        n_bleed = sprite_alpha.decontaminate(px, w, h, 250)
        nw, nh, out = sprite_alpha.downsample(px, w, h, ICON_TARGET)
        # 降采样会把"整块全透明"的输出像素写成 (0,0,0,0)，等于把上面的处理抹掉 →
        # 再处理一次：保证**最终这张图**里，透明区和半透明区的 RGB 都是内容色。
        n_bleed += sprite_alpha.decontaminate(out, nw, nh, 250)

    dst.parent.mkdir(parents=True, exist_ok=True)
    sprite_alpha.write_png(dst, nw, nh, bytes(out))
    # .mcmeta 与贴图同名同目录：Minecraft 读到它才会启用线性过滤（blur）与边缘钳制（clamp）
    dst.with_name(dst.name + ".mcmeta").write_text(ICON_MCMETA, encoding="utf-8")
    return (f"fixed    {src.name} -> {dst.relative_to(ROOT)}"
            f"  ({w}x{h} -> {nw}x{nh}"
            + (f"，抠背景 {keyed} px" if keyed else "")
            + f"，边缘去污 {n_bleed} px，+ .mcmeta)")


def pick_source(cands: list) -> pathlib.Path:
    """按顺序取**第一个存在的**候选源；都不存在就返回第一个（让下游报 MISSING/SKIP）。"""
    paths = [SRC / c for c in cands]
    for p in paths:
        if p.exists():
            return p
    return paths[0]


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
    print(f"App 图标 -> {ICON_DIR.relative_to(ROOT)}"
          f"（{'原图直上' if ICON_TARGET <= 0 and not ICON_BLEED else ('只清理透明区 RGB' if ICON_TARGET <= 0 else f'边缘去污 + {ICON_TARGET}px')}"
          f" + .mcmeta）")
    for dst_name, cands in ICON_SOURCES.items():
        src = pick_source(cands)
        note = f"（源：{src.name}）" if len(cands) > 1 else ""
        print("  " + copy_icon(src, ICON_DIR / dst_name, args.dry_run) + note)
    return 0


if __name__ == "__main__":
    sys.exit(main())
