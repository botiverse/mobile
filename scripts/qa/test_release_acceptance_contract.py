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

    def test_login_case_must_name_the_qa_server(self) -> None:
        # Presence half: deleting OranixSlockTest from the login case would
        # leave the server picker to the model — the checker must go red on
        # the deletion, not just on a wrong addition.
        path = self.case_dir / "case-01-login-email-password.yaml"
        path.write_text(
            path.read_text(encoding="utf-8").replace("OranixSlockTest", "the test server"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ContractError, "must name 'OranixSlockTest'"):
            load_contract(self.case_dir)

    def test_server_switch_case_must_name_both_qa_servers(self) -> None:
        # The one case whose subject IS server selection: stripping either
        # exact label reverts it to abstract A/B goals, which the runtime
        # guard answers with verdict error by design (task #171).
        path = self.case_dir / "26-server-switch.yaml"
        path.write_text(
            path.read_text(encoding="utf-8").replace("qa-server-b-slock", "server B"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ContractError, "must name 'qa-server-b-slock'"):
            load_contract(self.case_dir)

    def test_selection_goal_without_named_server_is_rejected_anywhere(self) -> None:
        # Structural tooth positive control: a case not otherwise pinned gains
        # a selection-driving goal with no named server — the literal pre-fix
        # 26-server-switch shape ("Tap server B exactly once") on a file that
        # never names a QA server. This is the axis the absence/presence teeth
        # don't cover: guarding FUTURE cases, not the known three.
        path = self.case_dir / "07-thread-reply.yaml"
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nextra_steps:\n  - goal: \"Tap server B exactly once.\"\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ContractError, "drives server selection but the case names no QA server"):
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
