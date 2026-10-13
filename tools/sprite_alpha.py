#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 / 修复"精灵图在 Minecraft 里出现白边"的问题（纯标准库，无需 Pillow）。

两种典型成因，本工具先给量化证据再动手：

A. **白底 matte**：半透明边缘像素的 RGB 是"内容与白底按 alpha 混合后"的颜色。
   在 PS 里看不出来（PS 把画布当白底合成），一叠到深色图标底色上就成了一圈白边。
   修法：去白底 un-matte —— `rgb' = (rgb - 255*(1-a)) / a`。

B. **最近邻缩采（NEAREST）**：256×256 的图在手机上只画 ~28 像素，GUI 默认最近邻采样
   **只挑一个像素**、不做平均，于是原本柔和的半透明边缘被"抽"成一圈硬边（PS 里微弱 → 游戏里成描边）。
   修法：按目标尺寸做一次**预乘 alpha 的均值降采样**（等价于 PS 里缩小图片），
   必要时再让渲染走线性过滤。

用法
----
    python tools/sprite_alpha.py report <png...>            # 只看统计（不改文件）
    python tools/sprite_alpha.py fix <png...> --target 96   # un-matte + 降采样，输出到 --out-dir
    python tools/sprite_alpha.py fix <png...> --mode erode --threshold 0.5   # 硬边（直接砍掉半透明边缘）

默认 `--mode unmatte`：只做去白底 + 降采样，保持柔和边缘。
`--in-place` 才会覆盖原图（默认写到 --out-dir，避免手滑毁掉素材）。
"""

from __future__ import annotations

import argparse
import pathlib
import struct
import sys
import zlib
from collections import Counter

PNG_SIG = b"\x89PNG\r\n\x1a\n"


# ============================================================================
# PNG 读写（只支持 8bit 的 RGB / RGBA，够用且没有第三方依赖）
# ============================================================================


def read_png(path: pathlib.Path) -> tuple[int, int, int, bytes]:
    """返回 (w, h, channels, 逐像素字节)。channels: 3=RGB, 4=RGBA。

    支持 1/2/4/8bit、非隔行：
      * colortype 0 灰度、2 RGB、4 灰度+alpha、6 RGBA
      * **colortype 3 调色板（PLTE + 可选 tRNS）** —— Piskel / PS「8 位图片」导出就是这种。
        调色板一律展开成 RGBA：索引图本身没有"边缘颜色"可言，但展开之后才能谈边缘。
    """
    data = path.read_bytes()
    if data[:8] != PNG_SIG:
        raise ValueError(f"{path.name}: 不是 PNG")
    pos = 8
    idat = bytearray()
    plte = b""
    trns = b""
    w = h = bitdepth = colortype = interlace = 0
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h, bitdepth, colortype, _comp, _filt, interlace = struct.unpack(">IIBBBBB", chunk)
        elif ctype == b"PLTE":
            plte = chunk
        elif ctype == b"tRNS":
            trns = chunk
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
    if interlace != 0:
        raise ValueError(f"{path.name}: 不支持隔行（Adam7）PNG")
    if bitdepth not in (1, 2, 4, 8):
        raise ValueError(f"{path.name}: 只支持 1/2/4/8bit（bitdepth={bitdepth}）")
    stored = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(colortype)
    if stored is None:
        raise ValueError(f"{path.name}: 未知 colortype={colortype}")
    if colortype == 3 and not plte:
        raise ValueError(f"{path.name}: 调色板 PNG 缺 PLTE")

    bits = bitdepth * stored
    bpp = max(1, bits // 8)
    stride = (w * bits + 7) // 8
    raw = zlib.decompress(bytes(idat))
    lines = []
    prev = bytearray(stride)
    p = 0
    for _y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        lines.append(bytes(line))
        prev = line

    def samples(line: bytes) -> list:
        """把一行拆成样本序列（bitdepth<8 时按 MSB 优先解包）。"""
        if bitdepth == 8:
            return list(line[: w * stored])
        out = []
        per, mask = 8 // bitdepth, (1 << bitdepth) - 1
        need = w * stored
        for byte in line:
            for k in range(per - 1, -1, -1):
                out.append((byte >> (k * bitdepth)) & mask)
                if len(out) == need:
                    return out
        return out

    # 调色板：PLTE 是 RGB 三元组；tRNS 是逐项 alpha（缺省 255）
    pal = []
    if colortype == 3:
        for i in range(0, len(plte) - 2, 3):
            pal.append((plte[i], plte[i + 1], plte[i + 2], trns[i] if i < len(trns) else 255))

    maxv = (1 << bitdepth) - 1
    out = bytearray(w * h * 4)
    for y in range(h):
        s = samples(lines[y])
        for x in range(w):
            o = (y * w + x) * 4
            if colortype == 3:
                r, g, b, a = pal[s[x]]
            elif colortype == 0:
                v = s[x] * 255 // maxv if bitdepth < 8 else s[x]
                r = g = b = v
                a = 255
            elif colortype == 4:
                i0 = x * 2
                v = s[i0] * 255 // maxv if bitdepth < 8 else s[i0]
                r = g = b = v
                a = s[i0 + 1] if bitdepth == 8 else s[i0 + 1] * 255 // maxv
            else:
                i0 = x * stored
                r, g, b = s[i0], s[i0 + 1], s[i0 + 2]
                a = s[i0 + 3] if stored == 4 else 255
            out[o], out[o + 1], out[o + 2], out[o + 3] = r, g, b, a
    return w, h, (3 if colortype in (0, 2) else 4), bytes(out)


def write_png(path: pathlib.Path, w: int, h: int, rgba: bytes) -> None:
    """写 8bit RGBA PNG（filter=0，够用）。"""
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    path.write_bytes(PNG_SIG + chunk(b"IHDR", ihdr)
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def to_rgba(w: int, h: int, channels: int, data: bytes) -> bytearray:
    if channels == 4:
        return bytearray(data)
    out = bytearray(w * h * 4)
    for i in range(w * h):
        out[i * 4:i * 4 + 3] = data[i * 3:i * 3 + 3]
        out[i * 4 + 3] = 255
    return out


# ============================================================================
# 统计
# ============================================================================


def report(path: pathlib.Path, show_ramp: bool = False) -> dict:
    w, h, ch, data = read_png(path)
    px = to_rgba(w, h, ch, data)
    n = w * h
    hist = [0] * 5          # a=0 / 0<a<64 / <128 / <255 / =255
    edge_light = edge_total = 0
    partial_light = partial_total = 0
    partial_mean = [0, 0, 0]
    trans_total = trans_light = 0
    trans_mean = [0, 0, 0]
    # 按 alpha 分档统计颜色：描边的"颜色"就是从最外那几档来的
    bands = [(1, 15), (16, 63), (64, 127), (128, 191), (192, 254)]
    bsum = [[0, 0, 0] for _ in bands]
    bcnt = [0] * len(bands)
    outer = Counter()
    for i in range(n):
        a = px[i * 4 + 3]
        r, g, b = px[i * 4], px[i * 4 + 1], px[i * 4 + 2]
        if a == 0:
            hist[0] += 1
            trans_total += 1
            trans_mean[0] += r
            trans_mean[1] += g
            trans_mean[2] += b
            if min(r, g, b) > 200:
                trans_light += 1
        elif a == 255:
            hist[4] += 1
        else:
            partial_total += 1
            hist[1 if a < 64 else (2 if a < 128 else 3)] += 1
            partial_mean[0] += r
            partial_mean[1] += g
            partial_mean[2] += b
            if min(r, g, b) > 200:      # 偏白
                partial_light += 1
            for bi, (lo, hi) in enumerate(bands):
                if lo <= a <= hi:
                    bsum[bi][0] += r
                    bsum[bi][1] += g
                    bsum[bi][2] += b
                    bcnt[bi] += 1
                    if bi <= 1:
                        outer[(r >> 3, g >> 3, b >> 3)] += 1
                    break
        # 轮廓：四邻里有全透明的
        if a > 0:
            x, y = i % w, i // w
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and px[(ny * w + nx) * 4 + 3] == 0:
                    edge_total += 1
                    if min(r, g, b) > 200:
                        edge_light += 1
                    break
    if partial_total:
        partial_mean = [v // partial_total for v in partial_mean]
    if trans_total:
        trans_mean = [v // trans_total for v in trans_mean]
    if show_ramp:
        print("    边缘 ramp（每条扫描线：从透明侧走到不透明侧的前 6 个像素，格式 r,g,b/a）")
        ys = [int(h * f) for f in (0.25, 0.4, 0.5, 0.6, 0.75)]
        for y in ys:
            row = [(px[(y * w + x) * 4], px[(y * w + x) * 4 + 1], px[(y * w + x) * 4 + 2],
                    px[(y * w + x) * 4 + 3]) for x in range(w)]
            x0 = next((x for x in range(w) if row[x][3] > 0), None)
            if x0 is None:
                continue
            seg = " ".join(f"{r},{g},{b}/{a}" for (r, g, b, a) in row[x0:x0 + 6])
            print(f"      y={y:3d} x0={x0:3d}: {seg}")
    return {
        "name": path.name, "w": w, "h": h, "channels": ch,
        "hist": hist, "partial_total": partial_total, "partial_mean": partial_mean,
        "partial_light_pct": (100.0 * partial_light / partial_total) if partial_total else 0.0,
        "edge_total": edge_total, "edge_light_pct": (100.0 * edge_light / edge_total) if edge_total else 0.0,
        "trans_total": trans_total, "trans_mean": trans_mean,
        "trans_light_pct": (100.0 * trans_light / trans_total) if trans_total else 0.0,
        "bands": bands, "bsum": bsum, "bcnt": bcnt, "outer": outer,
    }


def print_bands(r: dict) -> None:
    """打印按 alpha 分档的边缘平均色 —— 实机描边是什么颜色，看这里。"""
    if not r["bcnt"]:
        return
    print("    边缘按 alpha 分档的平均色（最外档 = 描边的颜色来源）：")
    for (lo, hi), s, c in zip(r["bands"], r["bsum"], r["bcnt"]):
        if not c:
            continue
        m = tuple(v // c for v in s)
        print(f'      a={lo:>3}-{hi:<3} {c:>5} px   平均 ({m[0]:>3},{m[1]:>3},{m[2]:>3})'
              f'  #{m[0]:02X}{m[1]:02X}{m[2]:02X}')
    if r["outer"]:
        print("    a<=63 外层像素最常见的颜色（量化到 8 级，前 6）：")
        for (rr, gg, bb), c in r["outer"].most_common(6):
            print(f'      #{rr << 3:02X}{gg << 3:02X}{bb << 3:02X}  x{c}')


def print_report(r: dict) -> None:
    tot = sum(r["hist"])
    print(f'{r["name"]}  {r["w"]}x{r["h"]}  colortype={"RGBA" if r["channels"] == 4 else "RGB（无透明通道！）"}')
    print(f'    alpha: 全透明 {r["hist"][0]}  |  0-63 {r["hist"][1]}  |  64-127 {r["hist"][2]}'
          f'  |  128-254 {r["hist"][3]}  |  全不透明 {r["hist"][4]}   (共 {tot})')
    print(f'    半透明像素 {r["partial_total"]}，其中偏白(min(RGB)>200) 占 {r["partial_light_pct"]:.1f}%'
          f'，平均色 {tuple(r["partial_mean"])}')
    print(f'    轮廓像素 {r["edge_total"]}，其中偏白占 {r["edge_light_pct"]:.1f}%')
    print(f'    全透明像素 {r["trans_total"]}，其中偏白占 {r["trans_light_pct"]:.1f}%'
          f'，平均色 {tuple(r["trans_mean"])}   ← 这些 RGB 平时看不见，一旦走线性过滤/mipmap 就会渗进边缘')
    verdict = []
    if r["channels"] == 3:
        verdict.append("没有 alpha 通道 —— 白底会被原样画出来（应导出 PNG-24 带透明）")
    if r["trans_light_pct"] > 50:
        verdict.append("透明区的 RGB 是白的 → 开线性过滤/生成 mipmap 会**自己造出白边**（需要先做颜色外扩 bleed）")
    if r["partial_light_pct"] > 25:
        verdict.append("半透明边缘明显偏白 → 白底 matte（un-matte 可修）")
    if r["partial_total"] > r["edge_total"] * 3:
        verdict.append("半透明过渡带很宽 → 缩到手机上会被 NEAREST 抽成硬边（降采样/线性过滤可修）")
    print("    判断：" + ("；".join(verdict) if verdict else "没看出明显问题"))
    print_bands(r)


# ============================================================================
# 修复
# ============================================================================


def unmatte(px: bytearray, w: int, h: int, cut: float = 0.06) -> int:
    """去白底 matte：rgb' = (rgb - 255*(1-a)) / a。返回改动像素数。"""
    changed = 0
    for i in range(w * h):
        a = px[i * 4 + 3] / 255.0
        if a == 0.0 or a == 1.0:
            continue
        if a <= cut:
            px[i * 4 + 3] = 0          # 最外那圈几乎全透的，直接丢掉（它就是白圈的来源之一）
            changed += 1
            continue
        for c in range(3):
            v = (px[i * 4 + c] - 255.0 * (1.0 - a)) / a
            px[i * 4 + c] = 0 if v < 0 else (255 if v > 255 else int(v + 0.5))
        changed += 1
    return changed


def erode(px: bytearray, w: int, h: int, threshold: float = 0.5) -> int:
    """硬边：alpha < threshold → 0，否则 255。"""
    changed = 0
    for i in range(w * h):
        a = px[i * 4 + 3] / 255.0
        nv = 0 if a < threshold else 255
        if nv != px[i * 4 + 3]:
            px[i * 4 + 3] = nv
            changed += 1
    return changed


def bleed(px: bytearray, w: int, h: int, depth: int = 0) -> int:
    """**颜色外扩（alpha bleed）**：把不透明像素的颜色向外扩散填满透明区，alpha 保持 0。

    这是修"白边/黑边"的关键一步：透明像素的 RGB 平时不参与渲染（a=0 贡献为 0），
    但一旦采样器把邻近像素做平均（线性过滤、mipmap、整体缩小），那些白 RGB 就会渗进轮廓。
    把透明区的 RGB 换成"最近的真实颜色"之后，任何过滤采到的都是内容色，不会再凭空造边。

    @param depth 0 = 填满所有透明像素；>0 = 只外扩这么多像素（够覆盖一个过滤核）
    @return 被改动的像素数
    """
    from collections import deque

    n = w * h
    dist = [-1] * n
    q: deque[int] = deque()
    for i in range(n):
        if px[i * 4 + 3] > 0:
            dist[i] = 0
            q.append(i)
    changed = 0
    while q:
        i = q.popleft()
        d = dist[i]
        if depth and d >= depth:
            continue
        x, y = i % w, i // w
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if dist[j] == -1:
                    px[j * 4] = px[i * 4]
                    px[j * 4 + 1] = px[i * 4 + 1]
                    px[j * 4 + 2] = px[i * 4 + 2]
                    dist[j] = d + 1
                    q.append(j)
                    changed += 1
    return changed


def key_background(px: bytearray, w: int, h: int, key=(255, 255, 255), tol: int = 0) -> int:
    """**抠背景**：把与图像四边连通的 key 色像素的 alpha 置 0。

    为什么需要：调色板（colortype=3）导出**常常没有透明度**——Piskel 的"8 位图片"、
    或 PS 存成索引色时，原本透明的地方会变成实心白。这种图直接塞给 Minecraft，
    在手机屏上就是一个**白色方块**。

    为什么只吃"与边框连通"的：狐狸身上的白（耳内侧、白毛）是被深色线稿**封闭**的，
    从边框走不到，所以不会被误伤 —— 这比全局 `#FFFFFF → 透明` 安全得多。

    @param key 背景色；@param tol 容差（0 = 只吃完全相等的）
    @return 被置成全透明的像素数
    """
    from collections import deque

    n = w * h
    t = tol
    is_key = [False] * n
    for i in range(n):
        if (abs(px[i * 4] - key[0]) <= t and abs(px[i * 4 + 1] - key[1]) <= t
                and abs(px[i * 4 + 2] - key[2]) <= t):
            is_key[i] = True
    seen = [False] * n
    q: deque[int] = deque()
    for x in range(w):
        for y in (0, h - 1):
            i = y * w + x
            if is_key[i] and not seen[i]:
                seen[i] = True
                q.append(i)
    for y in range(h):
        for x in (0, w - 1):
            i = y * w + x
            if is_key[i] and not seen[i]:
                seen[i] = True
                q.append(i)
    while q:
        i = q.popleft()
        x, y = i % w, i // w
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if is_key[j] and not seen[j]:
                    seen[j] = True
                    q.append(j)
    cnt = 0
    for i in range(n):
        if seen[i] and px[i * 4 + 3] != 0:
            px[i * 4 + 3] = 0
            cnt += 1
    return cnt


def decontaminate(px: bytearray, w: int, h: int, solid: int = 250) -> int:
    """**边缘去污**：把所有**非全不透明**像素的颜色，换成最近的"实心"像素的颜色（alpha 不动）。

    这是"白边 / 青边 / 描边"真正的根，`bleed()` 治不了它：

      * PNG 存的是**非预乘** RGBA。PS 里被蒙版/线条压住的底色（白底、线稿底稿的青色…）
        不会消失，而是以"**颜色还是满饱和、alpha 只剩 1~12**"的形式留在边缘上。
        PS 和普通看图器按 alpha 合成，所以**肉眼完全看不见**。
      * Minecraft 生成 mipmap 时 RGB 和 alpha **各做各的无权平均**（不是预乘平均）：
        一个 `#38D0FF@a=1` 的像素，会把**整份饱和青**塞进 mip 层的 RGB，
        而它的 alpha 只贡献 1/4^k。于是 mip 纹素 = "**高 alpha + 被污染的 RGB**"
        —— 采样出来就是一圈**饱和的青色描边**，而且比原图那点"微弱的颜色"浓得多。
      * 我的 `bleed()` 只填 `a == 0` 的像素，救不了这圈 `a = 1~254`。

    做法：以 `alpha >= solid` 的像素为源做多源 BFS（4 邻），把源色铺满其余像素。
    和 `bleed()` 一样只改 RGB，**不碰 alpha**，所以边缘的柔和过渡完整保留。

    @param solid 视作"实心"的 alpha 门槛，默认 250
    @return 被改动的像素数
    """
    from collections import deque

    n = w * h
    src = [-1] * n
    q: deque[int] = deque()
    for i in range(n):
        if px[i * 4 + 3] >= solid:
            src[i] = i
            q.append(i)
    changed = 0
    while q:
        i = q.popleft()
        x, y = i % w, i // w
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if src[j] == -1:
                    src[j] = src[i]
                    q.append(j)
                    if (px[j * 4], px[j * 4 + 1], px[j * 4 + 2]) != (px[src[i] * 4], px[src[i] * 4 + 1], px[src[i] * 4 + 2]):
                        px[j * 4] = px[src[i] * 4]
                        px[j * 4 + 1] = px[src[i] * 4 + 1]
                        px[j * 4 + 2] = px[src[i] * 4 + 2]
                        changed += 1
    return changed


def downsample(px: bytearray, w: int, h: int, target: int) -> tuple[int, int, bytearray]:
    """预乘 alpha 的均值降采样（正确做法：颜色按 alpha 加权平均，再除回去）。"""
    if target >= max(w, h):
        return w, h, px
    scale = target / max(w, h)
    nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
    out = bytearray(nw * nh * 4)
    for ny in range(nh):
        y0, y1 = int(ny * h / nh), max(int(ny * h / nh) + 1, int((ny + 1) * h / nh))
        for nx in range(nw):
            x0, x1 = int(nx * w / nw), max(int(nx * w / nw) + 1, int((nx + 1) * w / nw))
            sa = sr = sg = sb = 0.0
            cnt = 0
            for y in range(y0, min(y1, h)):
                base = y * w * 4
                for x in range(x0, min(x1, w)):
                    i = base + x * 4
                    a = px[i + 3] / 255.0
                    sa += a
                    sr += px[i] * a
                    sg += px[i + 1] * a
                    sb += px[i + 2] * a
                    cnt += 1
            o = (ny * nw + nx) * 4
            if cnt == 0 or sa <= 0:
                out[o:o + 4] = b"\x00\x00\x00\x00"
                continue
            out[o] = min(255, int(sr / sa + 0.5))
            out[o + 1] = min(255, int(sg / sa + 0.5))
            out[o + 2] = min(255, int(sb / sa + 0.5))
            out[o + 3] = min(255, int(255.0 * sa / cnt + 0.5))
    return nw, nh, out


def do_fix(path: pathlib.Path, args) -> None:
    w, h, ch, data = read_png(path)
    px = to_rgba(w, h, ch, data)
    if ch == 3:
        print(f"  {path.name}: 没有 alpha 通道，跳过（先去 PS 导出带透明的 PNG-24）")
        return
    n_un = n_er = n_bl = 0
    if args.mode in ("unmatte", "both"):
        n_un = unmatte(px, w, h, args.cut)
    if args.mode in ("erode", "both"):
        n_er = erode(px, w, h, args.threshold)
    if not args.no_bleed:
        n_bl = bleed(px, w, h, args.bleed_depth)
    out = path if args.in_place else (args.out_dir / path.name)
    out.parent.mkdir(parents=True, exist_ok=True)
    nw, nh, px2 = (w, h, px) if args.target <= 0 else downsample(px, w, h, args.target)
    if not args.no_bleed and (nw, nh) != (w, h):
        # 降采样会把"整块全透明"的输出像素写成 (0,0,0,0)，等于把上一步的外扩结果抹掉 →
        # 再外扩一次，保证**最终分辨率**上透明区的 RGB 也是内容色。
        n_bl += bleed(px2, nw, nh, args.bleed_depth)
    write_png(out, nw, nh, bytes(px2))
    print(f"  {path.name}: un-matte {n_un}、erode {n_er}、颜色外扩 {n_bl} 像素"
          f"、{w}x{h} -> {nw}x{nh}  -> {out}")


# ============================================================================
# 离线模拟：把"游戏里那一步缩放"在本地重放一遍，看白色到底从哪来
# ============================================================================


def _at(px: bytearray, w: int, h: int, x: int, y: int) -> tuple[int, int, int, int]:
    x = 0 if x < 0 else (w - 1 if x >= w else x)
    y = 0 if y < 0 else (h - 1 if y >= h else y)
    i = (y * w + x) * 4
    return px[i], px[i + 1], px[i + 2], px[i + 3]


def _sample(px: bytearray, w: int, h: int, u: float, v: float, mode: str) -> tuple[float, float, float, float]:
    if mode == "nearest":
        x = min(w - 1, int(u * w))
        y = min(h - 1, int(v * h))
        r, g, b, a = _at(px, w, h, x, y)
        return float(r), float(g), float(b), float(a)
    fx, fy = u * w - 0.5, v * h - 0.5
    x0, y0 = int(fx // 1), int(fy // 1)
    dx, dy = fx - x0, fy - y0
    acc = [0.0, 0.0, 0.0, 0.0]
    for xx, yy, wgt in ((x0, y0, (1 - dx) * (1 - dy)), (x0 + 1, y0, dx * (1 - dy)),
                        (x0, y0 + 1, (1 - dx) * dy), (x0 + 1, y0 + 1, dx * dy)):
        r, g, b, a = _at(px, w, h, xx, yy)
        acc[0] += r * wgt
        acc[1] += g * wgt
        acc[2] += b * wgt
        acc[3] += a * wgt
    return acc[0], acc[1], acc[2], acc[3]


def render_small(px: bytearray, w: int, h: int, target: int, mode: str, tile: int) -> bytearray:
    """把整张图缩到 target×target 并合成到 tile 底色上（模拟游戏里的显示结果）。"""
    out = bytearray(target * target * 4)
    tr, tg, tb = (tile >> 16) & 0xFF, (tile >> 8) & 0xFF, tile & 0xFF
    for ty in range(target):
        for tx in range(target):
            u, v = (tx + 0.5) / target, (ty + 0.5) / target
            r, g, b, a = _sample(px, w, h, u, v, mode)
            k = a / 255.0
            o = (ty * target + tx) * 4
            out[o] = min(255, int(r * k + tr * (1 - k) + 0.5))
            out[o + 1] = min(255, int(g * k + tg * (1 - k) + 0.5))
            out[o + 2] = min(255, int(b * k + tb * (1 - k) + 0.5))
            out[o + 3] = 255
    return out


def upscale(px: bytearray, w: int, h: int, k: int) -> bytearray:
    out = bytearray(w * k * h * k * 4)
    stride = w * k * 4
    for y in range(h * k):
        for x in range(w * k):
            i = ((y // k) * w + (x // k)) * 4
            o = y * stride + x * 4
            out[o:o + 4] = px[i:i + 4]
    return out


def count_whitish(px: bytearray) -> int:
    n = 0
    for i in range(0, len(px), 4):
        if min(px[i], px[i + 1], px[i + 2]) > 200:
            n += 1
    return n


def do_sim(path: pathlib.Path, args) -> None:
    w, h, ch, data = read_png(path)
    orig = to_rgba(w, h, ch, data)
    aw, ah = w, h
    if args.after:
        aw, ah, ach, adata = read_png(args.after)
        fixed = to_rgba(aw, ah, ach, adata)
        after_tag = args.after.name
    else:
        fixed = bytearray(orig)
        bleed(fixed, w, h, 0)
        after_tag = "颜色外扩后"
    tile = int(args.tile, 16)
    target, k = args.target, args.scale

    variants = [("原图", "nearest", orig, w, h), ("原图", "bilinear", orig, w, h),
                (after_tag, "nearest", fixed, aw if args.after else w, ah if args.after else h),
                (after_tag, "bilinear", fixed, aw if args.after else w, ah if args.after else h)]
    renders = [(tag, mode, render_small(src, sw, sh, target, mode, tile)) for tag, mode, src, sw, sh in variants]

    # 2×2 拼图：上行原图（最近邻 / 双线性），下行处理后（最近邻 / 双线性）
    cell = target * k
    sep = 4
    W = cell * 2 + sep * 3
    H = cell * 2 + sep * 3
    canvas = bytearray(b"\x20\x20\x20\xff" * (W * H))
    for idx, (_tag, _mode, small) in enumerate(renders):
        cx = sep + (idx % 2) * (cell + sep)
        cy = sep + (idx // 2) * (cell + sep)
        big = upscale(small, target, target, k)
        for y in range(cell):
            src = y * cell * 4
            dst = ((cy + y) * W + cx) * 4
            canvas[dst:dst + cell * 4] = big[src:src + cell * 4]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    write_png(args.out, W, H, bytes(canvas))

    print(f"{path.name}" + (f"  vs  {args.after.name}" if args.after else "") + f"  -> {args.out}")
    print(f"  目标尺寸 {target}x{target}（放大 {k} 倍显示）| 图标底色 #{args.tile.upper()}")
    print("  左上=原图+最近邻   右上=原图+双线性")
    print(f"  左下={after_tag}+最近邻   右下={after_tag}+双线性")
    for tag, mode, small in renders:
        print(f'  {tag}+{mode:<8}: 近白像素(全通道>200) {count_whitish(small):4d} / {target * target}')


# ============================================================================
# 漏底诊断：半透明边缘的颜色，是"内容色"还是"被蒙版盖住的底色"？
# ============================================================================
#
# 前两种成因（白底 matte / 最近邻抽边）解释不了「改完还是有描边」的情况。
# 第三种成因是**底色漏出**：PS 里图层被蒙版剪出柔和边缘时，被蒙版压住的底层颜色
# 会以半透明形式留在边上。PS 把画布当白/浅底合成，这圈颜色几乎看不见；
# 但游戏里它会和内容色一起被采样出来，缩到 28px 时被放大成一整圈描边。
#
# 判据：半透明像素的 RGB 应当≈**离它最近的全不透明内容像素**的 RGB。
# 偏离越大，越说明那个颜色不是内容色，而是漏出来的底色。


def nearest_opaque(px: bytearray, w: int, h: int) -> list:
    """多源 BFS：每个像素记录最近的全不透明像素下标；够不到的记 -1。"""
    from collections import deque

    n = w * h
    src = [-1] * n
    q: deque = deque()
    for i in range(n):
        if px[i * 4 + 3] == 255:
            src[i] = i
            q.append(i)
    while q:
        i = q.popleft()
        x, y = i % w, i // w
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if src[j] == -1:
                    src[j] = src[i]
                    q.append(j)
    return src


def _over_tile(px: bytearray, w: int, h: int, tile: int) -> bytearray:
    """把 RGBA 合成到纯色底上，返回不透明 RGBA。"""
    tr, tg, tb = (tile >> 16) & 0xFF, (tile >> 8) & 0xFF, tile & 0xFF
    out = bytearray(w * h * 4)
    for i in range(w * h):
        k = px[i * 4 + 3] / 255.0
        out[i * 4] = min(255, int(px[i * 4] * k + tr * (1 - k) + 0.5))
        out[i * 4 + 1] = min(255, int(px[i * 4 + 1] * k + tg * (1 - k) + 0.5))
        out[i * 4 + 2] = min(255, int(px[i * 4 + 2] * k + tb * (1 - k) + 0.5))
        out[i * 4 + 3] = 255
    return out


def do_matte(path: pathlib.Path, args) -> None:
    w, h, ch, data = read_png(path)
    if ch == 3:
        print(f"{path.name}: 没有 alpha 通道 —— 整张图都会被原样画出来，先导出带透明的 PNG-24")
        return
    px = to_rgba(w, h, ch, data)
    src = nearest_opaque(px, w, h)

    far = []                     # 判为"漏底"的半透明像素下标
    partial = 0
    hist = [0] * 6               # d: 0-15 / 16-31 / 32-63 / 64-127 / 128-191 / 192-255
    agg = [0, 0, 0]
    for i in range(w * h):
        a = px[i * 4 + 3]
        if a == 0 or a == 255 or src[i] < 0:
            continue
        partial += 1
        j = src[i]
        d = max(abs(px[i * 4 + c] - px[j * 4 + c]) for c in range(3))
        hist[0 if d < 16 else (1 if d < 32 else (2 if d < 64 else (3 if d < 128 else (4 if d < 192 else 5))))] += 1
        if d > args.thr:
            far.append(i)
            for c in range(3):
                agg[c] += px[i * 4 + c]

    # 漏底色的主色调（量化到 8 级，看是不是同一个底色在漏）
    from collections import Counter
    buckets = Counter()
    for i in far:
        buckets[(px[i * 4] >> 3, px[i * 4 + 1] >> 3, px[i * 4 + 2] >> 3)] += 1

    print(f"{path.name}  {w}x{h}")
    print(f"  可判定的半透明像素 {partial}（另有够不到实心像素的软区未计入）")
    print(f"  与最近实心像素的颜色距离分布："
          f"<16 {hist[0]}  |  16-31 {hist[1]}  |  32-63 {hist[2]}  |  64-127 {hist[3]}"
          f"  |  128-191 {hist[4]}  |  192-255 {hist[5]}")
    if partial:
        print(f"  距离 > {args.thr} 的「可疑漏底」像素 {len(far)} 个（占半透明 {100.0 * len(far) / partial:.1f}%）")
    if far:
        mean = tuple(v // len(far) for v in agg)
        print(f"  这些像素的平均色 {mean}  (#{mean[0]:02X}{mean[1]:02X}{mean[2]:02X})")
        print("  最常见的几种漏底色（量化到 8 级，取前 5）：")
        for (r, g, b), c in buckets.most_common(5):
            print(f"    #{r << 3:02X}{g << 3:02X}{b << 3:02X}  x{c}")
        print("  → 若这些颜色和画面内容明显不同（比如内容全是暖橙/棕，这里却是中性灰），")
        print("    就是「蒙版底下的底色漏出来了」：把半透明像素的 RGB 换成最近实心像素的颜色即可。")
    else:
        print("  没发现可疑漏底：半透明像素的颜色都贴着最近的内容色。")

    # 三联图：原样 / 把漏底色换成内容色 / 偏离热力图
    tile = int(args.tile, 16)
    before = px
    after = bytearray(px)
    for i in far:
        j = src[i]
        after[i * 4:i * 4 + 3] = px[j * 4:j * 4 + 3]
    heat = bytearray(w * h * 4)
    for i in range(w * h):
        a = px[i * 4 + 3]
        if a == 255:
            heat[i * 4:i * 4 + 3] = b"\x3c\x3c\x3c"
            heat[i * 4 + 3] = 255
        elif a == 0:
            heat[i * 4:i * 4 + 3] = b"\x18\x18\x18"
            heat[i * 4 + 3] = 255
        else:
            j = src[i]
            d = 0 if j < 0 else max(abs(px[i * 4 + c] - px[j * 4 + c]) for c in range(3))
            t = min(1.0, d / 255.0)
            heat[i * 4] = int(40 + 215 * t)
            heat[i * 4 + 1] = int(200 * (1 - t) + 30 * t)
            heat[i * 4 + 2] = 60
            heat[i * 4 + 3] = 255

    k = args.scale
    cell, sep = w * k, 4
    W = cell * 3 + sep * 4
    H = cell + sep * 2
    canvas = bytearray(b"\x20\x20\x20\xff" * (W * H))
    for cx, panel in enumerate((_over_tile(before, w, h, tile), _over_tile(after, w, h, tile), heat)):
        big = upscale(panel, w, h, k)
        ox = sep + cx * (cell + sep)
        for y in range(cell):
            d0 = ((sep + y) * W + ox) * 4
            canvas[d0:d0 + cell * 4] = big[y * cell * 4:(y + 1) * cell * 4]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    write_png(args.out, W, H, bytes(canvas))
    print(f"  -> {args.out}")
    print(f"  左＝原样合成到 #{args.tile.upper()}   中＝把可疑漏底色换成最近实心像素的颜色   右＝偏离热力图（越红越可疑）")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="精灵图白边诊断/修复")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_rep = sub.add_parser("report")
    p_rep.add_argument("files", nargs="+")
    p_rep.add_argument("--ramp", action="store_true", help="额外打印边缘 ramp（每条扫描线的像素序列）")

    p_fix = sub.add_parser("fix")
    p_fix.add_argument("files", nargs="+")
    p_fix.add_argument("--mode", choices=["none", "unmatte", "erode", "both"], default="none",
                       help="none=只做颜色外扩（默认，最安全）；unmatte=去白底；erode=硬边；both=两者都做")
    p_fix.add_argument("--cut", type=float, default=0.06, help="un-matte 时低于该 alpha 的像素直接丢掉")
    p_fix.add_argument("--threshold", type=float, default=0.5, help="erode 的 alpha 阈值")
    p_fix.add_argument("--target", type=int, default=96, help="降采样目标长边（0 = 不降）")
    p_fix.add_argument("--no-bleed", action="store_true", help="不做颜色外扩（默认会做，它是修白边的关键）")
    p_fix.add_argument("--bleed-depth", type=int, default=0, help="颜色外扩深度（0 = 填满透明区）")
    p_fix.add_argument("--out-dir", type=pathlib.Path, default=None)
    p_fix.add_argument("--in-place", action="store_true")

    p_sim = sub.add_parser("sim", help="离线模拟：把图缩到目标尺寸、合成到底色上，输出对比图（看白边从哪来）")
    p_sim.add_argument("file")
    p_sim.add_argument("--after", type=pathlib.Path, default=None,
                       help="处理后的图（不给就用「颜色外扩」的内存版作对照）")
    p_sim.add_argument("--target", type=int, default=28, help="游戏里实际显示的像素尺寸（手机上一格图标约 28px）")
    p_sim.add_argument("--scale", type=int, default=8, help="对比图每格放大倍数")
    p_sim.add_argument("--tile", default="424242", help="图标方块底色（RRGGBB），默认相机的深灰")
    p_sim.add_argument("--out", type=pathlib.Path, default=pathlib.Path("sprite_sim.png"))

    p_mat = sub.add_parser("matte", help="漏底诊断：半透明边缘的颜色是不是来自被蒙版盖住的底色")
    p_mat.add_argument("file")
    p_mat.add_argument("--tile", default="EEEEEE", help="合成底色 RRGGBB（默认手机图标方块的浅灰）")
    p_mat.add_argument("--thr", type=int, default=32, help="判定「漏底」的颜色距离阈值（0-255）")
    p_mat.add_argument("--scale", type=int, default=3, help="输出放大倍数")
    p_mat.add_argument("--out", type=pathlib.Path, default=pathlib.Path("matte_check.png"))

    args = ap.parse_args()
    files = [pathlib.Path(f) for f in (args.files if args.cmd not in ("sim", "matte") else [args.file])]
    if args.cmd == "report":
        for f in files:
            print_report(report(f, show_ramp=args.ramp))
            print()
        return 0
    if args.cmd == "sim":
        do_sim(files[0], args)
        return 0
    if args.cmd == "matte":
        do_matte(files[0], args)
        return 0
    if not args.in_place and args.out_dir is None:
        print("ERROR: 需要 --out-dir DIR 或 --in-place（默认不覆盖原图）", file=sys.stderr)
        return 2
    for f in files:
        do_fix(f, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
