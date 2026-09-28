#!/usr/bin/env python3
"""Fetch one artifact named by the immutable Kuikly release manifest."""

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path

import kuikly_release_manifest_gate as manifest_gate


DEFAULT_RELEASE = "2.24.0-raft.6.1"
DEFAULT_REGISTRY = "https://maven.artifacts.botiverse.dev"


def load_closure(path: Path) -> list:
    with path.open("r", encoding="utf-8") as handle:
        closure = json.load(handle)
    if not isinstance(closure, list) or not closure:
        raise ValueError("closure must be a non-empty JSON list")
    return closure


def fetch(args: argparse.Namespace) -> None:
    closure = load_closure(args.closure)
    registry = manifest_gate.Registry(args.registry_base)
    locator = manifest_gate.locator_path(args.release)
    found, payload, status = registry.get_bytes(locator)
    if not found:
        raise RuntimeError(f"Kuikly release manifest unavailable: {locator} (HTTP {status})")

    manifest = json.loads(payload.decode("utf-8"))
    errors = manifest_gate.validate_manifest(manifest, args.release, closure)
    if errors:
        raise RuntimeError("invalid Kuikly release manifest: " + "; ".join(errors))

    matches = [
        publication
        for publication in manifest["publications"]
        if publication.get("coordinate") == args.coordinate
        and publication.get("kind") == args.kind
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one {args.kind} for {args.coordinate}, found {len(matches)}"
        )

    publication = matches[0]
    found, data, status = registry.get_bytes(publication["path"])
    if not found:
        raise RuntimeError(f"manifest artifact unavailable: {publication['path']} (HTTP {status})")
    actual = hashlib.sha256(data).hexdigest()
    if len(data) != publication["size"] or actual != publication["sha256"]:
        raise RuntimeError(
            f"manifest artifact byte mismatch: {publication['path']} "
            f"bytes={len(data)}/{publication['size']} sha256={actual}/{publication['sha256']}"
        )

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = None
    temporary = None
    try:
        descriptor, temporary = tempfile.mkstemp(prefix=output.name + ".", dir=str(output.parent))
        with os.fdopen(descriptor, "wb") as handle:
            descriptor = None
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, output)
        temporary = None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None and os.path.exists(temporary):
            os.unlink(temporary)

    print(
        f"Kuikly manifest artifact ready: coordinate={args.coordinate} kind={args.kind} "
        f"bytes={len(data)} sha256={actual} output={output}"
    )


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", default=DEFAULT_RELEASE)
    parser.add_argument(
        "--registry-base",
        default=os.environ.get("RAFT_ARTIFACTS_MAVEN_URL", DEFAULT_REGISTRY),
    )
    parser.add_argument(
        "--closure",
        type=Path,
        default=repo_root / "compose/gradle/kuikly-release-closure.json",
    )
    parser.add_argument("--coordinate", required=True)
    parser.add_argument("--kind", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        fetch(args)
    except Exception as error:
        print(f"Kuikly manifest artifact fetch failed: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
