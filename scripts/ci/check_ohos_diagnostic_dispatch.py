#!/usr/bin/env python3
"""Restrict the temporary task68 signing mode before any signing secrets are read."""

import os
import re
import sys
import unittest


def validate(env):
    required = {
        "NATIVE_DIAGNOSTIC_PAIR": "true",
        "PUBLISH_TO_HANDS": "false",
        "VERSION_MAJOR": "1",
        "VERSION_MINOR": "13",
        "VERSION_PATCH": "1",
        "VERSION_BUILD": "900",
        "VERSION_SUFFIX": "diag-113bb09c",
    }
    for key, expected in required.items():
        if env.get(key) != expected:
            raise ValueError(f"task68 requires {key}={expected}")
    requested = env.get("REQUESTED_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", requested):
        raise ValueError("task68 source_sha must be the full reviewed driver SHA")
    if requested != env.get("GITHUB_SHA"):
        raise ValueError("task68 workflow ref and source_sha must resolve to the same reviewed driver")


class DispatchTests(unittest.TestCase):
    def setUp(self):
        self.env = dict(NATIVE_DIAGNOSTIC_PAIR="true", PUBLISH_TO_HANDS="false",
                        VERSION_MAJOR="1", VERSION_MINOR="13", VERSION_PATCH="1",
                        VERSION_BUILD="900", VERSION_SUFFIX="diag-113bb09c",
                        REQUESTED_SHA="a" * 40, GITHUB_SHA="a" * 40)

    def test_fixed_artifact_only_driver(self):
        validate(self.env)

    def test_cannot_publish_or_change_mode_or_identity(self):
        for key in self.env:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate({**self.env, key: ""})
        for key, value in (("PUBLISH_TO_HANDS", "true"), ("REQUESTED_SHA", "b" * 40),
                           ("REQUESTED_SHA", "a" * 39), ("REQUESTED_SHA", "A" * 40),
                           ("VERSION_BUILD", "901"), ("VERSION_SUFFIX", "$(id)")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate({**self.env, key: value})


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        unittest.main(argv=[sys.argv[0]])
    elif sys.argv[1:]:
        sys.exit("usage: check_ohos_diagnostic_dispatch.py [--self-test]")
    else:
        try:
            validate(os.environ)
        except ValueError as error:
            sys.exit(f"::error::{error}")
        print("Task68 diagnostic dispatch is fixed, artifact-only, and driver SHA matched")
