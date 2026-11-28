"""Registry of first-batch mobile performance scenarios on fixture ``mobile-bench-v1``."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

DEFAULT_FIXTURE_TAG = "mobile-bench-v1"
ANDROID_PACKAGE = "build.raft.app"
IOS_BUNDLE_ID = "build.raft.app"


@dataclass(frozen=True)
class ScenarioSpec:
    """Declarative specification for one closed-loop mobile performance scenario."""

    name: str
    description: str
    fixture_tag: str
    primary_kpi: str
    ios_primary_kpi: str
    secondary_kpis: tuple[str, ...]
    required_segments: tuple[str, ...]
    optional_segments: tuple[str, ...]
    default_warm_state: str
    android_capture: dict[str, Any]
    ios_capture: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


SCENARIOS: dict[str, ScenarioSpec] = {
    "cold_start_home": ScenarioSpec(
        name="cold_start_home",
        description="Force-stopped launch to first usable home feed on mobile-bench-v1.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="ttfd_ms",
        ios_primary_kpi="ttfd_ms",
        secondary_kpis=("duration_ms", "frame_p95_ms", "mem_peak_mb"),
        required_segments=("app_init_ms", "first_frame_ms"),
        optional_segments=("home_feed_ready_ms", "frame_count"),
        default_warm_state="cold",
        android_capture={
            "package": ANDROID_PACKAGE,
            "component": f"{ANDROID_PACKAGE}/.MainActivity",
            "duration_ms": 8000,
            "force_stop": True,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "ActivityTaskManager|SlockPerf|SlockPerfScene",
            "calibration_metric": "ttfd_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 8000,
            "cold_launch": True,
            "xctrace_templates": ("App Launch", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "ttfd_ms",
        },
    ),
    "thread_open": ScenarioSpec(
        name="thread_open",
        description="Open a dense benchmark thread and settle viewport without anchor jitter.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("frame_p95_ms", "slow_frame_ratio", "hitch_ms_per_s", "mem_peak_mb"),
        required_segments=(
            "cache_rows_shown_ms",
            "network_applied_ms",
            "reading_anchor_attempt_count",
            "visible_shift_dp",
            "shift_count",
        ),
        optional_segments=("first_frame_ms", "target_positioned_ms", "stable_ms", "frame_count"),
        default_warm_state="profile_warm",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 6000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|open_trace_summary|open_trace_shift|reading_anchor|thread_open",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 6000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "duration_ms",
        },
    ),
    "thread_scroll_media": ScenarioSpec(
        name="thread_scroll_media",
        description="Sustained fling scroll across an image- and attachment-heavy thread.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="frame_p95_ms",
        ios_primary_kpi="hitch_ms_per_s",
        secondary_kpis=("frame_p50_ms", "frame_p99_ms", "slow_frame_ratio", "hitch_ms_per_s", "mem_peak_mb"),
        required_segments=("frame_count", "image_decode_ms"),
        optional_segments=("scroll_distance_dp", "dropped_frame_count"),
        default_warm_state="profile_warm",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 10000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "swipe_count": 6,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|thread_scroll|image_decode",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 10000,
            "cold_launch": False,
            "swipe_count": 6,
            "xctrace_templates": ("Animation Hitches", "Metal System Trace", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "duration_ms",
        },
    ),
    "activity_switch": ScenarioSpec(
        name="activity_switch",
        description="Switch between Activity/Inbox and Channel tabs back-to-back.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("frame_p95_ms", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=("tab_switch_ms", "frame_count"),
        optional_segments=("jank_frame_count",),
        default_warm_state="profile_warm",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 6000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|bottom_tab_|tab_switch",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 6000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "duration_ms",
        },
    ),
    "composer_typing": ScenarioSpec(
        name="composer_typing",
        description="Rapid text entry inside thread composer with IME open.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="frame_p95_ms",
        ios_primary_kpi="hitch_ms_per_s",
        secondary_kpis=("frame_p50_ms", "frame_p99_ms", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=("keystroke_count", "frame_count"),
        optional_segments=("ime_layout_ms", "input_latency_p95_ms"),
        default_warm_state="profile_warm",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 8000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "keystroke_count": 40,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|thread_composer|composer_typing",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 8000,
            "cold_launch": False,
            "keystroke_count": 40,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "duration_ms",
        },
    ),
    "composer_send": ScenarioSpec(
        name="composer_send",
        description="Tap send in composer to optimistic message render and list settle.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("frame_p95_ms", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=("optimistic_render_ms", "frame_count"),
        optional_segments=("ack_applied_ms", "jank_frame_count"),
        default_warm_state="profile_warm",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 6000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|composer_send|thread_composer",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 6000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "duration_ms",
        },
    ),
    "webview_artifact_open": ScenarioSpec(
        name="webview_artifact_open",
        description="Open an interactive HTML Raft Artifact in the in-app WebView/WKWebView.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="ttfd_ms",
        ios_primary_kpi="ttfd_ms",
        secondary_kpis=("duration_ms", "frame_p95_ms", "mem_peak_mb"),
        required_segments=("webview_ready_ms", "artifact_dom_ready_ms"),
        optional_segments=("bridge_init_ms", "frame_count"),
        default_warm_state="profile_warm",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 10000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|webview|artifact",
            "calibration_metric": "ttfd_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 10000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_metric": "ttfd_ms",
        },
    ),
}

SCENARIO_NAMES: tuple[str, ...] = tuple(SCENARIOS.keys())


def get_scenario(name: str) -> ScenarioSpec:
    """Look up a registered scenario by exact name."""
    try:
        return SCENARIOS[name]
    except KeyError as exc:
        valid = ", ".join(SCENARIO_NAMES)
        raise KeyError(f"Unknown scenario {name!r}; registered scenarios: {valid}") from exc


def list_scenarios() -> list[ScenarioSpec]:
    """Return all registered first-batch scenarios in canonical order."""
    return [SCENARIOS[name] for name in SCENARIO_NAMES]


def get_primary_kpi(scenario_name: str, *, project: str | None = None) -> str:
    """Return the scenario's primary KPI, respecting platform-specific scroll/typing KPI."""
    spec = get_scenario(scenario_name)
    if project == "raft-ios":
        return spec.ios_primary_kpi
    return spec.primary_kpi
