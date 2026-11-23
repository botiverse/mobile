#!/usr/bin/env python3
"""Unit tests for scripts/app_links/diagnose.py (no network, no macOS).

The iOS path matching itself is Apple's swcutil (run by the workflow's macOS job, with its own
self-test on the pinned fixtures); these tests cover everything around it.
"""
from __future__ import annotations

import json
import plistlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import diagnose as d  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"


class RepoReadersTest(unittest.TestCase):
    def test_ios_hosts_and_bundle_come_from_the_ios_project(self):
        self.assertEqual(["app.raft.build", "open.raft.build"], d.ios_entitled_hosts())
        self.assertEqual("build.raft.app", d.ios_bundle_id())

    def test_ios_bundle_id_ignores_app_extension_targets(self):
        pbx = (d.ROOT / "compose/iosApp/iosApp.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
        self.assertIn("PRODUCT_BUNDLE_IDENTIFIER = build.raft.app.share;", pbx)
        self.assertEqual("build.raft.app", d.ios_bundle_id())

    def test_upload_key_fingerprints_come_from_the_signing_verifier(self):
        fps = d.android_upload_fingerprints()
        self.assertEqual(2, len(fps))
        self.assertTrue(fps[0]["sha256"].startswith("B4:91:93:81"))
        self.assertTrue(fps[1]["sha256"].startswith("5F:08:BB:E8"))

    def test_main_manifest_has_one_verified_filter_on_the_raft_hosts(self):
        filters = d.web_filters(d.source_manifest(d.ROOT, "main"))
        verified = [f for f in filters if f["auto_verify"]]
        self.assertEqual(1, len(verified))
        self.assertEqual(["app.raft.build", "open.raft.build"], verified[0]["hosts"])
        self.assertIn(("pathPrefix", "/s/"), verified[0]["paths"])

    def test_url_matrix_is_well_formed(self):
        urls = d.load_json(d.HERE / "url_matrix.json")["urls"]
        self.assertEqual(len(urls), len({u["id"] for u in urls}))
        for u in urls:
            self.assertTrue(u["path"].startswith("/"), u)
            self.assertIn(u["expect"]["ios"], ("app", "web"))
            self.assertIn(u["expect"]["android"], ("app", "web"))


class AndroidMatcherTest(unittest.TestCase):
    F = {"auto_verify": True, "browsable": True, "schemes": ["https"], "hosts": ["app.raft.build"],
         "paths": [("pathPrefix", "/s/"), ("path", "/download"), ("pathPattern", "/a/.*/v/.*"), ("pathSuffix", ".pdf")]}

    def test_path_kinds(self):
        cases = [
            ("https://app.raft.build/s/x/dm/1", True),
            ("https://app.raft.build/download", True),
            ("https://app.raft.build/download/", False),
            ("https://app.raft.build/a/1/v/2", True),
            ("https://app.raft.build/a/1/x/2", False),
            ("https://app.raft.build/f/report.pdf", True),
            ("http://app.raft.build/s/x", False),
            ("https://open.raft.build/s/x", False),
        ]
        for url, want in cases:
            self.assertEqual(want, d.android_filter_matches(self.F, url), url)

    def test_pattern_glob(self):
        self.assertEqual(r"/a/.*", d.android_pattern_regex("/a/.*"))
        self.assertIsNotNone(__import__("re").fullmatch(d.android_pattern_regex("/x\\.y"), "/x.y"))
        self.assertIsNone(__import__("re").fullmatch(d.android_pattern_regex("/x\\.y"), "/xzy"))

    def test_outcome_needs_a_verified_filter_and_a_verified_host(self):
        unverified = dict(self.F, auto_verify=False)
        self.assertEqual("app", d.android_outcome([self.F], "https://app.raft.build/s/x", {"app.raft.build"})[0])
        self.assertEqual("web", d.android_outcome([self.F], "https://app.raft.build/s/x", set())[0])
        self.assertEqual("web", d.android_outcome([unverified], "https://app.raft.build/s/x", {"app.raft.build"})[0])
        self.assertEqual("web", d.android_outcome([self.F], "https://app.raft.build/login", {"app.raft.build"})[0])

    def test_aapt_xmltree_reader_matches_the_source_reader(self):
        dump = "\n".join([
            "N: android=http://schemas.android.com/apk/res/android",
            "  E: manifest (line=2)",
            "    E: application (line=10)",
            "      E: activity (line=20)",
            "        E: intent-filter (line=30)",
            "          A: android:autoVerify(0x010104ee)=(type 0x12)0xffffffff",
            "          E: action (line=31)",
            '            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")',
            "          E: category (line=32)",
            '            A: android:name(0x01010003)="android.intent.category.BROWSABLE" (Raw: "android.intent.category.BROWSABLE")',
            "          E: data (line=33)",
            '            A: android:scheme(0x01010027)="https" (Raw: "https")',
            "          E: data (line=34)",
            '            A: android:host(0x01010028)="app.raft.build" (Raw: "app.raft.build")',
            "          E: data (line=35)",
            '            A: android:pathPrefix(0x0101002b)="/s/" (Raw: "/s/")',
            "        E: intent-filter (line=40)",
            "          A: android:autoVerify(0x010104ee)=(type 0x12)0x0",
            "          E: action (line=41)",
            '            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")',
            "          E: data (line=42)",
            '            A: android:scheme(0x01010027)="https" (Raw: "https")',
            '            A: android:host(0x01010028)="x.example" (Raw: "x.example")',
        ])
        filters = d.web_filters(d.parse_aapt_xmltree(dump))
        self.assertEqual(2, len(filters))
        self.assertTrue(filters[0]["auto_verify"])
        self.assertEqual(["app.raft.build"], filters[0]["hosts"])
        self.assertEqual([("pathPrefix", "/s/")], filters[0]["paths"])
        self.assertFalse(filters[1]["auto_verify"])
        self.assertEqual(["x.example"], filters[1]["hosts"])


class SwcutilOutputTest(unittest.TestCase):
    APP = "XDAPXFY8FZ.build.raft.app"

    def test_tn3155_output_shapes(self):
        matched = '{ s = applinks, a = XDAPXFY8FZ.build.raft.app, d = app.raft.build }:\nPattern "https://app.raft.build/download" matched.\n'
        blocked = '{ s = applinks, a = XDAPXFY8FZ.build.raft.app, d = app.raft.build }:\nPattern "https://app.raft.build/s/x/dm/1" blocked match.\n'
        nomatch = '{ s = applinks, a = XDAPXFY8FZ.build.raft.app, d = app.raft.build }:\nInput JSON did not match input URL.\n'
        self.assertEqual("app", d.parse_swcutil(matched, self.APP)[0])
        self.assertEqual("web", d.parse_swcutil(blocked, self.APP)[0])
        self.assertEqual("web", d.parse_swcutil(nomatch, self.APP)[0])
        self.assertEqual("web", d.parse_swcutil("Input JSON did not match input URL.\n", self.APP)[0])

    def test_other_app_ids_do_not_count(self):
        other = '{ s = applinks, a = OTHER.app, d = app.raft.build }:\nPattern "https://app.raft.build/x" matched.\n'
        self.assertEqual("web", d.parse_swcutil(other, self.APP)[0])

    def test_only_the_applinks_service_decides(self):
        # 2026-11-21: the AASA gained `webcredentials` (passkeys); swcutil prints a block for it that
        # "matches" every URL of the host. That is not a universal link.
        web = "{ s = webcredentials, a = XDAPXFY8FZ.build.raft.app, d = app.raft.build }"
        app = "{ s = applinks, a = XDAPXFY8FZ.build.raft.app, d = app.raft.build }"
        same_line = web + ': Pattern "https://app.raft.build/login" matched.\n'
        self.assertEqual("web", d.parse_swcutil(same_line, self.APP)[0])
        next_line = web + ':\nPattern "https://app.raft.build/login" matched.\n'
        self.assertEqual("web", d.parse_swcutil(next_line, self.APP)[0])
        both_web = same_line + app + ":\nInput JSON did not match input URL.\n"
        self.assertEqual("web", d.parse_swcutil(both_web, self.APP)[0])
        both_app = same_line + app + ': Pattern "https://app.raft.build/s/x/dm/1" matched.\n'
        self.assertEqual("app", d.parse_swcutil(both_app, self.APP)[0])
        app_first = app + ':\nPattern "https://app.raft.build/s/x/dm/1" blocked match.\n' + next_line
        self.assertEqual("web", d.parse_swcutil(app_first, self.APP)[0])

    def test_unknown_output_is_no_verdict(self):
        self.assertIsNone(d.parse_swcutil("swcutil: Operation not permitted\n", self.APP)[0])


class AasaTest(unittest.TestCase):
    APP = "XDAPXFY8FZ.build.raft.app"

    def resp(self, body: bytes, status=200, ctype="application/json", **headers):
        return {"url": "u", "status": status, "headers": {"content-type": ctype, **headers}, "body": body}

    def test_regression_fixtures_pin_the_old_and_new_dm_excludes(self):
        old = d.load_json(FIXTURES / "aasa-prod-2026-11-17.json")
        new = d.load_json(FIXTURES / "aasa-slock-15228.json")
        old_ex = {c["/"] for c in d.aasa_components(old, self.APP) if c.get("exclude")}
        new_ex = {c["/"] for c in d.aasa_components(new, self.APP) if c.get("exclude")}
        self.assertIn("/s/*/dm/*/*", old_ex)  # the 11-17 rule that blocked every DM link on iOS
        self.assertNotIn("/s/*/dm/*/*", new_ex)
        self.assertIn("/s/*/dm/*/?*", new_ex)
        cases = {(f, p): e for f, p, e in d.SELF_TEST}
        self.assertEqual("web", cases[("aasa-prod-2026-11-17.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9")])
        self.assertEqual("app", cases[("aasa-slock-15228.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9")])

    def test_response_checks(self):
        good = (FIXTURES / "aasa-slock-15228.json").read_bytes()
        self.assertEqual(d.PASS, d.check_aasa_response(self.resp(good), self.APP)[0])
        self.assertEqual(d.FAIL, d.check_aasa_response(self.resp(good), "TEAM.other")[0])
        self.assertEqual(d.FAIL, d.check_aasa_response(self.resp(good, ctype="text/plain"), self.APP)[0])
        self.assertEqual(d.FAIL, d.check_aasa_response(self.resp(b"<html>", ctype="text/html"), self.APP)[0])
        redirect = d.check_aasa_response(self.resp(b"", status=302, location="https://x/"), self.APP)
        self.assertEqual(d.FAIL, redirect[0])
        self.assertIn("redirect", redirect[1])
        self.assertEqual(d.FAIL, d.check_aasa_response({"url": "u", "status": None, "headers": {}, "body": b"", "error": "timeout"}, self.APP)[0])

    def test_cdn_diff_names_the_changed_patterns(self):
        old = d.load_json(FIXTURES / "aasa-prod-2026-11-17.json")
        new = d.load_json(FIXTURES / "aasa-slock-15228.json")
        diff = d.aasa_diff(old, new, self.APP)
        self.assertIn("`/s/*/dm/*/?*`", diff.split("CDN only")[0])
        self.assertIn("`/s/*/dm/*/*`", diff.split("CDN only")[1])

    def test_entitlements_are_read_from_a_signed_binary(self):
        ent = plistlib.dumps({"application-identifier": self.APP,
                              "com.apple.developer.associated-domains": ["applinks:app.raft.build"]}, fmt=plistlib.FMT_XML)
        blob = b"\xcf\xfa\xed\xfe junk \xfa\xde\x71\x71\x00\x00\x01\x00" + ent + b"\x00 more"
        found = d.ios_entitlements_from_binary(blob)
        self.assertEqual(self.APP, found[0]["application-identifier"])


class ReportTest(unittest.TestCase):
    def results(self, ios_status=d.PASS, self_test=d.PASS):
        web = {"step": "web", "meta": {"app_id": "T.b", "ios_hosts": ["h"], "android_package": "p", "android_verified_hosts": ["h"], "preview_hosts": []},
               "rows": [d.row("iOS AASA", "c", "h", d.PASS, "ok")],
               "android_matrix": {"columns": ["h"], "rows": [{"id": "x", "label": "x", "path": "/s/x", "expect": "app", "cells": {"h": {"outcome": "app", "ok": True}}}]}}
        st = d.row("iOS matcher", "self-test", "mac", self_test, "")
        st["self_test"] = True
        ios = {"step": "ios", "rows": [st, d.row("iOS URL matrix (swcutil)", "cdn", "h", ios_status, "wrong: dm")],
               "ios_matrix": {"columns": ["h CDN"], "rows": [{"id": "dm", "label": "DM | link", "path": "/s/x/dm/1", "expect": "app", "cells": {"h CDN": {"outcome": "web", "ok": False}}}]}}
        return [web, ios]

    def test_render_has_counts_tables_and_limits(self):
        text = d.render(self.results(ios_status=d.FAIL), {"when": "now"})
        self.assertIn("**1 FAIL · 0 WARN · 2 PASS**", text)
        self.assertIn("| iOS URL matrix (swcutil) | cdn | h | ❌ FAIL | wrong: dm |", text)
        self.assertIn("| `/s/x/dm/1`<br>DM \\| link | app | ❌ web |", text)
        self.assertIn("### What this cannot check", text)
        self.assertIn("No result from step(s): builds", text)

    def test_probe_rows_are_shown_but_not_judged(self):
        results = self.results()
        results[1]["ios_matrix"]["rows"].append({"id": "p", "label": "probe", "path": "/p", "expect": "app", "cells": {"h CDN": {"outcome": "web", "ok": None}}})
        self.assertIn("| `/p`<br>probe | probe | ℹ️ web |", d.render(results, {}))

    def _report(self, results, fail_on_fail):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            for r in results:
                (Path(tmp) / f"{r['step']}.json").write_text(json.dumps(r))
            argv = ["report", tmp, "--summary", f"{tmp}/s.md", "--json", f"{tmp}/a.json"] + (["--fail-on-fail"] if fail_on_fail else [])
            import contextlib, io
            with contextlib.redirect_stdout(io.StringIO()):
                return d.main(argv)

    def test_exit_codes(self):
        self.assertEqual(0, self._report(self.results(ios_status=d.FAIL), fail_on_fail=False))
        self.assertEqual(1, self._report(self.results(ios_status=d.FAIL), fail_on_fail=True))
        self.assertEqual(0, self._report(self.results(), fail_on_fail=True))
        self.assertEqual(2, self._report(self.results(self_test=d.FAIL), fail_on_fail=False))
        self.assertEqual(2, self._report(self.results()[:1], fail_on_fail=False))


if __name__ == "__main__":
    unittest.main()
