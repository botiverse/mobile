#!/usr/bin/env python3
"""Focused launch + render-jank benchmark for a single installed APK.

Task #187 (1.9.0 release validation, CI emulator / SwiftShader softGPU).
Reuses the `scripts/perf` conventions but is self-contained (direct `adb`,
stdlib only) so it runs unattended inside android-emulator-runner.

What it measures, N>=5 samples each, and writes to <out>/launch_bench.json:

  cold  : `pm clear` (wipe process + data) -> `am start -W` -> parse
          TotalTime/WaitTime. True from-scratch process start to first frame.
  warm  : KEYCODE_HOME (process kept alive) -> `am start -W`. Resume path.
  startup_jank : after a launch, `dumpsys gfxinfo <pkg> reset`, drive a few
          generic swipes on whatever surface is shown, then parse
          `dumpsys gfxinfo <pkg>` Janky-frames + 50/90/95/99th percentile.

IMPORTANT SCOPE / HONESTY:
  * On a softGPU emulator, frame-timing / jank is RELATIVE / TREND evidence
    only -- never extrapolate to a physical-GPU absolute. The caller labels it.
  * This binary is the immutable product alpha with NO logged-in session, so
    the surface reached is onboarding/login. Home/Activity/Thread jank needs an
    authenticated account path and is reported UNKNOWN by the caller, not here.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from statistics import median


def adb(device: str, *args: str, check: bool = True, timeout: float = 60.0) -> str:
    proc = subprocess.run(
        ["adb", "-s", device, *args],
        capture_output=True, text=True, timeout=timeout,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"adb {' '.join(args)} rc={proc.returncode} stderr={proc.stderr.strip()!r}"
        )
    return proc.stdout


def sh(device: str, cmd: str, *, check: bool = True, timeout: float = 60.0) -> str:
    return adb(device, "shell", cmd, check=check, timeout=timeout)


def percentile(values: list[float], pct: float) -> float | None:
    """Nearest-rank percentile on a copy of the samples (no interpolation)."""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = max(1, min(len(ordered), round(pct / 100.0 * len(ordered))))
    return ordered[rank - 1]


def summarize(values: list[float]) -> dict:
    return {
        "n": len(values),
        "samples": values,
        "min": min(values) if values else None,
        "p50": median(values) if values else None,
        "p95": percentile(values, 95),
        "max": max(values) if values else None,
    }


_TOTAL = re.compile(r"TotalTime:\s*(\d+)")
_WAIT = re.compile(r"WaitTime:\s*(\d+)")


def parse_am_start(out: str) -> dict | None:
    """`am start -W` prints TotalTime/WaitTime (ms). None if launch failed."""
    if "Error" in out and "TotalTime" not in out:
        return None
    tm = _TOTAL.search(out)
    wm = _WAIT.search(out)
    if not tm:
        return None
    return {"total_ms": int(tm.group(1)),
            "wait_ms": int(wm.group(1)) if wm else None}


def cold_launch(device: str, package: str, component: str) -> dict | None:
    sh(device, f"am force-stop {package}", check=False)
    sh(device, f"pm clear {package}", check=False)  # true cold: wipe app data
    time.sleep(2.0)
    out = sh(device, f"am start -W -n {component}", check=False, timeout=90)
    time.sleep(2.5)  # let first frame settle before the next iteration
    return parse_am_start(out)


def warm_launch(device: str, package: str, component: str) -> dict | None:
    # Warm start = process killed but app DATA + OS caches warm (the typical
    # "reopen from a killed app" path). force-stop (not pm clear) so it's
    # distinct from the pm-clear cold above, and NOT a HOME hot-resume (which
    # reports TotalTime 0 because nothing is actually launched).
    sh(device, f"am force-stop {package}", check=False)
    time.sleep(1.5)
    out = sh(device, f"am start -W -n {component}", check=False, timeout=90)
    time.sleep(1.5)
    return parse_am_start(out)


_JANK = re.compile(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)")
_TOTAL_FRAMES = re.compile(r"Total frames rendered:\s*(\d+)")
_PCTILE = re.compile(r"(\d+)th percentile:\s*(\d+)ms")


def parse_gfxinfo(out: str) -> dict:
    tf = _TOTAL_FRAMES.search(out)
    jk = _JANK.search(out)
    pct = {int(m.group(1)): int(m.group(2)) for m in _PCTILE.finditer(out)}
    return {
        "total_frames": int(tf.group(1)) if tf else None,
        "janky_frames": int(jk.group(1)) if jk else None,
        "janky_pct": float(jk.group(2)) if jk else None,
        "frame_p50_ms": pct.get(50),
        "frame_p90_ms": pct.get(90),
        "frame_p95_ms": pct.get(95),
        "frame_p99_ms": pct.get(99),
    }


def startup_jank(device: str, package: str, component: str,
                 swipes: int = 8) -> dict:
    """Launch, reset gfxinfo, drive generic swipes, read framestats.

    Surface is whatever the un-authed alpha shows (onboarding/login). This is a
    softGPU RELATIVE/TREND datapoint labelled as 'startup surface', NOT a
    Home/Activity/Thread measurement.
    """
    sh(device, f"am start -W -n {component}", check=False, timeout=90)
    time.sleep(2.0)
    sh(device, f"dumpsys gfxinfo {package} reset", check=False)
    # Read screen size for centred vertical swipes.
    size = sh(device, "wm size", check=False)
    m = re.search(r"(\d+)x(\d+)", size)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2280)
    cx = w // 2
    for i in range(swipes):
        y1, y2 = (int(h * 0.75), int(h * 0.30)) if i % 2 == 0 else (int(h * 0.30), int(h * 0.75))
        sh(device, f"input swipe {cx} {y1} {cx} {y2} 250", check=False)
        time.sleep(0.4)
    out = sh(device, f"dumpsys gfxinfo {package}", check=False, timeout=60)
    return parse_gfxinfo(out)


def main() -> int:
    p = argparse.ArgumentParser(description="Launch + render-jank benchmark (task #187).")
    p.add_argument("--device", required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--component", required=True,
                   help="launchable component, e.g. build.raft.app.alpha/build.raft.app.MainActivity")
    p.add_argument("--iterations", type=int, default=6)
    p.add_argument("--out", default="artifacts/perf-launch")
    args = p.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Fail loud, never silent-green: an unresolved launcher would make every
    # `am start` a no-op and yield an all-null "PASS". Refuse to run.
    component = args.component.strip()
    if not component or "/" not in component:
        print(f"FATAL: empty/invalid launcher component {args.component!r} "
              f"for {args.package}; cannot launch. Failing the run.", flush=True)
        return 2
    args.component = component

    result: dict = {
        "device": args.device,
        "package": args.package,
        "component": args.component,
        "iterations_requested": args.iterations,
        "gpu": "swiftshader_indirect (softGPU) - jank is RELATIVE/TREND only",
    }

    cold: list[float] = []
    for i in range(args.iterations):
        r = cold_launch(args.device, args.package, args.component)
        print(f"[cold {i+1}/{args.iterations}] {r}", flush=True)
        if r:
            cold.append(r["total_ms"])
    result["cold_launch_total_ms"] = summarize(cold)

    warm: list[float] = []
    for i in range(args.iterations):
        r = warm_launch(args.device, args.package, args.component)
        print(f"[warm {i+1}/{args.iterations}] {r}", flush=True)
        if r:
            warm.append(r["total_ms"])
    result["warm_launch_total_ms"] = summarize(warm)

    try:
        result["startup_surface_jank"] = startup_jank(
            args.device, args.package, args.component)
    except Exception as exc:  # noqa: BLE001 - record, don't abort the run
        result["startup_surface_jank"] = {"error": str(exc)}
    print(f"[startup_jank] {result['startup_surface_jank']}", flush=True)

    # Surfaces requiring an authenticated session on the immutable alpha.
    result["home_activity_thread_jank"] = {
        "status": "UNKNOWN",
        "reason": "immutable product alpha has no scripted logged-in account "
                  "path in CI; Home/Activity/Thread require auth. Per release "
                  "policy: no account/fixture path -> UNKNOWN, not extrapolated.",
    }

    (out_dir / "launch_bench.json").write_text(json.dumps(result, indent=2))
    print("\n=== launch_bench.json ===", flush=True)
    print(json.dumps(result, indent=2), flush=True)

    # Fail loud if nothing was actually measured, so the run goes RED instead of
    # reporting an all-null "success" that reads as a passing benchmark.
    if not cold and not warm:
        print("FATAL: 0 cold and 0 warm launches captured; "
              "every am start failed. Failing the run.", flush=True)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
