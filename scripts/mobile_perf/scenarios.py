"""Registry of first-batch mobile performance scenarios on fixture ``mobile-bench-v1``."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

DEFAULT_FIXTURE_TAG = "mobile-bench-v1"
ANDROID_PACKAGE = "build.raft.app"
IOS_BUNDLE_ID = "build.raft.app"
VALID_CALIBRATION_MODES: frozenset[str] = frozenset({"latency_ms", "slow_frames"})


@dataclass(frozen=True)
class ScenarioSpec:
    """Declarative specification for one closed-loop mobile performance scenario."""

    name: str
    online_events: tuple[str, ...]
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
    calibration_mode: str = "latency_ms"
    calibration_metric: str = "duration_ms"
    ios_calibration_metric: str = "duration_ms"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


SCENARIOS: dict[str, ScenarioSpec] = {
    "cold_start": ScenarioSpec(
        name="cold_start",
        online_events=("mobile.cold_start", "startup"),
        description="Force-stopped cold launch to TTID/TTFD and first feed render on mobile-bench-v1.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="ttfd_ms",
        ios_primary_kpi="ttfd_ms",
        secondary_kpis=("duration_ms", "mem_peak_mb", "frame_p95_ms"),
        required_segments=(
            "process_init_ms",
            "compose_first_frame_ms",
            "auth_bootstrap_ms",
            "first_feed_render_ms",
        ),
        optional_segments=("frame_count", "ttid_ms"),
        default_warm_state="cold",
        calibration_mode="latency_ms",
        calibration_metric="ttfd_ms",
        ios_calibration_metric="ttfd_ms",
        android_capture={
            "package": ANDROID_PACKAGE,
            "component": f"{ANDROID_PACKAGE}/.MainActivity",
            "duration_ms": 8000,
            "force_stop": True,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "ActivityTaskManager|SlockPerf|SlockPerfScene|cold_start",
            "calibration_mode": "latency_ms",
            "calibration_metric": "ttfd_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 8000,
            "cold_launch": True,
            "xctrace_templates": ("App Launch", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "latency_ms",
            "calibration_metric": "ttfd_ms",
        },
    ),
    "channel_switch": ScenarioSpec(
        name="channel_switch",
        online_events=("mobile.frames.nav_transition",),
        description="Switch between channels/tabs and measure navigation transition latency and frames.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("ttfd_ms", "frame_p95_ms", "frame_p99_ms", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=(
            "input_delay_ms",
            "network_ms",
            "recompose_layout_ms",
            "render_gpu_ms",
            "frame_count",
        ),
        optional_segments=(),
        default_warm_state="profile_warm",
        calibration_mode="latency_ms",
        calibration_metric="duration_ms",
        ios_calibration_metric="duration_ms",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 6000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|bottom_tab_|channel_switch|nav_transition",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 6000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
    ),
    "thread_scroll_ime": ScenarioSpec(
        name="thread_scroll_ime",
        online_events=("mobile.frames.scroll",),
        description="Sustained fling scroll across a dense thread with IME open/dismiss settle.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="frame_p95_ms",
        ios_primary_kpi="hitch_ms_per_s",
        secondary_kpis=("duration_ms", "frame_p50_ms", "frame_p99_ms", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=(
            "ui_thread_ms",
            "recompose_ms",
            "render_thread_or_metal_ms",
            "ime_settle_ms",
            "frame_count",
        ),
        optional_segments=(),
        default_warm_state="profile_warm",
        calibration_mode="slow_frames",
        calibration_metric="slow_frame_ratio",
        ios_calibration_metric="hitch_ms_per_s",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 10000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "swipe_count": 6,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|thread_scroll|ime_settle",
            "calibration_mode": "slow_frames",
            "calibration_metric": "slow_frame_ratio",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 10000,
            "cold_launch": False,
            "swipe_count": 6,
            "xctrace_templates": ("Animation Hitches", "Metal System Trace", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "slow_frames",
            "calibration_metric": "hitch_ms_per_s",
        },
    ),
    "composer_first_char": ScenarioSpec(
        name="composer_first_char",
        online_events=("first_char_latency",),
        description="First-character keystroke latency and frame pacing in the thread composer.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("frame_p95_ms", "frame_p99_ms", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=(
            "first_char_p50_ms",
            "first_char_p95_ms",
            "key_to_state_ms",
            "text_layout_ms",
            "recompose_ms",
            "draw_ms",
            "frame_count",
        ),
        optional_segments=(),
        default_warm_state="profile_warm",
        calibration_mode="latency_ms",
        calibration_metric="duration_ms",
        ios_calibration_metric="duration_ms",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 8000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "keystroke_count": 40,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|thread_composer|first_char_latency",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 8000,
            "cold_launch": False,
            "keystroke_count": 40,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
    ),
    "webview_open": ScenarioSpec(
        name="webview_open",
        online_events=("mobile.webview.load",),
        description="Open an interactive HTML Raft Artifact in the in-app WebView/WKWebView.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("mem_peak_mb", "ttfd_ms", "frame_p95_ms"),
        required_segments=(
            "container_init_ms",
            "prewarm_hit_count",
            "network_ms",
            "first_paint_ms",
        ),
        optional_segments=("frame_count",),
        default_warm_state="profile_warm",
        calibration_mode="latency_ms",
        calibration_metric="duration_ms",
        ios_calibration_metric="duration_ms",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 10000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|webview|artifact",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 10000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
    ),
    "thread_open": ScenarioSpec(
        name="thread_open",
        online_events=("mobile.thread_open",),
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
            "frame_count",
        ),
        optional_segments=("first_frame_ms", "target_positioned_ms", "stable_ms"),
        default_warm_state="profile_warm",
        calibration_mode="latency_ms",
        calibration_metric="duration_ms",
        ios_calibration_metric="duration_ms",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 6000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|open_trace_summary|open_trace_shift|reading_anchor|thread_open",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 6000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
    ),
    "foreground_resume": ScenarioSpec(
        name="foreground_resume",
        online_events=("mobile.frames.resume", "activity:foreground_sync_updated"),
        description="Return app from background to foreground and complete sync + first resumed frame.",
        fixture_tag=DEFAULT_FIXTURE_TAG,
        primary_kpi="duration_ms",
        ios_primary_kpi="duration_ms",
        secondary_kpis=("frame_p95_ms", "mem_peak_mb", "slow_frame_ratio", "hitch_ms_per_s"),
        required_segments=(
            "resume_first_frame_ms",
            "mutex_wait_ms",
            "jitter_wait_ms",
            "network_fetch_ms",
            "rss_mb",
            "pss_mb",
            "frame_count",
        ),
        optional_segments=(),
        default_warm_state="profile_warm",
        calibration_mode="latency_ms",
        calibration_metric="duration_ms",
        ios_calibration_metric="duration_ms",
        android_capture={
            "package": ANDROID_PACKAGE,
            "duration_ms": 6000,
            "force_stop": False,
            "gfxinfo_reset": True,
            "perfetto_categories": ("gfx", "view", "binder_driver", "am", "wm"),
            "logcat_filter": "SlockPerf|foreground_resume|foreground_sync_updated",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
        },
        ios_capture={
            "bundle_id": IOS_BUNDLE_ID,
            "duration_ms": 6000,
            "cold_launch": False,
            "xctrace_templates": ("Animation Hitches", "Time Profiler"),
            "signpost_subsystem": "build.raft.app.perf",
            "calibration_mode": "latency_ms",
            "calibration_metric": "duration_ms",
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
    """Return the scenario's primary KPI, respecting platform-specific scroll KPI."""
    spec = get_scenario(scenario_name)
    if project == "raft-ios":
        return spec.ios_primary_kpi
    return spec.primary_kpi


def get_calibration_config(
    scenario_name: str,
    *,
    project: str | None = None,
) -> tuple[str, str]:
    """Return ``(calibration_mode, calibration_metric)`` for a scenario and platform."""
    spec = get_scenario(scenario_name)
    metric = spec.ios_calibration_metric if project == "raft-ios" else spec.calibration_metric
    return spec.calibration_mode, metric
