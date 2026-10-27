#!/usr/bin/env python3
"""把 HuhuTalk 源码里 1.21 风格的 EventBusSubscriber 导入改成 Forge 1.20.1 写法。

Forge 1.20.1 的正确类型是 ``net.minecraftforge.fml.common.Mod.EventBusSubscriber``，
其 Bus 也嵌套在同一个注解里（``...Mod.EventBusSubscriber.Bus``）。本脚本只改这两个
类型名（import 与全限定引用都会被覆盖），不触碰任何业务条件或方法体。

安全约束：
  * 仓库固定为 ``D:/Moddevelop/HuhuTalk-Forge-1.20.1``，否则拒绝执行；
  * 只扫描 ``src/main/java`` 与 ``src/test/java`` 下的 ``*.java``；
  * 跳过符号链接与逃出仓库的路径；
  * 默认 dry-run，只有显式 ``--apply`` 才写文件；
  * 保留 UTF-8 BOM 与原始 CRLF/LF 字节；
  * 写入前逐一校验扫描时记录的 SHA-256，文件被改动则整体中止；
  * 同目录临时文件 + ``os.replace`` 原子写入；
  * 幂等：第二次运行不再产生变更。

该脚本由上游评审后执行，实施卡内不运行。
"""

from __future__ import annotations

import argparse
import codecs
import difflib
import hashlib
import os
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

EXPECTED_REPO = Path("D:/Moddevelop/HuhuTalk-Forge-1.20.1").resolve()
SOURCE_ROOTS = (Path("src/main/java"), Path("src/test/java"))
SKIP_DIR_NAMES = {".git", ".gradle", "build", "run", "run-data", "out", "logs"}

# 先替换更长的 ``.Bus`` 形式，再替换类型本身；两者都幂等。
REPLACEMENTS = (
    (
        "net.minecraftforge.fml.common.EventBusSubscriber.Bus",
        "net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus",
    ),
    (
        "net.minecraftforge.fml.common.EventBusSubscriber",
        "net.minecraftforge.fml.common.Mod.EventBusSubscriber",
    ),
)


@dataclass(frozen=True)
class Change:
    path: Path
    original_bytes: bytes
    updated_bytes: bytes
    original_text: str
    updated_text: str
    sha256: str


def is_under(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
        return True
    except ValueError:
        return False


def check_expected_repo(repo: Path) -> None:
    repo = repo.resolve()
    if repo != EXPECTED_REPO:
        raise SystemExit(
            f"refusing to write: repository is {repo!r}, expected {EXPECTED_REPO!r}"
        )
    properties = repo / "gradle.properties"
    if not properties.is_file():
        raise SystemExit(f"refusing to write: missing {properties}")
    if "minecraft_version=1.20.1" not in properties.read_text(encoding="utf-8"):
        raise SystemExit("refusing to write: gradle.properties is not the 1.20.1 project")
    if not (repo / "src/main/java/com/huhutalk/HuhuTalk.java").is_file():
        raise SystemExit("refusing to write: missing HuhuTalk.java marker")


def _prune_directories(current: Path, names: list[str], repo: Path) -> list[str]:
    kept = []
    for name in sorted(names):
        if name in SKIP_DIR_NAMES:
            continue
        child = current / name
        if child.is_symlink():
            continue
        try:
            resolved = child.resolve()
        except OSError:
            continue
        if not is_under(resolved, repo):
            continue
        kept.append(name)
    return kept


def iter_java_files(repo: Path):
    repo = repo.resolve()
    for root_relative in SOURCE_ROOTS:
        root = (repo / root_relative).resolve()
        if not root.is_dir():
            continue
        if not is_under(root, repo):
            raise SystemExit(f"refusing: source root escapes repository: {root}")
        for current, dirnames, filenames in os.walk(root, followlinks=False):
            current_path = Path(current)
            dirnames[:] = _prune_directories(current_path, dirnames, repo)
            for name in sorted(filenames):
                if not name.endswith(".java"):
                    continue
                path = current_path / name
                if path.is_symlink() or not path.is_file():
                    continue
                try:
                    resolved = path.resolve()
                except OSError:
                    continue
                if not is_under(resolved, repo):
                    continue
                yield resolved


def rewrite_text(text: str) -> str:
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    return text


def decode_source(raw: bytes) -> tuple[str, bool]:
    if raw.startswith(codecs.BOM_UTF8):
        return raw[len(codecs.BOM_UTF8):].decode("utf-8"), True
    return raw.decode("utf-8"), False


def encode_source(text: str, bom: bool) -> bytes:
    data = text.encode("utf-8")
    return codecs.BOM_UTF8 + data if bom else data


def gather_changes(repo: Path) -> list[Change]:
    changes = []
    for path in iter_java_files(repo):
        raw = path.read_bytes()
        try:
            text, bom = decode_source(raw)
        except UnicodeDecodeError as error:
            raise SystemExit(f"refusing to edit non-UTF-8 file {path}: {error}") from error
        updated_text = rewrite_text(text)
        if updated_text == text:
            continue
        updated_bytes = encode_source(updated_text, bom)
        if updated_bytes == raw:
            continue
        changes.append(
            Change(
                path=path,
                original_bytes=raw,
                updated_bytes=updated_bytes,
                original_text=text,
                updated_text=updated_text,
                sha256=hashlib.sha256(raw).hexdigest(),
            )
        )
    return changes


def verify_write_targets(repo: Path, changes: list[Change]) -> None:
    repo = repo.resolve()
    for change in changes:
        try:
            resolved = change.path.resolve()
        except OSError as error:
            raise SystemExit(f"refusing to write: cannot resolve {change.path}: {error}") from error
        if not is_under(resolved, repo):
            raise SystemExit(f"refusing to write outside the repository: {resolved}")
        try:
            current = resolved.read_bytes()
        except OSError as error:
            raise SystemExit(f"refusing to write: cannot re-read {resolved}: {error}") from error
        if current != change.original_bytes:
            raise SystemExit(
                f"refusing to write {resolved}: file changed since it was scanned "
                f"(scanned sha256={change.sha256}); re-run the dry run and review again"
            )
        if hashlib.sha256(current).hexdigest() != change.sha256:
            raise SystemExit(f"refusing to write {resolved}: sha256 mismatch")


def apply_changes(repo: Path, changes: list[Change]) -> None:
    verify_write_targets(repo, changes)
    for change in changes:
        resolved = change.path.resolve()
        file_descriptor, temp_name = tempfile.mkstemp(
            prefix=resolved.name + ".", suffix=".tmp", dir=resolved.parent
        )
        temp_path = Path(temp_name)
        try:
            with os.fdopen(file_descriptor, "wb") as handle:
                handle.write(change.updated_bytes)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, resolved)
        finally:
            if temp_path.exists():
                temp_path.unlink()


def print_change(change: Change, repo: Path, show_diff: bool) -> None:
    relative = change.path.resolve().relative_to(repo.resolve())
    print(f"  {relative}  sha256={change.sha256[:12]}")
    if not show_diff:
        return
    diff = difflib.unified_diff(
        change.original_text.splitlines(),
        change.updated_text.splitlines(),
        fromfile=str(relative),
        tofile=str(relative) + " (forge 1.20.1)",
        lineterm="",
    )
    print("\n".join(diff))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", dest="apply", action="store_true",
                      help="write the changes; otherwise only report a dry run")
    mode.add_argument("--dry-run", dest="apply", action="store_false",
                      help="report changes without writing (default)")
    parser.set_defaults(apply=False)
    parser.add_argument("--diff", action="store_true",
                        help="print a unified diff for every changed file")
    args = parser.parse_args(argv)

    repo = EXPECTED_REPO
    check_expected_repo(repo)
    changes = gather_changes(repo)

    print(f"repo: {repo}")
    print(f"mode: {'APPLY' if args.apply else 'DRY RUN'}")
    print(f"changed files: {len(changes)}")
    for change in changes:
        print_change(change, repo, args.diff)

    if args.apply:
        apply_changes(repo, changes)
        print(f"applied: {len(changes)} file(s)")
    elif changes:
        print("dry run: no files written; re-run with --apply after review")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
