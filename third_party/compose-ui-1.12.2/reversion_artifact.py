#!/usr/bin/env python3
"""Copy one Maven artifact version from a local repo to a staging repo under a new version.

The upstream build publishes `org.jetbrains.compose.ui:ui-<target>:1.12.2` (the exact upstream
version, so every project dependency in the .pom/.module is written as 1.12.2, like the official
release). This script re-versions only that artifact to e.g. 1.12.2-raft.1:

  * files `<artifact>-<from>[-classifier].<ext>` are copied as `<artifact>-<to>[-classifier].<ext>`;
  * the .pom's own <version> (the one after the top-level <artifactId>) is changed; dependency and
    dependencyManagement versions are untouched;
  * the .module's per-variant file `url`/`name` are renamed; dependencies, constraints, attributes,
    sizes and hashes stay as they are (the content of those files does not change). The .module
    `component` block still points at the official root `ui:<from>` module: Gradle reads only its
    attributes when consuming a platform module directly;
  * .md5/.sha1/.sha256/.sha512 are regenerated; signatures (.asc) and maven-metadata.xml are dropped.

Usage: reversion_artifact.py SRC_REPO DST_REPO GROUP ARTIFACT FROM_VERSION TO_VERSION
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

CHECKSUMS = ("md5", "sha1", "sha256", "sha512")


def fail(message: str) -> None:
    print(f"reversion_artifact: {message}", file=sys.stderr)
    sys.exit(1)


def write_checksums(path: Path) -> None:
    data = path.read_bytes()
    for algorithm in CHECKSUMS:
        Path(f"{path}.{algorithm}").write_text(hashlib.new(algorithm, data).hexdigest())


def rewrite_pom(text: str, artifact: str, old: str, new: str) -> str:
    own = re.compile(
        r"(<artifactId>" + re.escape(artifact) + r"</artifactId>\s*<version>)" + re.escape(old) + r"(</version>)")
    rewritten, count = own.subn(lambda m: m.group(1) + new + m.group(2), text)
    if count != 1:
        fail(f"expected exactly one own <version>{old}</version> in the {artifact} pom, found {count}")
    return rewritten


def rewrite_module(text: str, artifact: str, old: str, new: str) -> str:
    module = json.loads(text)
    renamed = 0
    for variant in module.get("variants", []):
        if "available-at" in variant:
            fail("unexpected available-at variant: this must be a platform module, not the root module")
        for entry in variant.get("files", []):
            for key in ("url", "name"):
                value = entry[key]
                if f"-{old}" in value:
                    entry[key] = value.replace(f"-{old}", f"-{new}", 1)
                    renamed += 1
            if not entry["url"].startswith(f"{artifact}-{new}"):
                fail(f"file url {entry['url']} was not re-versioned")
    if renamed == 0:
        fail("no file entries re-versioned in .module")
    return json.dumps(module, indent=2) + "\n"


def main(argv: list[str]) -> None:
    if len(argv) != 7:
        fail(__doc__.strip().splitlines()[-1])
    src_repo, dst_repo, group, artifact, old, new = Path(argv[1]), Path(argv[2]), argv[3], argv[4], argv[5], argv[6]
    rel = Path(*group.split("."), artifact)
    src = src_repo / rel / old
    dst = dst_repo / rel / new
    if not src.is_dir():
        fail(f"missing {src}")
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    prefix = f"{artifact}-{old}"
    copied = []
    for path in sorted(src.iterdir()):
        name = path.name
        if name.endswith((".asc",) + tuple(f".{c}" for c in CHECKSUMS)) or name.startswith("maven-metadata"):
            continue
        if not name.startswith(prefix) or name[len(prefix)] not in ".-":
            fail(f"unexpected file {name}")
        target = dst / (f"{artifact}-{new}" + name[len(prefix):])
        if name.endswith(".pom"):
            target.write_text(rewrite_pom(path.read_text(), artifact, old, new))
        elif name.endswith(".module"):
            target.write_text(rewrite_module(path.read_text(), artifact, old, new))
        else:
            shutil.copyfile(path, target)
        write_checksums(target)
        copied.append(target.name)
    for required in (".pom", ".module", ".klib"):
        if not any(n.endswith(required) for n in copied):
            fail(f"no {required} published for {artifact}")
    # The module's file hashes must still describe the copied files.
    module = json.loads((dst / f"{artifact}-{new}.module").read_text())
    for variant in module["variants"]:
        for entry in variant.get("files", []):
            data = (dst / entry["url"]).read_bytes()
            if len(data) != entry["size"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                fail(f"{entry['url']} does not match the size/sha256 recorded in the .module")
    print(f"{group}:{artifact}:{new} <- {old}: " + ", ".join(copied))


if __name__ == "__main__":
    main(sys.argv)
