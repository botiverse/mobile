#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("gitlink_carry_in_gate.py")
SPEC = importlib.util.spec_from_file_location("gitlink_carry_in_gate", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
GATE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = GATE
SPEC.loader.exec_module(GATE)


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


def write(repo: Path, relative: str, content: str) -> None:
    target = repo / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def commit_all(repo: Path, subject: str) -> str:
    git(repo, "add", "-A")
    git(repo, "commit", "-m", subject)
    return git(repo, "rev-parse", "HEAD")


def commit_index(repo: Path, subject: str) -> str:
    git(repo, "commit", "-m", subject)
    return git(repo, "rev-parse", "HEAD")


class GitlinkCarryInGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory(prefix="gitlink-gate-test-")
        self.root = Path(self.temp_dir.name)
        self.submodule = self.root / "submodule"
        self.mobile = self.root / "mobile"
        self._init_repo(self.submodule)
        self._init_repo(self.mobile)
        self._create_submodule_history()
        self._create_mobile_history()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    @staticmethod
    def _init_repo(repo: Path) -> None:
        repo.mkdir()
        git(repo, "init", "-b", "main")
        git(repo, "config", "user.name", "Fixture")
        git(repo, "config", "user.email", "fixture@example.com")

    def _create_submodule_history(self) -> None:
        write(self.submodule, "reverted.txt", "base\n")
        write(self.submodule, "stable.txt", "stable\n")
        self.old_pin = commit_all(self.submodule, "base")

        write(self.submodule, "reverted.txt", "temporary\n")
        write(self.submodule, "carried.txt", "first\n")
        write(self.submodule, "nested/added.txt", "added\n")
        self.middle_pin = commit_all(self.submodule, "touch reverted and carried files")

        write(self.submodule, "reverted.txt", "base\n")
        write(self.submodule, "carried.txt", "final\n")
        self.new_pin = commit_all(self.submodule, "restore one file and retain carry-in")

        git(self.submodule, "checkout", "--orphan", "unrelated")
        git(self.submodule, "rm", "-rf", ".")
        write(self.submodule, "unrelated.txt", "unrelated\n")
        self.unrelated_pin = commit_all(self.submodule, "unrelated history")

    def _create_mobile_history(self) -> None:
        write(
            self.mobile,
            ".gitmodules",
            "\n".join(
                [
                    '[submodule "fixture"]',
                    "\tpath = vendor/fixture",
                    f"\turl = {self.submodule}",
                    "",
                ]
            ),
        )
        write(self.mobile, "README.md", "base\n")
        git(self.mobile, "add", ".gitmodules", "README.md")
        git(
            self.mobile,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{self.old_pin},vendor/fixture",
        )
        self.base = commit_index(self.mobile, "pin fixture base")

        write(self.mobile, "README.md", "docs only\n")
        git(self.mobile, "add", "README.md")
        self.no_change_head = commit_index(self.mobile, "docs only")

        git(self.mobile, "checkout", "-b", "carry", self.base)
        git(
            self.mobile,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{self.new_pin},vendor/fixture",
        )
        self.carry_head = commit_index(self.mobile, "advance fixture pin")

        git(self.mobile, "checkout", "-b", "unrelated-pin", self.base)
        git(
            self.mobile,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{self.unrelated_pin},vendor/fixture",
        )
        self.unrelated_head = commit_index(self.mobile, "pin unrelated history")

        git(self.mobile, "checkout", "-b", "remove", self.base)
        git(self.mobile, "rm", "--cached", "vendor/fixture")
        write(self.mobile, ".gitmodules", "")
        self.removed_head = commit_all(self.mobile, "remove fixture gitlink")

    def audit(self, head: str):
        return GATE.audit(
            self.mobile,
            self.base,
            head,
            allow_local_remotes=True,
        )

    def test_arm_zero_unchanged_gitlink_does_not_report_carry_in(self) -> None:
        manifest = self.audit(self.no_change_head)
        self.assertEqual("no_changes", manifest["status"])
        self.assertEqual([], manifest["submodules"])
        markdown = GATE.render_markdown(manifest)
        self.assertIn("No gitlink changes", markdown)
        self.assertNotIn("Files different at the two endpoint trees (1)", markdown)

    def test_clean_gitlink_removal_is_audited_and_allowed(self) -> None:
        manifest = self.audit(self.removed_head)
        self.assertEqual("pass", manifest["status"])
        self.assertEqual([], manifest["submodules"])
        self.assertEqual(
            [{"path": "vendor/fixture", "old": self.old_pin, "url": str(self.submodule)}],
            manifest["removed_submodules"],
        )
        markdown = GATE.render_markdown(manifest)
        self.assertIn("Removed gitlinks", markdown)
        self.assertIn("`.gitmodules` entry and tracked path absent", markdown)

    def test_gitlink_removal_with_stale_module_metadata_is_hard_red(self) -> None:
        git(self.mobile, "checkout", "-q", "-b", "dirty-remove-metadata", self.base)
        git(self.mobile, "rm", "--cached", "vendor/fixture")
        head = commit_index(self.mobile, "remove pin but retain metadata")
        with self.assertRaisesRegex(GATE.GateError, "remains in head .gitmodules"):
            self.audit(head)

    def test_gitlink_replaced_by_regular_file_is_hard_red(self) -> None:
        git(self.mobile, "checkout", "-q", "-b", "dirty-remove-path", self.base)
        git(self.mobile, "rm", "--cached", "vendor/fixture")
        shutil.rmtree(self.mobile / "vendor/fixture")
        write(self.mobile, "vendor/fixture", "not a submodule\n")
        write(self.mobile, ".gitmodules", "")
        head = commit_all(self.mobile, "replace gitlink with a file")
        with self.assertRaisesRegex(GATE.GateError, "replaced by another tracked object"):
            self.audit(head)

    def test_base_advancing_the_pin_does_not_blame_a_branch_that_never_touched_it(self) -> None:
        # The real case this gate got wrong: the base advanced the submodule
        # pin on its own, and a branch cut before that — touching only docs —
        # was hard-redded for "reversing" it and forced to rebase.
        #
        # Nothing is reversed. The merge keeps the base's newer pin; the branch
        # simply predates it. Comparing the base TIP against head sees a
        # backward move that no commit on the branch ever made, and that fires
        # on every open branch each time the base moves a submodule.
        #
        # Comparing against the merge base asks the question that matters —
        # what did this branch introduce — and here the answer is nothing.
        manifest = GATE.audit(
            self.mobile,
            self.carry_head,  # base tip: the pin was advanced here
            self.no_change_head,  # branch: docs only, never touched the gitlink
            allow_local_remotes=True,
        )
        self.assertEqual("no_changes", manifest["status"])
        self.assertEqual([], manifest["submodules"])
        # And the manifest says which commit it compared from, so a reader is
        # never left guessing why nothing was reported.
        self.assertEqual(self.base, manifest["comparison_base"])
        self.assertEqual(self.carry_head, manifest["base"])

    def test_a_branch_that_really_moves_the_pin_backward_is_still_caught(self) -> None:
        # The protection this gate exists for must survive the fix above. When
        # the reversal is genuinely the branch's own doing it is still a hard
        # red, because it is present in the branch's own diff.
        git(self.mobile, "checkout", "-q", "-b", "revert-pin", self.carry_head)
        git(
            self.mobile,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{self.old_pin},vendor/fixture",
        )
        reverting_head = commit_index(self.mobile, "move fixture pin backward")
        with self.assertRaisesRegex(GATE.GateError, "not a true ancestor"):
            GATE.audit(
                self.mobile,
                self.carry_head,
                reverting_head,
                allow_local_remotes=True,
            )

    def test_empty_base_input_is_hard_red(self) -> None:
        with self.assertRaisesRegex(GATE.GateError, "base commit is empty"):
            GATE.audit(
                self.mobile,
                "",
                self.carry_head,
                allow_local_remotes=True,
            )

    def test_non_ancestor_pin_is_hard_red(self) -> None:
        with self.assertRaisesRegex(GATE.GateError, "not a true ancestor"):
            self.audit(self.unrelated_head)

    def test_process_and_endpoint_lists_distinguish_reverted_file(self) -> None:
        manifest = self.audit(self.carry_head)
        self.assertEqual("pass", manifest["status"])
        self.assertEqual(1, len(manifest["submodules"]))
        audit = manifest["submodules"][0]

        self.assertEqual(self.old_pin, audit["merge_base"])
        self.assertEqual(0, audit["behind"])
        self.assertEqual(2, audit["commit_count"])

        process_paths = {item["path"] for item in audit["process_touched"]}
        endpoint_paths = {item["path"] for item in audit["endpoint_changed"]}
        self.assertEqual(
            {"carried.txt", "nested/added.txt", "reverted.txt"},
            process_paths,
        )
        self.assertEqual({"carried.txt", "nested/added.txt"}, endpoint_paths)
        self.assertEqual(["reverted.txt"], audit["process_only"])
        self.assertEqual(
            git(self.submodule, "rev-parse", f"{self.old_pin}:reverted.txt"),
            git(self.submodule, "rev-parse", f"{self.new_pin}:reverted.txt"),
        )

        endpoint_by_path = {item["path"]: item for item in audit["endpoint_changed"]}
        carried = endpoint_by_path["carried.txt"]
        self.assertEqual("A", carried["status"])
        self.assertEqual("000000", carried["old_mode"])
        self.assertEqual(GATE.ZERO_SHA, carried["old_object"])
        self.assertNotEqual(carried["old_object"], carried["new_object"])

        markdown = GATE.render_markdown(manifest)
        self.assertIn("A. Files touched by interval commits (3)", markdown)
        self.assertIn("B. Files different at the two endpoint trees (2)", markdown)
        self.assertIn("A-only (changed during the interval, restored by the new endpoint): 1", markdown)


if __name__ == "__main__":
    unittest.main()
