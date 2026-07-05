from __future__ import annotations

import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Iterable

from .hdc import HarmonyDevice, quote_shell
from .runner import HarmonyUseError

TLS_NATIVE_STUB = "TLS_sessions_are_not_supported_on_Native_platform"
NETWORKKMM_MARKERS = (
    "slock_networkkmm_failed",
    "slock_networkkmm_init_failed",
    "slock_networkkmm",
)
TLS_VERIFY_MARKERS = (
    "CURLE_SSL",
    "SSL peer certificate",
    "peer verify",
    "certificate verify",
    "certificate verification",
    "SSL certificate problem",
)


def capture_hilog(
    device: HarmonyDevice,
    *,
    out: Path,
    bundle: str | None = None,
    tag: str | None = None,
    contains: str | None = None,
    duration: float = 10.0,
) -> dict[str, object]:
    out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out / f"hilog-{stamp}.txt"
    args = ["shell", "hilog"]
    if tag:
        args = ["shell", f"hilog -D {quote_shell(tag)}"]
    result = device.hdc(args, check=False, timeout=duration if duration > 0 else None)
    text = result.stdout + result.stderr
    lines = filter_hilog_lines(text.splitlines(), bundle=bundle, tag=tag, contains=contains)
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return {
        "target": device.ensure_target(),
        "path": str(path),
        "line_count": len(lines),
        "analysis": analyze_hilog_lines(lines),
    }


def filter_hilog_lines(
    lines: Iterable[str],
    *,
    bundle: str | None = None,
    tag: str | None = None,
    contains: str | None = None,
) -> list[str]:
    filtered: list[str] = []
    for line in lines:
        if bundle and bundle not in line:
            continue
        if tag and tag not in line:
            continue
        if contains and contains not in line:
            continue
        filtered.append(line)
    return filtered


def analyze_hilog_file(path: Path) -> dict[str, object]:
    if not path.exists():
        raise HarmonyUseError(f"HiLog file not found: {path}")
    return analyze_hilog_text(path.read_text(encoding="utf-8", errors="replace"))


def analyze_hilog_text(text: str) -> dict[str, object]:
    return analyze_hilog_lines(text.splitlines())


def analyze_hilog_lines(lines: Iterable[str]) -> dict[str, object]:
    line_list = list(lines)
    tags = Counter()
    slock_auth: list[str] = []
    network_failures: list[str] = []
    tls_verify_failures: list[str] = []
    crashes: list[str] = []
    for line in line_list:
        tag = extract_tag(line)
        if tag:
            tags[tag] += 1
        if "SlockSharedAuth" in line or "shared_auth_" in line:
            slock_auth.append(line)
        if is_network_failure_line(line):
            network_failures.append(line)
        if is_tls_verify_failure_line(line):
            tls_verify_failures.append(line)
        if "SIGSEGV" in line or "SEGV_" in line or "FATAL" in line:
            crashes.append(line)
    return {
        "line_count": len(line_list),
        "top_tags": tags.most_common(10),
        "slock_auth_count": len(slock_auth),
        "network_failure_count": len(network_failures),
        "tls_verify_failure_count": len(tls_verify_failures),
        "crash_count": len(crashes),
        # If this is true after the NetworkKMM migration, the app likely ran an old Ktor/CIO build.
        "tls_native_stub": any(TLS_NATIVE_STUB in line for line in network_failures),
        "networkkmm_failure": any(any(marker in line for marker in NETWORKKMM_MARKERS) for line in network_failures),
        "tls_verify_failure": bool(tls_verify_failures),
        "samples": {
            "slock_auth": slock_auth[-5:],
            "network_failures": network_failures[-5:],
            "tls_verify_failures": tls_verify_failures[-5:],
            "crashes": crashes[-5:],
        },
    }


def extract_tag(line: str) -> str | None:
    match = re.search(r"\b([A-Za-z0-9_.-]*Slock[A-Za-z0-9_.-]*)\b", line)
    return match.group(1) if match else None


def is_network_failure_line(line: str) -> bool:
    return (
        "slock_auth_ktor_failed" in line
        or TLS_NATIVE_STUB in line
        or any(marker in line for marker in NETWORKKMM_MARKERS)
    )


def is_tls_verify_failure_line(line: str) -> bool:
    return any(marker.lower() in line.lower() for marker in TLS_VERIFY_MARKERS)
