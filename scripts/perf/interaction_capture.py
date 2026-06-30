"""Interaction-path perf capture for Slock Android.

Drives a UI scenario (open channel, type, send, scroll, click) while an atrace
records the same window. The output is two artefacts:

  - <out>/atrace.txt           full-text atrace dump (parse with atrace_summary.py)
  - <out>/ui_state_<label>.{png,xml}
                              screenshots + uiautomator XML for each stage

This is the capture shape used for the send-path audit on `#Slock-Island`
(2026-06-26, owner request: "发送消息后，整个列表也会卡一下，发送 loading 动画都会卡").

Usage:
  python3 scripts/perf/interaction_capture.py \
      --device emulator-5554 \
      --package build.raft.app \
      --label send-message-slock-island \
      --out ./artifacts/perf-send-2026-06-26 \
      --stage open-channel:574:1621 \
      --stage type:530:2175:audit-test \
      --stage tap:993:2258 \
      --atrace-window 10
"""

from __future__ import annotations

import argparse
import datetime
import json
import shlex
import subprocess
import sys
import time
from pathlib import Path


def adb_shell(device: str, command: str, *, timeout: float = 30.0) -> str:
    return subprocess.run(
        ["adb", "-s", device, "shell", command],
        capture_output=True, text=True, timeout=timeout,
    ).stdout or ""


def adb_shell_rc(device: str, command: str, *, timeout: float = 30.0):
    return subprocess.run(
        ["adb", "-s", device, "shell", command],
        capture_output=True, text=True, timeout=timeout,
    )


def get_pid(device: str, package: str) -> str | None:
    out = adb_shell(device, f"pidof {package}", timeout=5.0)
    return out.strip() or None
    return subprocess.run(
        ["adb", "-s", device, "shell", command],
        capture_output=True, text=True, timeout=timeout,
    )


def get_app_state(device: str, package: str, out_dir: Path, label: str) -> dict:
    """Capture screenshot + uiautomator XML using the existing adb helper."""
    out_dir.mkdir(parents=True, exist_ok=True)
    helper = Path(__file__).resolve().parents[1] / "slock_android_adb.py"
    cmd = [
        sys.executable, str(helper), "--device", device,
        "get-app-state", "--out", str(out_dir), "--prefix", label,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30.0)
    if proc.stdout.strip():
        try:
            return json.loads(proc.stdout)
        except json.JSONDecodeError:
            return {"error": proc.stdout[:200]}
    return {"error": proc.stderr[:200]}


def run_stage(device: str, package: str, stage: str, out_dir: Path, idx: int) -> dict:
    """Execute one stage string and return its metadata.

    Supported stage syntaxes:
        tap:X:Y                    -> input tap at screen coordinates
        type:X:Y:TEXT              -> tap (X,Y) then `input text TEXT`
        press:KEY                  -> `input keyevent KEY`
        swipe:X1:Y1:X2:Y2:MS       -> input swipe
        open:COMPONENT             -> `am start -n COMPONENT`
        home                       -> KEYCODE_HOME
    """
    label = f"stage_{idx:02d}_{stage.split(':', 1)[0]}"
    parsed: dict = {"raw": stage, "label": label}
    if ":" not in stage:
        parsed["error"] = "missing action"
        return parsed
    action, rest = stage.split(":", 1)
    parts = rest.split(":")
    try:
        if action == "tap":
            x, y = int(parts[0]), int(parts[1])
            subprocess.run(["adb", "-s", device, "shell", "input", "tap", str(x), str(y)],
                           check=True, timeout=10.0)
            parsed["executed"] = f"input tap {x} {y}"
        elif action == "type":
            x, y, text = int(parts[0]), int(parts[1]), ":".join(parts[2:])
            subprocess.run(["adb", "-s", device, "shell", "input", "tap", str(x), str(y)],
                           check=True, timeout=10.0)
            time.sleep(0.5)
            subprocess.run(["adb", "-s", device, "shell", "input", "text", text],
                           check=True, timeout=10.0)
            parsed["executed"] = f"tap+text at ({x},{y}) len={len(text)}"
        elif action == "press":
            subprocess.run(["adb", "-s", device, "shell", "input", "keyevent", parts[0]],
                           check=True, timeout=10.0)
            parsed["executed"] = f"input keyevent {parts[0]}"
        elif action == "swipe":
            x1, y1, x2, y2, ms = (int(p) for p in parts[:5])
            subprocess.run(
                ["adb", "-s", device, "shell", "input", "swipe",
                 str(x1), str(y1), str(x2), str(y2), str(ms)],
                check=True, timeout=10.0,
            )
            parsed["executed"] = f"input swipe ({x1},{y1}) -> ({x2},{y2}) in {ms}ms"
        elif action == "open":
            subprocess.run(
                ["adb", "-s", device, "shell", "am", "start", "-n", rest],
                check=True, timeout=15.0,
            )
            parsed["executed"] = f"am start -n {rest}"
        elif action == "home":
            subprocess.run(["adb", "-s", device, "shell", "input", "keyevent", "KEYCODE_HOME"],
                           check=True, timeout=10.0)
            parsed["executed"] = "KEYCODE_HOME"
        else:
            parsed["error"] = f"unknown action: {action}"
            return parsed
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        parsed["error"] = str(exc)
    # Capture state after the stage (always, even on error).
    parsed["state"] = get_app_state(device, package, out_dir, label)
    return parsed


def main() -> int:
    p = argparse.ArgumentParser(description="Interaction-path perf capture for Slock Android.")
    p.add_argument("--device", required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--label", default="interaction")
    p.add_argument("--out", default="artifacts/perf-interaction")
    p.add_argument("--stage", action="append", default=[],
                   help="Stage string. Repeatable. See module docstring for syntax.")
    p.add_argument("--atrace-window", type=float, default=10.0,
                   help="Seconds of atrace covering the entire stage sequence (default 10).")
    p.add_argument("--atrace-categories", default="sched freq gfx view input dalvik am wm res",
                   help="atrace category list. Default covers Compose-frame + lifecycle.")
    p.add_argument("--logcat-filter",
                   default="SlockPerf|SlockPerfScene|tab_switch_frame|Choreographer|Skipped|jank|Exception|FATAL",
                   help="Regex filter for the saved logcat slice.")
    args = p.parse_args()

    run_id = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_root = Path(args.out) / f"{args.label}-{run_id}"
    out_root.mkdir(parents=True, exist_ok=True)

    summary = {
        "tool": "perf.interaction_capture",
        "version": "1.0",
        "started_at": datetime.datetime.now().isoformat(),
        "device": args.device,
        "package": args.package,
        "label": args.label,
        "atrace_window_seconds": args.atrace_window,
        "atrace_categories": args.atrace_categories,
        "stages": args.stage,
        "results": [],
    }
    print(f"[{run_id}] output: {out_root}", flush=True)

    # 1. baseline state + logcat clear.
    summary["baseline_state"] = get_app_state(args.device, args.package, out_root, "baseline")
    adb_shell(args.device, "logcat -c", timeout=5.0)

    # 2. start atrace.
    atrace_path = "/data/local/tmp/perf-interaction-atrace.txt"
    adb_shell(args.device, f"rm -f {atrace_path}", timeout=5.0)
    print(f"[stage] atrace -t {args.atrace_window}s categories={args.atrace_categories}", flush=True)
    # Background atrace inside the device's shell using nohup + setsid so the
    # shell command returns immediately. `&` alone often hangs the
    # `adb shell` wrapper on macOS.
    atrace_proc = adb_shell_rc(
        args.device,
        f"nohup setsid atrace -a {args.package} -b 32768 "
        f"-t {int(args.atrace_window)} -o {atrace_path} {args.atrace_categories} "
        f"</dev/null >/dev/null 2>&1 &",
        timeout=10.0,
    )

    # 2a. pre-flight: top -H for CPU-per-thread baseline + pid for /proc reads.
    # Note: `top -n 1 -b -o %CPU` only outputs the first 15-20 threads by default,
    # but `-n 999` exceeds the emulator's stream-buffer (1932+ threads) and
    # makes adb hang. We ask for `-n 200` which is plenty for the per-app
    # threads we care about (top 200 is way more than any app's working set).
    pre_top = adb_shell(args.device, "top -H -n 1 -b -o %CPU", timeout=20.0)
    (out_root / "00_top_pre.txt").write_text(pre_top or "", encoding="utf-8")
    pid = get_pid(args.device, args.package)
    if pid:
        (out_root / "00_pid.txt").write_text(pid + "\n", encoding="utf-8")
        # Pre-flight /proc/<pid>/task/<tid>/stat for delta computation
        proc_dir_pre = out_root / "proc_task_stat_pre"
        proc_dir_pre.mkdir(exist_ok=True)
        for tid in adb_shell(args.device, f"ls /proc/{pid}/task/", timeout=5.0).split():
            name = adb_shell(args.device, f"cat /proc/{pid}/task/{tid}/comm", timeout=5.0).strip()
            safe_name = "".join(c if c.isalnum() else "_" for c in name) or "noname"
            stat = adb_shell(args.device, f"cat /proc/{pid}/task/{tid}/stat", timeout=5.0)
            (proc_dir_pre / f"{tid}_{safe_name}.stat").write_text(
                stat or "", encoding="utf-8",
            )

    # 3. run each stage.
    t0 = time.time()
    # Top -H midpoint sample (CPU during scenario work) — scheduled at the
    # middle of the atrace window.
    midpoint_at = t0 + args.atrace_window / 2.0
    for idx, stage in enumerate(args.stage):
        # Stagger slightly so the atrace has interesting transitions to record.
        time.sleep(0.3)
        print(f"[stage {idx}] {stage}", flush=True)
        result = run_stage(args.device, args.package, stage, out_root, idx)
        result["elapsed_s"] = round(time.time() - t0, 3)
        summary["results"].append(result)
        # Capture top -H around the midpoint of the atrace window so the
        # sample reflects work, not setup/teardown.
        if pid and time.time() < midpoint_at:
            time_to_mid = max(0.0, midpoint_at - time.time())
            time.sleep(time_to_mid)
            (out_root / "01_top_mid.txt").write_text(
                adb_shell(args.device, "top -H -n 1 -b -o %CPU", timeout=20.0) or "",
                encoding="utf-8",
            )

    # 4. final logcat tail + top -H post-flight.
    elapsed = time.time() - t0
    if elapsed < args.atrace_window:
        time.sleep(args.atrace_window - elapsed)
    print("[stage] capturing logcat + pulling atrace", flush=True)
    logcat_rc = subprocess.run(
        ["adb", "-s", args.device, "logcat", "-d", "-v", "threadtime", "*:V"],
        capture_output=True, text=True, timeout=30.0,
    )
    (out_root / "logcat_full.txt").write_text(logcat_rc.stdout or "", encoding="utf-8")
    # Filtered slice for quick scanning.
    if args.logcat_filter:
        import re
        pat = re.compile(args.logcat_filter)
        (out_root / "logcat_filtered.txt").write_text(
            "\n".join(line for line in (logcat_rc.stdout or "").splitlines() if pat.search(line)),
            encoding="utf-8",
        )

    # 4a. post-flight: top -H + /proc/<pid>/task/<tid>/stat for delta.
    post_top = adb_shell(args.device, "top -H -n 1 -b -o %CPU", timeout=20.0)
    (out_root / "02_top_post.txt").write_text(post_top or "", encoding="utf-8")
    if pid:
        proc_dir = out_root / "proc_task_stat_post"
        proc_dir.mkdir(exist_ok=True)
        for tid in adb_shell(args.device, f"ls /proc/{pid}/task/", timeout=5.0).split():
            name = adb_shell(args.device, f"cat /proc/{pid}/task/{tid}/comm", timeout=5.0).strip()
            # Sanitize the comm name: replace any non-alphanumeric chars with `_`
            # so the filename is filesystem-safe (comm can contain spaces, dots,
            # parens, slashes, etc., which break path joining).
            safe_name = "".join(c if c.isalnum() else "_" for c in name) or "noname"
            stat = adb_shell(args.device, f"cat /proc/{pid}/task/{tid}/stat", timeout=5.0)
            (proc_dir / f"{tid}_{safe_name}.stat").write_text(
                stat or "", encoding="utf-8",
            )

    try:
        subprocess.run(
            ["adb", "-s", args.device, "pull", atrace_path, str(out_root / "atrace.txt")],
            check=True, timeout=30.0, capture_output=True, text=True,
        )
        summary["atrace_path"] = str(out_root / "atrace.txt")
    except subprocess.CalledProcessError as exc:
        summary["atrace_error"] = str(exc)

    summary["finished_at"] = datetime.datetime.now().isoformat()
    (out_root / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"[done] artifacts in {out_root}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())