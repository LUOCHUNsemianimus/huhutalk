#!/usr/bin/env python3
"""生成 HuhuTalk 原生 Forge GameTest 的空结构模板。

固定仓库：D:\\Moddevelop\\HuhuTalk-Forge-1.20.1
默认 dry-run；只有显式 --apply 才写入。目标已存在且内容不同时拒绝覆盖。

生成：src/gametest/resources/data/huhutalk/structures/port_empty.nbt
结构内容（Java Edition NBT，gzip）：
  root Compound（空名）
    DataVersion Int = 3465
    size List<Int> = [3, 3, 3]
    palette List<Compound> = [{Name: "minecraft:air"}]
    blocks List<Compound> = []
    entities List<Compound> = []

不下载 Minecraft 模板，不使用第三方库，结果可复现（gzip mtime=0）。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import os
import struct
import sys
import tempfile
from pathlib import Path
from typing import NoReturn

REPO = Path(r"D:\Moddevelop\HuhuTalk-Forge-1.20.1")
RELATIVE_TARGET = Path("src/gametest/resources/data/huhutalk/structures/port_empty.nbt")
DATA_VERSION = 3465
SIZE = (3, 3, 3)

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def _write_string(out: io.BytesIO, value: str) -> None:
    raw = value.encode("utf-8")
    if len(raw) > 0xFFFF:
        raise ValueError("string too long for NBT")
    out.write(struct.pack(">H", len(raw)))
    out.write(raw)


def _write_int(out: io.BytesIO, name: str, value: int) -> None:
    out.write(bytes([TAG_INT]))
    _write_string(out, name)
    out.write(struct.pack(">i", value))


def _write_int_list(out: io.BytesIO, name: str, values: tuple[int, ...]) -> None:
    out.write(bytes([TAG_LIST]))
    _write_string(out, name)
    out.write(bytes([TAG_INT]))
    out.write(struct.pack(">i", len(values)))
    for value in values:
        out.write(struct.pack(">i", value))


def _write_empty_compound_list(out: io.BytesIO, name: str) -> None:
    out.write(bytes([TAG_LIST]))
    _write_string(out, name)
    out.write(bytes([TAG_COMPOUND]))
    out.write(struct.pack(">i", 0))


def build_nbt() -> bytes:
    out = io.BytesIO()
    # root TAG_Compound + 空名
    out.write(bytes([TAG_COMPOUND]))
    _write_string(out, "")

    _write_int(out, "DataVersion", DATA_VERSION)
    _write_int_list(out, "size", SIZE)

    # palette: List<Compound>，仅一个 air 条目
    out.write(bytes([TAG_LIST]))
    _write_string(out, "palette")
    out.write(bytes([TAG_COMPOUND]))
    out.write(struct.pack(">i", 1))
    out.write(bytes([TAG_STRING]))
    _write_string(out, "Name")
    _write_string(out, "minecraft:air")
    out.write(bytes([TAG_END]))

    _write_empty_compound_list(out, "blocks")
    _write_empty_compound_list(out, "entities")

    # root END
    out.write(bytes([TAG_END]))
    return gzip.compress(out.getvalue(), compresslevel=9, mtime=0)


def _read_string(data: bytes, pos: int) -> tuple[str, int]:
    length = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    return data[pos:pos + length].decode("utf-8"), pos + length


def _read_payload(data: bytes, pos: int, tag_type: int):
    if tag_type == TAG_INT:
        return struct.unpack_from(">i", data, pos)[0], pos + 4
    if tag_type == TAG_STRING:
        return _read_string(data, pos)
    if tag_type == TAG_LIST:
        item_type = data[pos]
        length = struct.unpack_from(">i", data, pos + 1)[0]
        pos += 5
        items = []
        for _ in range(length):
            item, pos = _read_payload(data, pos, item_type)
            items.append(item)
        return {"type": item_type, "items": items}, pos
    if tag_type == TAG_COMPOUND:
        result = {}
        while True:
            child_type = data[pos]
            pos += 1
            if child_type == TAG_END:
                return result, pos
            name, pos = _read_string(data, pos)
            value, pos = _read_payload(data, pos, child_type)
            result[name] = value
    raise ValueError(f"unsupported NBT tag type: {tag_type}")


def validate_nbt(payload: bytes) -> None:
    data = gzip.decompress(payload)
    if not data or data[0] != TAG_COMPOUND:
        raise ValueError("root is not a compound")
    name, pos = _read_string(data, 1)
    if name != "":
        raise ValueError("root name must be empty")
    root, pos = _read_payload(data, pos, TAG_COMPOUND)

    if pos != len(data):
        raise ValueError("trailing bytes after root compound")
    if root.get("DataVersion") != DATA_VERSION:
        raise ValueError("DataVersion mismatch")
    size = root.get("size")
    if not isinstance(size, dict) or size.get("type") != TAG_INT:
        raise ValueError("size must be List<Int>")
    if size.get("items") != [3, 3, 3]:
        raise ValueError("size must be [3, 3, 3]")
    palette = root.get("palette")
    if not isinstance(palette, dict) or palette.get("type") != TAG_COMPOUND:
        raise ValueError("palette must be List<Compound>")
    if palette.get("items") != [{"Name": "minecraft:air"}]:
        raise ValueError("palette must contain only minecraft:air")
    for key in ("blocks", "entities"):
        value = root.get(key)
        if not isinstance(value, dict) or value.get("type") != TAG_COMPOUND:
            raise ValueError(f"{key} must be List<Compound>")
        if value.get("items") != []:
            raise ValueError(f"{key} must be empty")


def _refuse(message: str) -> NoReturn:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def _check_unresolved_chain(candidate: Path, repo: Path) -> None:
    """在 resolve 之前逐级检查候选路径及其到 repo 的父目录。

    先对未 resolve 的路径检查符号链接，再比较每个组件的 realpath，避免内部
    符号链接/目录联接在 resolve 之后被折叠而无法识别。
    """
    current = candidate
    while True:
        if current.is_symlink():
            _refuse(f"path component must not be a symlink: {current}")
        resolved = current.resolve()
        if resolved != current:
            _refuse(f"path component resolves through a link: {current} -> {resolved}")
        if current == repo:
            return
        parent = current.parent
        if parent == current:
            _refuse(f"target escapes fixed repository: {candidate}")
        current = parent


def resolve_target() -> Path:
    repo = REPO.resolve()
    if not (repo / "gradle.properties").is_file():
        _refuse(f"fixed repository not found: {repo}")
    properties = (repo / "gradle.properties").read_text(encoding="utf-8")
    if "minecraft_version=1.20.1" not in properties:
        _refuse("gradle.properties does not pin minecraft_version=1.20.1")

    candidate = repo / RELATIVE_TARGET
    if repo not in candidate.parents:
        _refuse(f"target escapes fixed repository: {candidate}")
    _check_unresolved_chain(candidate, repo)

    target = candidate.resolve()
    if repo != target and repo not in target.parents:
        _refuse(f"target escapes fixed repository: {target}")
    return target


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--apply", action="store_true", help="写入目标文件（默认只预览）")
    group.add_argument("--check", action="store_true", help="目标已是最新则退出 0，否则退出 1")
    args = parser.parse_args(argv)

    target = resolve_target()
    payload = build_nbt()
    validate_nbt(payload)
    digest = hashlib.sha256(payload).hexdigest()

    print(f"repo:   {REPO.resolve()}")
    print(f"target: {target}")
    print(f"bytes:  {len(payload)}")
    print(f"sha256: {digest}")

    if target.exists():
        existing = target.read_bytes()
        if existing == payload:
            print("status: already up to date")
            return 0
        if args.check:
            print("status: STALE (existing file differs)")
            return 1
        _refuse("target already exists with different content; refusing to overwrite")

    if args.check:
        print("status: MISSING")
        return 1

    if not args.apply:
        print("status: dry-run (pass --apply to write)")
        return 0

    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(
        prefix=target.name + ".", suffix=".tmp", dir=target.parent
    )
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        # 替换前再次确认目标没有被并发写入成不同内容。
        if target.exists():
            existing = target.read_bytes()
            if existing == payload:
                print("status: already up to date")
                return 0
            _refuse("target already exists with different content; refusing to overwrite")
        os.replace(temp, target)
    finally:
        if temp.exists():
            try:
                temp.unlink()
            except OSError:
                pass
    written = target.read_bytes()
    if written != payload:
        _refuse("written file does not match generated payload")
    print("status: written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
