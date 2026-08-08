#!/usr/bin/env python3
"""Kuikly release completion-manifest gate (task #34, contract v0.3).

Consumer-side gate for the Kuikly release completion manifest. The gate is the
only machine answer to "is this Kuikly release complete and consumable":

  ALL_COMPLETE_EXACT   manifest present, every listed path GETs back byte-exact
  ALL_ABSENT           locator 404 and every owned coordinate absent (unpublished)
  PARTIAL              some versioned objects exist but no manifest (incomplete)
  CONFLICT             list/GET mismatch, unexpected primary, orphan/unclassified,
                       or manifest present but a listed path absent/mismatched

Exit codes:
  0  ALL_COMPLETE_EXACT (consumable)
  10 ALL_ABSENT (unpublished; fail-closed for consumers)
  20 PARTIAL (fail before dependency resolution)
  30 CONFLICT (incident; manifest schema/consistency failures land here too)
  40 usage error (bad/missing closure file)

Contract: task #33 canonical (msg db455f74 / attachment 4c1fd501, sha256
b14f5a24). The locator convention (kuikly-release-manifest:<release> as the
sole completion marker, 404 = unpublished, no fallback) was authorized by
artin 2026-08-08 (msg c0ed6c52).

Self-test: --self-test exercises all four states plus invalid-schema against a
directory-based fake registry; no network required.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

STATE_COMPLETE = "ALL_COMPLETE_EXACT"
STATE_ABSENT = "ALL_ABSENT"
STATE_PARTIAL = "PARTIAL"
STATE_CONFLICT = "CONFLICT"

EXIT_COMPLETE = 0
EXIT_ABSENT = 10
EXIT_PARTIAL = 20
EXIT_CONFLICT = 30
EXIT_INVALID = 40

GROUP_PATH = "com/tencent/kuikly-open"
MANIFEST_KIND = "manifest"

ALLOWED_TARGETS = {"kmp-metadata", "jvm-tooling", "android", "ios", "ohos"}
ALLOWED_KINDS = {
    "pom", "gradle-module", "jar", "aar", "klib", "sources",
    "tooling-metadata", "kotlin-resources", "xcframework-zip",
    "podspec-json", "har", "provenance-json",
}


def log(msg: str) -> None:
    print(f"kuikly-release-gate: {msg}")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def locator_path(release: str) -> str:
    return f"{GROUP_PATH}/kuikly-release-manifest/{release}/kuikly-release-manifest-{release}.json"


class Registry:
    """Read-only registry access. Two backends: http (production) and dir
    (self-test fixture)."""

    def __init__(self, base: str, offline_root: str = None):
        self.base = base.rstrip("/")
        self.offline_root = offline_root

    def get_bytes(self, rel_path: str):
        """Return (found: bool, bytes_or_none, status: int)."""
        if self.offline_root is not None:
            p = os.path.join(self.offline_root, rel_path)
            if os.path.isfile(p):
                with open(p, "rb") as f:
                    return True, f.read(), 200
            return False, None, 404
        url = f"{self.base}/{rel_path}"
        req = urllib.request.Request(url, method="GET")

        class _NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None

        opener = urllib.request.build_opener(_NoRedirect)
        try:
            with opener.open(req, timeout=60) as resp:
                return True, resp.read(), resp.status
        except urllib.error.HTTPError as e:
            return False, None, e.code
        except urllib.error.URLError as e:
            raise RuntimeError(f"registry unreachable: {url}: {e}") from e

    def list_prefix(self, prefix: str):
        """Return the list of object paths under prefix (paginated to the end
        against a real registry). The dir backend walks the tree."""
        if self.offline_root is not None:
            root = os.path.join(self.offline_root, prefix)
            out = []
            if not os.path.isdir(root):
                return out
            for dirpath, _, files in os.walk(root):
                for fn in files:
                    full = os.path.join(dirpath, fn)
                    out.append(os.path.relpath(full, self.offline_root))
            return sorted(out)
        # Production pagination: registry list API is registry-specific; the
        # gate contract requires exhaustive pagination to the terminal page.
        # Placeholder until the registry list endpoint contract is bound.
        raise NotImplementedError(
            "production inventory listing requires the registry list endpoint"
        )


def validate_manifest(manifest: dict, expected_version: str, closure: list) -> list:
    """Return a list of consistency errors (empty = valid)."""
    errors = []
    if manifest.get("schema") != "kuikly-release-manifest/v1":
        errors.append(f"bad schema: {manifest.get('schema')!r}")
    if manifest.get("version") != expected_version:
        errors.append(
            f"manifest version {manifest.get('version')!r} != expected {expected_version!r}"
        )
    source = manifest.get("source") or {}
    for field in ("commit", "tree"):
        val = source.get(field)
        if not (isinstance(val, str) and len(val) == 40 and all(c in "0123456789abcdef" for c in val)):
            errors.append(f"source.{field} not a 40-hex lowercase SHA: {val!r}")
    if not source.get("tag"):
        errors.append("source.tag missing")
    pubs = manifest.get("publications")
    if not isinstance(pubs, list) or not pubs:
        errors.append("publications missing or empty")
        return errors
    seen_paths = set()
    seen_gavs = set()
    for pub in pubs:
        coord = pub.get("coordinate")
        path = pub.get("path")
        target = pub.get("target")
        kind = pub.get("kind")
        sha = pub.get("sha256")
        nbytes = pub.get("bytes")
        if target == MANIFEST_KIND:
            errors.append("manifest must not list itself in publications")
            continue
        if not coord or not path:
            errors.append(f"publication missing coordinate/path: {pub!r}")
            continue
        if target not in ALLOWED_TARGETS:
            errors.append(f"unknown target {target!r} in {coord}")
        if kind not in ALLOWED_KINDS:
            errors.append(f"unknown kind {kind!r} in {coord}")
        if not (isinstance(sha, str) and len(sha) == 64 and all(c in "0123456789abcdef" for c in sha)):
            errors.append(f"bad sha256 in {coord}: {sha!r}")
        if not (isinstance(nbytes, int) and not isinstance(nbytes, bool) and nbytes >= 0):
            errors.append(f"bad bytes in {coord}: {nbytes!r}")
        if path in seen_paths:
            errors.append(f"duplicate path {path!r}")
        seen_paths.add(path)
        seen_gavs.add(coord)
        if not path.startswith(GROUP_PATH + "/"):
            errors.append(f"path {path!r} outside {GROUP_PATH}")
        if not os.path.basename(path).startswith(coord.split(":")[1]):
            errors.append(f"filename does not begin with artifact id: {path!r}")
    # closure check: unique publication GAVs must equal the expected closure
    expected_gavs = set(closure)
    if seen_gavs != expected_gavs:
        missing = expected_gavs - seen_gavs
        extra = seen_gavs - expected_gavs
        if missing:
            errors.append(f"closure missing coordinates: {sorted(missing)}")
        if extra:
            errors.append(f"unexpected coordinates beyond closure: {sorted(extra)}")
    # set digest
    set_sha = manifest.get("set_sha256")
    if not (isinstance(set_sha, str) and len(set_sha) == 64):
        errors.append(f"bad set_sha256: {set_sha!r}")
    else:
        entries = sorted(
            (p["path"], p["sha256"], p["bytes"])
            for p in pubs
            if p.get("path") and p.get("sha256") is not None and p.get("bytes") is not None
        )
        payload = b"".join(
            f"{p}\x00{s}\x00{b}\n".encode("utf-8") for (p, s, b) in entries
        )
        recomputed = hashlib.sha256(payload).hexdigest()
        if recomputed != set_sha:
            errors.append(f"set_sha256 mismatch: manifest {set_sha} != recomputed {recomputed}")
    return errors


def verify_publications(registry: Registry, manifest: dict) -> list:
    """GET every listed publication and verify bytes/hash. Returns errors."""
    errors = []
    for pub in manifest.get("publications", []):
        if pub.get("target") == MANIFEST_KIND:
            continue
        path = pub.get("path")
        found, data, status = registry.get_bytes(path)
        if not found:
            errors.append(f"listed path absent on readback: {path} (HTTP {status})")
            continue
        if len(data) != pub.get("bytes"):
            errors.append(f"byte count mismatch for {path}: {len(data)} != {pub.get('bytes')}")
        actual = hashlib.sha256(data).hexdigest()
        if actual != pub.get("sha256"):
            errors.append(f"sha256 mismatch for {path}: {actual} != {pub.get('sha256')}")
    return errors


def preflight(registry: Registry, release: str, closure: list):
    """Four-state preflight. Returns (state, errors)."""
    errors = []
    locator = locator_path(release)
    found, manifest_bytes, status = registry.get_bytes(locator)
    if not found and status not in (404,):
        errors.append(f"locator GET failed with unexpected status {status}")
        return STATE_CONFLICT, errors
    if found:
        try:
            manifest = json.loads(manifest_bytes.decode("utf-8"))
        except Exception as e:
            errors.append(f"manifest not parseable JSON: {e}")
            return STATE_CONFLICT, errors
        verrs = validate_manifest(manifest, release, closure)
        if verrs:
            errors.extend(verrs)
            return STATE_CONFLICT, errors
        perrs = verify_publications(registry, manifest)
        if perrs:
            errors.extend(perrs)
            return STATE_CONFLICT, errors
        return STATE_COMPLETE, errors
    # manifest absent: classify by inventory presence
    present = []
    try:
        inventory = registry.list_prefix(GROUP_PATH)
    except NotImplementedError:
        # Production listing unbound: with manifest absent we cannot prove
        # ALL_ABSENT; treat conservatively as PARTIAL (fail-closed).
        errors.append("locator absent and inventory listing unavailable; fail closed as PARTIAL")
        return STATE_PARTIAL, errors
    import re as _re
    version_re = _re.compile(_re.escape(f"/{release}") + r"(-|/)")
    versioned = [p for p in inventory if version_re.search(p) and p != locator]
    unexpected = [p for p in versioned if not any(p.startswith(f"{GROUP_PATH}/{c.split(':')[1]}/") for c in closure)]
    if unexpected:
        errors.append(f"unexpected versioned objects outside closure: {unexpected}")
        return STATE_CONFLICT, errors
    if not versioned:
        return STATE_ABSENT, errors
    errors.append(f"locator absent but {len(versioned)} versioned objects present")
    return STATE_PARTIAL, errors


def run_gate(release: str, registry: Registry, closure: list) -> int:
    state, errors = preflight(registry, release, closure)
    for e in errors:
        log(f"ERROR: {e}")
    log(f"state={state}")
    return {
        STATE_COMPLETE: EXIT_COMPLETE,
        STATE_ABSENT: EXIT_ABSENT,
        STATE_PARTIAL: EXIT_PARTIAL,
        STATE_CONFLICT: EXIT_CONFLICT,
    }[state]


# ---------------- self-test ----------------

def _write(root: str, rel: str, data: bytes) -> None:
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)


def _make_manifest(release: str, pubs: list) -> dict:
    entries = sorted((p["path"], p["sha256"], p["bytes"]) for p in pubs)
    payload = b"".join(f"{p}\x00{s}\x00{b}\n".encode("utf-8") for (p, s, b) in entries)
    return {
        "schema": "kuikly-release-manifest/v1",
        "version": release,
        "source": {
            "commit": "a" * 40,
            "tree": "b" * 40,
            "tag": f"kuikly-v{release}",
        },
        "set_sha256": hashlib.sha256(payload).hexdigest(),
        "publications": pubs,
    }


def self_test() -> int:
    import tempfile

    release = "2.21.0-raft.0"
    closure = [
        f"com.tencent.kuikly-open:core:{release}-2.1.21",
        f"com.tencent.kuikly-open:core-render-android:{release}-2.1.21",
    ]
    core_bytes = b"fake-core-jar-bytes"
    render_bytes = b"fake-render-aar-bytes"
    pubs = [
        {
            "coordinate": closure[0],
            "target": "kmp-metadata",
            "kind": "gradle-module",
            "path": f"{GROUP_PATH}/core/{release}-2.1.21/core-{release}-2.1.21.module",
            "sha256": hashlib.sha256(core_bytes).hexdigest(),
            "bytes": len(core_bytes),
        },
        {
            "coordinate": closure[1],
            "target": "android",
            "kind": "aar",
            "path": f"{GROUP_PATH}/core-render-android/{release}-2.1.21/core-render-android-{release}-2.1.21.aar",
            "sha256": hashlib.sha256(render_bytes).hexdigest(),
            "bytes": len(render_bytes),
        },
    ]
    manifest = _make_manifest(release, pubs)

    failures = []

    def check(name, expected_exit, build_root):
        root = build_root()
        reg = Registry(base="", offline_root=root)
        got = run_gate(release, reg, closure)
        if got != expected_exit:
            failures.append(f"{name}: expected exit {expected_exit}, got {got}")
        else:
            log(f"self-test arm {name}: OK (exit {got})")

    def root_complete():
        root = tempfile.mkdtemp()
        for pub in pubs:
            _write(root, pub["path"], core_bytes if "core-" in pub["path"] and "render" not in pub["path"] else render_bytes)
        _write(root, locator_path(release), json.dumps(manifest).encode())
        return root

    def root_absent():
        return tempfile.mkdtemp()

    def root_partial():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)  # one object, no manifest
        return root

    def root_conflict_missing_path():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)  # render object missing
        _write(root, locator_path(release), json.dumps(manifest).encode())
        return root

    def root_conflict_bad_bytes():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)
        _write(root, pubs[1]["path"], b"CORRUPTED-BYTES")
        _write(root, locator_path(release), json.dumps(manifest).encode())
        return root

    def root_conflict_unexpected():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)
        _write(root, pubs[1]["path"], render_bytes)
        _write(root, f"{GROUP_PATH}/rogue/{release}-2.1.21/rogue.jar", b"x")
        return root

    def root_invalid_schema():
        root = tempfile.mkdtemp()
        bad = dict(manifest)
        bad["schema"] = "wrong-schema"
        _write(root, pubs[0]["path"], core_bytes)
        _write(root, pubs[1]["path"], render_bytes)
        _write(root, locator_path(release), json.dumps(bad).encode())
        return root

    def root_closure_mismatch():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)
        _write(root, pubs[1]["path"], render_bytes)
        short = dict(manifest)
        short["publications"] = [pubs[0]]  # manifest missing one closure coordinate
        entries = sorted((pp["path"], pp["sha256"], pp["bytes"]) for pp in short["publications"])
        payload = b"".join(f"{pp}\x00{ss}\x00{bb}\n".encode("utf-8") for (pp, ss, bb) in entries)
        import hashlib as _h
        short["set_sha256"] = _h.sha256(payload).hexdigest()
        _write(root, locator_path(release), json.dumps(short).encode())
        return root

    def root_duplicate_path():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)
        _write(root, pubs[1]["path"], render_bytes)
        dup = dict(manifest)
        dup_pubs = list(pubs) + [dict(pubs[0])]
        entries = sorted(set((pp["path"], pp["sha256"], pp["bytes"]) for pp in dup_pubs))
        payload = b"".join(f"{pp}\x00{ss}\x00{bb}\n".encode("utf-8") for (pp, ss, bb) in entries)
        import hashlib as _h
        dup["publications"] = dup_pubs
        dup["set_sha256"] = _h.sha256(payload).hexdigest()
        _write(root, locator_path(release), json.dumps(dup).encode())
        return root

    check("complete", EXIT_COMPLETE, root_complete)
    check("absent", EXIT_ABSENT, root_absent)
    check("partial", EXIT_PARTIAL, root_partial)
    check("closure-mismatch", EXIT_CONFLICT, root_closure_mismatch)
    check("duplicate-path", EXIT_CONFLICT, root_duplicate_path)
    check("conflict-missing-path", EXIT_CONFLICT, root_conflict_missing_path)
    check("conflict-bad-bytes", EXIT_CONFLICT, root_conflict_bad_bytes)
    check("conflict-unexpected", EXIT_CONFLICT, root_conflict_unexpected)
    check("invalid-schema", EXIT_CONFLICT, root_invalid_schema)

    if failures:
        for f in failures:
            log(f"SELF-TEST FAILURE: {f}")
        return 1
    log("self-test: all arms OK")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", help="release id, e.g. 2.21.0-raft.0")
    parser.add_argument("--registry-base", help="registry base URL (production)")
    parser.add_argument("--offline-root", help="directory-based fake registry (testing)")
    parser.add_argument("--closure", help="JSON file: list of expected publication GAVs")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.release or not args.closure:
        parser.error("--release and --closure are required (or use --self-test)")
    with open(args.closure, "r", encoding="utf-8") as f:
        closure = json.load(f)
    if not isinstance(closure, list) or not closure:
        log("ERROR: closure file must be a non-empty JSON list of GAVs")
        return EXIT_INVALID
    registry = Registry(base=args.registry_base or "", offline_root=args.offline_root)
    return run_gate(args.release, registry, closure)


if __name__ == "__main__":
    sys.exit(main())
