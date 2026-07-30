#!/usr/bin/env python3
"""Validate the release-pinned OHOS Hands dependency and its HAR bytes."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import hmac
import io
import json
import re
import tarfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


HANDS_NAME = "@botiverse/hands"
LEGACY_SPEC = "file:./libs/hands.har"
LEGACY_LOCK_KEY = "@botiverse/hands@libs/hands.har"
REGISTRY_URL_TEMPLATE = (
    "https://ohpm.openharmony.cn/ohpm/@botiverse/hands/-/hands-{version}.har"
)
MAX_HAR_BYTES = 64 * 1024 * 1024
MAX_METADATA_BYTES = 1024 * 1024
SEMVER_RE = re.compile(
    r"^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$"
)
DEPENDENCY_LINE_RE = re.compile(
    r"^\s*(?P<key_quote>['\"])@botiverse/hands(?P=key_quote)\s*:\s*"
    r"(?P<value_quote>['\"])(?P<value>[^'\"]+)(?P=value_quote)\s*,?\s*"
    r"(?://.*)?$"
)


class ContractError(ValueError):
    """Raised when the checked-in dependency contract is incomplete or ambiguous."""


def _manifest_dependency(path: Path) -> str:
    matches = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = DEPENDENCY_LINE_RE.match(line)
        if match:
            matches.append(match.group("value"))
    if len(matches) != 1:
        raise ContractError(
            f"{path} must declare exactly one {HANDS_NAME} dependency; found {len(matches)}"
        )
    return matches[0]


def _load_lock(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"could not parse OHOS lockfile {path}: {error}") from error
    if not isinstance(payload, dict):
        raise ContractError(f"OHOS lockfile {path} must contain an object")
    return payload


def _mapping(payload: dict[str, Any], key: str) -> dict[str, Any]:
    value = payload.get(key)
    if not isinstance(value, dict):
        raise ContractError(f"OHOS lockfile field {key!r} must contain an object")
    return value


def _package_record(lock: dict[str, Any], key: str) -> dict[str, Any]:
    packages = _mapping(lock, "packages")
    record = packages.get(key)
    if not isinstance(record, dict):
        raise ContractError(f"OHOS lockfile is missing package record {key!r}")
    return record


def _require_single_hands_entry(mapping: dict[str, Any], key: str, field: str) -> None:
    candidates = sorted(candidate for candidate in mapping if candidate.startswith(f"{HANDS_NAME}@"))
    if candidates != [key]:
        raise ContractError(
            f"OHOS lockfile {field} must contain only {key!r} for Hands; found {candidates!r}"
        )


def _require_equal(record: dict[str, Any], field: str, expected: str, key: str) -> None:
    actual = record.get(field)
    if actual != expected:
        raise ContractError(
            f"OHOS lockfile {key}.{field} is {actual!r}; expected {expected!r}"
        )


def _archive_metadata(payload: bytes, source: str) -> tuple[str, str]:
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as archive:
            member = archive.getmember("package/oh-package.json5")
            if member.size > MAX_METADATA_BYTES:
                raise ContractError(
                    f"Hands package metadata in {source} exceeds {MAX_METADATA_BYTES} bytes"
                )
            extracted = archive.extractfile(member)
            if extracted is None:
                raise ContractError(f"{source} has no readable package/oh-package.json5")
            metadata_bytes = extracted.read(MAX_METADATA_BYTES + 1)
            if len(metadata_bytes) > MAX_METADATA_BYTES:
                raise ContractError(
                    f"Hands package metadata in {source} exceeds {MAX_METADATA_BYTES} bytes"
                )
            metadata = json.loads(metadata_bytes.decode("utf-8"))
    except ContractError:
        raise
    except (KeyError, tarfile.TarError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError(f"could not read Hands package metadata from {source}: {error}") from error

    if not isinstance(metadata, dict):
        raise ContractError(f"Hands package metadata in {source} must contain an object")
    name = metadata.get("name")
    version = metadata.get("version")
    if not isinstance(name, str) or not isinstance(version, str):
        raise ContractError(f"Hands package metadata in {source} lacks string name/version")
    return name, version


def _read_bounded(response: Any, source: str) -> bytes:
    length = response.headers.get("Content-Length")
    if length and length.isdigit() and int(length) > MAX_HAR_BYTES:
        raise ContractError(f"Hands registry HAR is too large: {length} bytes from {source}")
    payload = response.read(MAX_HAR_BYTES + 1)
    if len(payload) > MAX_HAR_BYTES:
        raise ContractError(f"Hands registry HAR exceeds {MAX_HAR_BYTES} bytes: {source}")
    return payload


def fetch_registry_har(source: str) -> bytes:
    request = urllib.request.Request(
        source,
        headers={"User-Agent": "raft-mobile-release-preflight/1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return _read_bounded(response, source)
    except (OSError, urllib.error.URLError) as error:
        raise ContractError(f"could not download pinned Hands registry HAR {source}: {error}") from error


def read_har(path: Path) -> bytes:
    size = path.stat().st_size
    if size > MAX_HAR_BYTES:
        raise ContractError(f"Hands HAR is too large: {size} bytes at {path}")
    return path.read_bytes()


def _verify_registry_artifact(
    payload: bytes,
    source: str,
    integrity_digest: bytes,
    version: str,
) -> str:
    actual_digest = hashlib.sha512(payload).digest()
    if not hmac.compare_digest(actual_digest, integrity_digest):
        raise ContractError(f"Hands registry HAR sha512 does not match lock integrity: {source}")
    name, archive_version = _archive_metadata(payload, source)
    if name != HANDS_NAME or archive_version != version:
        raise ContractError(
            f"Hands registry HAR metadata is {name}@{archive_version}; "
            f"expected {HANDS_NAME}@{version}"
        )
    return hashlib.sha256(payload).hexdigest()


def validate_dependency(
    manifest_path: Path,
    lock_path: Path,
    *,
    legacy_har_path: Path | None = None,
    registry_payload: bytes | None = None,
    fetch_registry: bool = False,
) -> dict[str, str]:
    dependency = _manifest_dependency(manifest_path)
    lock = _load_lock(lock_path)
    specifiers = _mapping(lock, "specifiers")

    if dependency == LEGACY_SPEC:
        key = LEGACY_LOCK_KEY
        _require_single_hands_entry(specifiers, key, "specifiers")
        if specifiers.get(key) != key:
            raise ContractError(f"OHOS lockfile specifier {key!r} is missing or mismatched")
        _require_single_hands_entry(_mapping(lock, "packages"), key, "packages")
        record = _package_record(lock, key)
        _require_equal(record, "name", HANDS_NAME, key)
        _require_equal(record, "resolved", "libs/hands.har", key)
        _require_equal(record, "registryType", "local", key)
        version = record.get("version")
        if not isinstance(version, str) or not version:
            raise ContractError(f"OHOS lockfile {key}.version must be a non-empty string")
        if legacy_har_path is None or not legacy_har_path.is_file():
            raise ContractError("legacy OHOS Hands dependency requires readable libs/hands.har")
        payload = read_har(legacy_har_path)
        name, archive_version = _archive_metadata(payload, str(legacy_har_path))
        if name != HANDS_NAME or archive_version != version:
            raise ContractError(
                f"legacy Hands HAR metadata is {name}@{archive_version}; "
                f"expected {HANDS_NAME}@{version}"
            )
        return {
            "mode": "legacy-local",
            "name": HANDS_NAME,
            "version": version,
            "source": str(legacy_har_path),
            "artifact_sha256": hashlib.sha256(payload).hexdigest(),
        }

    if not SEMVER_RE.fullmatch(dependency):
        raise ContractError(
            f"{HANDS_NAME} must use an exact registry version or {LEGACY_SPEC}; got {dependency!r}"
        )

    version = dependency
    key = f"{HANDS_NAME}@{version}"
    _require_single_hands_entry(specifiers, key, "specifiers")
    if specifiers.get(key) != key:
        raise ContractError(f"OHOS lockfile specifier {key!r} is missing or mismatched")
    _require_single_hands_entry(_mapping(lock, "packages"), key, "packages")
    record = _package_record(lock, key)
    _require_equal(record, "name", HANDS_NAME, key)
    _require_equal(record, "version", version, key)
    _require_equal(record, "registryType", "ohpm", key)
    source = REGISTRY_URL_TEMPLATE.format(version=version)
    _require_equal(record, "resolved", source, key)

    integrity = record.get("integrity")
    if not isinstance(integrity, str) or not integrity.startswith("sha512-"):
        raise ContractError(f"OHOS lockfile {key}.integrity must be a sha512 SRI value")
    try:
        integrity_digest = base64.b64decode(integrity.removeprefix("sha512-"), validate=True)
    except (binascii.Error, ValueError) as error:
        raise ContractError(f"OHOS lockfile {key}.integrity is not valid base64") from error
    if len(integrity_digest) != hashlib.sha512().digest_size:
        raise ContractError(f"OHOS lockfile {key}.integrity is not a complete sha512 digest")

    if registry_payload is not None and fetch_registry:
        raise ContractError("provide registry bytes or fetch them, not both")
    payload = fetch_registry_har(source) if fetch_registry else registry_payload
    if payload is None:
        raise ContractError("registry dependency verification requires the pinned HAR bytes")
    artifact_sha256 = _verify_registry_artifact(
        payload,
        source,
        integrity_digest,
        version,
    )
    return {
        "mode": "ohpm-registry",
        "name": HANDS_NAME,
        "version": version,
        "source": source,
        "artifact_sha256": artifact_sha256,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--legacy-har", type=Path)
    parser.add_argument("--registry-artifact", type=Path)
    parser.add_argument("--fetch-registry", action="store_true")
    args = parser.parse_args()

    if args.registry_artifact and args.fetch_registry:
        parser.error("--registry-artifact and --fetch-registry are mutually exclusive")
    try:
        registry_payload = (
            read_har(args.registry_artifact) if args.registry_artifact is not None else None
        )
        result = validate_dependency(
            args.manifest,
            args.lock,
            legacy_har_path=args.legacy_har,
            registry_payload=registry_payload,
            fetch_registry=args.fetch_registry,
        )
    except (ContractError, OSError) as error:
        parser.exit(1, f"error: {error}\n")
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
