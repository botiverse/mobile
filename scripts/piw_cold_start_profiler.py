#!/usr/bin/env python3
"""Cold-start profiler for Slock Android (RaftLegacyHostActivity).

Stops the app, clears logcat, launches cold, then at each checkpoint captures:
- top -H -p <pid>           (per-thread CPU snapshot)
- kill -3 <pid>             (Dalvik/ART thread dump -> logcat)
- dumpsys cpuinfo           (system CPU pressure)
- logcat tail with marker   (so we can correlate)

Output: artifacts/android-adb/<run-id>/ with one subdir per checkpoint.

Usage:
  python3 scripts/piw_cold_start_profiler.py \
      --device emulator-5554 \
      --package build.raft.app \
      --component build.raft.app/.shared.RaftLegacyHostActivity \
      --checkpoints 0.5 1 2 3 5 8 \
      --out /Users/artin/Downloads/piw-coldstart

Written for one-shot task #125, so intentionally small and explicit.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import List


def run(
    cmd: List[str],
    *,
    capture: bool = True,
    timeout: float = 30.0,
    check: bool = False,
) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        capture_output=capture,
        text=True,
        timeout=timeout,
        check=check,
    )


def adb_shell(device: str, command: str, *, timeout: float = 30.0) -> str:
    result = run(["adb", "-s", device, "shell", command], timeout=timeout)
    return result.stdout or ""


def adb_shell_with_rc(device: str, command: str, *, timeout: float = 30.0):
    return run(["adb", "-s", device, "shell", command], timeout=timeout)


def get_pid(device: str, package: str) -> str | None:
    out = adb_shell(device, f"pidof {package}", timeout=5.0)
    return out.strip() or None


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Cold-start profiler for Slock Android.")
    parser.add_argument("--device", required=True, help="adb serial, e.g. emulator-5554")
    parser.add_argument("--package", required=True, help="app package, e.g. build.raft.app")
    parser.add_argument(
        "--component",
        required=True,
        help="activity component, e.g. build.raft.app/.shared.RaftLegacyHostActivity",
    )
    parser.add_argument(
        "--checkpoints",
        nargs="+",
        type=float,
        default=[0.5, 1.0, 2.0, 3.0, 5.0, 8.0],
        help="seconds-after-launch checkpoints",
    )
    parser.add_argument(
        "--out",
        default=str(Path.home() / "Downloads" / "piw-coldstart"),
        help="output directory",
    )
    parser.add_argument(
        "--pre-launch-sleep",
        type=float,
        default=1.0,
        help="seconds to wait between force-stop and start",
    )
    args = parser.parse_args()

    run_id = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_root = Path(args.out) / run_id
    out_root.mkdir(parents=True, exist_ok=True)

    summary: dict = {
        "device": args.device,
        "package": args.package,
        "component": args.component,
        "checkpoints": args.checkpoints,
        "started_at": datetime.datetime.now().isoformat(),
        "stages": [],
    }

    print(f"[{run_id}] output: {out_root}")

    # 1. Pre-launch: force-stop + logcat clear + capture baseline cpuinfo.
    print("[stage] force-stop")
    adb_shell(args.device, f"am force-stop {args.package}", timeout=10.0)
    time.sleep(args.pre_launch_sleep)

    print("[stage] logcat -c")
    adb_shell(args.device, "logcat -c", timeout=5.0)

    print("[stage] baseline cpuinfo")
    write_file(out_root / "00_baseline_cpuinfo.txt", adb_shell(args.device, "dumpsys cpuinfo", timeout=10.0))
    write_file(out_root / "00_baseline_top.txt", adb_shell(args.device, "top -n 1 -b", timeout=10.0))

    # 2. Start the activity cold.
    print(f"[stage] am start -W -n {args.component}")
    start_marker_ts = time.time()
    start_rc = adb_shell_with_rc(
        args.device,
        f"am start -W -n {args.component}",
        timeout=15.0,
    )
    write_file(
        out_root / "00_am_start.txt",
        f"returncode={start_rc.returncode}\nstdout:\n{start_rc.stdout}\nstderr:\n{start_rc.stderr}",
    )
    summary["stages"].append({
        "name": "am_start",
        "elapsed_s": time.time() - start_marker_ts,
        "stdout_first_line": (start_rc.stdout or "").splitlines()[0] if start_rc.stdout else "",
    })

    # 3. Per-checkpoint capture loop.
    pid = None
    sorted_checkpoints = sorted(args.checkpoints)
    for idx, t in enumerate(sorted_checkpoints):
        target = start_marker_ts + t
        sleep_for = target - time.time()
        if sleep_for > 0:
            time.sleep(sleep_for)
        actual = time.time() - start_marker_ts
        cp_dir = out_root / f"cp_{idx:02d}_t{int(t*1000):04d}ms"
        cp_dir.mkdir(parents=True, exist_ok=True)

        # Refresh PID if not yet known.
        if pid is None:
            pid = get_pid(args.device, args.package)
        stage_meta: dict = {"name": f"checkpoint_{idx}", "target_t": t, "actual_t": round(actual, 3), "pid": pid}

        if pid is None:
            print(f"[warn] checkpoint {idx} (t={t}s): no pid for {args.package} yet")
            stage_meta["note"] = "no_pid"
            write_file(cp_dir / "no_pid.txt", f"pidof {args.package} returned empty")
            summary["stages"].append(stage_meta)
            continue

        # 3a. kill -3 to request Dalvik thread dump -> captured in logcat.
        # Use SIGQUIT (3) to ART/Dalvik; output goes to logcat.
        adb_shell(args.device, f"kill -3 {pid}", timeout=5.0)

        # 3b. top -H per-thread CPU at this instant.
        write_file(cp_dir / "top_threads.txt", adb_shell(args.device, f"top -H -n 1 -b -p {pid}", timeout=10.0))

        # 3c. /proc/<pid>/task/<tid>/stat for all threads (richer than top).
        threads_dir = cp_dir / "proc_task_stat"
        threads_dir.mkdir(exist_ok=True)
        listing = adb_shell(args.device, f"ls /proc/{pid}/task/", timeout=5.0)
        for tid in listing.split():
            stat = adb_shell(args.device, f"cat /proc/{pid}/task/{tid}/stat", timeout=5.0)
            name = adb_shell(args.device, f"cat /proc/{pid}/task/{tid}/comm", timeout=5.0).strip()
            write_file(threads_dir / f"{tid}_{name}.stat", stat)

        # 3d. dumpsys cpuinfo for system context.
        write_file(cp_dir / "dumpsys_cpuinfo.txt", adb_shell(args.device, "dumpsys cpuinfo", timeout=10.0))

        # 3e. dumpsys gfxinfo for that pid (per-frame jank).
        write_file(cp_dir / "dumpsys_gfxinfo.txt", adb_shell(args.device, f"dumpsys gfxinfo {args.package}", timeout=15.0))

        # 3f. Current activity stack.
        write_file(cp_dir / "dumpsys_activity.txt", adb_shell(args.device, "dumpsys activity activities", timeout=10.0))

        print(f"[checkpoint {idx}] t={actual:.2f}s pid={pid} captured")
        summary["stages"].append(stage_meta)

    # 4. Final dump: pull the logcat to capture all kill -3 thread dumps that landed there.
    print("[stage] capture logcat")
    logcat_path = out_root / "logcat_full.txt"
    rc = run(
        ["adb", "-s", args.device, "logcat", "-d", "-v", "threadtime", "*:V"],
        timeout=30.0,
    )
    write_file(logcat_path, rc.stdout or "")

    # 5. Final SIGQUIT-dump snapshot for the per-thread state right now (>= max checkpoint).
    if pid:
        adb_shell(args.device, f"kill -3 {pid}", timeout=5.0)
        time.sleep(0.5)
        rc = run(["adb", "-s", args.device, "logcat", "-d", "-v", "threadtime", "*:V"], timeout=30.0)
        write_file(out_root / "logcat_final.txt", rc.stdout or "")

    summary["finished_at"] = datetime.datetime.now().isoformat()
    write_file(out_root / "summary.json", json.dumps(summary, indent=2))

    print(f"[done] artifacts in {out_root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())