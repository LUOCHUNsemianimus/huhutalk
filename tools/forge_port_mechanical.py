#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HuhuTalk Forge 1.20.1 —— 严格限定的机械迁移脚本（默认 dry-run）。

用途
----
把 NeoForge 1.21.1 源码里“已确认、可机械替换”的符号做预迁移，减少后续真实 API
适配的 diff 噪声。它**不**生成任何 net.minecraft / net.neoforged 类，**不**删除功能，
**不**排除源码，**不**把编译错误用默认返回或空实现吞掉。

只处理两棵固定目录下的 .java 文件：
    <repo>/src/main/java
    <repo>/src/test/java

只写入 <repo>，仓库根本身必须等于 EXPECTED_REPO（resolve 后比较）。
默认不写任何文件；只有显式 --apply 才落地。--apply 还会从已校验的 MDK 参考目录
同步 Gradle wrapper（gradlew / gradlew.bat / gradle/wrapper 两个文件）。

安全边界
--------
* 无 shell 调用、无网络访问、无递归删除、不清空目录、不触碰 .git。
* 不跟随越界符号链接：os.walk(followlinks=False) 且每个文件再按 realpath 复核。
* UTF-8 严格读取；解码失败的文件跳过并报告，绝不按其它编码猜测重写。
* 不新增/删除换行；保留原文件的行尾（CRLF/LF）与 BOM。
* 幂等：第二次运行应报告 0 处新增变化。

用法
----
    python tools/forge_port_mechanical.py                 # dry-run，打印摘要
    python tools/forge_port_mechanical.py --diff          # dry-run，打印完整 unified diff
    python tools/forge_port_mechanical.py --apply         # 真正写入 + 同步 wrapper
    python tools/forge_port_mechanical.py --check         # 有待改文件时退出码 1

    # 可选项
    --repo PATH        仅用于显式确认仓库；必须 resolve 到 EXPECTED_REPO
    --skip-wrapper     --apply 时不同步 Gradle wrapper
    --max-diff-lines N 摘要模式下每个文件最多打印多少行 diff（默认 12）

退出码：0 成功；1 --check 发现待改文件；2 参数/环境校验失败。
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import os
import re
import sys
from pathlib import Path
from typing import Dict, Iterable, List, NoReturn, Optional, Pattern, Tuple

# ── 固定路径（本卡只允许操作这两个位置）────────────────────────────────────────
EXPECTED_REPO = Path("D:/Moddevelop/HuhuTalk-Forge-1.20.1")
MDK_REFERENCE = Path(
    "D:/Moddevelop/.workflow/projects/huhutalk/work/"
    "forge-port-preparation-20261027/mdk-reference"
)

SOURCE_ROOTS = ("src/main/java", "src/test/java")

WRAPPER_FILES = (
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar",
)

# ── 机械替换规则 ────────────────────────────────────────────────────────────────
# 顺序即优先级：更具体的规则必须排在更宽的前缀规则之前。
# TEXT_RULES 为纯字符串替换；REGEX_RULES 为正则替换。
TEXT_RULES: List[Tuple[str, str, str]] = [
    # NeoForge 的 common.NeoForge 在 Forge 1.20.1 对应 common.MinecraftForge。
    # 必须早于 net.neoforged.neoforge 的通用前缀规则。
    ("pkg:neoforge-common", "net.neoforged.neoforge.common.NeoForge",
     "net.minecraftforge.common.MinecraftForge"),
    ("pkg:distmarker", "net.neoforged.api.distmarker",
     "net.minecraftforge.api.distmarker"),
    ("pkg:eventbus", "net.neoforged.bus.api",
     "net.minecraftforge.eventbus.api"),
    ("pkg:javafmlmod", "net.neoforged.fml.javafmlmod",
     "net.minecraftforge.fml.javafmlmod"),
    ("pkg:fml", "net.neoforged.fml", "net.minecraftforge.fml"),
    ("pkg:neoforge", "net.neoforged.neoforge", "net.minecraftforge"),
]

REGEX_RULES: List[Tuple[str, Pattern[str], str]] = [
    # 事件总线单例：NeoForge.EVENT_BUS -> MinecraftForge.EVENT_BUS
    ("bus:EVENT_BUS", re.compile(r"\bNeoForge\.EVENT_BUS\b"),
     "MinecraftForge.EVENT_BUS"),
    # 1.20.1 的 ResourceLocation 仍支持 public 构造器。
    # 可选的全限定前缀一并吞掉，避免 net.minecraft.resources.new ResourceLocation(。
    ("rl:fromNamespaceAndPath",
     re.compile(r"\b(?:net\.minecraft\.resources\.)?ResourceLocation\.fromNamespaceAndPath\s*\("),
     "new net.minecraft.resources.ResourceLocation("),
    # 注意：不匹配 tryParse；parse 与 tryParse 是两个不同方法。
    ("rl:parse",
     re.compile(r"\b(?:net\.minecraft\.resources\.)?ResourceLocation\.parse\s*\("),
     "new net.minecraft.resources.ResourceLocation("),
    # Java 21 Math.clamp -> Minecraft 1.20.1 Mth.clamp
    ("math:clamp", re.compile(r"\bMath\.clamp\s*\("),
     "net.minecraft.util.Mth.clamp("),
]

# 已确认的三处 List.getFirst()（接收者固定），不批量改所有集合。
GET_FIRST_RECEIVERS = ("models", "due", "otherNames")

# 迁移后仍可能出现、必须由后续任务卡人工处理的残留记号。
RESIDUAL_PATTERNS: List[Tuple[str, Pattern[str]]] = [
    ("net.neoforged", re.compile(r"net\.neoforged")),
    ("NeoForge.identifier", re.compile(r"\bNeoForge\b")),
    ("ResourceLocation.fromNamespaceAndPath",
     re.compile(r"\bResourceLocation\.fromNamespaceAndPath\b")),
    ("ResourceLocation.parse", re.compile(r"\bResourceLocation\.parse\s*\(")),
    ("Math.clamp", re.compile(r"\bMath\.clamp\s*\(")),
    (".getFirst(", re.compile(r"\.getFirst\s*\(")),
    # 通用前缀规则会生成下面这些“前缀正确、类在 Forge 1.20.1 不存在”的引用；
    # 它们不是本脚本能机械修复的，必须由后续真实 API 适配处理。
    ("TARGET-MISSING:Mod.EventBusSubscriber",
     re.compile(r"net\.minecraftforge\.fml\.common\.EventBusSubscriber")),
    ("TARGET-MISSING:IPayloadContext",
     re.compile(r"net\.minecraftforge\.network\.handling\.IPayloadContext")),
    ("TARGET-MISSING:RegisterPayloadHandlersEvent",
     re.compile(r"net\.minecraftforge\.network\.event\.RegisterPayloadHandlersEvent")),
    ("TARGET-MISSING:common.capabilities.Capabilities",
     re.compile(r"net\.minecraftforge\.capabilities\.Capabilities")),
    ("TARGET-MISSING:TickEvent.ServerTickEvent",
     re.compile(r"net\.minecraftforge\.event\.tick\.ServerTickEvent")),
    ("TARGET-MISSING:TickEvent.ClientTickEvent",
     re.compile(r"net\.minecraftforge\.client\.event\.ClientTickEvent")),
    ("TARGET-MISSING:RegisterGuiOverlaysEvent",
     re.compile(r"net\.minecraftforge\.client\.event\.RegisterGuiLayersEvent")),
]



def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def fail(message: str) -> NoReturn:
    print("ERROR: " + message, file=sys.stderr)
    raise SystemExit(2)


def is_within(child: Path, parent: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def resolve_repo(raw: str) -> Path:
    candidate = Path(raw)
    try:
        resolved = candidate.resolve(strict=True)
    except (FileNotFoundError, OSError) as exc:
        fail("仓库路径不可用: %s (%s)" % (candidate, exc))

    if not resolved.is_dir():
        fail("仓库路径不是目录: %s" % resolved)

    expected = EXPECTED_REPO.resolve(strict=False)
    if resolved != expected:
        fail(
            "拒绝操作：--repo 解析为 %s，期望固定仓库 %s"
            % (resolved, expected)
        )
    return resolved


def iter_java_files(root: Path, repo_root: Path) -> Iterable[Path]:
    """按确定顺序遍历 .java；不跟随符号链接目录，越界文件跳过。"""
    if not root.is_dir():
        return
    real_root = root.resolve()
    if not is_within(real_root, repo_root):
        fail("源码根越界: %s" % root)

    for dirpath, dirnames, filenames in os.walk(str(real_root), followlinks=False):
        # 剪掉符号链接目录，避免越出仓库或形成环。
        dirnames[:] = sorted(
            d for d in dirnames
            if not os.path.islink(os.path.join(dirpath, d))
        )
        for name in sorted(filenames):
            if not name.endswith(".java"):
                continue
            path = Path(dirpath) / name
            if path.is_symlink():
                continue
            try:
                real = path.resolve(strict=True)
            except OSError:
                continue
            if not is_within(real, repo_root):
                continue
            yield path


def build_regex_rules() -> List[Tuple[str, Pattern[str], str]]:
    rules = list(REGEX_RULES)
    for receiver in GET_FIRST_RECEIVERS:
        rules.append((
            "collection:%s.getFirst" % receiver,
            re.compile(r"\b%s\.getFirst\s*\(\s*\)" % re.escape(receiver)),
            "%s.get(0)" % receiver,
        ))
    return rules


def transform(text: str) -> Tuple[str, Dict[str, int]]:
    counts: Dict[str, int] = {}
    result = text

    for name, old, new in TEXT_RULES:
        occurrences = result.count(old)
        if occurrences:
            counts[name] = counts.get(name, 0) + occurrences
            result = result.replace(old, new)

    for name, pattern, replacement in build_regex_rules():
        result, occurrences = pattern.subn(replacement, result)
        if occurrences:
            counts[name] = counts.get(name, 0) + occurrences

    return result, counts


def residual_counts(text: str) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for name, pattern in RESIDUAL_PATTERNS:
        occurrences = len(pattern.findall(text))
        if occurrences:
            counts[name] = occurrences
    return counts


def decode_utf8(data: bytes, path: Path) -> Optional[str]:
    try:
        # utf-8 解码保留 BOM 字符（\ufeff），写回时原样保留。
        return data.decode("utf-8")
    except UnicodeDecodeError as exc:
        print("SKIP（不是合法 UTF-8，未改动）: %s (%s)" % (path, exc))
        return None


def short_diff(old: str, new: str, path: Path, max_lines: int) -> List[str]:
    old_lines = old.splitlines(keepends=True)
    new_lines = new.splitlines(keepends=True)
    diff = list(difflib.unified_diff(
        old_lines, new_lines,
        fromfile=str(path), tofile=str(path) + " (migrated)",
        n=2,
    ))
    if max_lines > 0 and len(diff) > max_lines:
        hidden = len(diff) - max_lines
        diff = diff[:max_lines] + ["...（省略 %d 行 diff，用 --diff 查看全部）\n" % hidden]
    return diff


def wrapper_plan(repo: Path) -> List[Tuple[Path, Path, str, str, bool]]:
    """返回 (源, 目标, 源sha, 目标sha或空, 是否需要复制)。"""
    reference = MDK_REFERENCE.resolve(strict=False)
    if not reference.is_dir():
        fail("MDK 参考目录不存在: %s" % reference)

    plan = []
    for relative in WRAPPER_FILES:
        source = reference / relative
        target = repo / relative
        if not source.is_file():
            fail("MDK 参考文件缺失: %s" % source)
        if not is_within(target.resolve(strict=False), repo):
            fail("wrapper 目标越界: %s" % target)
        source_hash = sha256_file(source)
        target_hash = sha256_file(target) if target.is_file() else ""
        plan.append((source, target, source_hash, target_hash, source_hash != target_hash))
    return plan


def copy_wrapper_atomic(source: Path, target: Path, expected_hash: str) -> None:
    data = source.read_bytes()
    actual = sha256_bytes(data)
    if actual != expected_hash:
        fail("wrapper 源文件在读取期间变化: %s" % source)
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_name(target.name + ".tmp-port-%d" % os.getpid())
    try:
        temp.write_bytes(data)
        os.replace(str(temp), str(target))
    finally:
        if temp.exists():
            temp.unlink()


def write_atomic(path: Path, data: bytes) -> None:
    temp = path.with_name(path.name + ".tmp-port-%d" % os.getpid())
    try:
        temp.write_bytes(data)
        os.replace(str(temp), str(path))
    finally:
        if temp.exists():
            temp.unlink()


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="HuhuTalk NeoForge->Forge 机械迁移（默认 dry-run）",
    )
    parser.add_argument("--repo", default=str(EXPECTED_REPO),
                        help="仓库根；必须 resolve 到固定路径")
    parser.add_argument("--apply", action="store_true",
                        help="真正写入；缺省只做 dry-run")
    parser.add_argument("--check", action="store_true",
                        help="dry-run 且有待改文件时退出码 1")
    parser.add_argument("--diff", action="store_true",
                        help="dry-run 时打印完整 unified diff")
    parser.add_argument("--max-diff-lines", type=int, default=12,
                        help="摘要模式下每个文件最多打印的 diff 行数")
    parser.add_argument("--skip-wrapper", action="store_true",
                        help="--apply 时不同步 Gradle wrapper")
    args = parser.parse_args(argv)

    if args.apply and args.check:
        fail("--apply 与 --check 不能同时使用")

    repo = resolve_repo(args.repo)
    print("repo : %s" % repo)
    print("mode : %s" % ("APPLY（会写文件）" if args.apply else "DRY-RUN（不写文件）"))

    # 1) 只读扫描并计算计划
    plans = []  # (path, data, new_text, counts, old_hash, new_hash)
    scanned = 0
    residual_total: Dict[str, int] = {}

    for relative_root in SOURCE_ROOTS:
        root = repo / relative_root
        for path in iter_java_files(root, repo):
            scanned += 1
            data = path.read_bytes()
            text = decode_utf8(data, path)
            if text is None:
                continue
            new_text, counts = transform(text)
            for name, number in residual_counts(new_text).items():
                residual_total[name] = residual_total.get(name, 0) + number
            if not counts:
                continue
            plans.append((
                path,
                data,
                new_text,
                counts,
                sha256_bytes(data),
                sha256_bytes(new_text.encode("utf-8")),
            ))

    print("scanned: %d 个 .java（src/main/java + src/test/java）" % scanned)
    print("files needing changes: %d" % len(plans))

    # 2) wrapper 计划（--apply 前先校验，避免 Java 写入后才发现参考目录缺失）
    wrapper = []
    needs_wrapper = False
    if args.apply and not args.skip_wrapper:
        wrapper = wrapper_plan(repo)
        needs_wrapper = any(item[4] for item in wrapper)
    elif not args.apply and not args.skip_wrapper:
        wrapper = wrapper_plan(repo)
        needs_wrapper = any(item[4] for item in wrapper)

    # 3) 打印逐文件摘要
    totals: Dict[str, int] = {}
    for path, _data, new_text, counts, old_hash, new_hash in plans:
        total = sum(counts.values())
        for name, number in counts.items():
            totals[name] = totals.get(name, 0) + number
        try:
            shown = path.relative_to(repo)
        except ValueError:
            shown = path
        print("")
        print("%s  changes=%d" % (shown, total))
        print("  sha256 %s -> %s" % (old_hash, new_hash))
        for name in sorted(counts):
            print("    %-28s %d" % (name, counts[name]))

        if args.diff:
            text = _data.decode("utf-8")
            sys.stdout.writelines(short_diff(text, new_text, path, max_lines=0))
        elif args.max_diff_lines > 0:
            text = _data.decode("utf-8")
            sys.stdout.writelines(short_diff(
                text, new_text, path, max_lines=args.max_diff_lines))

    # 4) wrapper 摘要
    if wrapper:
        print("")
        if args.skip_wrapper:
            print("wrapper: 已跳过（--skip-wrapper）")
        else:
            print("wrapper: %s" % ("需要同步" if needs_wrapper else "已是最新"))
            for source, target, source_hash, target_hash, changed in wrapper:
                print("  %s -> %s  %s" % (
                    source.relative_to(MDK_REFERENCE.resolve(strict=False)),
                    target.relative_to(repo),
                    "copy %s" % source_hash if changed else "up-to-date",
                ))
                if changed and target_hash:
                    print("       (old sha256 %s)" % target_hash)

    # 5) 汇总残留人工项
    print("")
    if totals:
        print("replacements by rule:")
        for name in sorted(totals):
            print("  %-28s %d" % (name, totals[name]))
        print("total replacements: %d" % sum(totals.values()))
    else:
        print("no changes planned.")

    print("")
    print("residual tokens needing later manual API work:")
    if residual_total:
        for name in sorted(residual_total):
            print("  %-34s %d" % (name, residual_total[name]))
    else:
        print("  (none)")

    # 6) 写入
    if args.apply:
        if needs_wrapper and not args.skip_wrapper:
            for source, target, source_hash, _target_hash, changed in wrapper:
                if changed:
                    copy_wrapper_atomic(source, target, source_hash)
                    print("COPIED wrapper: %s" % target.relative_to(repo))

        written = 0
        for path, data, new_text, _counts, old_hash, new_hash in plans:
            # 计划生成后若文件被外部改动，停止而不是覆盖它。
            if sha256_file(path) != old_hash:
                fail("文件在运行期间被改动，已停止且未写入: %s" % path)
            new_data = new_text.encode("utf-8")
            if sha256_bytes(new_data) != new_hash:
                fail("内部哈希不一致，未写入: %s" % path)
            write_atomic(path, new_data)
            actual = sha256_file(path)
            if actual != new_hash:
                fail("写入后哈希不一致: %s" % path)
            written += 1
            print("WROTE %s  %s -> %s" % (
                path.relative_to(repo), old_hash, new_hash))
        print("")
        print("applied: %d file(s) changed." % written)
        if written == 0:
            print("idempotent: 再执行一次仍不会有新增变化。")
        return 0

    if args.check and (plans or needs_wrapper):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
