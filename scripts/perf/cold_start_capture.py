"""Cold-start perf capture for Slock Android.

Reusable extraction of `piw_cold_start_profiler.py`. Forces the app to stop,
clears logcat, launches it cold, then at each checkpoint records:

  - per-thread /proc/<pid>/task/<tid>/stat (jiffies + state)
  - top -H -n 1 -b -p <pid>
  - dumpsys cpuinfo / gfxinfo / activity activities
  - logcat tail with monotonic-relative timestamps

This is the data the Kuikly #native ANR investigation (2026-06-26) and
subsequent cold-start audits (#125) used to localize `HRContextQueueH` as
the single-thread bottleneck. The shape of the data is what downstream
`atrace_summary.py` and `regression_compare.py` consume.

Usage:
  python3 scripts/perf/cold_start_capture.py \
      --device emulator-5554 \
      --package build.raft.app \
      --component build.raft.app/.MainActivity \
      --checkpoints 0.5 1 2 3 5 8 \
      --out ./artifacts/coldstart-2026-06-26
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


def adb_shell(device: str, command: str, *, timeout: float = 30.0) -> str:
    proc = subprocess.run(
        ["adb", "-s", device, "shell", command],
        capture_output=True, text=True, timeout=timeout,
    )
    return proc.stdout or ""


def adb_shell_rc(device: str, command: str, *, timeout: float = 30.0):
    return subprocess.run(
        ["adb", "-s", device, "shell", command],
        capture_output=True, text=True, timeout=timeout,
    )


def get_pid(device: str, package: str) -> str | None:
    out = adb_shell(device, f"pidof {package}", timeout=5.0)
    return out.strip() or None


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def collect_checkpoint(device: str, pid: str, out_dir: Path) -> None:
    """One snapshot at a moment in time: top, per-thread stat, gfxinfo, etc."""
    out_dir.mkdir(parents=True, exist_ok=True)
    write(out_dir / "top_threads.txt",
          adb_shell(device, f"top -H -n 1 -b -p {pid}", timeout=10.0))
    write(out_dir / "dumpsys_cpuinfo.txt",
          adb_shell(device, "dumpsys cpuinfo", timeout=10.0))
    write(out_dir / "dumpsys_gfxinfo.txt",
          adb_shell(device, "dumpsys gfxinfo", timeout=15.0))
    write(out_dir / "dumpsys_activity.txt",
          adb_shell(device, "dumpsys activity activities", timeout=10.0))
    threads_dir = out_dir / "proc_task_stat"
    threads_dir.mkdir(exist_ok=True)
    for tid in adb_shell(device, f"ls /proc/{pid}/task/", timeout=5.0).split():
        name = adb_shell(device, f"cat /proc/{pid}/task/{tid}/comm", timeout=5.0).strip()
        stat = adb_shell(device, f"cat /proc/{pid}/task/{tid}/stat", timeout=5.0)
        write(threads_dir / f"{tid}_{name}.stat", stat)


def main() -> int:
    p = argparse.ArgumentParser(description="Cold-start perf capture for Slock Android.")
    p.add_argument("--device", required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--component", required=True,
                   help="Launcher activity component, e.g. build.raft.app/.MainActivity. "
                        "RaftLegacyHostActivity is not exported.")
    p.add_argument("--checkpoints", nargs="+", type=float,
                   default=[0.5, 1.0, 2.0, 3.0, 5.0, 8.0])
    p.add_argument("--out", default="artifacts/perf-coldstart")
    p.add_argument("--pre-launch-sleep", type=float, default=1.0)
    p.add_argument("--atrace-window", type=float, default=0.0,
                   help="If > 0, start an atrace covering (window) seconds during "
                        "the launch window. Default 0 disables atrace (use "
                        "`interaction_capture.py` for that).")
    args = p.parse_args()

    run_id = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_root = Path(args.out) / run_id
    out_root.mkdir(parents=True, exist_ok=True)

    summary = {
        "tool": "perf.cold_start_capture",
        "version": "1.0",
        "started_at": datetime.datetime.now().isoformat(),
        "device": args.device,
        "package": args.package,
        "component": args.component,
        "checkpoints_requested": args.checkpoints,
        "atrace_window_seconds": args.atrace_window,
        "stages": [],
    }

    print(f"[{run_id}] output: {out_root}", flush=True)

    # 1. baseline + force-stop + logcat clear.
    adb_shell(args.device, f"am force-stop {args.package}", timeout=10.0)
    time.sleep(args.pre_launch_sleep)
    adb_shell(args.device, "logcat -c", timeout=5.0)
    write(out_root / "00_baseline_cpuinfo.txt",
          adb_shell(args.device, "dumpsys cpuinfo", timeout=10.0))
    write(out_root / "00_baseline_top.txt",
          adb_shell(args.device, "top -n 1 -b", timeout=10.0))

    # 2. start atrace if requested.
    atrace_proc = None
    if args.atrace_window > 0:
        atrace_path = "/data/local/tmp/perf-coldstart-atrace.txt"
        adb_shell(args.device, f"rm -f {atrace_path}", timeout=5.0)
        # Background atrace inside the device's shell using nohup + setsid so the
        # shell command returns immediately. `&` alone often hangs the
        # `adb shell` wrapper on macOS.
        atrace_proc = adb_shell_rc(
            args.device,
            f"nohup setsid atrace -a {args.package} -b 65536 "
            f"-t {int(args.atrace_window)} -o {atrace_path} "
            f"sched freq gfx view input dalvik am wm res </dev/null >/dev/null 2>&1 &",
            timeout=10.0,
        )

    # 3. launch.
    print(f"[stage] am start -W -n {args.component}", flush=True)
    t0 = time.time()
    rc = adb_shell_rc(args.device, f"am start -W -n {args.component}", timeout=15.0)
    write(out_root / "00_am_start.txt",
          f"returncode={rc.returncode}\nstdout:\n{rc.stdout}\nstderr:\n{rc.stderr}")
    summary["stages"].append({
        "name": "am_start",
        "elapsed_s": time.time() - t0,
        "stdout_first_line": (rc.stdout or "").splitlines()[0] if rc.stdout else "",
    })

    # 4. checkpoints.
    pid = None
    for idx, target_t in enumerate(sorted(args.checkpoints)):
        target = t0 + target_t
        if (sleep := target - time.time()) > 0:
            time.sleep(sleep)
        actual = time.time() - t0
        cp_dir = out_root / f"cp_{idx:02d}_t{int(target_t * 1000):04d}ms"
        if pid is None:
            pid = get_pid(args.device, args.package)
        stage = {"name": f"checkpoint_{idx}", "target_t": target_t, "actual_t": round(actual, 3), "pid": pid}
        if pid is None:
            stage["note"] = "no_pid_yet"
            write(cp_dir / "no_pid.txt", f"pidof {args.package} returned empty")
        else:
            collect_checkpoint(args.device, pid, cp_dir)
        summary["stages"].append(stage)
        print(f"[checkpoint {idx}] target_t={target_t}s actual_t={actual:.2f}s pid={pid}", flush=True)

    # 5. final logcat tail.
    write(out_root / "logcat_full.txt",
          subprocess.run(
              ["adb", "-s", args.device, "logcat", "-d", "-v", "threadtime", "*:V"],
              capture_output=True, text=True, timeout=30.0,
          ).stdout)

    # 6. pull atrace if started.
    if atrace_proc is not None and args.atrace_window > 0:
        print("[stage] pull atrace", flush=True)
        # Give atrace a moment to flush its file
        time.sleep(2.0)
        local_path = out_root / "atrace.txt"
        try:
            subprocess.run(
                ["adb", "-s", args.device, "pull",
                 "/data/local/tmp/perf-coldstart-atrace.txt", str(local_path)],
                capture_output=True, text=True, timeout=30.0, check=True,
            )
            summary["atrace_path"] = str(local_path)
        except subprocess.CalledProcessError as exc:
            summary["atrace_error"] = str(exc)

    summary["finished_at"] = datetime.datetime.now().isoformat()
    write(out_root / "summary.json", json.dumps(summary, indent=2))
    print(f"[done] artifacts in {out_root}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())