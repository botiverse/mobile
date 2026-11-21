#!/usr/bin/env python3
"""Check a re-versioned platform artifact against the official release it replaces.

Fails unless, compared to the official files:
  * the .module has the same component reference, variants, attributes, capabilities,
    dependencies and dependency constraints (only file names/sizes/hashes may differ);
  * the .pom has the same group, packaging, dependencies and dependencyManagement, and its own
    version is the new one;
  * the klib manifest (default/manifest) has the same compiler_version, abi_version,
    metadata_version, IR signature versions, unique_name, targets and dependency list.

Usage: compare_with_official.py STAGED_DIR OFFICIAL_DIR ARTIFACT OFFICIAL_VERSION NEW_VERSION
  STAGED_DIR    holds <artifact>-<new>.{module,pom,klib}
  OFFICIAL_DIR  holds <artifact>-<official>.{module,pom,klib} downloaded from Maven Central
"""
from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MANIFEST_KEYS = ("compiler_version", "abi_version", "metadata_version", "ir_signature_versions",
                 "unique_name", "short_name", "native_targets", "builtins_platform", "language_features")
errors: list[str] = []


def check(label: str, ours, official) -> None:
    status = "ok  " if ours == official else "DIFF"
    if ours != official:
        errors.append(label)
    shown = ours if isinstance(ours, str) else json.dumps(ours, sort_keys=True)[:200]
    print(f"[{status}] {label}: {shown}")
    if ours != official:
        print(f"       official: {official if isinstance(official, str) else json.dumps(official, sort_keys=True)[:2000]}")
        if not isinstance(ours, str):
            print(f"       ours:     {json.dumps(ours, sort_keys=True)[:2000]}")


def module_view(path: Path) -> dict:
    module = json.loads(path.read_text())
    variants = {}
    for variant in module["variants"]:
        variants[variant["name"]] = {
            "attributes": variant.get("attributes"),
            "capabilities": variant.get("capabilities"),
            "dependencies": sorted(json.dumps(d, sort_keys=True) for d in variant.get("dependencies", [])),
            "dependencyConstraints": sorted(json.dumps(d, sort_keys=True) for d in variant.get("dependencyConstraints", [])),
            "files": len(variant.get("files", [])),
        }
    return {"formatVersion": module["formatVersion"], "component": module["component"], "variants": variants}


def pom_view(path: Path) -> dict:
    root = ET.parse(path).getroot()

    def deps(xpath: str) -> list[str]:
        out = []
        for dep in root.findall(xpath, NS):
            out.append("|".join((dep.findtext(f"m:{k}", "", NS) or "").strip()
                                for k in ("groupId", "artifactId", "version", "scope", "type", "classifier")))
        return sorted(out)

    return {
        "groupId": root.findtext("m:groupId", "", NS),
        "artifactId": root.findtext("m:artifactId", "", NS),
        "version": root.findtext("m:version", "", NS),
        "packaging": root.findtext("m:packaging", "", NS),
        "dependencies": deps("m:dependencies/m:dependency"),
        "dependencyManagement": deps("m:dependencyManagement/m:dependencies/m:dependency"),
    }


def manifest(path: Path) -> dict:
    with zipfile.ZipFile(path) as klib:
        text = klib.read("default/manifest").decode()
    out = {}
    for line in text.splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip().replace("\\:", ":")
    return out


def main(argv: list[str]) -> None:
    if len(argv) != 6:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    staged, official, artifact, old, new = Path(argv[1]), Path(argv[2]), argv[3], argv[4], argv[5]
    ours_name, official_name = f"{artifact}-{new}", f"{artifact}-{old}"
    print(f"== {artifact}: {new} (staged) vs {old} (official)")

    ours_module, official_module = module_view(staged / f"{ours_name}.module"), module_view(official / f"{official_name}.module")
    check("module formatVersion", ours_module["formatVersion"], official_module["formatVersion"])
    check("module component", ours_module["component"], official_module["component"])
    check("module variant names", sorted(ours_module["variants"]), sorted(official_module["variants"]))
    for name in sorted(set(ours_module["variants"]) & set(official_module["variants"])):
        for key in ("attributes", "capabilities", "dependencies", "dependencyConstraints", "files"):
            check(f"module {name} {key}", ours_module["variants"][name][key], official_module["variants"][name][key])

    ours_pom, official_pom = pom_view(staged / f"{ours_name}.pom"), pom_view(official / f"{official_name}.pom")
    check("pom own version", ours_pom["version"], new)
    for key in ("groupId", "artifactId", "packaging", "dependencies", "dependencyManagement"):
        check(f"pom {key}", ours_pom[key], official_pom[key])

    ours_manifest, official_manifest = manifest(staged / f"{ours_name}.klib"), manifest(official / f"{official_name}.klib")
    for key in MANIFEST_KEYS:
        check(f"klib manifest {key}", ours_manifest.get(key), official_manifest.get(key))
    check("klib manifest depends (set)", sorted(ours_manifest.get("depends", "").split()),
          sorted(official_manifest.get("depends", "").split()))

    if errors:
        print(f"FAIL {artifact}: {len(errors)} difference(s) from official {old}: " + "; ".join(errors), file=sys.stderr)
        sys.exit(1)
    print(f"OK {artifact}: matches official {old} apart from its own version")


if __name__ == "__main__":
    main(sys.argv)
