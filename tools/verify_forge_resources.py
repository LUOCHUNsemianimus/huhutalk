#!/usr/bin/env python3
"""Read-only audit of HuhuTalk's 1.20.1 resources.

This is the independent resource check required by the task card; it is *not*
replaced by ``compileJava``.  It never writes files.

Checks:
  * every ``src/main/resources/**/*.json`` plus ``pack.mcmeta`` parses as strict
    JSON (duplicate object keys are an error);
  * ``pack.mcmeta`` uses ``pack_format`` 15;
  * ``huhutalk.mixins.json`` uses ``compatibilityLevel`` JAVA_17 and
    ``injectors.defaultRequire`` 1;
  * the actual ``assets/huhutalk/lang/en_us.json`` and ``zh_cn.json`` files have
    the same key set, with corresponding printf argument types/indices;
  * the three target altar recipes use the real TLM 1.5.3
    ``touhou_little_maid:altar_crafting`` schema: native item output NBT,
    the audited 6 ingredients in order (duplicates kept), power and count;
  * the five legacy 1.20.1-invalid paths do not exist, the five target paths do,
    and the WiFi loot table / mineable pickaxe tag keep their meaning.

Exit codes: 0 = all checks passed, 1 = audit findings, 2 = cannot run.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path("D:/Moddevelop/HuhuTalk-Forge-1.20.1").resolve()
RESOURCES = REPO / "src/main/resources"

RECIPES = {
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

TARGET_RECIPES = "data/huhutalk/recipes/altar_recipe"
LEGACY_RECIPES = "data/huhutalk/recipe/altar_recipe"
TARGET_LOOT = "data/huhutalk/loot_tables/blocks/wifi_block.json"
LEGACY_LOOT = "data/huhutalk/loot_table/blocks/wifi_block.json"
TARGET_TAG = "data/minecraft/tags/blocks/mineable/pickaxe.json"
LEGACY_TAG = "data/minecraft/tags/block/mineable/pickaxe.json"

FORMAT_RE = re.compile(r"%(?:(\d+)\$)?[-+ 0,#(]*\d*(?:\.\d+)?([A-Za-z%])")


class Findings:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.notes: list[str] = []

    def fail(self, message: str) -> None:
        self.errors.append(message)

    def info(self, message: str) -> None:
        self.notes.append(message)


class DuplicateKeyError(ValueError):
    pass


def _no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key!r}")
        result[key] = value
    return result


def load(path: Path, findings: Findings) -> dict | None:
    try:
        raw = path.read_bytes()
    except OSError as error:
        findings.fail(f"{path.relative_to(REPO)}: cannot read: {error}")
        return None
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        findings.fail(f"{path.relative_to(REPO)}: not UTF-8: {error}")
        return None
    try:
        value = json.loads(text, object_pairs_hook=_no_duplicates)
    except (json.JSONDecodeError, DuplicateKeyError) as error:
        findings.fail(f"{path.relative_to(REPO)}: invalid JSON: {error}")
        return None
    if not isinstance(value, dict):
        findings.fail(f"{path.relative_to(REPO)}: top-level value is not an object")
        return None
    return value


def audit_all_json(findings: Findings) -> None:
    files = sorted(RESOURCES.rglob("*.json"))
    parsed = 0
    for path in files:
        if load(path, findings) is not None:
            parsed += 1
    findings.info(f"strict JSON: {parsed}/{len(files)} *.json parsed")
    pack_path = RESOURCES / "pack.mcmeta"
    if not pack_path.is_file():
        findings.fail("pack.mcmeta: missing")
        return
    pack = load(pack_path, findings)
    if pack is None:
        return
    pack_format = pack.get("pack", {}).get("pack_format") if isinstance(pack.get("pack"), dict) else None
    if pack_format != 15:
        findings.fail(f"pack.mcmeta: pack_format is {pack_format!r}, expected 15")
    else:
        findings.info("pack.mcmeta: pack_format 15")


def audit_mixins(findings: Findings) -> None:
    mixins = load(RESOURCES / "huhutalk.mixins.json", findings)
    if mixins is None:
        return
    if mixins.get("compatibilityLevel") != "JAVA_17":
        findings.fail(
            f"huhutalk.mixins.json: compatibilityLevel is "
            f"{mixins.get('compatibilityLevel')!r}, expected 'JAVA_17'"
        )
    injectors = mixins.get("injectors")
    if not isinstance(injectors, dict) or injectors.get("defaultRequire") != 1:
        findings.fail("huhutalk.mixins.json: injectors.defaultRequire is not 1")
    if "compatibilityLevel" in mixins and "injectors" in mixins:
        findings.info("huhutalk.mixins.json: JAVA_17 / defaultRequire 1")


def format_signature(text: str) -> list[tuple[int, str]]:
    arguments: list[tuple[int, str]] = []
    auto = 0
    for match in FORMAT_RE.finditer(text):
        index, conversion = match.groups()
        if conversion == "%":
            continue
        if index is None:
            auto += 1
            position = auto
        else:
            position = int(index)
        arguments.append((position, conversion.lower()))
    return sorted(arguments)


def audit_lang(findings: Findings) -> None:
    lang_dir = RESOURCES / "assets/huhutalk/lang"
    en_path = lang_dir / "en_us.json"
    zh_path = lang_dir / "zh_cn.json"
    names = sorted(path.name for path in lang_dir.glob("*.json")) if lang_dir.is_dir() else []
    findings.info(f"lang files: {', '.join(names) if names else '(none)'}")
    if not en_path.is_file() or not zh_path.is_file():
        findings.fail("lang: expected assets/huhutalk/lang/en_us.json and zh_cn.json")
        return
    en = load(en_path, findings)
    zh = load(zh_path, findings)
    if en is None or zh is None:
        return
    en_keys, zh_keys = set(en), set(zh)
    missing_in_zh = sorted(en_keys - zh_keys)
    missing_in_en = sorted(zh_keys - en_keys)
    if missing_in_zh:
        findings.fail(f"lang: {len(missing_in_zh)} key(s) missing in zh_cn: {missing_in_zh[:10]}")
    if missing_in_en:
        findings.fail(f"lang: {len(missing_in_en)} key(s) missing in en_us: {missing_in_en[:10]}")
    for key in sorted(en_keys & zh_keys):
        if not isinstance(en[key], str) or not isinstance(zh[key], str):
            findings.fail(f"lang: value for {key!r} is not a string")
            continue
        en_args = format_signature(en[key])
        zh_args = format_signature(zh[key])
        if en_args != zh_args:
            findings.fail(
                f"lang: printf arguments differ for {key!r}: en={en_args} zh={zh_args}"
            )
    findings.info(
        f"lang: en_us={len(en)} keys, zh_cn={len(zh)} keys, "
        f"common={len(en_keys & zh_keys)}, mismatched placeholders checked"
    )


def audit_recipes(findings: Findings) -> None:
    for name, expected in RECIPES.items():
        relative = f"{TARGET_RECIPES}/{name}.json"
        path = RESOURCES / relative
        if not path.is_file():
            findings.fail(f"recipe {name}: missing target {relative}")
            continue
        value = load(path, findings)
        if value is None:
            continue
        if set(value) != {"type", "output", "power", "ingredients"}:
            findings.fail(
                f"recipe {name}: keys are {sorted(value)}, expected "
                "type/output/power/ingredients only"
            )
            continue
        if value.get("type") != "touhou_little_maid:altar_crafting":
            findings.fail(
                f"recipe {name}: type is {value.get('type')!r}, "
                "expected 'touhou_little_maid:altar_crafting'"
            )
        if value.get("ingredients") != expected["ingredients"]:
            findings.fail(f"recipe {name}: ingredients/order/count differ from audited values")
        if value.get("power") != expected["power"]:
            findings.fail(f"recipe {name}: power is {value.get('power')!r}, expected {expected['power']}")
        output = value.get("output")
        if not isinstance(output, dict) or set(output) != {"type", "nbt"}:
            findings.fail(f"recipe {name}: output must be an object with type/nbt only")
            continue
        if output.get("type") != "minecraft:item":
            findings.fail(f"recipe {name}: output.type must be minecraft:item")
        nbt = output.get("nbt")
        if not isinstance(nbt, dict) or set(nbt) != {"Item"}:
            findings.fail(f"recipe {name}: output.nbt must contain only Item")
            continue
        item = nbt.get("Item")
        if not isinstance(item, dict) or set(item) != {"id", "Count"}:
            findings.fail(f"recipe {name}: output.nbt.Item must contain only id/Count")
            continue
        if item.get("id") != expected["result"]["id"]:
            findings.fail(
                f"recipe {name}: output id is {item.get('id')!r}, "
                f"expected {expected['result']['id']!r}"
            )
        count = item.get("Count")
        if count != expected["result"]["count"] or isinstance(count, bool):
            findings.fail(f"recipe {name}: output Count is {count!r}, expected 1")
        if len(expected["ingredients"]) != 6:
            findings.fail(f"recipe {name}: audit table does not describe 6 ingredients")

    findings.info(f"altar recipes: {len(RECIPES)} target files checked")


def audit_paths_and_loot_tag(findings: Findings) -> None:
    legacy = [f"{LEGACY_RECIPES}/{name}.json" for name in RECIPES]
    legacy += [LEGACY_LOOT, LEGACY_TAG]
    for relative in legacy:
        if (RESOURCES / relative).exists():
            findings.fail(f"legacy path still exists: {relative}")

    targets = [f"{TARGET_RECIPES}/{name}.json" for name in RECIPES]
    targets += [TARGET_LOOT, TARGET_TAG]
    for relative in targets:
        if not (RESOURCES / relative).is_file():
            findings.fail(f"target path missing: {relative}")

    loot = load(RESOURCES / TARGET_LOOT, findings) if (RESOURCES / TARGET_LOOT).is_file() else None
    if loot is not None:
        if loot.get("type") != "minecraft:block":
            findings.fail("WiFi loot: type is not minecraft:block")
        found_item = False
        found_condition = False
        pools = loot.get("pools")
        if isinstance(pools, list):
            for pool in pools:
                if not isinstance(pool, dict):
                    continue
                entries = pool.get("entries")
                if isinstance(entries, list) and any(
                    isinstance(entry, dict) and entry.get("name") == "huhutalk:wifi_block"
                    for entry in entries
                ):
                    found_item = True
                conditions = pool.get("conditions")
                if isinstance(conditions, list) and any(
                    isinstance(condition, dict)
                    and condition.get("condition") == "minecraft:survives_explosion"
                    for condition in conditions
                ):
                    found_condition = True
        if not found_item:
            findings.fail("WiFi loot: no entry drops huhutalk:wifi_block")
        if not found_condition:
            findings.fail("WiFi loot: missing minecraft:survives_explosion condition")
        if found_item and found_condition:
            findings.info("WiFi loot: drops huhutalk:wifi_block with survives_explosion")

    tag = load(RESOURCES / TARGET_TAG, findings) if (RESOURCES / TARGET_TAG).is_file() else None
    if tag is not None:
        values = tag.get("values")
        if not isinstance(values, list) or "huhutalk:wifi_block" not in values:
            findings.fail("mineable/pickaxe tag: huhutalk:wifi_block is not listed")
        else:
            findings.info("mineable/pickaxe: huhutalk:wifi_block listed")


def main() -> int:
    if not RESOURCES.is_dir():
        print(f"ERROR: missing resource root: {RESOURCES}", file=sys.stderr)
        return 2
    findings = Findings()
    audit_all_json(findings)
    audit_mixins(findings)
    audit_lang(findings)
    audit_recipes(findings)
    audit_paths_and_loot_tag(findings)

    for note in findings.notes:
        print(f"INFO {note}")
    for error in findings.errors:
        print(f"FAIL {error}")
    print(
        f"result: {'PASS' if not findings.errors else 'FAIL'} "
        f"({len(findings.errors)} finding(s))"
    )
    return 0 if not findings.errors else 1


if __name__ == "__main__":
    sys.exit(main())
