#!/usr/bin/env python3
"""Kuikly release completion-manifest gate for the immutable 2.24 release.

Consumer-side gate for the Kuikly release completion manifest. In the default
admission mode, ALL_COMPLETE_EXACT means the immutable locator and complete
manifest identity/closure are exact; platform resolvers verify consumed bytes.
Without --admission-only, the audit mode additionally GETs and hashes all 920
listed objects and validates checksum sidecar content:

  ALL_COMPLETE_EXACT   manifest exact; in audit mode every listed byte is exact
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
import concurrent.futures
import hashlib
import json
import ssl
import os
import sys
import time
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
ALLOWED_KINDS = {
    "aar", "checksum-md5", "checksum-sha1", "checksum-sha256",
    "checksum-sha512", "cinterop-klib", "gradle-module", "har", "jar",
    "javadoc", "klib", "kotlin-resources", "metadata-jar", "podspec-json",
    "pom", "provenance-json", "sources", "tooling-metadata",
    "xcframework-zip",
}
CHECKSUM_KINDS = {
    "checksum-md5", "checksum-sha1", "checksum-sha256", "checksum-sha512",
}
CHECKSUM_ALGORITHMS = {
    "checksum-md5": "md5",
    "checksum-sha1": "sha1",
    "checksum-sha256": "sha256",
    "checksum-sha512": "sha512",
}
EXPECTED_FILE_COUNT = 920
EXPECTED_PRIMARY_COUNT = 184
EXPECTED_CHECKSUM_COUNT = 736
EXPECTED_PRODUCERS = {
    "ios-renderer", "normal-linux", "normal-macos", "ohos-gradle",
    "ohos-renderer",
}
USER_AGENT = "raft-mobile-kuikly-release-gate/1.0"


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
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT}, method="GET")

        class _NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None

        opener = urllib.request.build_opener(_NoRedirect)
        last_error = None
        for attempt in range(3):
            try:
                with opener.open(req, timeout=60) as resp:
                    return True, resp.read(), resp.status
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return False, None, e.code
                if e.code not in (408, 425, 429) and not 500 <= e.code <= 599:
                    return False, None, e.code
                last_error = e
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                last_error = e
            except ssl.SSLError as e:
                # e.g. UNEXPECTED_EOF_WHILE_READING: CDN-side transport flake,
                # indistinguishable from content state without a retry.
                last_error = e
            if attempt < 2:
                time.sleep(2 ** attempt)
        raise RuntimeError(f"registry unreachable after 3 attempts: {url}: {last_error}") from last_error

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
    if manifest.get("releaseSet") != expected_version:
        errors.append(
            f"manifest releaseSet {manifest.get('releaseSet')!r} != expected {expected_version!r}"
        )
    if manifest.get("publishable") is not True:
        errors.append("manifest is not publication-ready")
    if manifest.get("artifactGroup") != "com.tencent.kuikly-open":
        errors.append("artifactGroup mismatch")
    if manifest.get("versions") != {
        "normal": f"{expected_version}-2.1.21",
        "ohos": f"{expected_version}-2.0.21-ohos",
    }:
        errors.append("version planes mismatch")
    if manifest.get("mutableMetadataPolicy") != "exclude-artifact-level-maven-metadata-and-companions":
        errors.append("mutable metadata policy mismatch")
    locator = manifest.get("completionLocator") or {}
    expected_locator = locator_path(expected_version)
    if locator != {
        "group": "com.tencent.kuikly-open",
        "artifact": "kuikly-release-manifest",
        "version": expected_version,
        "path": expected_locator,
    }:
        errors.append(f"completion locator mismatch: {locator!r}")
    source = manifest.get("source") or {}
    for field in ("commit", "tree"):
        val = source.get(field)
        if not (isinstance(val, str) and len(val) == 40 and all(c in "0123456789abcdef" for c in val)):
            errors.append(f"source.{field} not a 40-hex lowercase SHA: {val!r}")
    if source.get("repository") != "botiverse/KuiklyUI":
        errors.append(f"source.repository mismatch: {source.get('repository')!r}")
    tag = source.get("tag") or {}
    if not (
        tag.get("state") == "frozen"
        and isinstance(tag.get("ref"), str)
        and tag["ref"].startswith("refs/tags/")
        and tag.get("commit") == source.get("commit")
    ):
        errors.append(f"source tag is not frozen to the source commit: {tag!r}")
    if manifest.get("toolchainTagRef") != tag.get("ref"):
        errors.append("toolchain tag binding mismatch")
    toolchains = manifest.get("toolchains")
    if not isinstance(toolchains, dict) or set(toolchains) != EXPECTED_PRODUCERS:
        errors.append("toolchain producer closure mismatch")
    artifact_closure = manifest.get("artifactClosure")
    if not isinstance(artifact_closure, list):
        errors.append("artifactClosure missing")
        artifact_closure = []
    closure_coordinates = [item.get("coordinate") for item in artifact_closure if isinstance(item, dict)]
    if len(artifact_closure) != len(closure) or manifest.get("publicationSeedCount") != len(closure):
        errors.append(f"manifest must contain exactly {len(closure)} publication seeds")
    if len(closure_coordinates) != len(set(closure_coordinates)):
        errors.append("artifactClosure contains duplicate coordinates")
    if set(closure_coordinates) != set(closure):
        missing = set(closure) - set(closure_coordinates)
        extra = set(closure_coordinates) - set(closure)
        if missing:
            errors.append(f"closure missing coordinates (is the closure file for release {expected_version}?): {sorted(missing)}")
        if extra:
            errors.append(f"unexpected coordinates beyond closure (is the closure file for release {expected_version}?): {sorted(extra)}")
    pubs = manifest.get("publications")
    if not isinstance(pubs, list) or not pubs:
        errors.append("publications missing or empty")
        return errors
    seen_paths = set()
    seen_gavs = set()
    publications_by_path = {
        publication.get("path"): publication
        for publication in pubs
        if isinstance(publication, dict) and publication.get("path")
    }
    for pub in pubs:
        coord = pub.get("coordinate")
        path = pub.get("path")
        kind = pub.get("kind")
        sha = pub.get("sha256")
        nbytes = pub.get("size")
        if not coord or not path:
            errors.append(f"publication missing coordinate/path: {pub!r}")
            continue
        if kind not in ALLOWED_KINDS:
            errors.append(f"unknown kind {kind!r} in {coord}")
        if not (isinstance(sha, str) and len(sha) == 64 and all(c in "0123456789abcdef" for c in sha)):
            errors.append(f"bad sha256 in {coord}: {sha!r}")
        if not (isinstance(nbytes, int) and not isinstance(nbytes, bool) and nbytes >= 0):
            errors.append(f"bad size in {coord}: {nbytes!r}")
        if path in seen_paths:
            errors.append(f"duplicate path {path!r}")
        seen_paths.add(path)
        seen_gavs.add(coord)
        if not path.startswith(GROUP_PATH + "/"):
            errors.append(f"path {path!r} outside {GROUP_PATH}")
        if not os.path.basename(path).startswith(coord.split(":")[1]):
            errors.append(f"filename does not begin with artifact id: {path!r}")
        if pub.get("sourceExact") != source.get("commit") or pub.get("tree") != source.get("tree"):
            errors.append(f"publication source identity mismatch: {path!r}")
        if kind in CHECKSUM_KINDS:
            companion = pub.get("companionOf")
            companion_publication = publications_by_path.get(companion)
            if not isinstance(companion, str) or companion_publication is None:
                errors.append(f"orphan checksum companion: {path!r}")
            elif (
                companion_publication.get("kind") in CHECKSUM_KINDS
                or companion_publication.get("coordinate") != coord
            ):
                errors.append(f"invalid checksum companion: {path!r}")
    # Every GAV must have at least one primary carrier.
    primary_gavs = {
        pub.get("coordinate") for pub in pubs if pub.get("kind") not in CHECKSUM_KINDS
    }
    expected_gavs = set(closure)
    if seen_gavs != expected_gavs or primary_gavs != expected_gavs:
        missing = expected_gavs - primary_gavs
        extra = seen_gavs - expected_gavs
        if missing:
            errors.append(f"closure missing coordinates (is the closure file for release {expected_version}?): {sorted(missing)}")
        if extra:
            errors.append(f"unexpected coordinates beyond closure (is the closure file for release {expected_version}?): {sorted(extra)}")
    # set digest
    if len(closure) == 37 and len(pubs) != EXPECTED_FILE_COUNT:
        errors.append(f"production manifest must contain {EXPECTED_FILE_COUNT} files")
    if manifest.get("publicationFileCount") != len(pubs):
        errors.append("publicationFileCount mismatch")
    primary_count = sum(pub.get("kind") not in CHECKSUM_KINDS for pub in pubs)
    checksum_count = sum(pub.get("kind") in CHECKSUM_KINDS for pub in pubs)
    if manifest.get("publicationPrimaryFileCount") != primary_count:
        errors.append("publicationPrimaryFileCount mismatch")
    if len(closure) == 37 and primary_count != EXPECTED_PRIMARY_COUNT:
        errors.append(f"production manifest must contain {EXPECTED_PRIMARY_COUNT} primaries")
    if manifest.get("publicationChecksumFileCount") != checksum_count:
        errors.append("publicationChecksumFileCount mismatch")
    if len(closure) == 37 and checksum_count != EXPECTED_CHECKSUM_COUNT:
        errors.append(f"production manifest must contain {EXPECTED_CHECKSUM_COUNT} checksums")
    if manifest.get("publicationSignatureFileCount") != 0:
        errors.append("unexpected publication signatures")
    predecessors = manifest.get("predecessors")
    if not isinstance(predecessors, list):
        errors.append("predecessors missing")
    else:
        required_missing = []
        for item in predecessors:
            if not isinstance(item, dict):
                errors.append("invalid predecessor record")
                continue
            if item.get("requiredOnRaft") is not True:
                continue
            receipt = item.get("receipt") or {}
            if not (
                item.get("status") == "verified"
                and receipt.get("coordinate") == item.get("coordinate")
                and receipt.get("status") == "verified"
                and receipt.get("publicReadbackState") == "verified"
                and isinstance(receipt.get("manifestSha256"), str)
                and len(receipt["manifestSha256"]) == 64
            ):
                required_missing.append(item.get("coordinate"))
        if required_missing:
            errors.append(f"required Raft predecessors are not verified: {required_missing}")
    set_sha = manifest.get("setSha256")
    if not (isinstance(set_sha, str) and len(set_sha) == 64):
        errors.append(f"bad set_sha256: {set_sha!r}")
    else:
        entries = sorted(
            (p["path"], p["sha256"], p["size"])
            for p in pubs
            if p.get("path") and p.get("sha256") is not None and p.get("size") is not None
        )
        payload = b"".join(
            f"{p}\x00{s}\x00{b}\n".encode("utf-8") for (p, s, b) in entries
        )
        recomputed = hashlib.sha256(payload).hexdigest()
        if recomputed != set_sha:
            errors.append(f"setSha256 mismatch: manifest {set_sha} != recomputed {recomputed}")
    return errors


def worker_count() -> int:
    raw = os.environ.get("KUIKLY_GATE_WORKERS", "12")
    try:
        configured = int(raw)
    except ValueError as error:
        raise ValueError("KUIKLY_GATE_WORKERS must be an integer") from error
    return max(1, min(32, configured))


def verify_publications(registry: Registry, manifest: dict) -> list:
    """GET every listed publication and verify bytes/hash. Returns errors."""
    errors = []
    primary_digests = {}
    sidecar_bodies = {}

    def fetch_one(pub):
        path = pub.get("path")
        try:
            found, data, status = registry.get_bytes(path)
        except Exception as error:
            return path, None, None, [f"listed path readback failed: {path}: {error}"]
        item_errors = []
        if not found:
            return path, None, None, [f"listed path absent on readback: {path} (HTTP {status})"]
        if len(data) != pub.get("size"):
            item_errors.append(f"byte count mismatch for {path}: {len(data)} != {pub.get('size')}")
        actual = hashlib.sha256(data).hexdigest()
        if actual != pub.get("sha256"):
            item_errors.append(f"sha256 mismatch for {path}: {actual} != {pub.get('sha256')}")
        if pub.get("kind") in CHECKSUM_KINDS:
            return path, data, None, item_errors
        digests = {"sha256": actual}
        digests.update(
            {
                algorithm: hashlib.new(algorithm, data).hexdigest()
                for algorithm in ("md5", "sha1", "sha512")
            }
        )
        return path, None, digests, item_errors

    workers = worker_count()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(fetch_one, pub) for pub in manifest.get("publications", [])]
        for future in concurrent.futures.as_completed(futures):
            path, sidecar_body, digests, item_errors = future.result()
            errors.extend(item_errors)
            if sidecar_body is not None:
                sidecar_bodies[path] = sidecar_body
            if digests is not None:
                primary_digests[path] = digests
    for pub in manifest.get("publications", []):
        kind = pub.get("kind")
        if kind not in CHECKSUM_ALGORITHMS:
            continue
        path = pub.get("path")
        companion = pub.get("companionOf")
        if path not in sidecar_bodies or companion not in primary_digests:
            continue
        expected = primary_digests[companion][CHECKSUM_ALGORITHMS[kind]].encode("ascii") + b"\n"
        if sidecar_bodies[path] != expected:
            errors.append(f"checksum sidecar content mismatch for {path}")
    return sorted(errors)


def preflight(registry: Registry, release: str, closure: list, full_readback: bool = True):
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
        if full_readback:
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


def run_gate(release: str, registry: Registry, closure: list, full_readback: bool = True) -> int:
    state, errors = preflight(registry, release, closure, full_readback=full_readback)
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
    entries = sorted((p["path"], p["sha256"], p["size"]) for p in pubs)
    payload = b"".join(f"{p}\x00{s}\x00{b}\n".encode("utf-8") for (p, s, b) in entries)
    closure = []
    seen = set()
    for pub in pubs:
        coordinate = pub["coordinate"]
        if coordinate not in seen:
            seen.add(coordinate)
            closure.append({"coordinate": coordinate})
    return {
        "schema": "kuikly-release-manifest/v1",
        "releaseSet": release,
        "artifactGroup": "com.tencent.kuikly-open",
        "versions": {
            "normal": f"{release}-2.1.21",
            "ohos": f"{release}-2.0.21-ohos",
        },
        "mutableMetadataPolicy": "exclude-artifact-level-maven-metadata-and-companions",
        "completionLocator": {
            "group": "com.tencent.kuikly-open",
            "artifact": "kuikly-release-manifest",
            "version": release,
            "path": locator_path(release),
        },
        "source": {
            "repository": "botiverse/KuiklyUI",
            "commit": "a" * 40,
            "tree": "b" * 40,
            "tag": {
                "ref": f"refs/tags/kuikly-v{release}",
                "object": "c" * 40,
                "commit": "a" * 40,
                "state": "frozen",
            },
        },
        "toolchainTagRef": f"refs/tags/kuikly-v{release}",
        "toolchains": {producer: {"version": "test"} for producer in EXPECTED_PRODUCERS},
        "artifactClosure": closure,
        "publicationSeedCount": len(closure),
        "publicationFileCount": len(pubs),
        "publicationPrimaryFileCount": len(pubs),
        "publicationChecksumFileCount": 0,
        "publicationSignatureFileCount": 0,
        "predecessors": [],
        "setSha256": hashlib.sha256(payload).hexdigest(),
        "publishable": True,
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
            "kind": "gradle-module",
            "path": f"{GROUP_PATH}/core/{release}-2.1.21/core-{release}-2.1.21.module",
            "sha256": hashlib.sha256(core_bytes).hexdigest(),
            "size": len(core_bytes),
            "sourceExact": "a" * 40,
            "tree": "b" * 40,
        },
        {
            "coordinate": closure[1],
            "kind": "aar",
            "path": f"{GROUP_PATH}/core-render-android/{release}-2.1.21/core-render-android-{release}-2.1.21.aar",
            "sha256": hashlib.sha256(render_bytes).hexdigest(),
            "size": len(render_bytes),
            "sourceExact": "a" * 40,
            "tree": "b" * 40,
        },
    ]
    manifest = _make_manifest(release, pubs)

    failures = []

    def check(name, expected_exit, build_root, full_readback=True):
        root = build_root()
        reg = Registry(base="", offline_root=root)
        got = run_gate(release, reg, closure, full_readback=full_readback)
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
        short["artifactClosure"] = [short["artifactClosure"][0]]
        short["publicationSeedCount"] = 1
        short["publicationFileCount"] = 1
        short["publicationPrimaryFileCount"] = 1
        entries = sorted((pp["path"], pp["sha256"], pp["size"]) for pp in short["publications"])
        payload = b"".join(f"{pp}\x00{ss}\x00{bb}\n".encode("utf-8") for (pp, ss, bb) in entries)
        import hashlib as _h
        short["setSha256"] = _h.sha256(payload).hexdigest()
        _write(root, locator_path(release), json.dumps(short).encode())
        return root

    def root_duplicate_path():
        root = tempfile.mkdtemp()
        _write(root, pubs[0]["path"], core_bytes)
        _write(root, pubs[1]["path"], render_bytes)
        dup = dict(manifest)
        dup_pubs = list(pubs) + [dict(pubs[0])]
        entries = sorted(set((pp["path"], pp["sha256"], pp["size"]) for pp in dup_pubs))
        payload = b"".join(f"{pp}\x00{ss}\x00{bb}\n".encode("utf-8") for (pp, ss, bb) in entries)
        import hashlib as _h
        dup["publications"] = dup_pubs
        dup["publicationFileCount"] = len(dup_pubs)
        dup["publicationPrimaryFileCount"] = len(dup_pubs)
        dup["setSha256"] = _h.sha256(payload).hexdigest()
        _write(root, locator_path(release), json.dumps(dup).encode())
        return root

    check("complete", EXIT_COMPLETE, root_complete)
    check("admission-complete", EXIT_COMPLETE, root_complete, full_readback=False)
    check("absent", EXIT_ABSENT, root_absent)
    check("partial", EXIT_PARTIAL, root_partial)
    check("closure-mismatch", EXIT_CONFLICT, root_closure_mismatch)
    check("duplicate-path", EXIT_CONFLICT, root_duplicate_path)
    check("admission-bad-bytes-deferred-to-resolver", EXIT_COMPLETE, root_conflict_bad_bytes, full_readback=False)
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
    parser.add_argument(
        "--registry-base",
        default="https://maven.artifacts.botiverse.dev",
        help="registry base URL (default: production maven.artifacts.botiverse.dev)",
    )
    parser.add_argument("--offline-root", help="directory-based fake registry (testing)")
    parser.add_argument("--closure", help="JSON file: list of expected publication GAVs")
    parser.add_argument(
        "--admission-only",
        action="store_true",
        help="validate the completion manifest and closure without re-reading all publications",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    try:
        worker_count()
    except ValueError as error:
        log(f"ERROR: {error}")
        return EXIT_INVALID
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
    return run_gate(args.release, registry, closure, full_readback=not args.admission_only)


if __name__ == "__main__":
    sys.exit(main())
