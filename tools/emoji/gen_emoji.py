#!/usr/bin/env python3
"""Generate the mobile emoji picker's data from the same emoji set the Web picker shows.

Sources (pinned in manifest.json, vendored under source/):
  - emoji-picker-react src/data/emojis.json   the set, categories, order, English names and
                                              skin-tone variations of the picker Web uses
                                              (slock packages/web `emoji-picker-react`, same version)
  - CLDR annotations (zh)                     Chinese keywords and names: cldr-json
  - CLDR annotationsDerived (zh)              `annotations-full` and `annotations-derived-full`
                                              (flags, ZWJ sequences and keycaps are only in
                                              the derived file)

What the Web picker shows and the generated data keeps:
  - Every emoji of every category, in the library's order. Web does not set
    `emojiVersion`, whose default (null) shows every emoji in the data, so no version
    cutoff is applied here either; each emoji keeps the Unicode Emoji version it was
    added in, so the picker could hide newer ones like the `emojiVersion` prop does.
  - The English names (`n`): search matches any of them; the last one is the name.
  - A skin tone picks, for every emoji with variations, the variation in that tone
    (emoji-picker-react `emojiVariationUnified`: the first whose code points include
    the tone, or the emoji itself when none does), made here per tone so the app
    indexes it. One deviation: for two-person emoji the variation with every person in
    the tone wins over the library's first match (light first person, toned second).
  - What is sent is the emoji's code points as text (`parseNativeEmoji(unified)`), the
    value Web passes to toggle a reaction or sets as a sidebar section's emoji.

Chinese keywords are CLDR's `default` keywords plus its `tts` name (and the parts of a
`tts` name such as "旗: 中国"), looked up with and without U+FE0F. Every emoji must
have them; a missing one fails the run.

Output: compose/shared/src/commonMain/kotlin/build/raft/app/emoji/RaftEmojiData.g.kt, one
string per emoji (tab-separated fields; see the generated header).

Usage:
  gen_emoji.py            regenerate the Kotlin file
  gen_emoji.py --check    fail if a source does not match manifest.json or the Kotlin file is stale
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MANIFEST = HERE / "manifest.json"
OUTPUT = REPO / "compose/shared/src/commonMain/kotlin/build/raft/app/emoji/RaftEmojiData.g.kt"

# emoji-picker-react `Categories` order without `suggested` (the app's own recents) and
# `custom` (Web passes no custom emoji).
CATEGORIES = [
    "smileys_people",
    "animals_nature",
    "food_drink",
    "travel_places",
    "activities",
    "objects",
    "symbols",
    "flags",
]

# emoji-picker-react `SkinTones` without NEUTRAL, in the library's order.
SKIN_TONES = ["1f3fb", "1f3fc", "1f3fd", "1f3fe", "1f3ff"]

FIELD_SEPARATOR = "\t"
LIST_SEPARATOR = "|"
VARIANT_SEPARATOR = ","


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def verify_sources(manifest: dict) -> list[str]:
    errors = []
    for key, entry in manifest["sources"].items():
        path = HERE / entry["path"]
        if not path.exists():
            errors.append(f"{key}: missing {entry['path']}")
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != entry["sha256"]:
            errors.append(f"{entry['path']}: sha256 {digest} != manifest {entry['sha256']}")
    return errors


def native(unified: str) -> str:
    """emoji-picker-react `parseNativeEmoji`."""
    return "".join(chr(int(part, 16)) for part in unified.split("-"))


def tone_variants(emoji: dict) -> list[str]:
    """The variation for each tone (emoji-picker-react `emojiUnified(emoji, tone)`), or [] without variations.

    The library takes the first variation that has the tone anywhere, which for an emoji of two
    people ("people holding hands") is the light-toned first person with the toned second one.
    A variation where every modifier is the tone comes first here; the library's choice is the
    fallback (the same for every one-person emoji).
    """
    variations = emoji.get("v") or []
    if not variations:
        return []

    def uniform(variation: str, tone: str) -> bool:
        modifiers = [part for part in variation.split("-") if part in SKIN_TONES]
        return bool(modifiers) and all(part == tone for part in modifiers)

    return [
        next((v for v in variations if uniform(v, tone)), None)
        or next((v for v in variations if tone in v), emoji["u"])
        for tone in SKIN_TONES
    ]


def zh_keywords(emoji_text: str, annotations: dict, derived: dict) -> list[str]:
    candidates = [emoji_text, emoji_text.replace("️", "")]
    entry = None
    for candidate in candidates:
        entry = annotations.get(candidate) or derived.get(candidate)
        if entry:
            break
    if not entry:
        return []
    keywords: list[str] = []
    # The tts name first: it is the emoji's Chinese name.
    for tts in entry.get("tts", []):
        keywords.append(tts)
        for separator in (": ", "："):
            if separator in tts:
                keywords.extend(part.strip() for part in tts.split(separator))
    keywords.extend(entry.get("default", []))
    seen: set[str] = set()
    result = []
    for keyword in keywords:
        keyword = keyword.strip().lower()
        if keyword and keyword not in seen:
            seen.add(keyword)
            result.append(keyword)
    return result


def check_field(value: str, what: str) -> str:
    for forbidden in (FIELD_SEPARATOR, LIST_SEPARATOR, "\n", "\r"):
        if forbidden in value:
            raise SystemExit(f"{what}: {value!r} contains a separator {forbidden!r}")
    return value


def build_rows(manifest: dict) -> tuple[list[list[str]], dict]:
    sources = manifest["sources"]
    data = json.loads((HERE / sources["emojiPickerReact"]["path"]).read_text(encoding="utf-8"))
    annotations = json.loads((HERE / sources["cldrAnnotationsZh"]["path"]).read_text(encoding="utf-8"))
    derived = json.loads((HERE / sources["cldrAnnotationsDerivedZh"]["path"]).read_text(encoding="utf-8"))
    annotations = annotations["annotations"]["annotations"]
    derived = derived["annotationsDerived"]["annotations"]

    unknown = set(data["emojis"]) - set(CATEGORIES) - {"custom"}
    if unknown:
        raise SystemExit(f"emoji-picker-react has categories this generator does not know: {sorted(unknown)}")
    if data["emojis"].get("custom"):
        raise SystemExit("emoji-picker-react ships custom emoji; expected none")

    rows: list[list[str]] = []
    seen: set[str] = set()
    missing_zh = []
    stats = {"counts": {}, "max_version": 0.0, "with_tones": 0}
    for category in CATEGORIES:
        emojis = data["emojis"][category]
        stats["counts"][category] = len(emojis)
        category_rows = []
        for emoji in emojis:
            unified = emoji["u"].lower()
            if unified in seen:
                raise SystemExit(f"duplicate emoji {unified}")
            seen.add(unified)
            names = [check_field(name.strip().lower(), unified) for name in emoji["n"] if name.strip()]
            # Some names repeat ("flag: china" twice): keep each once, at its last place, so
            # the last name stays the emoji's name.
            names = list(reversed(list(dict.fromkeys(reversed(names)))))
            if not names:
                raise SystemExit(f"{unified}: no English name")
            variants = tone_variants(emoji)
            if variants:
                stats["with_tones"] += 1
            added = emoji["a"]
            float(added)  # must parse
            stats["max_version"] = max(stats["max_version"], float(added))
            zh = [check_field(keyword, unified) for keyword in zh_keywords(native(unified), annotations, derived)]
            if not zh:
                missing_zh.append(unified)
            category_rows.append(
                [unified, added, VARIANT_SEPARATOR.join(variants), LIST_SEPARATOR.join(names), LIST_SEPARATOR.join(zh)]
            )
        rows.append(category_rows)
    if missing_zh:
        raise SystemExit(f"no CLDR zh annotation for {len(missing_zh)} emoji: {missing_zh[:20]}")
    stats["total"] = len(seen)
    return rows, stats


def kotlin_string(value: str) -> str:
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\t", "\\t")
    )
    return f'"{escaped}"'


def camel(category: str) -> str:
    head, *rest = category.split("_")
    return head + "".join(part.capitalize() for part in rest)


def render(manifest: dict) -> str:
    rows, stats = build_rows(manifest)
    sources = manifest["sources"]
    lines = [
        "// Generated by tools/emoji/gen_emoji.py. Do not edit by hand.",
        "// Sources (tools/emoji/manifest.json):",
    ]
    for entry in sources.values():
        lines.append(f"//   {entry['origin']} sha256={entry['sha256'][:16]}")
    lines += [
        "//",
        "// One string per emoji, fields separated by a tab:",
        "//   unified    code points in hex, '-' separated (what is sent: these code points as text)",
        "//   addedIn    Unicode Emoji version that added it",
        "//   tones      for an emoji with skin tones: the variation for each of the 5 tones",
        "//              (1F3FB..1F3FF), ',' separated; empty otherwise",
        "//   names      English names, '|' separated; the last is the emoji's name",
        "//   zh         Chinese keywords (CLDR zh), '|' separated; the first is the emoji's name",
        "",
        "package build.raft.app.emoji",
        "",
        "internal object RaftEmojiData {",
        f"    const val PickerVersion: String = {kotlin_string(manifest['emojiPickerReactVersion'])}",
        f"    const val CldrVersion: String = {kotlin_string(manifest['cldrVersion'])}",
        f"    const val EmojiCount: Int = {stats['total']}",
        f"    const val SkinToneEmojiCount: Int = {stats['with_tones']}",
        "",
        "    /** emoji-picker-react category ids, in the picker's order. */",
        "    val CategoryIds: List<String> =",
        "        listOf(",
    ]
    lines += [f"            {kotlin_string(category)}," for category in CATEGORIES]
    lines += [
        "        )",
        "",
        "    /** How many emoji each of [CategoryIds] has. */",
        "    val CategoryCounts: List<Int> = listOf(" + ", ".join(str(stats["counts"][c]) for c in CATEGORIES) + ")",
        "",
        "    /** Each category's rows, in [CategoryIds] order. */",
        "    fun categoryRows(): List<List<String>> =",
        "        listOf(",
    ]
    lines += [f"            {camel(category)}()," for category in CATEGORIES]
    lines += ["        )", ""]
    for category, category_rows in zip(CATEGORIES, rows):
        lines.append(f"    private fun {camel(category)}(): List<String> =")
        lines.append("        listOf(")
        for row in category_rows:
            lines.append(f"            {kotlin_string(FIELD_SEPARATOR.join(row))},")
        lines.append("        )")
        lines.append("")
    lines[-1] = "}"
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if sources or the Kotlin file are stale")
    args = parser.parse_args()

    manifest = load_manifest()
    errors = verify_sources(manifest)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    text = render(manifest)
    if args.check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else None
        if current != text:
            print(f"{OUTPUT.relative_to(REPO)} is stale; run python3 tools/emoji/gen_emoji.py", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO)} is current")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
