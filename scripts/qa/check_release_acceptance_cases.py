#!/usr/bin/env python3
"""Validate the canonical source set and its generated automated plan."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from release_acceptance_contract import ContractError, DEFAULT_CASE_DIR, load_contract


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case-dir", type=Path, default=DEFAULT_CASE_DIR)
    parser.add_argument(
        "--emit-plan",
        action="store_true",
        help="emit the validated 25-case create-run input instead of a status line",
    )
    args = parser.parse_args()
    try:
        contract = load_contract(args.case_dir)
    except ContractError as exc:
        print(f"release acceptance case contract FAILED: {exc}", file=sys.stderr)
        return 1
    if args.emit_plan:
        print(json.dumps(contract.plan(), ensure_ascii=False, indent=2))
    else:
        print("release acceptance case contract PASS: 26 canonical = 25 automated + 1 manual-only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
