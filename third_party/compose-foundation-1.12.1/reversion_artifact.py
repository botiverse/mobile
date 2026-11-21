#!/usr/bin/env python3
"""Stage the patched AAR as a new version of the official Maven artifact.

Like third_party/compose-ui-1.12.2/reversion_artifact.py, except that the main file's bytes change
here, so its size and hashes in the .module change too. OFFICIAL_DIR holds the official release
files the workflow downloaded and checked (the .pom, the .module and every file the .module lists);
PATCHED_AAR comes from repack_aar.py. Written to DST_REPO/<group path>/<artifact>/<to>/:

  * `<artifact>-<to>.aar`: the patched AAR;
  * every other file the .module lists (the sources and samples-sources jars), copied unchanged
    under the new version's file name; each must match the size and hashes the official .module
    records for it;
  * the .pom with only its own <version> changed (dependency versions are untouched);
  * the .module with only the files' `url`/`name` re-versioned and the .aar's size and hashes
    replaced. Variants, attributes, dependencies, constraints and the `component` block (the
    official root module; Gradle reads only its attributes when it consumes this module directly)
    stay as they are, in Gradle's JSON layout;
  * .md5/.sha1/.sha256/.sha512 for each of them. No signatures, no maven-metadata.xml.

The result is then checked against the official files: the .pom differs in one line (its own
version), the .module only in those file fields, and every listed file is staged with the recorded
size and hashes.

Usage: reversion_artifact.py OFFICIAL_DIR PATCHED_AAR DST_REPO GROUP ARTIFACT FROM_VERSION TO_VERSION
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
HASH_KEYS = ("size", "sha512", "sha256", "sha1", "md5")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fail(message: str) -> None:
    print(f"reversion_artifact: {message}", file=sys.stderr)
    sys.exit(1)


def digests(data: bytes) -> dict:
    return {"size": len(data), **{key: hashlib.new(key, data).hexdigest() for key in HASH_KEYS[1:]}}


def write(path: Path, data: bytes) -> None:
    path.write_bytes(data)
    for algorithm in CHECKSUMS:
        Path(f"{path}.{algorithm}").write_text(hashlib.new(algorithm, data).hexdigest())


def reversion(value: str, old: str, new: str) -> str:
    return value.replace(f"-{old}", f"-{new}", 1)


def rewrite_pom(text: str, artifact: str, old: str, new: str) -> str:
    own = re.compile(
        r"(<artifactId>" + re.escape(artifact) + r"</artifactId>\s*<version>)" + re.escape(old) + r"(</version>)")
    rewritten, count = own.subn(lambda m: m.group(1) + new + m.group(2), text)
    if count != 1:
        fail(f"expected exactly one own <version>{old}</version> in the {artifact} pom, found {count}")
    return rewritten


def module_files(module: dict) -> list[dict]:
    return [entry for variant in module.get("variants", []) for entry in variant.get("files", [])]


def stage(official_dir: Path, patched: bytes, out: Path, artifact: str, old: str, new: str) -> None:
    src, dst = f"{artifact}-{old}", f"{artifact}-{new}"
    text = (official_dir / f"{src}.module").read_bytes().decode("utf-8")
    module = json.loads(text)
    if json.dumps(module, indent=2) != text:
        fail("the official .module is not in Gradle's JSON layout; writing it back would change more than its file fields")
    files: dict[str, bytes] = {}
    for variant in module.get("variants", []):
        if "available-at" in variant:
            fail("unexpected available-at variant: this must be the platform module, not the root module")
        for entry in variant.get("files", []):
            url = entry["url"]
            if not url.startswith(src) or url[len(src)] not in ".-":
                fail(f"unexpected file url {url}")
            data = (official_dir / url).read_bytes()
            if digests(data) != {key: entry[key] for key in HASH_KEYS}:
                fail(f"{url} does not match the size and hashes in the official .module")
            if url == f"{src}.aar":
                data = patched
                entry.update(digests(patched))
            for key in ("url", "name"):
                entry[key] = reversion(entry[key], old, new)
            if not entry["url"].startswith(dst):
                fail(f"file url {entry['url']} was not re-versioned")
            files[entry["url"]] = data
    if f"{dst}.aar" not in files:
        fail(f"the .module lists no {src}.aar")
    for name, data in files.items():
        write(out / name, data)
    pom = rewrite_pom((official_dir / f"{src}.pom").read_bytes().decode("utf-8"), artifact, old, new)
    write(out / f"{dst}.pom", pom.encode("utf-8"))
    write(out / f"{dst}.module", json.dumps(module, indent=2).encode("utf-8"))


def check(official_dir: Path, patched: bytes, out: Path, group: str, artifact: str, old: str, new: str) -> None:
    src, dst = f"{artifact}-{old}", f"{artifact}-{new}"
    official_pom = (official_dir / f"{src}.pom").read_bytes().decode("utf-8").split("\n")
    staged_pom = (out / f"{dst}.pom").read_bytes().decode("utf-8").split("\n")
    changed = [(a, b) for a, b in zip(official_pom, staged_pom) if a != b]
    if len(official_pom) != len(staged_pom) or len(changed) != 1 \
            or changed[0][0].strip() != f"<version>{old}</version>" or changed[0][1] != changed[0][0].replace(old, new):
        fail(f".pom differs from the official one in more than its own <version>: {changed[:3]}")
    root = ET.fromstring("\n".join(staged_pom))
    own = tuple(root.findtext(f"m:{key}", "", NS) for key in ("groupId", "artifactId", "version"))
    if own != (group, artifact, new):
        fail(f".pom coordinates are {own}, expected {(group, artifact, new)}")

    official_module = json.loads((official_dir / f"{src}.module").read_bytes())
    staged_text = (out / f"{dst}.module").read_bytes().decode("utf-8")
    staged_module = json.loads(staged_text)

    def without_file_fields(module: dict) -> dict:
        module = copy.deepcopy(module)
        for entry in module_files(module):
            for key in ("name", "url") + HASH_KEYS:
                entry.pop(key, None)
        return module

    if without_file_fields(official_module) != without_file_fields(staged_module) \
            or json.dumps(staged_module, indent=2) != staged_text:
        fail(".module differs from the official one outside the files' names, urls, sizes and hashes")
    expected_names = {f"{dst}.pom", f"{dst}.module"}
    for before, after in zip(module_files(official_module), module_files(staged_module)):
        for key in ("url", "name"):
            if after[key] != reversion(before[key], old, new):
                fail(f".module file {key} {after[key]!r}, expected {reversion(before[key], old, new)!r}")
        data = (out / after["url"]).read_bytes()
        if digests(data) != {key: after[key] for key in HASH_KEYS}:
            fail(f"{after['url']} does not match the size and hashes in the staged .module")
        source = patched if before["url"] == f"{src}.aar" else (official_dir / before["url"]).read_bytes()
        if data != source:
            fail(f"{after['url']} is not {'the patched AAR' if source is patched else 'the official ' + before['url']}")
        if before["url"] != f"{src}.aar" and {key: before[key] for key in HASH_KEYS} != {key: after[key] for key in HASH_KEYS}:
            fail(f"{after['url']}: size or hashes changed in the .module, but the file did not")
        expected_names.add(after["url"])
    expected_names |= {f"{name}.{algorithm}" for name in list(expected_names) for algorithm in CHECKSUMS}
    actual_names = {path.name for path in out.iterdir()}
    if actual_names != expected_names:
        fail(f"staged files differ from what the .module lists: extra {sorted(actual_names - expected_names)}, "
             f"missing {sorted(expected_names - actual_names)}")
    for name in expected_names:
        if name.rsplit(".", 1)[-1] in CHECKSUMS:
            base, algorithm = name.rsplit(".", 1)
            if (out / name).read_text() != hashlib.new(algorithm, (out / base).read_bytes()).hexdigest():
                fail(f"{name} is not the {algorithm} of {base}")


def main(argv: list[str]) -> None:
    if len(argv) != 8:
        fail(__doc__.strip().splitlines()[-1])
    official_dir, patched_aar, dst_repo = Path(argv[1]), Path(argv[2]), Path(argv[3])
    group, artifact, old, new = argv[4:8]
    patched = patched_aar.read_bytes()
    if patched == (official_dir / f"{artifact}-{old}.aar").read_bytes():
        fail(f"{patched_aar} is the official AAR")
    out = dst_repo / Path(*group.split(".")) / artifact / new
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    stage(official_dir, patched, out, artifact, old, new)
    check(official_dir, patched, out, group, artifact, old, new)
    print(f"{group}:{artifact}:{new} <- {old} (the .aar patched, everything else as published):")
    for path in sorted(out.iterdir()):
        if path.name.rsplit(".", 1)[-1] not in CHECKSUMS:
            data = path.read_bytes()
            print(f"  {len(data):>9}  sha256 {hashlib.sha256(data).hexdigest()}  {path.name}")


if __name__ == "__main__":
    main(sys.argv)
