from __future__ import annotations

from pathlib import Path


NETWORK_PATTERNS = (
    "slock_auth_ktor_failed",
    "slock_networkkmm",
    "slock_http_",
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


def analyze_logcat_file(
    path: Path,
    *,
    package: str | None = None,
    trim_last_start: bool = False,
    trim_out: Path | None = None,
) -> dict[str, object]:
    text = path.read_text(encoding="utf-8", errors="replace")
    return analyze_logcat_text(
        text,
        package=package,
        trim_last_start=trim_last_start,
        trim_out=trim_out,
    )


def analyze_logcat_text(
    text: str,
    *,
    package: str | None = None,
    trim_last_start: bool = False,
    trim_out: Path | None = None,
) -> dict[str, object]:
    lines = text.splitlines()
    launch_events = find_launch_events(lines, package=package)
    analyzed_lines = lines
    trimmed_path: str | None = None
    if trim_last_start:
        start_index = launch_events[-1]["line_index"] if launch_events else 0
        analyzed_lines = lines[start_index:]
        if trim_out is not None:
            trim_out.parent.mkdir(parents=True, exist_ok=True)
            trim_out.write_text("\n".join(analyzed_lines) + ("\n" if analyzed_lines else ""), encoding="utf-8")
            trimmed_path = str(trim_out)
    network = matching_lines(analyzed_lines, NETWORK_PATTERNS)
    crashes = matching_lines(analyzed_lines, CRASH_PATTERNS)
    render = matching_lines(analyzed_lines, RENDER_PATTERNS)
    return {
        "line_count": len(lines),
        "analyzed_line_count": len(analyzed_lines),
        "trimmed_to_last_start": trim_last_start,
        "trimmed_path": trimmed_path,
        "package": package,
        "launch_count": len(launch_events),
        "last_launch": launch_events[-1] if launch_events else None,
        "launch_samples": launch_events[:5],
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


def find_launch_events(lines: list[str], *, package: str | None = None) -> list[dict[str, object]]:
    events: list[dict[str, object]] = []
    normalized_package = package.lower() if package else None
    for index, line in enumerate(lines):
        lower = line.lower()
        if normalized_package and normalized_package not in lower:
            continue
        kind = launch_event_kind(lower)
        if kind is None:
            continue
        events.append({"line_index": index, "kind": kind, "line": line})
    return events


def launch_event_kind(lower_line: str) -> str | None:
    if "activitytaskmanager" in lower_line and " start u0 " in lower_line:
        return "activity_start"
    if "activitymanager" in lower_line and "start proc" in lower_line:
        return "process_start"
    if "displayed " in lower_line and "/" in lower_line:
        return "activity_displayed"
    if "wm_on_resume_called" in lower_line:
        return "activity_resume"
    return None


def matching_lines(lines: list[str], patterns: tuple[str, ...]) -> list[str]:
    result: list[str] = []
    for line in lines:
        lower = line.lower()
        if any(pattern in lower for pattern in patterns):
            result.append(line)
    return result
