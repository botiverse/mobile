#!/usr/bin/env python3
"""Self-test for raft_ui_version_watch.py."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from raft_ui_version_watch import compare  # noqa: E402


def manifest(*versions: str) -> dict:
    sources = {
        f"raftUi{i}": {"origin": f"npm:@botiverse/raft-ui@{v}/dist/styles.css"}
        for i, v in enumerate(versions)
    }
    sources["tailwind"] = {"origin": "npm:tailwindcss@4.3.3/theme.css"}
    return {"sources": sources}


def web(spec: str | None) -> dict:
    return {"dependencies": {} if spec is None else {"@botiverse/raft-ui": spec}}


class CompareTest(unittest.TestCase):
    def test_web_ahead_is_behind(self) -> None:
        self.assertEqual(
            compare(manifest("0.20.0", "0.20.0"), web("0.22.1")),
            {"mobile": "0.20.0", "web": "0.22.1", "behind": "true"},
        )

    def test_same_version_is_current(self) -> None:
        self.assertEqual(compare(manifest("0.22.1"), web("0.22.1"))["behind"], "false")

    def test_numeric_not_lexical_order(self) -> None:
        self.assertEqual(compare(manifest("0.9.0"), web("0.10.0"))["behind"], "true")

    def test_mobile_ahead_is_not_behind(self) -> None:
        self.assertEqual(compare(manifest("0.23.0"), web("0.22.1"))["behind"], "false")

    def test_mixed_manifest_versions_fail_closed(self) -> None:
        with self.assertRaises(ValueError):
            compare(manifest("0.20.0", "0.21.0"), web("0.22.1"))

    def test_missing_web_dependency_fails_closed(self) -> None:
        with self.assertRaises(ValueError):
            compare(manifest("0.20.0"), web(None))

    def test_web_range_fails_closed(self) -> None:
        with self.assertRaises(ValueError):
            compare(manifest("0.20.0"), web("^0.22.1"))


if __name__ == "__main__":
    unittest.main()
