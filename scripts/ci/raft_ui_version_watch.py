#!/usr/bin/env python3
"""Compare the raft-ui version mobile follows with the one the Web pins.

Mobile follows the raft-ui version recorded in tools/design-tokens/manifest.json
(the vendored styles.css origin). The Web pins its version in slock
packages/web/package.json. When the Web is ahead, the daily watch workflow opens
one GitHub issue per Web version so the bump is picked up once, not every day.

Usage:
  raft_ui_version_watch.py --manifest PATH --web-package PATH [--github-output PATH]

Prints `mobile=<v>`, `web=<v>` and `behind=true|false`; with --github-output the
same keys are appended there for later workflow steps. Fails closed: a missing
or unparsable version is an error, never "up to date".
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
MANIFEST_ORIGIN = re.compile(r"^npm:@botiverse/raft-ui@(\d+\.\d+\.\d+)/")


def parse_version(text: str) -> tuple[int, int, int]:
    match = VERSION.match(text.strip())
    if not match:
        raise ValueError(f"not an exact x.y.z version: {text!r}")
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def mobile_version(manifest: dict) -> str:
    found = set()
    for source in manifest.get("sources", {}).values():
        match = MANIFEST_ORIGIN.match(str(source.get("origin", "")))
        if match:
            found.add(match.group(1))
    if len(found) != 1:
        raise ValueError(f"manifest must name exactly one raft-ui version, found {sorted(found)}")
    return found.pop()


def web_version(package: dict) -> str:
    deps = package.get("dependencies", {})
    spec = deps.get("@botiverse/raft-ui")
    if spec is None:
        raise ValueError("packages/web/package.json has no @botiverse/raft-ui dependency")
    # The Web pins an exact version; a range would make "the version the Web
    # ships" ambiguous, so refuse it instead of guessing.
    parse_version(spec)
    return spec.strip()


def compare(manifest: dict, package: dict) -> dict[str, str]:
    mobile = mobile_version(manifest)
    web = web_version(package)
    behind = parse_version(web) > parse_version(mobile)
    return {"mobile": mobile, "web": web, "behind": "true" if behind else "false"}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--web-package", required=True, type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)

    result = compare(
        json.loads(args.manifest.read_text(encoding="utf-8")),
        json.loads(args.web_package.read_text(encoding="utf-8")),
    )
    lines = [f"{key}={value}" for key, value in result.items()]
    print("\n".join(lines))
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as out:
            out.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
