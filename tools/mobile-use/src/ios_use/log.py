from __future__ import annotations

from pathlib import Path


NETWORK_PATTERNS = (
    "slock_auth",
    "socket.io",
    "nw_connection",
    "nsurlerrordomain",
    "ssl",
    "network",
)

CRASH_PATTERNS = (
    "fatal error",
    "terminating app",
    "uncaught exception",
    "sigabrt",
    "konan",
)

REALTIME_PATTERNS = (
    "slockiosrealtimehost",
    "realtime",
    "socket",
)


def analyze_ios_log_file(path: Path) -> dict[str, object]:
    return analyze_ios_log_text(path.read_text(encoding="utf-8", errors="replace"))


def analyze_ios_log_text(text: str) -> dict[str, object]:
    lines = text.splitlines()
    network = matching_lines(lines, NETWORK_PATTERNS)
    crashes = matching_lines(lines, CRASH_PATTERNS)
    realtime = matching_lines(lines, REALTIME_PATTERNS)
    return {
        "line_count": len(lines),
        "network_signal_count": len(network),
        "crash_count": len(crashes),
        "realtime_signal_count": len(realtime),
        "has_network_signal": bool(network),
        "has_crash": bool(crashes),
        "samples": {
            "network": network[:5],
            "crash": crashes[:5],
            "realtime": realtime[:5],
        },
    }


def matching_lines(lines: list[str], patterns: tuple[str, ...]) -> list[str]:
    result: list[str] = []
    for line in lines:
        lower = line.lower()
        if any(pattern in lower for pattern in patterns):
            result.append(line)
    return result
