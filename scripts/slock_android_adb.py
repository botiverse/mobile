#!/usr/bin/env python3
"""Compatibility wrapper for the packaged android-use CLI."""

from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    repo_root = Path(__file__).resolve().parents[1]
    src = repo_root / "tools" / "mobile-use" / "src"
    if src.exists():
        sys.path.insert(0, str(src))
    from android_use.cli import main as android_use_main

    return android_use_main()


if __name__ == "__main__":
    sys.exit(main())
