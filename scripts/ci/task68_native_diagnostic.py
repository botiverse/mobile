#!/usr/bin/env python3
"""Fixed Task68 control/probe preparation and post-signing payload checks.

No network, signing, key access, build, installation or code from the input ZIP.
The caller MUST run official verify-app successfully for all three signed HAPs
before verify, exporting verified/{original,control,probe}.{cer,p7b} in a fresh
directory. This helper compares those public outputs; it is not a signature
verifier. This diagnostic path does not change the normal release closure.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import stat
import subprocess
import sys
import tempfile
import zipfile


ARCHIVE_SHA = "f3ac82093a8cb046b5734a472815415c801486fa076bc01b7d9a160bffc6ceb4"
APP_NAME = "raft-ohos-1.13.1-diag-113bb09c-11301900.app"
APP_SHA = "11346f726763bc23bebf3a2e4bc993bbdf6c7b051677f8a5a6b0a8ea4503422c"
SIGNED_NAME = "raft-ohos-ohosApp-default-unsigned-entry-default.signed-1.13.1-diag-113bb09c-11301900.hap"
SIGNED_SHA = "2b19103c939258b0150aa811d35de8f4b1738a2aa8e535321fcca10cca90aeed"
BASE_HAP_SHA = "f0910d173f0c40ca207477094d444007fcd4d14790653b8afba7212260913f3c"
TARGET = "libs/arm64-v8a/libkuikly.so"
ORIGINAL_LIBRARY_SHA = "c5dc59fdfca7013a8880be6fd8fdbd98b1737badf7d470d7494a5618ff6efdd6"
LIBRARY_SHA = {
    "control": "e8067971e1a7f0dd36369a61f790e758457310e4989a89b116268dc0c19a449b",
    "probe": "acf28fc36a11b08b545fe9299d7a3281ab8536704b90ac56237bd0e830d8359f",
}
UNSIGNED_SHA = {
    "control": "f92dc0dc03f1280e33991c5730f04a7ce5e6fb47b91e101e5b3a521348ab8bfc",
    "probe": "4d2df3e1af344d8f0b5d9bacff07d0e3295a3462acffaa6009b3b4d1cbfd483e",
}
FROZEN = {
    "libs/arm64-v8a/libshared.so": "ebb1ccfe8ebb864c3e42a5b7d6aa4df30ef16dcec8633d9d65a72b9409f5d54f",
    "libs/arm64-v8a/libentry.so": "df045509b49b7e5947a12081fe965817cca107912176cdba61a7225b5fc36d3d",
    "ets/modules.abc": "75d10c3b9224fdca133acdc1da1b3dd25be830acc236b2da5276c386acbabe40",
    "ets/widgets.abc": "9f7d47a3cdb4dd716a2593e1c25a7027f90917f1cb5f3370da522725e12f63d3",
}
FIXTURE_MEMBERS = {
    "README.md", "prepare_native_probe_hap.py", "control/libkuikly.so",
    "probe/libkuikly.so", "receipts/control-manifest.json",
    "receipts/probe-manifest.json", "receipts/paired-carrier-receipt.json",
    "receipts/probe-download.json", "receipts/elf-comparison.json",
    "receipts/control-provenance.json", "receipts/control-toolchain.json",
    "receipts/probe-provenance.json", "receipts/probe-toolchain.json", "checksums.json",
}
SOURCE = {
    "original_run": 38503177796, "original_artifact": 11868217887,
    "original_artifact_name": "raft-ohos-1.13.1-diag-113bb09c-11301900",
    "application_sha": "4fc2ea871da9c5ec101e4c8c6832ebeda412061a",
    "control_sha": "8226bb433c1fb97c0ac424979ab77add9d6258e5",
    "probe_sha": "a76e111b65e1e922e6340f98611672732e590dbf",
    "probe_run": 38595671669,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked_bytes(path, expected):
    data = Path(path).read_bytes()
    require(sha(data) == expected, f"SHA-256 mismatch: {Path(path).name}")
    return data


def unique_file(directory, name):
    found = [p for p in Path(directory).rglob(name) if p.is_file()]
    require(len(found) == 1, f"expected one {name}, found {len(found)}")
    return found[0]


def safe_entries(archive):
    infos = archive.infolist()
    seen = set()
    for item in infos:
        name = item.filename
        require(name not in seen, f"duplicate ZIP member: {name}")
        seen.add(name)
        require(name and not name.startswith("/") and "\\" not in name
                and ".." not in PurePosixPath(name).parts, "unsafe ZIP member")
        require(not stat.S_ISLNK(item.external_attr >> 16), "ZIP symlink rejected")
        require(not item.flag_bits & 1, "encrypted ZIP member rejected")
        require(item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                "unsupported ZIP compression")
    return infos


def input_libraries(path):
    raw = checked_bytes(path, ARCHIVE_SHA)
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = safe_entries(archive)
        require({i.filename for i in infos} == FIXTURE_MEMBERS, "fixture member set changed")
        require(sum(i.file_size for i in infos) < 8 * 1024 * 1024, "fixture expanded size excessive")
        checksums = json.loads(archive.read("checksums.json"))
        require(set(checksums) == FIXTURE_MEMBERS - {"checksums.json"}, "checksum member set changed")
        for name, expected in checksums.items():
            require(sha(archive.read(name)) == expected, f"fixture member checksum mismatch: {name}")
        libraries = {role: archive.read(f"{role}/libkuikly.so") for role in LIBRARY_SHA}
        for role, data in libraries.items():
            require(sha(data) == LIBRARY_SHA[role], f"{role} renderer changed")
        # Deliberately do not extract or execute the archived Python/README.
        return libraries


def hap_payload(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = safe_entries(archive)
        require(sum(i.file_size for i in infos) < 256 * 1024 * 1024, "HAP expanded size excessive")
        entries = {i.filename: {"sha256": sha(archive.read(i.filename)), "size": i.file_size}
                   for i in infos}
        module = json.loads(archive.read("module.json"))
    app = module.get("app", {})
    require(app.get("bundleName") == "build.raft.mobile", "bundle identity changed")
    require(app.get("versionCode") == 11301900, "versionCode changed")
    require(app.get("versionName") == "1.13.1-diag-113bb09c", "versionName changed")
    return {"entries": entries, "app": app}


def original_inputs(directory):
    app_path = unique_file(directory, APP_NAME)
    signed_path = unique_file(directory, SIGNED_NAME)
    app = checked_bytes(app_path, APP_SHA)
    signed = checked_bytes(signed_path, SIGNED_SHA)
    with zipfile.ZipFile(io.BytesIO(app)) as archive:
        infos = safe_entries(archive)
        require([i.filename for i in infos if i.filename.endswith(".hap")] == ["entry-default.hap"],
                "original App Pack HAP set changed")
        unsigned = archive.read("entry-default.hap")
    require(sha(unsigned) == BASE_HAP_SHA, "original unsigned HAP changed")
    base = hap_payload(unsigned)
    require(len(base["entries"]) == 264 and ".pages.info" not in base["entries"], "base entry set changed")
    require(base["entries"].get(TARGET, {}).get("sha256") == ORIGINAL_LIBRARY_SHA,
            "original renderer changed")
    check_frozen(base)
    compare_signed_payload(base, hap_payload(signed))
    return app_path, signed_path, base


def check_frozen(payload):
    for name, expected in FROZEN.items():
        require(payload["entries"].get(name, {}).get("sha256") == expected,
                f"frozen payload changed: {name}")


def compare_renderer_payload(base, changed, role):
    require(set(base["entries"]) == set(changed["entries"]), "unsigned payload member set changed")
    require(base["app"] == changed["app"], "unsigned app metadata changed")
    differences = [n for n in base["entries"] if base["entries"][n] != changed["entries"][n]]
    require(differences == [TARGET], "unsigned payload differs beyond renderer")
    require(changed["entries"][TARGET]["sha256"] == LIBRARY_SHA[role], "wrong renderer role")
    check_frozen(changed)


def compare_signed_payload(unsigned, signed):
    before = unsigned["entries"]
    after = {n: v for n, v in signed["entries"].items() if n != ".pages.info"}
    require(before == after, "signed HAP payload changed beyond signing-generated .pages.info")
    require(unsigned["app"] == signed["app"], "signed app metadata changed")


def public_identity(directory):
    identities = {}
    for role in ("original", "control", "probe"):
        identities[role] = {}
        for suffix in ("cer", "p7b"):
            path = Path(directory) / f"{role}.{suffix}"
            require(path.is_file() and not path.is_symlink(), f"missing verify-app public output: {path.name}")
            data = path.read_bytes()
            require(0 < len(data) < 4 * 1024 * 1024, f"invalid public output size: {path.name}")
            identities[role][suffix + "_sha256"] = sha(data)
    require(identities["original"] == identities["control"] == identities["probe"],
            "signer certificate or profile differs from original")
    return identities


def write_receipt(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write("\n")


def prepare(original_dir, work_dir):
    scripts = Path(__file__).resolve().parent
    libraries = input_libraries(scripts / "fixtures/task68-native-probe-inputs.zip")
    app, _, base = original_inputs(original_dir)
    work = Path(work_dir)
    work.mkdir(parents=True, exist_ok=True)
    require(not any(work.iterdir()), "work directory must be empty")
    helper = scripts / "prepare_native_probe_hap.py"
    require(helper.is_file(), "checked-in prepare_native_probe_hap.py missing")
    outputs = {}
    with tempfile.TemporaryDirectory(prefix="task68-renderers-") as scratch:
        for role, data in libraries.items():
            library = Path(scratch) / f"{role}.so"
            library.write_bytes(data)
            output = work / f"{role}-UNSIGNED.hap"
            subprocess.run([sys.executable, str(helper), "--base", str(app),
                            "--expected-base-sha256", APP_SHA, "--renderer", str(library),
                            "--expected-renderer-sha256", LIBRARY_SHA[role], "--output", str(output),
                            "--manifest", str(work / f"{role}-manifest.json")], check=True)
            raw = checked_bytes(output, UNSIGNED_SHA[role])
            compare_renderer_payload(base, hap_payload(raw), role)
            outputs[role] = {"file": output.name, "sha256": sha(raw), "renderer_sha256": LIBRARY_SHA[role]}
    receipt = {"state": "UNSIGNED_NOT_INSTALLED", "source": SOURCE, "archive_sha256": ARCHIVE_SHA,
               "original_app_sha256": APP_SHA, "original_signed_hap_sha256": SIGNED_SHA,
               "original_unsigned_hap_sha256": BASE_HAP_SHA, "entry_count": 264,
               "changed_entries": [TARGET], "frozen_entries": FROZEN, "outputs": outputs,
               "notice": "Only checked-in helper executed; archived Python was not extracted or executed. No signing/install. ZIP alignment/installability requires official signing/verification and device readback."}
    write_receipt(work / "preparation-receipt.json", receipt)
    return receipt


def verify(original_dir, work_dir, signed_dir, verified_dir, receipt_path):
    _, _, base = original_inputs(original_dir)
    public = public_identity(verified_dir)
    outputs = {}
    for role in LIBRARY_SHA:
        unsigned = checked_bytes(Path(work_dir) / f"{role}-UNSIGNED.hap", UNSIGNED_SHA[role])
        prepared = hap_payload(unsigned)
        compare_renderer_payload(base, prepared, role)
        path = Path(signed_dir) / f"{role}.signed.hap"
        raw = path.read_bytes()
        signed = hap_payload(raw)
        compare_signed_payload(prepared, signed)
        outputs[role] = {"file": path.name, "sha256": sha(raw), "bytes": len(raw),
                         "unsigned_sha256": UNSIGNED_SHA[role], "renderer_sha256": LIBRARY_SHA[role],
                         "entry_count_without_signing_pages": len(prepared["entries"]),
                         "signing_generated_pages_present": ".pages.info" in signed["entries"]}
    receipt = {"state": "PAYLOAD_AND_PUBLIC_SIGNER_IDENTITY_MATCH", "source": SOURCE,
               "bundle": "build.raft.mobile", "versionCode": 11301900,
               "versionName": "1.13.1-diag-113bb09c", "original_signed_hap_sha256": SIGNED_SHA,
               "frozen_entries": FROZEN, "public_verify_app_export_hashes": public, "outputs": outputs,
               "signature_validation_boundary": "Caller must have run official verify-app successfully on original/control/probe and created these public exports in a fresh directory. Python compares exported bytes and payloads; it does not validate signatures or assert runtime PASS.",
               "runtime": "NOT_RUN"}
    write_receipt(receipt_path, receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    check = commands.add_parser("verify")
    for command in (prep, check):
        command.add_argument("--original-dir", required=True)
        command.add_argument("--work-dir", required=True)
    check.add_argument("--signed-dir", required=True)
    check.add_argument("--verified-dir", required=True)
    check.add_argument("--receipt", required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.original_dir, args.work_dir)
        else:
            result = verify(args.original_dir, args.work_dir, args.signed_dir, args.verified_dir, args.receipt)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Task68 REFUSED: {error}", file=sys.stderr)
        return 2
    print(json.dumps({"state": result["state"], "outputs": result["outputs"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
