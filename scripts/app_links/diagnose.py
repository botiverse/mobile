#!/usr/bin/env python3
"""App link diagnose: iOS Universal Links and Android App Links for the hosts the app claims.

Run by .github/workflows/app-link-diagnose.yml. Subcommands (each writes one JSON result file):

  web     (any OS)   AASA on the origin and on Apple's CDN, assetlinks.json, Google's Digital
                     Asset Links API, and the Android URL matrix against the source manifest.
                     Saves every AASA it read so the `ios` step can feed it to swcutil.
  ios     (macOS)    The URL matrix through Apple's own matcher, `sudo swcutil verify`
                     (TN3155), against the origin and the Apple-CDN copy of each AASA, plus a
                     self-test on two pinned AASA files (the 11-17 prod file must block DM links,
                     the botiverse/slock #15228 file must open them). --candidate adds a column
                     for an AASA not served yet (workflow input candidate_aasa_ref).
  builds  (Linux)    Optional: the latest published Hands builds. APK signer certificates and
                     shipped autoVerify filters; IPA associated-domains entitlement. WARN-only
                     when no Hands token is available.
  report             Merges the result files into a markdown summary and one JSON file.

No own iOS matcher: iOS path matching has undocumented normalisation (it adds a trailing "/"
to the URL path and to patterns ending in neither "/" nor "*"; botiverse/slock #15228, seen
with swcutil and a device sysdiagnose on 2026-11-17), so only Apple's tool is trusted for it.
The Android matcher below follows android.os.PatternMatcher / IntentFilter data matching.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
USER_AGENT = "raft-app-link-diagnose/1"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
APPLE_CDN = "https://app-site-association.cdn-apple.com/a/v1/"
DAL_API = "https://digitalassetlinks.googleapis.com/v1/"
HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls"
TRAILING_SLASH_SOURCE = "https://github.com/botiverse/slock/pull/15228"

PASS, FAIL, WARN, SKIP = "PASS", "FAIL", "WARN", "SKIP"


# ---------------------------------------------------------------- repo readers


def load_json(path: Path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def ios_entitled_hosts(root: Path = ROOT) -> list[str]:
    hosts: list[str] = []
    for ent in sorted((root / "compose/iosApp").rglob("*.entitlements")):
        data = plistlib.loads(ent.read_bytes())
        for item in data.get("com.apple.developer.associated-domains", []) or []:
            if item.startswith("applinks:"):
                host = item[len("applinks:"):].split("?", 1)[0]
                if host not in hosts:
                    hosts.append(host)
    return hosts


def ios_bundle_id(root: Path = ROOT) -> str:
    text = (root / "compose/iosApp/iosApp.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
    # Scope to the main application target's build configurations so app extensions
    # (such as RaftShareExtension / build.raft.app.share) and test bundles do not collide.
    app_config_lists = re.findall(
        r"isa = PBXNativeTarget;[^}]*?buildConfigurationList = ([A-F0-9]+)\b[^}]*?productType = \"com\.apple\.product-type\.application\";",
        text,
        re.S,
    )
    if len(app_config_lists) != 1:
        raise SystemExit(
            f"expected one application target in project.pbxproj, found {len(app_config_lists)}"
        )
    list_match = re.search(
        rf"\b{re.escape(app_config_lists[0])}\b[^=]*=\s*\{{[^}}]*?buildConfigurations\s*=\s*\(([^)]*)\);",
        text,
        re.S,
    )
    if not list_match:
        raise SystemExit(f"buildConfigurationList {app_config_lists[0]} not found in project.pbxproj")
    config_ids = re.findall(r"\b([A-F0-9]{24})\b", list_match.group(1))
    ids: set[str] = set()
    for cid in config_ids:
        block_match = re.search(
            rf"\b{re.escape(cid)}\b[^=]*=\s*\{{(.*?)\n\t\t\}};",
            text,
            re.S,
        )
        if not block_match:
            continue
        cfg_match = re.search(r"PRODUCT_BUNDLE_IDENTIFIER\s*=\s*([^;]+);", block_match.group(1))
        if cfg_match:
            ids.add(cfg_match.group(1).strip('"'))
    if len(ids) != 1:
        raise SystemExit(f"expected one app bundle id in project.pbxproj, found {sorted(ids)}")
    return ids.pop()


def android_upload_fingerprints(root: Path = ROOT) -> list[dict]:
    text = (root / "scripts/verify_android_apk_with_rotation.sh").read_text(encoding="utf-8")
    out = []
    for name, role in (("legacy_signer_sha256", "legacy upload key (API <= 32)"), ("rotated_signer_sha256", "rotated upload key (API 33+)")):
        m = re.search(rf'^{name}="([0-9a-f]{{64}})"', text, re.M)
        if not m:
            raise SystemExit(f"{name} not found in scripts/verify_android_apk_with_rotation.sh")
        out.append({"sha256": colon_fp(m.group(1)), "role": role, "source": "scripts/verify_android_apk_with_rotation.sh"})
    return out


def colon_fp(value: str) -> str:
    hexes = re.sub(r"[^0-9a-fA-F]", "", value).upper()
    return ":".join(hexes[i:i + 2] for i in range(0, len(hexes), 2))


# Generic element tree shared by the source-manifest reader and the `aapt dump xmltree` reader.


def node(tag: str, attrs: dict | None = None, children: list | None = None) -> dict:
    return {"tag": tag, "attrs": attrs or {}, "children": children or []}


def from_element(el: ET.Element) -> dict:
    attrs = {}
    for key, value in el.attrib.items():
        attrs[key.replace(ANDROID_NS, "android:")] = value
    return node(el.tag, attrs, [from_element(c) for c in el])


def walk(n: dict):
    yield n
    for c in n["children"]:
        yield from walk(c)


def web_filters(manifest: dict) -> list[dict]:
    """VIEW + BROWSABLE intent filters with an http(s) scheme, as plain data."""
    filters = []
    for n in walk(manifest):
        if n["tag"] != "intent-filter":
            continue
        actions = {c["attrs"].get("android:name") for c in n["children"] if c["tag"] == "action"}
        categories = {c["attrs"].get("android:name") for c in n["children"] if c["tag"] == "category"}
        data = [c["attrs"] for c in n["children"] if c["tag"] == "data"]
        schemes = [d["android:scheme"] for d in data if d.get("android:scheme")]
        if "android.intent.action.VIEW" not in actions or not any(s.startswith("http") for s in schemes):
            continue
        paths = []
        for d in data:
            for kind in ("path", "pathPrefix", "pathPattern", "pathSuffix", "pathAdvancedPattern"):
                if f"android:{kind}" in d:
                    paths.append((kind, d[f"android:{kind}"]))
        filters.append({
            "auto_verify": str(n["attrs"].get("android:autoVerify", "")).lower() == "true",
            "browsable": "android.intent.category.BROWSABLE" in categories,
            "schemes": sorted(set(schemes)),
            "hosts": sorted({d["android:host"] for d in data if d.get("android:host")}),
            "paths": paths,
        })
    return filters


def source_manifest(root: Path, source_set: str) -> dict | None:
    path = root / f"compose/androidApp/src/{source_set}/AndroidManifest.xml"
    if not path.is_file():
        return None
    return from_element(ET.parse(path).getroot())


def parse_aapt_xmltree(text: str) -> dict:
    """`aapt dump xmltree <apk> AndroidManifest.xml` output -> generic tree."""
    root = node("#document")
    stack: list[tuple[int, dict]] = [(-1, root)]
    for line in text.splitlines():
        stripped = line.lstrip(" ")
        depth = len(line) - len(stripped)
        if stripped.startswith("E: "):
            tag = stripped[3:].split(" ", 1)[0]
            while stack and stack[-1][0] >= depth:
                stack.pop()
            el = node(tag)
            stack[-1][1]["children"].append(el)
            stack.append((depth, el))
        elif stripped.startswith("A: "):
            m = re.match(r'A: ([^=(]+)(?:\(0x[0-9a-f]+\))?=(.*)$', stripped)
            if not m:
                continue
            name, raw = m.group(1), m.group(2)
            q = re.match(r'"((?:[^"\\]|\\.)*)"', raw)
            if q:
                value = q.group(1)
            elif re.match(r"\(type 0x12\)0x0*[1-9a-f]", raw):
                value = "true"
            elif raw.startswith("(type 0x12)"):
                value = "false"
            else:
                value = raw
            while stack and stack[-1][0] >= depth:
                stack.pop()
            stack[-1][1]["attrs"][name] = value
    return root


# ---------------------------------------------------------------- Android matching


def android_pattern_regex(pattern: str) -> str:
    """android.os.PatternMatcher PATTERN_SIMPLE_GLOB: '.' any char, '*' zero+ of the previous, '\\' escapes."""
    out, i = [], 0
    while i < len(pattern):
        ch = pattern[i]
        if ch == "\\" and i + 1 < len(pattern):
            out.append(re.escape(pattern[i + 1]))
            i += 2
            continue
        if ch == ".":
            out.append(".")
        elif ch == "*":
            out.append("*" if out else re.escape("*"))
        else:
            out.append(re.escape(ch))
        i += 1
    return "".join(out)


def android_path_matches(kind: str, value: str, path: str) -> bool:
    if kind == "path":
        return path == value
    if kind == "pathPrefix":
        return path.startswith(value)
    if kind == "pathSuffix":
        return path.endswith(value)
    if kind in ("pathPattern", "pathAdvancedPattern"):
        return re.fullmatch(android_pattern_regex(value), path) is not None
    return False


def android_filter_matches(f: dict, url: str) -> bool:
    u = urllib.parse.urlsplit(url)
    if u.scheme not in f["schemes"]:
        return False
    if f["hosts"] and not any(h == u.hostname or (h.startswith("*.") and (u.hostname or "").endswith(h[1:])) for h in f["hosts"]):
        return False
    if not f["paths"]:
        return True
    return any(android_path_matches(k, v, u.path) for k, v in f["paths"])


def android_outcome(filters: list[dict], url: str, verified_hosts: set[str]) -> tuple[str, str]:
    """Where Android 12+ opens a web link: the app only through a verified (autoVerify) filter."""
    host = urllib.parse.urlsplit(url).hostname or ""
    matching = [f for f in filters if android_filter_matches(f, url)]
    verified = [f for f in matching if f["auto_verify"]]
    if verified:
        if host in verified_hosts:
            return "app", "autoVerify filter " + describe_paths(verified[0])
        return "web", "autoVerify filter matches but the host does not verify (assetlinks)"
    if matching:
        return "web", "only a non-verified filter " + describe_paths(matching[0]) + " (browser on Android 12+)"
    return "web", "no intent filter"


def describe_paths(f: dict) -> str:
    return ", ".join(f"{k}={v!r}" for k, v in f["paths"]) or "(any path)"


# ---------------------------------------------------------------- HTTP


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # noqa: D401 - urllib hook
        return None


def fetch(url: str, follow: bool = False, timeout: int = 30) -> dict:
    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with opener.open(req, timeout=timeout) as resp:
            return {"url": url, "status": resp.status, "headers": {k.lower(): v for k, v in resp.headers.items()}, "body": resp.read()}
    except urllib.error.HTTPError as e:
        return {"url": url, "status": e.code, "headers": {k.lower(): v for k, v in (e.headers or {}).items()}, "body": e.read() if e.fp else b""}
    except Exception as e:  # network error, TLS, timeout
        return {"url": url, "status": None, "headers": {}, "body": b"", "error": f"{type(e).__name__}: {e}"}


def parse_json(body: bytes):
    try:
        return json.loads(body.decode("utf-8")), None
    except Exception as e:
        return None, f"{type(e).__name__}: {str(e)[:120]}"


# ---------------------------------------------------------------- iOS AASA checks


def aasa_app_ids(aasa: dict) -> list[str]:
    ids = []
    for d in ((aasa or {}).get("applinks") or {}).get("details") or []:
        ids += d.get("appIDs") or ([d["appID"]] if d.get("appID") else [])
    return ids


def aasa_components(aasa: dict, app_id: str) -> list[dict]:
    for d in ((aasa or {}).get("applinks") or {}).get("details") or []:
        if app_id in (d.get("appIDs") or [d.get("appID")]):
            return d.get("components") or [{"/": p} for p in d.get("paths") or []]
    return []


def check_aasa_response(resp: dict, app_id: str) -> tuple[str, str, dict | None]:
    if resp.get("error"):
        return FAIL, resp["error"], None
    status, ctype = resp["status"], resp["headers"].get("content-type", "")
    if status in (301, 302, 303, 307, 308):
        return FAIL, f"HTTP {status} redirect to {resp['headers'].get('location')} (Apple does not follow redirects)", None
    if status != 200:
        return FAIL, f"HTTP {status}", None
    data, err = parse_json(resp["body"])
    problems = []
    if "json" not in ctype.lower():
        problems.append(f"content-type {ctype or '(none)'}")
    if data is None:
        return FAIL, f"HTTP 200 but not JSON ({err}); content-type {ctype or '(none)'}", None
    if len(resp["body"]) > 128 * 1024:
        problems.append(f"{len(resp['body'])} bytes > 128 KB")
    ids = aasa_app_ids(data)
    if app_id not in ids:
        return FAIL, f"appIDs {ids} do not contain {app_id}", data
    detail = f"200, {ctype}, {len(resp['body'])} B, {app_id}, {len(aasa_components(data, app_id))} components"
    if problems:
        return FAIL, detail + "; " + "; ".join(problems), data
    return PASS, detail, data


def component_key(c: dict) -> str:
    keys = {k: c[k] for k in ("/", "?", "#", "exclude", "caseSensitive", "percentEncoded") if k in c}
    return json.dumps(keys, sort_keys=True)


def aasa_diff(a: dict, b: dict, app_id: str) -> str:
    ca = [component_key(c) for c in aasa_components(a, app_id)]
    cb = [component_key(c) for c in aasa_components(b, app_id)]
    only_a = [json.loads(k).get("/", k) for k in ca if k not in cb]
    only_b = [json.loads(k).get("/", k) for k in cb if k not in ca]
    parts = []
    if only_b:
        parts.append("origin only: " + ", ".join(f"`{p}`" for p in only_b))
    if only_a:
        parts.append("CDN only: " + ", ".join(f"`{p}`" for p in only_a))
    if not parts and ca != cb:
        parts.append("same components, different order")
    return "; ".join(parts) or "non-component fields differ"


# ---------------------------------------------------------------- web step


def row(section: str, check: str, target: str, status: str, detail: str) -> dict:
    return {"section": section, "check": check, "target": target, "status": status, "detail": detail}


def cmd_web(args) -> int:
    out = Path(args.out)
    aasa_dir = out / "aasa"
    aasa_dir.mkdir(parents=True, exist_ok=True)
    cfg = load_json(HERE / "config.json")
    matrix = load_json(HERE / "url_matrix.json")["urls"]
    bundle = ios_bundle_id()
    app_id = f"{cfg['apple_team_id']}.{bundle}"
    entitled = ios_entitled_hosts()
    main_filters = web_filters(source_manifest(ROOT, "main"))
    verified_hosts = sorted({h for f in main_filters if f["auto_verify"] for h in f["hosts"]})
    preview_hosts = []
    for source_set in ("alpha", "debug", "play", "release"):
        m = source_manifest(ROOT, source_set)
        for f in web_filters(m) if m else []:
            preview_hosts += [h for h in f["hosts"] if h not in entitled and h not in verified_hosts and h not in preview_hosts]
    rows: list[dict] = []
    ios_targets: list[dict] = []

    # 1-2. AASA on the origin and the Apple CDN.
    for host in entitled + preview_hosts:
        is_entitled = host in entitled
        label = host if is_entitled else f"{host} (staging; not in iOS entitlements)"
        wk = fetch(f"https://{host}/.well-known/apple-app-site-association")
        status, detail, origin = check_aasa_response(wk, app_id)
        if not is_entitled and status == FAIL:
            status = WARN
        rows.append(row("iOS AASA", "/.well-known/apple-app-site-association", label, status, detail))
        legacy = fetch(f"https://{host}/apple-app-site-association")
        lstatus, ldetail, _ = check_aasa_response(legacy, app_id)
        if lstatus != PASS:
            if origin is not None:
                lstatus, ldetail = PASS, f"not an AASA ({ldetail}); unused while /.well-known is valid"
            elif not is_entitled:
                lstatus = WARN
        rows.append(row("iOS AASA", "/apple-app-site-association (legacy)", label, lstatus, ldetail))
        if origin is not None:
            path = aasa_dir / f"{host}.origin.json"
            path.write_bytes(wk["body"])
            ios_targets.append({"host": host, "source": "origin", "file": str(path.relative_to(out)), "entitled": is_entitled})

        cdn = fetch(APPLE_CDN + host)
        headers = cdn["headers"]
        meta = ", ".join(f"{k}: {headers[k]}" for k in ("age", "cache-control", "apple-from", "apple-failure-reason", "apple-failure-details") if k in headers)
        cstatus, cdetail, cdn_data = check_aasa_response(cdn, app_id)
        if cdn_data is not None:
            path = aasa_dir / f"{host}.cdn.json"
            path.write_bytes(cdn["body"])
            same = origin is not None and cdn_data == origin
            ios_targets.append({"host": host, "source": "cdn", "file": str(path.relative_to(out)), "entitled": is_entitled, "same_as_origin": same})
            if origin is None:
                cstatus, cdetail = WARN, f"CDN has a copy but the origin failed; {cdetail}"
            elif same:
                cstatus, cdetail = PASS, "identical to origin"
            else:
                cstatus, cdetail = WARN, "differs from origin (devices use the CDN copy until it refreshes): " + aasa_diff(cdn_data, origin, app_id)
        elif not is_entitled:
            cstatus = WARN
        rows.append(row("Apple CDN", "app-site-association.cdn-apple.com copy", label, cstatus, f"{cdetail} ({meta or 'no cache headers'})"))

    # 4. Android: assetlinks.json, Google's Digital Asset Links API, URL matrix.
    expected_fps = android_upload_fingerprints() + cfg["android_extra_fingerprints"]
    pkg = cfg["android_package"]
    dal_ok_hosts: set[str] = set()
    for host in verified_hosts + preview_hosts:
        is_verified = host in verified_hosts
        label = host if is_verified else f"{host} (alpha flavor; no autoVerify)"
        resp = fetch(f"https://{host}/.well-known/assetlinks.json")
        data, err = parse_json(resp["body"]) if resp.get("status") == 200 else (None, resp.get("error") or f"HTTP {resp.get('status')}")
        if data is None:
            status, detail = FAIL, f"{err}; {resp.get('headers', {}).get('location', '')}".strip("; ")
        else:
            fps = {
                colon_fp(fp)
                for st in data if isinstance(st, dict)
                if HANDLE_ALL_URLS in (st.get("relation") or []) and (st.get("target") or {}).get("package_name") == pkg
                for fp in (st.get("target") or {}).get("sha256_cert_fingerprints") or []
            }
            missing = [f"{e['role']}" for e in expected_fps if e["sha256"] not in fps]
            ctype = resp["headers"].get("content-type", "")
            if missing or "json" not in ctype:
                status = FAIL
                detail = (f"package {pkg}: missing {', '.join(missing)}" if missing else "") + ("" if "json" in ctype else f" content-type {ctype}")
            else:
                status, detail = PASS, f"200, {ctype}, {pkg} with all {len(expected_fps)} expected fingerprints ({len(fps)} listed)"
        if not is_verified and status == FAIL:
            status = WARN
        rows.append(row("Android", "/.well-known/assetlinks.json", label, status, detail.strip()))

        # Google's own reading of the file (what the Play / verifier side sees).
        q = urllib.parse.urlencode({"source.web.site": f"https://{host}", "relation": HANDLE_ALL_URLS})
        dal = fetch(DAL_API + "statements:list?" + q, follow=True)
        listed, derr = parse_json(dal["body"]) if dal.get("status") == 200 else (None, dal.get("error") or f"HTTP {dal.get('status')}")
        if listed is None:
            dstatus, ddetail = WARN, f"statements:list failed: {derr}"
        else:
            got = {colon_fp(((s.get("target") or {}).get("androidApp") or {}).get("certificate", {}).get("sha256Fingerprint", ""))
                   for s in listed.get("statements") or [] if ((s.get("target") or {}).get("androidApp") or {}).get("packageName") == pkg}
            checks = []
            for e in expected_fps:
                cq = urllib.parse.urlencode({
                    "source.web.site": f"https://{host}", "relation": HANDLE_ALL_URLS,
                    "target.android_app.package_name": pkg, "target.android_app.certificate.sha256_fingerprint": e["sha256"],
                })
                cr = fetch(DAL_API + "assetlinks:check?" + cq, follow=True)
                cd, _ = parse_json(cr["body"]) if cr.get("status") == 200 else (None, None)
                checks.append((e["role"], bool(cd and cd.get("linked"))))
            unlinked = [r for r, ok in checks if not ok]
            missing = [e["role"] for e in expected_fps if e["sha256"] not in got]
            if unlinked or missing:
                dstatus = FAIL if is_verified else WARN
                ddetail = f"not linked: {', '.join(unlinked or missing)}; errors: {(listed.get('debugString') or '')[:160]}"
            else:
                dstatus, ddetail = PASS, f"statements:list has {len(got)} {pkg} fingerprints; assetlinks:check linked for all {len(checks)}"
                dal_ok_hosts.add(host)
        rows.append(row("Android", "Digital Asset Links API (statements:list + assetlinks:check)", label, dstatus, ddetail))

    android_matrix = []
    failures = []
    for u in matrix:
        cells = {}
        for host in verified_hosts:
            outcome, why = android_outcome(main_filters, f"https://{host}{u['path']}", dal_ok_hosts)
            ok = None if u.get("informational") else outcome == u["expect"]["android"]
            cells[host] = {"outcome": outcome, "detail": why, "ok": ok}
            if ok is False:
                failures.append(f"{u['id']}@{host}")
        android_matrix.append({"id": u["id"], "label": u["label"], "path": u["path"], "expect": u["expect"]["android"], "cells": cells})
    total = sum(1 for u in matrix if not u.get("informational")) * len(verified_hosts)
    rows.append(row(
        "Android", "URL matrix vs main AndroidManifest.xml intent filters", ", ".join(verified_hosts),
        FAIL if failures else PASS,
        (f"{total - len(failures)}/{total} as expected" + (f"; wrong: {', '.join(failures)}" if failures else "")),
    ))

    result = {
        "step": "web",
        "meta": {
            "app_id": app_id, "team_id_source": cfg["apple_team_id_source"], "bundle_id": bundle,
            "ios_hosts": entitled, "android_verified_hosts": verified_hosts, "preview_hosts": preview_hosts,
            "android_package": pkg, "android_fingerprints": expected_fps, "android_verified_filters": [f for f in main_filters if f["auto_verify"]],
        },
        "rows": rows,
        "ios_targets": ios_targets,
        "android_matrix": {"columns": verified_hosts, "rows": android_matrix},
    }
    (out / "web.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


# ---------------------------------------------------------------- iOS step (swcutil)


def parse_swcutil(stdout: str, app_id: str) -> tuple[str | None, str]:
    """Map `swcutil verify` output to app/web for one app ID (format: TN3155).

    Only the `applinks` service decides whether a link opens the app. An AASA that also has
    `webcredentials` (passkeys, password autofill) makes swcutil print a second block,
    `{ s = webcredentials, a = <app>, d = <host> }`, which "matches" every URL of the host; that is
    credential sharing, not a universal link, and is ignored here (2026-11-21: it turned `/`,
    `/login` and `/s/<slug>/settings` into false "opens the app" failures). A block that names no
    service is treated as applinks.
    """
    current, verdicts, loose, other_services = None, {}, [], set()
    for line in stdout.splitlines():
        line = line.strip()
        header = re.match(r"(\{[^}]*\ba = ([^,}\s]+)[^}]*\}):?\s*(.*)$", line)
        rest = line
        if header:
            service = re.search(r"\bs = ([^,}\s]+)", header.group(1))
            if service and service.group(1) != "applinks":
                current = False  # a block of another service: skip its verdict lines
                other_services.add(service.group(1))
            else:
                current = header.group(2)
            rest = header.group(3)
            if not rest:
                continue
        if current is False:
            continue
        for needle, outcome in (("blocked match", "web"), ("did not match", "web"), ("matched", "app")):
            if needle in rest:
                (verdicts.setdefault(current, []) if current else loose).append((outcome, line))
                break
    if app_id in verdicts:
        return verdicts[app_id][0][0], verdicts[app_id][0][1]
    if verdicts:
        return "web", f"no verdict for {app_id} (only {', '.join(k for k in verdicts if k)})"
    if loose:
        return loose[0][0], loose[0][1]
    if other_services:
        return "web", f"no applinks verdict (only {', '.join(sorted(other_services))})"
    return None, (stdout.strip().splitlines() or ["(no output)"])[-1][:200]


def run_swcutil(host: str, json_path: Path, url: str, app_id: str) -> tuple[str | None, str]:
    cmd = ["sudo", "-n", "swcutil", "verify", "-d", host, "-j", str(json_path), "-u", url]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"
    outcome, line = parse_swcutil(p.stdout + "\n" + p.stderr, app_id)
    if outcome is None:
        return None, f"exit {p.returncode}: {line}"
    return outcome, line


SELF_TEST = [
    # (fixture, path, expected). fixtures/aasa-prod-2026-11-17.json is app.raft.build's file as served
    # from 2026-11-17 02:10Z (it blocks every DM link on iOS); fixtures/aasa-slock-15228.json is the
    # staging file after botiverse/slock #15228 (excludes end in `/?*`), which opens them.
    ("aasa-prod-2026-11-17.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9", "web"),
    ("aasa-prod-2026-11-17.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9?msg=9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d", "web"),
    ("aasa-prod-2026-11-17.json", "/download", "app"),
    ("aasa-slock-15228.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9", "app"),
    ("aasa-slock-15228.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9?msg=9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d", "app"),
    ("aasa-slock-15228.json", "/s/oranixslocktest/dm/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9/extra", "web"),
]


def cmd_ios(args) -> int:
    web_dir = Path(args.web)
    web = load_json(web_dir / "web.json")
    app_id = web["meta"]["app_id"]
    matrix = load_json(HERE / "url_matrix.json")["urls"]
    rows = []
    version = "not macOS"
    if shutil.which("sw_vers"):
        sw = lambda flag: subprocess.run(["sw_vers", flag], capture_output=True, text=True).stdout.strip()
        version = f"macOS {sw('-productVersion')} ({sw('-buildVersion')})"

    # Self-test: proves this swcutil reproduces the 11-17 bug class before trusting the matrix.
    st_fail = []
    for fixture, path, expected in SELF_TEST:
        got, line = run_swcutil("app.raft.build", HERE / "fixtures" / fixture, f"https://app.raft.build{path}", app_id)
        if got != expected:
            st_fail.append(f"{fixture} {path}: expected {expected}, got {got} ({line})")
    rows.append(row(
        "iOS matcher", "swcutil self-test (11-17 prod AASA blocks DM links; slock #15228 opens them)", version,
        FAIL if st_fail else PASS,
        "; ".join(st_fail) if st_fail else f"{len(SELF_TEST)}/{len(SELF_TEST)} pinned cases as expected",
    ))
    rows[-1]["self_test"] = True

    columns, results = [], {}
    targets = list(web["ios_targets"])
    if args.candidate:
        # A candidate AASA (workflow input candidate_aasa_ref): the same matrix against a file not
        # served yet, matched as if app.raft.build served it, beside the live columns.
        targets.append({"host": "app.raft.build", "source": "candidate", "file": str(Path(args.candidate).resolve()), "entitled": True})
    for t in targets:
        col = f"{t['host']} {dict(cdn='CDN', candidate='candidate').get(t['source'], 'origin')}"
        columns.append({"key": col, **t})
        if t.get("same_as_origin"):
            continue  # filled from the origin column below
        for u in matrix:
            results[(col, u["id"])] = run_swcutil(t["host"], web_dir / t["file"], f"https://{t['host']}{u['path']}", app_id)
    for c in columns:
        if c.get("same_as_origin"):
            origin_col = f"{c['host']} origin"
            for u in matrix:
                outcome, line = results[(origin_col, u["id"])]
                results[(c["key"], u["id"])] = (outcome, "same file as origin: " + line)

    matrix_rows = []
    for u in matrix:
        cells = {}
        for c in columns:
            outcome, line = results[(c["key"], u["id"])]
            ok = None if u.get("informational") else outcome == u["expect"]["ios"]
            cells[c["key"]] = {"outcome": outcome, "detail": line, "ok": ok}
        matrix_rows.append({"id": u["id"], "label": u["label"], "path": u["path"], "expect": u["expect"]["ios"], "cells": cells})
    for c in columns:
        wrong = [r["id"] for r in matrix_rows if r["cells"][c["key"]]["ok"] is False]
        errors = [r["id"] for r in matrix_rows if r["cells"][c["key"]]["outcome"] is None and r["cells"][c["key"]]["ok"] is not None]
        counted = sum(1 for u in matrix if not u.get("informational"))
        status = PASS if not wrong else (FAIL if c["entitled"] and not errors else WARN)
        if not c["entitled"] and status == FAIL:
            status = WARN
        source = {"cdn": "Apple CDN copy (what devices install)", "candidate": "candidate file (not served)"}.get(c["source"], "origin file")
        detail = f"{counted - len(wrong)}/{counted} as expected"
        if wrong:
            detail += "; wrong: " + ", ".join(f"`{w}`" for w in wrong)
        if errors:
            detail += "; swcutil gave no verdict: " + ", ".join(errors)
        target = c["host"] if c["entitled"] else f"{c['host']} (staging; not in iOS entitlements)"
        rows.append(row("iOS URL matrix (swcutil)", source, target, status, detail))

    result = {"step": "ios", "rows": rows, "ios_matrix": {"columns": [c["key"] for c in columns], "rows": matrix_rows}}
    Path(args.out).write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


# ---------------------------------------------------------------- builds step (Hands)


class Hands:
    def __init__(self, token: str, api: str = "https://hands.build") -> None:
        self.token, self.api = token, api.rstrip("/")

    def get(self, path: str) -> dict:
        req = urllib.request.Request(self.api + path, headers={
            "Authorization": f"Bearer {self.token}", "Accept": "application/json", "User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.load(resp)

    def latest_published(self, slug: str, filetype: str, statuses=("active", "superseded")) -> tuple[dict, dict, Path]:
        app = next((a for a in self.get("/api/apps").get("apps", []) if a.get("slug") == slug), None)
        if not app:
            raise RuntimeError(f"app {slug} not visible to this token")
        builds = {b["id"]: b for b in self.get(f"/api/apps/{app['id']}/builds?limit=200").get("builds", [])}
        releases = [
            r for r in self.get(f"/api/apps/{app['id']}/releases?limit=200").get("releases", [])
            if r.get("status") in statuses and (r.get("channel") or "main") == "main" and r.get("build_id") in builds
        ]
        if not releases:
            raise RuntimeError(f"no published main release of {slug}")
        release = max(releases, key=lambda r: int(builds[r["build_id"]].get("version_code") or 0))
        build = builds[release["build_id"]]
        detail = self.get(f"/api/apps/{app['id']}/releases/{release['id']}")
        assets = [a for a in detail.get("assets", []) if a.get("filetype") == filetype
                  or str(a.get("file_name") or a.get("filename") or "").endswith("." + filetype)]
        if not assets:
            raise RuntimeError(f"release {release['id']} has no {filetype} asset")
        asset = assets[0]
        signed = self.get(f"/api/apps/{app['id']}/builds/{build['id']}/assets/{asset['id']}/download?presign=1")
        url = signed.get("url") or signed.get("download_url")
        if not url:
            raise RuntimeError("Hands returned no download URL")
        dest = Path(tempfile.mkdtemp(prefix="applink-")) / f"{slug}.{filetype}"
        digest = hashlib.sha256()
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=900) as resp, dest.open("wb") as fh:
            for chunk in iter(lambda: resp.read(1 << 20), b""):
                digest.update(chunk)
                fh.write(chunk)
        expected = (asset.get("file_hash") or asset.get("sha256") or "").lower()
        if expected and digest.hexdigest() != expected:
            raise RuntimeError(f"{filetype} SHA-256 {digest.hexdigest()} != Hands {expected}")
        return build, release, dest


def build_tools() -> Path | None:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home or not (Path(home) / "build-tools").is_dir():
        return None
    versions = sorted((Path(home) / "build-tools").iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    return versions[-1] if versions else None


def ios_entitlements_from_binary(blob: bytes) -> list[dict]:
    """XML entitlements plists embedded in a Mach-O code signature (blob magic 0xfade7171)."""
    out = []
    for m in re.finditer(rb"<\?xml[^>]*\?>\s*(?:<!DOCTYPE[^>]*>\s*)?<plist.*?</plist>", blob, re.S):
        chunk = m.group(0)
        if b"application-identifier" in chunk or b"associated-domains" in chunk:
            try:
                out.append(plistlib.loads(chunk))
            except Exception:
                pass
    return out


def cmd_builds(args) -> int:
    web = load_json(Path(args.web) / "web.json")
    meta = web["meta"]
    rows = []
    live_fps = {e["sha256"] for e in meta["android_fingerprints"]}

    token = os.environ.get("HANDS_ANDROID_TOKEN", "")
    if not token:
        rows.append(row("Latest builds", "Android APK on Hands", "raft-android", WARN, "skipped: no Hands token in this run (fork PR or secret not exposed)"))
    else:
        try:
            build, release, apk = Hands(token).latest_published("raft-android", "apk")
            label = f"{build.get('version_name')} ({build.get('version_code')})"
            bt = build_tools()
            if not bt:
                raise RuntimeError("no Android build-tools on this runner")
            certs = subprocess.run([str(bt / "apksigner"), "verify", "--print-certs", "-v", str(apk)], capture_output=True, text=True)
            digests = sorted({colon_fp(d) for d in re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs.stdout)})
            unknown = [d for d in digests if d not in live_fps]
            rows.append(row("Latest builds", "APK signer certificates are in assetlinks.json", f"raft-android {label}",
                            FAIL if unknown or not digests else PASS,
                            f"signers {len(digests)}; not in assetlinks: {unknown}" if unknown or not digests else f"all {len(digests)} signer certificates listed"))
            xml = subprocess.run([str(bt / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], capture_output=True, text=True)
            shipped = [f for f in web_filters(parse_aapt_xmltree(xml.stdout)) if f["auto_verify"]]
            want = meta["android_verified_filters"]
            norm = lambda fs: sorted(json.dumps({"hosts": f["hosts"], "paths": sorted(map(list, f["paths"]))}, sort_keys=True) for f in fs)
            if not shipped:
                rows.append(row("Latest builds", "APK autoVerify intent filters", f"raft-android {label}", FAIL, "no autoVerify web intent filter in the shipped manifest"))
            elif norm(shipped) == norm(want):
                rows.append(row("Latest builds", "APK autoVerify intent filters", f"raft-android {label}", PASS, "same hosts and paths as main's manifest: " + "; ".join(f"{','.join(f['hosts'])} {describe_paths(f)}" for f in shipped)))
            else:
                rows.append(row("Latest builds", "APK autoVerify intent filters", f"raft-android {label}", WARN,
                                "differs from main's manifest (expected when main moved on): shipped " + "; ".join(f"{','.join(f['hosts'])} {describe_paths(f)}" for f in shipped)))
            shutil.rmtree(apk.parent, ignore_errors=True)
        except Exception as e:
            rows.append(row("Latest builds", "Android APK on Hands", "raft-android", WARN, f"skipped: {type(e).__name__}: {str(e)[:200]}"))

    token = os.environ.get("HANDS_IOS_TOKEN", "")
    if not token:
        rows.append(row("Latest builds", "iOS IPA on Hands", "raft-ios", WARN, "skipped: no Hands token in this run (fork PR or secret not exposed)"))
    else:
        try:
            # iOS ships through TestFlight / the App Store; its Hands release stays a draft
            # (ios-release.yml SLOCK_HANDS_DRAFT=1), so drafts count here.
            build, release, ipa = Hands(token).latest_published("raft-ios", "ipa", ("active", "superseded", "draft"))
            label = f"{build.get('version_name')} ({build.get('version_code')}, Hands release {release.get('status')})"
            with zipfile.ZipFile(ipa) as z:
                info_name = next(n for n in z.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", n))
                info = plistlib.loads(z.read(info_name))
                ents = ios_entitlements_from_binary(z.read(info_name.rsplit("/", 1)[0] + "/" + info["CFBundleExecutable"]))
            ent = next((e for e in ents if "application-identifier" in e), ents[0] if ents else {})
            domains = ent.get("com.apple.developer.associated-domains") or []
            app_identifier = ent.get("application-identifier") or ""
            hosts = sorted(d.split(":", 1)[1].split("?", 1)[0] for d in domains if d.startswith("applinks:"))
            problems = []
            if app_identifier != meta["app_id"]:
                problems.append(f"application-identifier {app_identifier or '(none)'} != AASA appID {meta['app_id']}")
            if hosts != sorted(meta["ios_hosts"]):
                problems.append(f"applinks {hosts} != repo entitlements {sorted(meta['ios_hosts'])}")
            if any("mode=developer" in d for d in domains):
                problems.append("a shipped domain uses ?mode=developer")
            rows.append(row("Latest builds", "IPA associated-domains entitlement", f"raft-ios {label}",
                            FAIL if problems else PASS,
                            "; ".join(problems) if problems else f"{app_identifier}; applinks: {', '.join(hosts)}"))
            shutil.rmtree(ipa.parent, ignore_errors=True)
        except Exception as e:
            rows.append(row("Latest builds", "iOS IPA on Hands", "raft-ios", WARN, f"skipped: {type(e).__name__}: {str(e)[:200]}"))

    Path(args.out).write_text(json.dumps({"step": "builds", "rows": rows}, indent=2), encoding="utf-8")
    return 0


# ---------------------------------------------------------------- report


CANNOT_CHECK = [
    "Device-side state: each iPhone keeps the AASA it got from Apple's CDN at install time and refreshes it about once a week (TN3155); `swcutil show` / a sysdiagnose on the device is the only way to see that copy. A fixed file reaches devices only after the CDN refreshes **and** the device re-checks (reinstalling the app forces it).",
    "Per-device association state: the user's own choice (long-press > Open in Safari, or Android's per-app \"Open supported links\" setting), and Android's per-device verification result (`adb shell pm get-app-links build.raft.app`).",
    "Where the link is tapped: Universal Links do not fire on a link typed into Safari, on a same-domain navigation, or in some in-app browsers/WebViews; Android browsers' own intent handling varies.",
    "Networks that block or rewrite `app-site-association.cdn-apple.com` or `digitalassetlinks.googleapis.com` (MDM, VPN, corporate proxies, China networks), and MDM-managed associated domains.",
    "What the app does after it opens: this checks that the OS hands the URL to the app, not that the app routes it to the right screen.",
]

ICON = {PASS: "✅ PASS", FAIL: "❌ FAIL", WARN: "⚠️ WARN", SKIP: "⏭️ SKIP"}


def md(text: str) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def render(results: list[dict], context: dict) -> str:
    rows = [r for res in results for r in res.get("rows", [])]
    counts = {s: sum(1 for r in rows if r["status"] == s) for s in (FAIL, WARN, PASS, SKIP)}
    web = next((r for r in results if r.get("step") == "web"), {})
    meta = web.get("meta", {})
    lines = ["## App link diagnose", ""]
    lines.append(f"**{counts[FAIL]} FAIL · {counts[WARN]} WARN · {counts[PASS]} PASS**" + (f" · {context['when']}" if context.get("when") else ""))
    if meta:
        lines += ["", f"iOS appID `{meta['app_id']}` (bundle from project.pbxproj; Team ID from scripts/app_links/config.json) · iOS hosts {', '.join(meta['ios_hosts'])} · Android `{meta['android_package']}` verified hosts {', '.join(meta['android_verified_hosts'])} · staging {', '.join(meta['preview_hosts']) or '-'}"]
    missing = {"web", "ios", "builds"} - {r.get("step") for r in results}
    if missing:
        lines += ["", f"⚠️ No result from step(s): {', '.join(sorted(missing))} (job failed or skipped; see the run log)."]
    lines += ["", "| Area | Check | Target | Result | Detail |", "| --- | --- | --- | --- | --- |"]
    for r in rows:
        lines.append(f"| {md(r['section'])} | {md(r['check'])} | {md(r['target'])} | {ICON.get(r['status'], r['status'])} | {md(r['detail'])} |")

    def matrix_table(title: str, m: dict, note: str):
        if not m or not m.get("rows"):
            return
        cols = m["columns"]
        lines.extend(["", f"### {title}", "", note, "", "| URL path | expect | " + " | ".join(cols) + " |", "| --- | --- | " + " | ".join("---" for _ in cols) + " |"])
        for r in m["rows"]:
            cells = []
            for c in cols:
                cell = r["cells"].get(c, {})
                outcome = cell.get("outcome") or "?"
                mark = "ℹ️ " if cell.get("ok") is None else ("✅ " if cell["ok"] else "❌ ")
                cells.append(mark + outcome)
            expect = "probe" if all(c.get("ok") is None for c in r["cells"].values()) else r["expect"]
            lines.append(f"| `{md(r['path'])}`<br>{md(r['label'])} | {expect} | " + " | ".join(cells) + " |")

    ios = next((r for r in results if r.get("step") == "ios"), {})
    matrix_table(
        "iOS URL matrix (Apple `swcutil verify`)", ios.get("ios_matrix"),
        "`app` = opens the app, `web` = stays in Safari. Apple's own matcher, so it applies iOS's trailing-slash "
        f"normalisation of the path and of patterns ending in neither `/` nor `*` ([slock #15228]({TRAILING_SLASH_SOURCE})). "
        "The CDN column is what devices install.",
    )
    matrix_table(
        "Android URL matrix (main AndroidManifest.xml)", web.get("android_matrix"),
        "`app` = a verified App Link (autoVerify filter + assetlinks verified via Google's API), `web` = browser on Android 12+.",
    )
    lines += ["", "### What this cannot check", ""] + [f"- {t}" for t in CANNOT_CHECK]
    if context.get("run_url"):
        lines += ["", f"Run: {context['run_url']}"]
    return "\n".join(lines) + "\n"


STEP_ORDER = ["web", "ios", "builds"]


def cmd_report(args) -> int:
    results = []
    for d in args.inputs:
        for p in sorted(Path(d).rglob("*.json")):
            try:
                data = load_json(p)
            except Exception:
                continue
            if isinstance(data, dict) and data.get("step") in STEP_ORDER:
                results.append(data)
    results.sort(key=lambda r: STEP_ORDER.index(r["step"]))
    context = {"when": args.when, "run_url": args.run_url}
    text = render(results, context)
    Path(args.summary).write_text(text, encoding="utf-8")
    rows = [r for res in results for r in res.get("rows", [])]
    Path(args.json).write_text(json.dumps({"context": context, "results": results}, indent=2), encoding="utf-8")
    print(text)
    self_test_failed = any(r.get("self_test") and r["status"] == FAIL for r in rows)
    steps = {r.get("step") for r in results}
    if self_test_failed or not {"web", "ios"} <= steps:
        print("::error::app link diagnose could not run its own checks (swcutil self-test failed or a step produced no result)")
        return 2
    if args.fail_on_fail and any(r["status"] == FAIL for r in rows):
        print("::error::app link diagnose found FAIL rows")
        return 1
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("web")
    w.add_argument("--out", required=True)
    i = sub.add_parser("ios")
    i.add_argument("--web", required=True, help="directory with web.json and aasa/")
    i.add_argument("--out", required=True)
    i.add_argument("--candidate", default="", help="also run the matrix against this AASA file (as app.raft.build)")
    b = sub.add_parser("builds")
    b.add_argument("--web", required=True)
    b.add_argument("--out", required=True)
    r = sub.add_parser("report")
    r.add_argument("inputs", nargs="+")
    r.add_argument("--summary", required=True)
    r.add_argument("--json", required=True)
    r.add_argument("--when", default="")
    r.add_argument("--run-url", default="")
    r.add_argument("--fail-on-fail", action="store_true")
    args = p.parse_args(argv)
    return {"web": cmd_web, "ios": cmd_ios, "builds": cmd_builds, "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
