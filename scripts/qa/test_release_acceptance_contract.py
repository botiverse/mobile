from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from release_acceptance_contract import ContractError, DEFAULT_CASE_DIR, load_contract


class ReleaseAcceptanceContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.case_dir = Path(self.temp.name) / "cases"
        shutil.copytree(DEFAULT_CASE_DIR, self.case_dir)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_generated_plan_contains_each_automated_case_once_and_excludes_server_create(self) -> None:
        plan = load_contract(self.case_dir).plan()
        self.assertEqual(25, len(plan["case_ids"]))
        self.assertEqual(25, len(set(plan["case_ids"])))
        self.assertNotIn("server-create", plan["case_ids"])
        self.assertEqual(["server-create"], plan["excluded_manual_only_case_ids"])

    def test_duplicate_auth_fixture_is_rejected_before_last_value_can_win(self) -> None:
        path = self.case_dir / "case-01-login-email-password.yaml"
        path.write_text(path.read_text(encoding="utf-8") + "\nauth_fixture: none\n", encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "duplicate YAML key 'auth_fixture'"):
            load_contract(self.case_dir)

    def test_unrelated_scheduled_false_cannot_mask_true_execution_policy(self) -> None:
        path = self.case_dir / "19-server-create.yaml"
        source = path.read_text(encoding="utf-8").replace("  scheduled: false", "  scheduled: true", 1)
        source += "\ndecoy:\n  scheduled: false\n"
        path.write_text(source, encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "execution_policy.scheduled must be false"):
            load_contract(self.case_dir)

    def test_android_only_source_cannot_replace_unified_case_contract(self) -> None:
        path = self.case_dir / "case-01-login-email-password.yaml"
        source = path.read_text(encoding="utf-8")
        android_only = source[: source.index("ios:\n")]
        path.write_text(android_only, encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "must preserve the unified Android/iOS contract"):
            load_contract(self.case_dir)

    def test_duplicate_manifest_key_is_rejected(self) -> None:
        path = self.case_dir / "release-1.7.0-android.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        duplicate = path.read_text(encoding="utf-8").replace(
            '"schema_version": 1,',
            f'"schema_version": 1,\n  "schema_version": {value["schema_version"]},',
            1,
        )
        path.write_text(duplicate, encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "duplicate JSON key 'schema_version'"):
            load_contract(self.case_dir)


if __name__ == "__main__":
    unittest.main()
