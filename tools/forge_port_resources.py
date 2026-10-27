#!/usr/bin/env python3
"""Migrate the five HuhuTalk data resources to 1.20.1 directory names.

This script is intentionally *not* executed by the implementation task.  The
upstream reviewer runs it after reviewing it.

Default mode is a dry run.  With ``--apply`` it writes only when the repository
is the fixed ``D:/Moddevelop/HuhuTalk-Forge-1.20.1`` checkout.  ``--check``
reports whether anything is still pending (exit code 1) or the migration is
already complete (exit code 0).

Only these five fixed source/destination pairs are handled:

  data/huhutalk/recipe/altar_recipe/{phone_watch,phone_smart,wifi_block}.json
      -> data/huhutalk/recipes/altar_recipe/<same name>.json
  data/huhutalk/loot_table/blocks/wifi_block.json
      -> data/huhutalk/loot_tables/blocks/wifi_block.json
  data/minecraft/tags/block/mineable/pickaxe.json
      -> data/minecraft/tags/blocks/mineable/pickaxe.json

Safety rules:
  * the three altar recipes are rewritten from TLM 1.21-style
    ``altar_recipe_serializers`` + ``result`` into the real TLM 1.5.3 Forge
    schema ``touhou_little_maid:altar_crafting`` + ``output`` with the native
    ``Item`` NBT; ingredients keep their order/duplicates, ``power`` and the
    output count are preserved;
  * the loot table and block tag are copied byte-for-byte (directory only);
  * never follow symbolic links and never touch paths outside the repository;
  * destination files that already exist but differ from the expected bytes
    are refused, never overwritten;
  * all new files are written atomically (temp file + ``os.replace`` + fsync)
    and re-read/verified before any source path is unlinked;
  * before unlinking, the original source bytes are stored under
    ``tools/forge-port-resources-backup/<run>/`` with a JSON manifest, and a
    line is appended to ``tools/forge-port-resources.log``;
  * only the five exact source files are unlinked (never recursive, never a
    directory); a failed run never deletes a source whose destination did not
    land;
  * source bytes are re-read and their SHA256 rechecked immediately before
    writing/deleting;
  * the script is idempotent: a source that is gone with a correct destination
    counts as complete, so a second run reports zero changes.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import difflib
import json
import os
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

EXPECTED_REPO = Path("D:/Moddevelop/HuhuTalk-Forge-1.20.1").resolve()
RESOURCE_ROOT = Path("src/main/resources")

RECIPE_NAMES = ("phone_watch", "phone_smart", "wifi_block")

# Audited source values.  The script refuses to rewrite a recipe whose parsed
# content no longer matches these values, so a stale/edited source cannot be
# silently converted.
EXPECTED_RECIPES = {
    "phone_watch": {
        "power": 0.5,
        "result": {"id": "huhutalk:phone_watch", "count": 1},
        "ingredients": [
            {"item": "minecraft:clock"},
            {"item": "minecraft:gold_ingot"},
            {"item": "minecraft:gold_ingot"},
            {"item": "minecraft:redstone"},
            {"item": "minecraft:redstone"},
            {"item": "minecraft:glass"},
        ],
    },
    "phone_smart": {
        "power": 4.0,
        "result": {"id": "huhutalk:phone_smart", "count": 1},
        "ingredients": [
            {"item": "huhutalk:phone_watch"},
            {"item": "minecraft:nether_star"},
            {"item": "minecraft:enchanted_golden_apple"},
            {"item": "minecraft:ender_eye"},
            {"item": "minecraft:ender_eye"},
            {"item": "minecraft:diamond"},
        ],
    },
    "wifi_block": {
        "power": 1.0,
        "result": {"id": "huhutalk:wifi_block", "count": 1},
        "ingredients": [
            {"item": "minecraft:iron_block"},
            {"item": "minecraft:redstone"},
            {"item": "minecraft:redstone"},
            {"item": "minecraft:quartz"},
            {"item": "minecraft:lever"},
            {"item": "minecraft:lightning_rod"},
        ],
    },
}


class ResourceError(RuntimeError):
    """A guard refused the migration or the source/target state is invalid."""


@dataclass(frozen=True)
class Entry:
    key: str
    kind: str  # "recipe" | "loot" | "tag"
    source: Path
    target: Path
    source_sha256: str | None
    target_bytes: bytes | None
    action: str  # "write+delete" | "delete" | "done"
    source_raw: bytes | None = None
    target_raw: bytes | None = None


@dataclass
class Plan:
    entries: list[Entry] = field(default_factory=list)


# --------------------------------------------------------------------------
# path / repo guards
# --------------------------------------------------------------------------

def is_under(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
        return True
    except ValueError:
        return False


def check_expected_repo(repo: Path) -> None:
    if repo.resolve() != EXPECTED_REPO:
        raise ResourceError(
            f"refusing: repository is {repo.resolve()!r}, expected {EXPECTED_REPO!r}"
        )
    gradle_properties = repo / "gradle.properties"
    if not gradle_properties.is_file():
        raise ResourceError(f"refusing: missing {gradle_properties}")
    properties = gradle_properties.read_text(encoding="utf-8")
    if "minecraft_version=1.20.1" not in properties:
        raise ResourceError("refusing: gradle.properties is not the 1.20.1 project")
    if not (repo / RESOURCE_ROOT / "pack.mcmeta").is_file():
        raise ResourceError("refusing: missing src/main/resources/pack.mcmeta")


def checked_path(repo: Path, relative: Path) -> Path:
    """Return the absolute path, refusing escapes and symbolic links."""
    if relative.is_absolute():
        raise ResourceError(f"refusing: absolute path in fixed mapping: {relative}")
    absolute = Path(os.path.abspath(repo / relative))
    if not is_under(absolute, repo):
        raise ResourceError(f"refusing: path escapes repository: {relative}")
    try:
        resolved = Path(os.path.realpath(absolute))
    except OSError as error:
        raise ResourceError(f"refusing: cannot resolve {absolute}: {error}") from error
    if os.path.normcase(str(resolved)) != os.path.normcase(str(absolute)):
        raise ResourceError(f"refusing: symlink in path: {relative}")
    cursor = repo
    for part in relative.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise ResourceError(f"refusing: symbolic link in path: {relative}")
    return absolute


# --------------------------------------------------------------------------
# strict JSON + recipe transform
# --------------------------------------------------------------------------

class DuplicateKeyError(ValueError):
    pass


def _no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key!r}")
        result[key] = value
    return result


def parse_json(raw: bytes, label: str) -> dict:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ResourceError(f"{label}: not UTF-8: {error}") from error
    try:
        value = json.loads(text, object_pairs_hook=_no_duplicates)
    except (json.JSONDecodeError, DuplicateKeyError) as error:
        raise ResourceError(f"{label}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ResourceError(f"{label}: top-level JSON value is not an object")
    return value


def canonical_json(value: dict) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def validate_source_recipe(key: str, value: dict) -> None:
    expected = EXPECTED_RECIPES[key]
    if value.get("ingredients") != expected["ingredients"]:
        raise ResourceError(
            f"{key}: source ingredients differ from the audited order/amounts"
        )
    if value.get("power") != expected["power"]:
        raise ResourceError(f"{key}: source power differs from the audited value")
    result = value.get("result")
    if not isinstance(result, dict) or result.get("id") != expected["result"]["id"]:
        raise ResourceError(f"{key}: source result.id differs from the audited value")
    if result.get("count") != expected["result"]["count"] or isinstance(
        result.get("count"), bool
    ):
        raise ResourceError(f"{key}: source result.count differs from the audited value")


def transform_recipe(key: str, value: dict) -> bytes:
    validate_source_recipe(key, value)
    expected = EXPECTED_RECIPES[key]
    transformed = {
        "type": "touhou_little_maid:altar_crafting",
        "output": {
            "type": "minecraft:item",
            "nbt": {
                "Item": {
                    "id": expected["result"]["id"],
                    "Count": int(expected["result"]["count"]),
                }
            },
        },
        "power": expected["power"],
        "ingredients": expected["ingredients"],
    }
    return canonical_json(transformed)


def recipe_target_matches(key: str, value: dict) -> bool:
    if set(value) != {"type", "output", "power", "ingredients"}:
        return False
    if value.get("type") != "touhou_little_maid:altar_crafting":
        return False
    if value.get("power") != EXPECTED_RECIPES[key]["power"]:
        return False
    if value.get("ingredients") != EXPECTED_RECIPES[key]["ingredients"]:
        return False
    output = value.get("output")
    if not isinstance(output, dict) or set(output) != {"type", "nbt"}:
        return False
    if output.get("type") != "minecraft:item":
        return False
    nbt = output.get("nbt")
    if not isinstance(nbt, dict) or set(nbt) != {"Item"}:
        return False
    item = nbt.get("Item")
    if not isinstance(item, dict) or set(item) != {"id", "Count"}:
        return False
    if item.get("id") != EXPECTED_RECIPES[key]["result"]["id"]:
        return False
    return item.get("Count") == EXPECTED_RECIPES[key]["result"]["count"] and not isinstance(
        item.get("Count"), bool
    )


def copy_target_matches(key: str, value: dict) -> bool:
    if key == "loot":
        if value.get("type") != "minecraft:block":
            return False
        pools = value.get("pools")
        if not isinstance(pools, list) or not pools:
            return False
        found = False
        for pool in pools:
            if not isinstance(pool, dict):
                continue
            entries = pool.get("entries")
            if not isinstance(entries, list):
                continue
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                if entry.get("name") != "huhutalk:wifi_block":
                    continue
                conditions = pool.get("conditions")
                if not isinstance(conditions, list):
                    continue
                if any(
                    isinstance(condition, dict)
                    and condition.get("condition") == "minecraft:survives_explosion"
                    for condition in conditions
                ):
                    found = True
        return found
    if key == "tag":
        values = value.get("values")
        return isinstance(values, list) and "huhutalk:wifi_block" in values
    raise ResourceError(f"unknown copy entry: {key}")


# --------------------------------------------------------------------------
# inspection
# --------------------------------------------------------------------------

def build_entries(repo: Path) -> list[Entry]:
    entries = []
    for name in RECIPE_NAMES:
        entries.append(
            Entry(
                key=name,
                kind="recipe",
                source=checked_path(repo, RESOURCE_ROOT / "data/huhutalk/recipe/altar_recipe" / f"{name}.json"),
                target=checked_path(repo, RESOURCE_ROOT / "data/huhutalk/recipes/altar_recipe" / f"{name}.json"),
                source_sha256=None,
                target_bytes=None,
                action="done",
            )
        )
    entries.append(
        Entry(
            key="loot",
            kind="loot",
            source=checked_path(repo, RESOURCE_ROOT / "data/huhutalk/loot_table/blocks/wifi_block.json"),
            target=checked_path(repo, RESOURCE_ROOT / "data/huhutalk/loot_tables/blocks/wifi_block.json"),
            source_sha256=None,
            target_bytes=None,
            action="done",
        )
    )
    entries.append(
        Entry(
            key="tag",
            kind="tag",
            source=checked_path(repo, RESOURCE_ROOT / "data/minecraft/tags/block/mineable/pickaxe.json"),
            target=checked_path(repo, RESOURCE_ROOT / "data/minecraft/tags/blocks/mineable/pickaxe.json"),
            source_sha256=None,
            target_bytes=None,
            action="done",
        )
    )
    return entries


def sha256(data: bytes) -> str:
    import hashlib

    return hashlib.sha256(data).hexdigest()


def inspect(repo: Path) -> list[Entry]:
    entries = build_entries(repo)
    inspected = []
    for entry in entries:
        source_exists = entry.source.is_file()
        target_exists = entry.target.is_file()
        if not source_exists and not target_exists:
            raise ResourceError(
                f"refusing: neither source nor target exists for {entry.key}: "
                f"{entry.source.relative_to(repo)}"
            )
        source_raw = None
        target_raw = None
        target_bytes = None
        source_sha = None

        if source_exists:
            if entry.source.is_symlink():
                raise ResourceError(f"refusing: source is a symlink: {entry.source}")
            source_raw = entry.source.read_bytes()
            source_sha = sha256(source_raw)
            if entry.kind == "recipe":
                target_bytes = transform_recipe(
                    entry.key, parse_json(source_raw, str(entry.source))
                )
            else:
                target_bytes = source_raw

        if target_exists:
            if entry.target.is_symlink():
                raise ResourceError(f"refusing: target is a symlink: {entry.target}")
            target_raw = entry.target.read_bytes()
            target_value = parse_json(target_raw, str(entry.target))
            if entry.kind == "recipe":
                if not recipe_target_matches(entry.key, target_value):
                    raise ResourceError(
                        f"refusing: existing target has different content: "
                        f"{entry.target.relative_to(repo)}"
                    )
            elif not copy_target_matches(entry.key, target_value):
                raise ResourceError(
                    f"refusing: existing target has different content: "
                    f"{entry.target.relative_to(repo)}"
                )

        if source_exists and target_exists:
            assert target_bytes is not None and target_raw is not None
            if target_raw != target_bytes:
                raise ResourceError(
                    f"refusing: destination exists with different bytes: "
                    f"{entry.target.relative_to(repo)}; not overwriting"
                )
            action = "delete"
        elif source_exists:
            action = "write+delete"
        else:
            action = "done"

        inspected.append(
            Entry(
                key=entry.key,
                kind=entry.kind,
                source=entry.source,
                target=entry.target,
                source_sha256=source_sha,
                target_bytes=target_bytes,
                action=action,
                source_raw=source_raw,
                target_raw=target_raw,
            )
        )
    return inspected


# --------------------------------------------------------------------------
# apply helpers
# --------------------------------------------------------------------------

def write_atomic(path: Path, data: bytes) -> None:
    if path.exists():
        raise ResourceError(f"refusing: destination appeared during migration: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temp_name = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=path.parent
    )
    temp_path = Path(temp_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
    finally:
        if temp_path.exists():
            temp_path.unlink()


def write_backups(repo: Path, entries: list[Entry]) -> Path | None:
    sources = [entry for entry in entries if entry.source_raw is not None]
    if not sources:
        return None
    base = checked_path(repo, Path("tools/forge-port-resources-backup"))
    base.mkdir(parents=True, exist_ok=True)
    run_id = _dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir = base / run_id
    suffix = 0
    while run_dir.exists():
        suffix += 1
        run_dir = base / f"{run_id}-{suffix}"
    run_dir.mkdir(parents=True)
    manifest = {"created_at": _dt.datetime.now().isoformat(timespec="seconds"), "files": []}
    for entry in sources:
        flat = str(entry.source.relative_to(repo)).replace("/", "__").replace("\\", "__")
        backup_path = checked_path(repo, run_dir.relative_to(repo) / flat)
        write_atomic(backup_path, entry.source_raw)
        manifest["files"].append(
            {
                "source": str(entry.source.relative_to(repo)).replace("\\", "/"),
                "target": str(entry.target.relative_to(repo)).replace("\\", "/"),
                "backup": str(backup_path.relative_to(repo)).replace("\\", "/"),
                "sha256": entry.source_sha256,
                "bytes": len(entry.source_raw),
            }
        )
    write_atomic(checked_path(repo, run_dir.relative_to(repo) / "manifest.json"),
                 canonical_json(manifest))
    return run_dir


def append_log(repo: Path, lines: list[str]) -> None:
    log_path = checked_path(repo, Path("tools/forge-port-resources.log"))
    with log_path.open("a", encoding="utf-8", newline="\n") as handle:
        for line in lines:
            handle.write(line + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def apply_plan(repo: Path, entries: list[Entry]) -> None:
    # Re-read every source immediately before writing: review-time bytes must
    # still be the bytes being migrated.
    for entry in entries:
        if entry.source_sha256 is None:
            continue
        if not entry.source.is_file():
            raise ResourceError(f"refusing: source vanished: {entry.source}")
        current = entry.source.read_bytes()
        if sha256(current) != entry.source_sha256 or current != entry.source_raw:
            raise ResourceError(
                f"refusing: source changed since inspection: "
                f"{entry.source.relative_to(repo)}"
            )
        if entry.target.exists() and not entry.target.is_file():
            raise ResourceError(f"refusing: target is not a regular file: {entry.target}")

    log_lines = [
        f"{_dt.datetime.now().isoformat(timespec='seconds')} apply repo={repo}"
    ]

    # 1. Write every missing destination and verify it landed correctly.
    for entry in entries:
        if entry.action != "write+delete":
            continue
        assert entry.target_bytes is not None
        write_atomic(entry.target, entry.target_bytes)
        if entry.target.read_bytes() != entry.target_bytes:
            raise ResourceError(f"refusing: written bytes differ: {entry.target}")
        log_lines.append(
            f"wrote {entry.target.relative_to(repo)} sha256={sha256(entry.target_bytes)}"
        )

    # 2. Back up original source bytes before the first deletion so a later
    #    failure can still be recovered.
    run_dir = write_backups(repo, entries)
    if run_dir is not None:
        log_lines.append(f"backup {run_dir.relative_to(repo)}")

    # 3. Only now unlink the exact five source files (never directories).
    for entry in entries:
        if entry.action not in {"write+delete", "delete"}:
            continue
        checked_path(repo, entry.source.relative_to(repo))
        if entry.source.is_symlink():
            raise ResourceError(f"refusing: source became a symlink: {entry.source}")
        if not entry.target.is_file():
            raise ResourceError(
                f"refusing: destination missing before unlink: {entry.target}"
            )
        if entry.source.is_file():
            # Re-check the bytes once more directly before the destructive step.
            if entry.source.read_bytes() != entry.source_raw:
                raise ResourceError(
                    f"refusing: source changed before unlink: "
                    f"{entry.source.relative_to(repo)}"
                )
            entry.source.unlink()
            if entry.source.exists():
                raise ResourceError(f"refusing: source still exists: {entry.source}")
            log_lines.append(
                f"removed {entry.source.relative_to(repo)} sha256={entry.source_sha256}"
            )

    log_lines.append("result=ok")
    append_log(repo, log_lines)


# --------------------------------------------------------------------------
# reporting / main
# --------------------------------------------------------------------------

def print_plan(repo: Path, entries: list[Entry], show_diff: bool) -> int:
    pending = [entry for entry in entries if entry.action != "done"]
    print(f"repo: {repo}")
    print(f"fixed mappings: {len(entries)}; pending: {len(pending)}")
    for entry in entries:
        source_rel = entry.source.relative_to(repo).as_posix()
        target_rel = entry.target.relative_to(repo).as_posix()
        print(f"  [{entry.action}] {source_rel}")
        print(f"             -> {target_rel}")
        if show_diff and entry.action == "write+delete" and entry.kind == "recipe":
            assert entry.source_raw is not None and entry.target_bytes is not None
            diff = difflib.unified_diff(
                entry.source_raw.decode("utf-8").splitlines(),
                entry.target_bytes.decode("utf-8").splitlines(),
                fromfile=source_rel,
                tofile=target_rel,
                lineterm="",
            )
            print("\n".join("    " + line for line in diff))
    return len(pending)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", dest="mode", action="store_const", const="apply",
                      help="write the five resources and remove the old fixed files")
    mode.add_argument("--check", dest="mode", action="store_const", const="check",
                      help="exit 1 if the migration is still pending, 0 if complete")
    parser.set_defaults(mode="dry-run")
    parser.add_argument("--diff", action="store_true",
                        help="print unified JSON diffs for recipe rewrites (default in dry run)")
    args = parser.parse_args(argv)

    repo = EXPECTED_REPO
    try:
        check_expected_repo(repo)
        entries = inspect(repo)

        if args.mode == "check":
            pending = sum(1 for entry in entries if entry.action != "done")
            print(f"repo: {repo}")
            print(f"pending: {pending} of {len(entries)} fixed mappings")
            for entry in entries:
                print(f"  [{entry.action}] {entry.source.relative_to(repo).as_posix()}")
            return 1 if pending else 0

        pending = print_plan(
            repo, entries, show_diff=args.diff or args.mode == "dry-run"
        )
        if args.mode == "apply":
            if pending == 0:
                print("already complete: 0 changes (nothing written)")
                return 0
            apply_plan(repo, entries)
            print(f"applied: {pending} change(s); backup/log under tools/")
        elif pending:
            print("dry run: no files written; re-run with --apply after review")
        else:
            print("already complete: 0 changes")
        return 0
    except ResourceError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
