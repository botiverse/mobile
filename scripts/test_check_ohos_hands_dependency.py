#!/usr/bin/env python3
"""Contract tests for the OHOS Hands release dependency verifier."""

from __future__ import annotations

import base64
import hashlib
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

from check_ohos_hands_dependency import (
    ContractError,
    HANDS_NAME,
    LEGACY_LOCK_KEY,
    LEGACY_SPEC,
    REGISTRY_URL_TEMPLATE,
    validate_dependency,
)


VERSION = "0.3.3"


def make_har(name: str = HANDS_NAME, version: str = VERSION) -> bytes:
    metadata = json.dumps({"name": name, "version": version}).encode()
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        member = tarfile.TarInfo("package/oh-package.json5")
        member.size = len(metadata)
        archive.addfile(member, io.BytesIO(metadata))
    return output.getvalue()


def registry_lock(payload: bytes) -> dict[str, object]:
    key = f"{HANDS_NAME}@{VERSION}"
    return {
        "lockfileVersion": 3,
        "specifiers": {key: key},
        "packages": {
            key: {
                "name": HANDS_NAME,
                "version": VERSION,
                "integrity": "sha512-"
                + base64.b64encode(hashlib.sha512(payload).digest()).decode(),
                "resolved": REGISTRY_URL_TEMPLATE.format(version=VERSION),
                "registryType": "ohpm",
            }
        },
    }


def legacy_lock() -> dict[str, object]:
    return {
        "lockfileVersion": 3,
        "specifiers": {LEGACY_LOCK_KEY: LEGACY_LOCK_KEY},
        "packages": {
            LEGACY_LOCK_KEY: {
                "name": HANDS_NAME,
                "version": VERSION,
                "resolved": "libs/hands.har",
                "registryType": "local",
            }
        },
    }


class HandsDependencyContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.manifest = self.root / "oh-package.json5"
        self.lock = self.root / "oh-package-lock.json5"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_manifest(self, dependency: str, *, duplicate: bool = False) -> None:
        duplicate_line = (
            f'    "{HANDS_NAME}": "{dependency}",\n' if duplicate else ""
        )
        self.manifest.write_text(
            "{\n  dependencies: {\n"
            f'    "{HANDS_NAME}": "{dependency}",\n'
            f"{duplicate_line}"
            "    '@kuikly-open/render': 'file:./libs/render.har'\n"
            "  }\n}\n",
            encoding="utf-8",
        )

    def write_lock(self, payload: dict[str, object]) -> None:
        self.lock.write_text(json.dumps(payload), encoding="utf-8")

    def test_registry_dependency_verifies_lock_integrity_and_archive(self) -> None:
        payload = make_har()
        self.write_manifest(VERSION)
        self.write_lock(registry_lock(payload))

        result = validate_dependency(
            self.manifest,
            self.lock,
            registry_payload=payload,
        )

        self.assertEqual(result["mode"], "ohpm-registry")
        self.assertEqual(result["version"], VERSION)
        self.assertEqual(result["artifact_sha256"], hashlib.sha256(payload).hexdigest())

    def test_registry_dependency_rejects_ranges(self) -> None:
        payload = make_har()
        self.write_manifest("^0.3.3")
        self.write_lock(registry_lock(payload))

        with self.assertRaisesRegex(ContractError, "exact registry version"):
            validate_dependency(self.manifest, self.lock, registry_payload=payload)

    def test_registry_dependency_rejects_missing_integrity(self) -> None:
        payload = make_har()
        lock = registry_lock(payload)
        lock["packages"][f"{HANDS_NAME}@{VERSION}"].pop("integrity")  # type: ignore[index,union-attr]
        self.write_manifest(VERSION)
        self.write_lock(lock)

        with self.assertRaisesRegex(ContractError, "sha512 SRI"):
            validate_dependency(self.manifest, self.lock, registry_payload=payload)

    def test_registry_dependency_rejects_wrong_resolved_url(self) -> None:
        payload = make_har()
        lock = registry_lock(payload)
        lock["packages"][f"{HANDS_NAME}@{VERSION}"]["resolved"] = (  # type: ignore[index]
            "https://example.invalid/hands.har"
        )
        self.write_manifest(VERSION)
        self.write_lock(lock)

        with self.assertRaisesRegex(ContractError, "resolved"):
            validate_dependency(self.manifest, self.lock, registry_payload=payload)

    def test_registry_dependency_rejects_tampered_bytes(self) -> None:
        payload = make_har()
        self.write_manifest(VERSION)
        self.write_lock(registry_lock(payload))

        with self.assertRaisesRegex(ContractError, "sha512"):
            validate_dependency(
                self.manifest,
                self.lock,
                registry_payload=payload + b"tampered",
            )

    def test_registry_dependency_rejects_ambiguous_hands_lock_records(self) -> None:
        payload = make_har()
        lock = registry_lock(payload)
        stale_key = f"{HANDS_NAME}@0.3.2"
        lock["specifiers"][stale_key] = stale_key  # type: ignore[index]
        lock["packages"][stale_key] = {  # type: ignore[index]
            "name": HANDS_NAME,
            "version": "0.3.2",
            "integrity": "sha512-"
            + base64.b64encode(hashlib.sha512(payload).digest()).decode(),
            "resolved": REGISTRY_URL_TEMPLATE.format(version="0.3.2"),
            "registryType": "ohpm",
        }
        self.write_manifest(VERSION)
        self.write_lock(lock)

        with self.assertRaisesRegex(ContractError, "must contain only"):
            validate_dependency(self.manifest, self.lock, registry_payload=payload)

    def test_registry_dependency_rejects_wrong_archive_metadata(self) -> None:
        payload = make_har(version="0.3.2")
        self.write_manifest(VERSION)
        self.write_lock(registry_lock(payload))

        with self.assertRaisesRegex(ContractError, "metadata"):
            validate_dependency(self.manifest, self.lock, registry_payload=payload)

    def test_legacy_local_har_remains_supported(self) -> None:
        payload = make_har()
        har_path = self.root / "hands.har"
        har_path.write_bytes(payload)
        self.write_manifest(LEGACY_SPEC)
        self.write_lock(legacy_lock())

        result = validate_dependency(
            self.manifest,
            self.lock,
            legacy_har_path=har_path,
        )

        self.assertEqual(result["mode"], "legacy-local")
        self.assertEqual(result["version"], VERSION)

    def test_legacy_local_har_rejects_missing_artifact(self) -> None:
        self.write_manifest(LEGACY_SPEC)
        self.write_lock(legacy_lock())

        with self.assertRaisesRegex(ContractError, "requires readable"):
            validate_dependency(
                self.manifest,
                self.lock,
                legacy_har_path=self.root / "missing.har",
            )

    def test_duplicate_manifest_dependency_is_rejected(self) -> None:
        payload = make_har()
        self.write_manifest(VERSION, duplicate=True)
        self.write_lock(registry_lock(payload))

        with self.assertRaisesRegex(ContractError, "exactly one"):
            validate_dependency(self.manifest, self.lock, registry_payload=payload)


if __name__ == "__main__":
    unittest.main()
