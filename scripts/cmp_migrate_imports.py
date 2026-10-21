#!/usr/bin/env python3
"""Rewrite Kuikly UI references to upstream Compose Multiplatform / the Raft compat layer.

CMP big switch, Phase A. Idempotent: re-run it after every rebase that brings in Kuikly code.

    python3 scripts/cmp_migrate_imports.py            # rewrite in place, print a summary
    python3 scripts/cmp_migrate_imports.py --check    # exit 1 if anything would change

Every fully qualified `com.tencent.kuikly.<...>` reference (imports, inline FQNs, KDoc links,
`Class.forName` strings) is rewritten by the FIRST matching rule in RULES, longest prefixes first:

  * the Kuikly compose fork (`com.tencent.kuikly.compose.{ui,foundation,material3,animation}`) has
    the upstream API under `androidx.compose.*`; symbols that only exist in the fork are routed to
    the compat layer (`build.raft.app.compat.*`) or, when nothing replaces them yet, still to
    `androidx.compose.*` so the compiler flags them for the per-file fixers;
  * Kuikly core (Pager, Module, nvi JSON, annotations, manager) goes to the compat layer, which
    keeps the same simple names so call sites do not change;
  * `com.tencent.kuikly.lifecycle` goes to JetBrains' multiplatform androidx.lifecycle;
  * `com.tencent.kuikly.core.render.android` (the Android Kuikly host) is left alone: it is deleted
    with the Kuikly host, not migrated.

Imports that become meaningless (`com.tencent.kuikly.compose.setContent`: pages call the compat
`ComposeContainer.setContent` member) are dropped.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Source roots that compile into :shared / :androidApp. jsMain is not compiled by any target any
# more and is left untouched.
ROOTS = [
    "compose/shared/src/commonMain",
    "compose/shared/src/commonTest",
    "compose/shared/src/androidMain",
    "compose/shared/src/androidUnitTest",
    "compose/shared/src/androidInstrumentedTest",
    "compose/shared/src/iosMain",
    "compose/shared/src/iosTest",
    "compose/shared/src/nativeMain",
    "compose/shared/src/visualFixtures",
    "compose/shared/src/visualFixtureData",
    "compose/androidApp/src",
    "third_party/kuikly-compose-markdown/src",
    "third_party/markdown-parser/src",
]

COMPAT = "build.raft.app.compat"

# (kuikly prefix, replacement). A prefix matches a whole dotted name or a leading part of it
# (`a.b` matches `a.b` and `a.b.c`, not `a.bc`). Order does not matter: the longest prefix wins.
RULES: list[tuple[str, str]] = [
    # --- Kuikly-only symbols inside the forked upstream packages -------------------------------
    ("com.tencent.kuikly.compose.ui.platform.LocalActivity", f"{COMPAT}.pager.LocalActivity"),
    ("com.tencent.kuikly.compose.ui.text.font.KuiklyFont", f"{COMPAT}.ui.KuiklyFont"),
    ("com.tencent.kuikly.compose.foundation.text.SelectableText", f"{COMPAT}.ui.SelectableText"),
    ("com.tencent.kuikly.compose.ui.text.InlineBoxSpanStyle", f"{COMPAT}.ui.InlineBoxSpanStyle"),
    ("com.tencent.kuikly.compose.ui.input.pointer.nativeDispatchCapture", f"{COMPAT}.ui.nativeDispatchCapture"),
    ("com.tencent.kuikly.compose.foundation.drawer", f"{COMPAT}.ui.drawer"),
    ("com.tencent.kuikly.compose.material3.PullToRefreshState", f"{COMPAT}.ui.PullToRefreshState"),
    ("com.tencent.kuikly.compose.material3.pullToRefreshItem", f"{COMPAT}.ui.pullToRefreshItem"),
    ("com.tencent.kuikly.compose.material3.rememberPullToRefreshState", f"{COMPAT}.ui.rememberPullToRefreshState"),
    # --- Forked upstream packages: same API under androidx.compose ----------------------------
    ("com.tencent.kuikly.compose.ui", "androidx.compose.ui"),
    ("com.tencent.kuikly.compose.foundation", "androidx.compose.foundation"),
    ("com.tencent.kuikly.compose.material3", "androidx.compose.material3"),
    ("com.tencent.kuikly.compose.animation", "androidx.compose.animation"),
    # --- Other Kuikly compose packages ---------------------------------------------------------
    ("com.tencent.kuikly.compose.ComposeContainer", f"{COMPAT}.pager.ComposeContainer"),
    ("com.tencent.kuikly.compose.BackHandler", f"{COMPAT}.ui.BackHandler"),
    ("com.tencent.kuikly.compose.coroutines.withKuiklyIdleContext", f"{COMPAT}.ui.withKuiklyIdleContext"),
    ("com.tencent.kuikly.compose.coil3.AsyncImagePainter", "coil3.compose.AsyncImagePainter"),
    ("com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter", "coil3.compose.rememberAsyncImagePainter"),
    ("com.tencent.kuikly.compose.coil3.rememberAsyncImageBitmap", f"{COMPAT}.ui.rememberAsyncImageBitmap"),
    ("com.tencent.kuikly.compose.resources", f"{COMPAT}.ui"),
    ("com.tencent.kuikly.compose.text.SelectionContainer", "androidx.compose.foundation.text.selection.SelectionContainer"),
    ("com.tencent.kuikly.compose.text.rememberSelectionContainerState", f"{COMPAT}.ui.rememberSelectionContainerState"),
    ("com.tencent.kuikly.compose.extension", f"{COMPAT}.ui"),
    # --- Kuikly core: same simple names in the compat layer ------------------------------------
    ("com.tencent.kuikly.core.nvi.serialization.json", f"{COMPAT}.json"),
    ("com.tencent.kuikly.core.annotations.Page", f"{COMPAT}.pager.Page"),
    ("com.tencent.kuikly.core.pager", f"{COMPAT}.pager"),
    ("com.tencent.kuikly.core.manager", f"{COMPAT}.pager"),
    ("com.tencent.kuikly.core.base.IPagerId", f"{COMPAT}.pager.IPagerId"),
    ("com.tencent.kuikly.core.base.pagerId", f"{COMPAT}.pager.pagerId"),
    ("com.tencent.kuikly.core.base.BaseObject", f"{COMPAT}.pager.BaseObject"),
    ("com.tencent.kuikly.core.base.toInt", f"{COMPAT}.module.toInt"),
    ("com.tencent.kuikly.core.timer.setTimeout", f"{COMPAT}.pager.setTimeout"),
    ("com.tencent.kuikly.core.module", f"{COMPAT}.module"),
    ("com.tencent.kuikly.core.views.KeyboardParams", f"{COMPAT}.ui.KeyboardParams"),
    ("com.tencent.kuikly.core.views.TextInputState", f"{COMPAT}.ui.TextInputState"),
    ("com.tencent.kuikly.core.views.PlayState", f"{COMPAT}.ui.PlayState"),
    ("com.tencent.kuikly.core.views.VideoPlayControl", f"{COMPAT}.ui.VideoPlayControl"),
    ("com.tencent.kuikly.core.base.BorderStyle", f"{COMPAT}.ui.BorderStyle"),
    ("com.tencent.kuikly.core.base.Size", "androidx.compose.ui.geometry.Size"),
    ("com.tencent.kuikly.core.utils.urlParams", f"{COMPAT}.pager.urlParams"),
    # Kuikly's native-view DSL (DeclarativeBaseView, Attr, Event, ViewRef, views.*) has no
    # replacement on purpose: files that build native views are rewritten by hand.
    # --- Lifecycle -------------------------------------------------------------------------------
    ("com.tencent.kuikly.lifecycle.viewmodel.compose", "androidx.lifecycle.viewmodel.compose"),
    ("com.tencent.kuikly.lifecycle", "androidx.lifecycle"),
]

# Kuikly imports that have no counterpart and are removed outright.
DROPPED_IMPORTS = [
    re.compile(r"^import com\.tencent\.kuikly\.compose\.setContent\s*$\n?", re.M),
    # Kuikly reactive delegates (`by observable`) are replaced by Compose state in the compat Pager.
    re.compile(r"^import com\.tencent\.kuikly\.core\.reactive\.handler\.\*\s*$\n?", re.M),
]

KEEP_PREFIXES = ("com.tencent.kuikly.core.render.android",)

FQN = re.compile(r"com\.tencent\.kuikly\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_*]+)*")


def rewrite_name(name: str) -> str:
    if name.startswith(KEEP_PREFIXES):
        return name
    best: tuple[str, str] | None = None
    for prefix, replacement in RULES:
        if name == prefix or name.startswith(prefix + "."):
            if best is None or len(prefix) > len(best[0]):
                best = (prefix, replacement)
    if best is None:
        return name
    return best[1] + name[len(best[0]):]


def rewrite_text(text: str) -> str:
    for pattern in DROPPED_IMPORTS:
        text = pattern.sub("", text)
    return FQN.sub(lambda m: rewrite_name(m.group(0)), text)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="report files that would change; do not write")
    args = parser.parse_args()

    changed: list[Path] = []
    leftovers: dict[str, int] = {}
    for root in ROOTS:
        base = REPO / root
        if not base.exists():
            continue
        for path in sorted(base.rglob("*.kt")):
            original = path.read_text(encoding="utf-8")
            updated = rewrite_text(original)
            if updated != original:
                changed.append(path)
                if not args.check:
                    path.write_text(updated, encoding="utf-8")
            for m in FQN.finditer(updated):
                if not m.group(0).startswith(KEEP_PREFIXES):
                    key = ".".join(m.group(0).split(".")[:5])
                    leftovers[key] = leftovers.get(key, 0) + 1

    verb = "would change" if args.check else "rewrote"
    print(f"{verb} {len(changed)} files")
    if leftovers:
        print("unmapped Kuikly references (left for manual migration):")
        for key, count in sorted(leftovers.items(), key=lambda kv: -kv[1]):
            print(f"  {count:5d}  {key}")
    return 1 if (args.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
