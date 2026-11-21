#!/usr/bin/env python3
"""Rebuild an AAR with a replacement classes.jar.

Every entry of the official AAR is written again in the same order, with the same name, timestamp,
compression method, file attributes, extra field and comment; only the content of classes.jar is
replaced. Entries are compressed again, so compressed sizes may differ; verify_patch.py compares
the content of every entry.

Usage: repack_aar.py OFFICIAL_AAR CLASSES_JAR OUT_AAR
"""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path

CLASSES_JAR = "classes.jar"


def fail(message: str) -> None:
    print(f"repack_aar: {message}", file=sys.stderr)
    sys.exit(1)


def main(argv: list[str]) -> None:
    if len(argv) != 4:
        fail(__doc__.strip().splitlines()[-1])
    official, classes, out = Path(argv[1]), Path(argv[2]), Path(argv[3])
    if out.exists():
        fail(f"{out} already exists")
    replacement = classes.read_bytes()
    with zipfile.ZipFile(official) as source:
        infos = source.infolist()
        names = [info.filename for info in infos]
        if len(set(names)) != len(names):
            fail(f"{official} has duplicate entry names")
        if names.count(CLASSES_JAR) != 1:
            fail(f"{official} has no {CLASSES_JAR}")
        with zipfile.ZipFile(out, "x") as target:
            target.comment = source.comment
            for info in infos:
                copy = zipfile.ZipInfo(info.filename, info.date_time)
                for attribute in ("compress_type", "comment", "extra", "create_system", "create_version",
                                  "extract_version", "internal_attr", "external_attr"):
                    setattr(copy, attribute, getattr(info, attribute))
                data = replacement if info.filename == CLASSES_JAR else source.read(info)
                target.writestr(copy, data)
    print(f"{out}: {len(infos)} entries as in {official.name}, {CLASSES_JAR} replaced by {classes.name} "
          f"({len(replacement)} bytes)")


if __name__ == "__main__":
    main(sys.argv)
