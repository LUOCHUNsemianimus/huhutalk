#!/usr/bin/env python3
"""Migrate HuhuTalk network imports to the shared Forge 1.20.1 transport.

This script is intentionally *not* executed by the implementation task. The
upstream reviewer runs it after reviewing the source change.

Default mode is a dry run. With ``--apply`` it only writes when the repository
is the fixed ``D:/Moddevelop/HuhuTalk-Forge-1.20.1`` checkout.

Rules:
  * scan only ``src/main/java`` and ``src/test/java``;
  * only ``*.java`` files;
  * skip any ``network/transport`` directory completely, so the transport
    implementation keeps its genuine Forge/Netty imports;
  * never follow symbolic links (neither files nor directories), so a link
    cannot escape the fixed repository or smuggle in a transport file;
  * replace only imports/identifiers listed below, never business conditions;
  * turn ``RegistryFriendlyByteBuf`` into the native 1.20.1
    ``FriendlyByteBuf``;
  * preserve a UTF-8 BOM and the original CRLF/LF bytes (only the listed
    substrings are replaced; line endings are never decoded/re-encoded);
  * be idempotent (a second run produces no changes);
  * before ``--apply`` writes anything, re-read every file and abort if its
    bytes differ from the scanned bytes; write a temp file in the same
    directory and atomically ``os.replace`` it, after checking the resolved
    path is still inside the repository and outside ``network/transport``.
"""

from __future__ import annotations

import argparse
import codecs
import difflib
import os
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

EXPECTED_REPO = Path("D:/Moddevelop/HuhuTalk-Forge-1.20.1").resolve()
SOURCE_ROOTS = (Path("src/main/java"), Path("src/test/java"))
SKIP_DIR_NAMES = {".git", ".gradle", "build", "run", "run-data", "out", "logs"}

IMPORT_REPLACEMENTS = {
    "import net.minecraft.network.RegistryFriendlyByteBuf;":
        "import net.minecraft.network.FriendlyByteBuf;",
    "import net.minecraft.network.codec.ByteBufCodecs;":
        "import com.huhutalk.network.transport.ByteBufCodecs;",
    "import net.minecraft.network.codec.StreamCodec;":
        "import com.huhutalk.network.transport.StreamCodec;",
    "import net.minecraft.network.codec.*;":
        "import com.huhutalk.network.transport.*;",
    "import net.minecraft.network.protocol.common.custom.CustomPacketPayload;":
        "import com.huhutalk.network.transport.CustomPacketPayload;",
    "import net.minecraftforge.network.handling.IPayloadContext;":
        "import com.huhutalk.network.transport.IPayloadContext;",
    "import net.minecraftforge.network.PacketDistributor;":
        "import com.huhutalk.network.transport.PacketDistributor;",
}

IDENTIFIER_REPLACEMENTS = {
    "RegistryFriendlyByteBuf": "FriendlyByteBuf",
}


@dataclass(frozen=True)
class Change:
    path: Path
    original_bytes: bytes
    updated_bytes: bytes
    original_text: str
    updated_text: str


def is_under(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
        return True
    except ValueError:
        return False


def is_in_transport(relative: Path) -> bool:
    parts = relative.parts
    return any(
        parts[index] == "network" and parts[index + 1] == "transport"
        for index in range(len(parts) - 1)
    )


def check_expected_repo(repo: Path) -> None:
    repo = repo.resolve()
    if repo != EXPECTED_REPO:
        raise SystemExit(
            f"refusing to write: repository is {repo!r}, expected {EXPECTED_REPO!r}"
        )
    gradle_properties = repo / "gradle.properties"
    if not gradle_properties.is_file():
        raise SystemExit(f"refusing to write: missing {gradle_properties}")
    properties = gradle_properties.read_text(encoding="utf-8")
    if "minecraft_version=1.20.1" not in properties:
        raise SystemExit("refusing to write: gradle.properties is not the 1.20.1 project")
    if not (repo / "src/main/java/com/huhutalk/HuhuTalk.java").is_file():
        raise SystemExit("refusing to write: missing HuhuTalk.java marker")


def _prune_directories(current: Path, names: list[str], repo: Path) -> list[str]:
    kept = []
    for name in sorted(names):
        child = current / name
        if name in SKIP_DIR_NAMES:
            continue
        if child.is_symlink():
            # Never follow symbolic links; this also covers links leaving the repo.
            continue
        if current.name == "network" and name == "transport":
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
                if path.is_symlink():
                    # Skip file links entirely instead of following their target.
                    continue
                if not path.is_file():
                    continue
                try:
                    resolved = path.resolve()
                except OSError:
                    continue
                if not is_under(resolved, repo):
                    continue
                relative = resolved.relative_to(repo)
                if is_in_transport(relative):
                    continue
                yield resolved


def rewrite_text(text: str) -> str:
    for old, new in IMPORT_REPLACEMENTS.items():
        text = text.replace(old, new)
    for old, new in IDENTIFIER_REPLACEMENTS.items():
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
        changes.append(Change(path, raw, updated_bytes, text, updated_text))
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
        relative = resolved.relative_to(repo)
        if is_in_transport(relative):
            raise SystemExit(f"refusing to write into network/transport: {relative}")
        try:
            current = resolved.read_bytes()
        except OSError as error:
            raise SystemExit(f"refusing to write: cannot re-read {resolved}: {error}") from error
        if current != change.original_bytes:
            raise SystemExit(
                f"refusing to write {relative}: file changed since it was scanned; "
                "re-run the dry run and review again"
            )


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
    removed = sum(
        1 for line in change.original_text.splitlines()
        if line not in change.updated_text.splitlines()
    )
    added = sum(
        1 for line in change.updated_text.splitlines()
        if line not in change.original_text.splitlines()
    )
    print(f"  {relative}  (-{removed}/+{added})")
    if not show_diff:
        return
    diff = difflib.unified_diff(
        change.original_text.splitlines(),
        change.updated_text.splitlines(),
        fromfile=str(relative),
        tofile=str(relative) + " (migrated)",
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
