from __future__ import annotations

from pathlib import Path


NETWORK_PATTERNS = (
    "slock_auth_ktor_failed",
    "slock_networkkmm",
    "unknownhostexception",
    "connectexception",
    "sslhandshakeexception",
    "curle_ssl",
    "network unavailable",
)

CRASH_PATTERNS = (
    "fatal exception",
    "konan_main",
    "kuiklyexception",
    "androidruntime",
)

RENDER_PATTERNS = (
    "kuikly",
    "slocklucide",
    "recomposition",
    "profiler",
)


def analyze_logcat_file(path: Path) -> dict[str, object]:
    return analyze_logcat_text(path.read_text(encoding="utf-8", errors="replace"))


def analyze_logcat_text(text: str) -> dict[str, object]:
    lines = text.splitlines()
    network = matching_lines(lines, NETWORK_PATTERNS)
    crashes = matching_lines(lines, CRASH_PATTERNS)
    render = matching_lines(lines, RENDER_PATTERNS)
    return {
        "line_count": len(lines),
        "network_failure_count": len(network),
        "crash_count": len(crashes),
        "render_signal_count": len(render),
        "has_network_failure": bool(network),
        "has_crash": bool(crashes),
        "samples": {
            "network": network[:5],
            "crash": crashes[:5],
            "render": render[:5],
        },
    }


def matching_lines(lines: list[str], patterns: tuple[str, ...]) -> list[str]:
    result: list[str] = []
    for line in lines:
        lower = line.lower()
        if any(pattern in lower for pattern in patterns):
            result.append(line)
    return result
