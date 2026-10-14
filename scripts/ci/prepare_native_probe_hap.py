#!/usr/bin/env python3
"""Prepare an UNSIGNED native-renderer diagnostic HAP; never sign or install.

Inputs are an original .app (selecting its unsigned HAP) or unsigned .hap,
an ARM64 ELF shared library, and explicitly supplied SHA-256 values for both
input files. The output preserves ZIP entry contents/metadata except for
libs/arm64-v8a/libkuikly.so. ZIP container bytes need not remain identical.

This is a convenience artifact, not a release gate. Existing authorized
exact-source builds remain valid alternatives. The original managed signer
must create fresh signatures/code-sign data before installation. No keys,
profiles, device, build tools, workflow or network are accessed here.
"""

import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import tempfile
import zipfile


TARGET = "libs/arm64-v8a/libkuikly.so"
SIGNATURE_NAMES = {".pages.info", "signature.p7b", "signature.json"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked_read(path, expected, label):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise ValueError(f"{label}: expected SHA-256 must contain 64 hex characters")
    data = path.read_bytes()
    actual = digest(data)
    if actual != expected.lower():
        raise ValueError(f"{label}: SHA-256 mismatch (actual {actual})")
    return data


def require_arm64_elf(data, label):
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError(f"{label}: requires a 64-bit little-endian ARM64 ELF")
    kind, machine, version = struct.unpack_from("<HHI", data, 16)
    if (kind, machine, version) != (3, 183, 1):
        raise ValueError(f"{label}: requires ET_DYN / EM_AARCH64 ELF")
    if struct.unpack_from("<H", data, 52)[0] != 64:
        raise ValueError(f"{label}: invalid ELF64 header size")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize != 56 or not phnum or phoff < 64 or phoff + phnum * 56 > len(data):
        raise ValueError(f"{label}: invalid ELF64 program header table")
    load_count = 0
    for index in range(phnum):
        kind, _, offset, _, _, filesz, memsz, _ = struct.unpack_from("<IIQQQQQQ", data, phoff + index * 56)
        if kind == 1:
            load_count += 1
            if filesz > memsz or offset + filesz > len(data):
                raise ValueError(f"{label}: invalid ELF load segment")
    if not load_count:
        raise ValueError(f"{label}: ELF shared library has no load segment")


def entries(archive):
    infos = archive.infolist()
    seen = set()
    for info in infos:
        name = info.filename
        if name in seen:
            raise ValueError(f"duplicate ZIP entry: {name}")
        seen.add(name)
        if (not name or name.startswith("/") or "\\" in name
                or ".." in PurePosixPath(name).parts):
            raise ValueError(f"unsafe ZIP entry name: {name}")
        if info.flag_bits & 1:
            raise ValueError(f"encrypted ZIP entry unsupported: {name}")
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise ValueError(f"unsupported ZIP compression: {name}")
    return infos


def unsigned_hap(base_data, suffix, selected):
    if suffix == ".hap":
        if selected:
            raise ValueError("--hap-entry applies only to .app inputs")
        return base_data, None
    if suffix != ".app":
        raise ValueError("base must be an original .app or unsigned .hap")
    with zipfile.ZipFile(io.BytesIO(base_data)) as app:
        candidates = [i.filename for i in entries(app)
                      if not i.is_dir() and i.filename.lower().endswith(".hap")]
        if selected:
            if selected not in candidates:
                raise ValueError("--hap-entry does not name a HAP in the .app")
        elif len(candidates) != 1:
            raise ValueError("base .app must contain one HAP; specify --hap-entry explicitly")
        else:
            selected = candidates[0]
        return app.read(selected), selected


def reject_signature_material(raw, archive, infos):
    # The original task68 unsigned HAP has contiguous ZIP payloads before
    # the central directory. A signed HAP adds a non-ZIP signing block there.
    # Be conservative: reject any unexplained gap, not just one known magic.
    end = 0
    for info in infos:
        offset = info.header_offset
        if raw[offset:offset + 4] != b"PK\x03\x04":
            raise ValueError("invalid ZIP local header")
        name_len, extra_len = struct.unpack_from("<HH", raw, offset + 26)
        item_end = offset + 30 + name_len + extra_len + info.compress_size
        if info.flag_bits & 8:
            raise ValueError("data-descriptor ZIP entries unsupported; use the original unsigned HAP")
        end = max(end, item_end)
        lower = info.filename.lower()
        if (lower in SIGNATURE_NAMES or lower.startswith("meta-inf/")
                or lower.startswith(".signature/")):
            raise ValueError(f"signature-related ZIP entry cannot be reused: {info.filename}")
    if not infos or end != archive.start_dir:
        raise ValueError("HAP has a signing block or unsupported bytes before its central directory")
    if b"<hap sign block>" in raw[max(0, archive.start_dir - 64):archive.start_dir]:
        raise ValueError("signed HAP input rejected; use the original unsigned carrier")


def prepare(args):
    base = Path(args.base).resolve()
    renderer = Path(args.renderer).resolve()
    output = Path(args.output).resolve()
    manifest = Path(args.manifest).resolve()
    if output.suffix.lower() != ".hap":
        raise ValueError("output must have the .hap suffix")
    if output == manifest or output in (base, renderer) or manifest in (base, renderer):
        raise ValueError("outputs must be distinct from each other and both inputs")
    if output.exists() or manifest.exists():
        raise ValueError("refusing to overwrite an existing output or manifest")
    if not output.parent.is_dir() or not manifest.parent.is_dir():
        raise ValueError("output and manifest parent directories must already exist")
    base_data = checked_read(base, args.expected_base_sha256, "base")
    new_library = checked_read(renderer, args.expected_renderer_sha256, "renderer")
    require_arm64_elf(new_library, "renderer")
    hap_data, selected = unsigned_hap(base_data, base.suffix.lower(), args.hap_entry)
    records = []
    with zipfile.ZipFile(io.BytesIO(hap_data)) as source:
        infos = entries(source)
        reject_signature_material(hap_data, source, infos)
        if TARGET not in source.namelist():
            raise ValueError(f"base HAP lacks {TARGET}")
        require_arm64_elf(source.read(TARGET), "base renderer")
        # Temporary output is not published until a complete per-entry readback.
        with tempfile.TemporaryDirectory(prefix="native-probe-", dir=output.parent) as tmp:
            staged = Path(tmp) / "UNSIGNED.hap"
            with zipfile.ZipFile(staged, "w") as destination:
                destination.comment = source.comment
                for info in infos:
                    old = source.read(info.filename)
                    new = new_library if info.filename == TARGET else old
                    destination.writestr(copy.copy(info), new)
                    records.append({"name": info.filename, "size_before": len(old),
                                    "size_after": len(new), "sha256_before": digest(old),
                                    "sha256_after": digest(new), "changed": old != new})
            with zipfile.ZipFile(staged) as result:
                if result.namelist() != [i.filename for i in infos]:
                    raise ValueError("output ZIP entry order/list changed unexpectedly")
                for record in records:
                    if digest(result.read(record["name"])) != record["sha256_after"]:
                        raise ValueError(f"output entry verification failed: {record['name']}")
                if any(r["changed"] and r["name"] != TARGET for r in records):
                    raise ValueError("non-renderer content changed")
            payload = {
                "schema_version": 1, "state": "UNSIGNED_REQUIRES_ORIGINAL_MANAGED_SIGNER",
                "base_file": str(base), "base_sha256": digest(base_data),
                "selected_hap_entry": selected, "base_hap_sha256": digest(hap_data),
                "renderer_file": str(renderer), "renderer_sha256": digest(new_library),
                "output_file": str(output), "output_sha256": digest(staged.read_bytes()),
                "replacement_entry": TARGET, "changed_entries": [r["name"] for r in records if r["changed"]],
                "entry_count": len(records), "entries": records,
                "signing": "Outer .app signatures are not copied. Signed HAP markers/non-ZIP signing blocks are rejected. No old code-sign or package signature may be reused. Fresh signing and official verification are required.",
                "limitations": ["ELF header/architecture/load-segment validation only; not ABI or runtime validation.",
                                "Preserves ZIP entry content, timestamps, attributes, extra fields and compression method; ZIP flags/container bytes are regenerated.",
                                "Changing library size moves later ZIP offsets; copied ZipInfo does not prove alignment or installability. Standard sign-app/verify-app and device identity readback are still required.",
                                "Conservatively rejects data descriptors and non-ZIP payload gaps.",
                                "No key/profile access, signing, installation, version rewrite, build or workflow invocation.",
                                "Convenience artifact, not a prerequisite for other authorized diagnostic builds."]
            }
            staged_manifest = Path(tmp) / "manifest.json"
            staged_manifest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
            staged.chmod(0o600)
            staged_manifest.chmod(0o600)
            # Hard links provide an atomic no-clobber publish in the output FS.
            os.link(staged, output)
            manifest_created = False
            try:
                # The manifest can live on a different filesystem.
                with manifest.open("x", encoding="utf-8") as f:
                    manifest_created = True
                    f.write(staged_manifest.read_text())
                manifest.chmod(0o600)
            except Exception:
                output.unlink()
                if manifest_created:
                    manifest.unlink()
                raise
    return payload


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", required=True, help="original .app or unsigned .hap")
    parser.add_argument("--expected-base-sha256", required=True)
    parser.add_argument("--hap-entry", help="required when .app contains multiple HAPs")
    parser.add_argument("--renderer", required=True, help="new ARM64 ELF libkuikly.so")
    parser.add_argument("--expected-renderer-sha256", required=True)
    parser.add_argument("--output", required=True, help="new UNSIGNED .hap; never overwrite")
    parser.add_argument("--manifest", required=True, help="new per-entry JSON receipt")
    args = parser.parse_args()
    try:
        result = prepare(args)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, struct.error) as error:
        print(f"REFUSED: {error}", file=sys.stderr)
        return 2
    print(json.dumps({"state": result["state"], "output": args.output,
                      "sha256": result["output_sha256"], "manifest": args.manifest,
                      "changed_entries": result["changed_entries"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
