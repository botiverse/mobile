#!/usr/bin/env python3
"""Report for one run of .github/workflows/ios-link-probe.yml (measurement only).

Reads what the probe's steps leave in --dir, writes summary.txt and phase-profile-top.tsv there,
prints the summary and appends it as Markdown to --step-summary. It never fails the job: a file a
step did not write (timed out, cancelled, never ran) is reported as missing, and an unexpected
error becomes a ::warning::. The link step's own exit status is the job result.

  params.env, machine.env    key=value lines from the parameter and hardware steps
  {compile,link}.start/.end  epoch seconds around each Gradle invocation; .status = its exit code
  compile.log, link.log      Gradle output ending with the /usr/bin/time -l report
                             (link.log without the -Xprofile-phases lines)
  phase-profile.txt          the -Xprofile-phases lines: one tab per depth, "<Phase>: <N> msec"
  mem-samples.tsv            every 5 s of the link: Gradle daemon / all-java RSS (KB), swap used,
                             vm_stat free / compressor / swapout pages
  vm_stat-before.txt, vm_stat-after.txt
  daemon-cmdline.txt         the forked Gradle daemon's command line (the JVM flags it really got)
  gc.log                     -Xlog:gc* of that daemon

Usage: ios_link_probe_report.py --dir DIR [--framework DIR] [--xcode TEXT] [--step-summary FILE]
       ios_link_probe_report.py --self-test
"""
from __future__ import annotations

import argparse
import contextlib
import io
import re
import sys
import tempfile
import time
import traceback
from pathlib import Path

LINK_TASK = ":shared:linkPodReleaseFrameworkIosArm64"
DEFAULT_DISABLED_PHASES = "RemoveRedundantCallsToStaticInitializersPhase"
TOP_PHASES = 15
TIME_REAL = re.compile(r"^\s*(\d+(?:\.\d+)?) real\s+(\d+(?:\.\d+)?) user\s+(\d+(?:\.\d+)?) sys\s*$")
TIME_BYTES = re.compile(r"^\s*(\d+)\s+(maximum resident set size|peak memory footprint)\s*$")
PHASE = re.compile(r"^(\t*)(.*\S): (\d+) msec\s*$")
TASK = re.compile(r"^> Task (:\S+)(?: (.+?))?\s*$")
NOT_EXECUTED = {"UP-TO-DATE", "FROM-CACHE", "NO-SOURCE", "SKIPPED"}
OOM = re.compile(r"java\.lang\.OutOfMemoryError[^\r\n]*")
DISABLE_PHASES_ARG = re.compile(r"-Xdisable-phases=(\S+)")
VM_PAGE = re.compile(r"page size of (\d+) bytes")
VM_ROW = re.compile(r'^"?([^":]+)"?:\s+(\d+)\.?\s*$')
SIZE = r"(\d+(?:\.\d+)?)([KMG])"
GC_PAUSE = re.compile(r"GC\(\d+\) Pause (\w+).*? " + SIZE + "->" + SIZE + r"\(" + SIZE + r"\) (\d+(?:\.\d+)?)ms\s*$")
GC_USING = re.compile(r"\]\s*Using (G1|Parallel|Serial|Shenandoah|The Z Garbage Collector)\b")
GC_HEAP_MAX = re.compile(r"Heap Max Capacity: (\S+)")
GC_MEMORY = re.compile(r"\[gc,init[^\]]*\]\s*Memory: (\S+)")
GC_EVACUATION_FAILURE = re.compile(r"To-space exhausted|Evacuation Failure")
# Kotlin/Native's first-use download of LLVM / sysroots into ~/.konan/dependencies.
NATIVE_DEPENDENCIES = re.compile(r"Downloading native dependencies|Extracting dependency:")
MB_PER_UNIT = {"K": 1 / 1024, "M": 1.0, "G": 1024.0}


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def lines_of(path: Path):
    """Streams a possibly huge file (the phase profile can run to millions of lines)."""
    try:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                yield line.rstrip("\r\n")
    except OSError:
        return


def read_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    for line in read_text(path).splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip():
            env[key.strip()] = value.strip()
    return env


def read_int(path: Path) -> int | None:
    try:
        return int(read_text(path).strip())
    except ValueError:
        return None


def gib(nbytes: float | None) -> str:
    return "?" if nbytes is None else f"{nbytes / 2**30:.2f} GiB"


def mib(nbytes: float | None) -> str:
    return "?" if nbytes is None else f"{nbytes / 2**20:.1f} MiB"


def duration(seconds: float | None) -> str:
    if seconds is None:
        return "?"
    hours, rest = divmod(int(round(seconds)), 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}h {minutes:02d}m {secs:02d}s" if hours else f"{minutes}m {secs:02d}s"


def time_report(text: str) -> dict[str, float]:
    """The /usr/bin/time -l block (macOS: sizes in bytes)."""
    found: dict[str, float] = {}
    for line in text.splitlines():
        match = TIME_REAL.match(line)
        if match:
            found["real"], found["user"], found["sys"] = (float(value) for value in match.groups())
        match = TIME_BYTES.match(line)
        if match:
            found["max_rss" if match.group(2).startswith("maximum") else "footprint"] = float(match.group(1))
    return found


def phase_profile(path: Path) -> tuple[list[tuple[int, int, int, str]], int, int]:
    """(total_ms, calls, min_depth, phase) by total, the line count and the depth-0 total.

    Kotlin/Native lowers file by file, so most phases print once per file: summed by name here.
    A phase's time includes the phases nested in it (deeper lines).
    """
    totals: dict[str, list[int]] = {}
    lines = top_level_ms = 0
    for line in lines_of(path):
        match = PHASE.match(line)
        if not match:
            continue
        lines += 1
        depth, name, ms = len(match.group(1)), match.group(2).strip(), int(match.group(3))
        entry = totals.setdefault(name, [0, 0, depth, len(totals)])
        entry[0] += ms
        entry[1] += 1
        entry[2] = min(entry[2], depth)
        if depth == 0:
            top_level_ms += ms
    rows = sorted(totals.items(), key=lambda item: (-item[1][0], item[1][3]))
    return [(v[0], v[1], v[2], name) for name, v in rows], lines, top_level_ms


def size_mb(text: str) -> float | None:
    match = re.fullmatch(r"(\d+(?:\.\d+)?)([KMG])?", text.strip())
    return None if not match else float(match.group(1)) * MB_PER_UNIT[match.group(2) or "M"]


def memory_samples(path: Path) -> dict[str, float]:
    peak = {"samples": 0.0, "daemon_kb": 0.0, "java_kb": 0.0, "swap_mb": 0.0, "compressor_pages": 0.0}
    for line in lines_of(path):
        cols = line.split("\t")
        if len(cols) < 7 or not cols[0].isdigit():
            continue
        peak["samples"] += 1
        for key, index in (("daemon_kb", 1), ("java_kb", 2), ("compressor_pages", 5)):
            if cols[index].isdigit():
                peak[key] = max(peak[key], float(cols[index]))
        swap = size_mb(cols[3])
        if swap is not None:
            peak["swap_mb"] = max(peak["swap_mb"], swap)
    return peak


def vm_stat(text: str) -> tuple[int, dict[str, int]]:
    page = VM_PAGE.search(text)
    rows = {m.group(1).strip(): int(m.group(2)) for m in map(VM_ROW.match, text.splitlines()) if m}
    return (int(page.group(1)) if page else 0), rows


def gc_digest(path: Path) -> dict:
    gc: dict = {"present": path.is_file(), "pauses": 0, "full": 0, "pause_ms": 0.0, "max_pause_ms": 0.0,
                "peak_after_mb": 0.0, "peak_after_full_mb": 0.0, "evacuation_failures": 0,
                "collector": None, "heap_max": None, "memory": None}
    for line in lines_of(path):
        if GC_EVACUATION_FAILURE.search(line):
            gc["evacuation_failures"] += 1
        match = GC_PAUSE.search(line)
        if match:
            after_mb = float(match.group(4)) * MB_PER_UNIT[match.group(5)]
            pause_ms = float(match.group(8))
            gc["pauses"] += 1
            gc["pause_ms"] += pause_ms
            gc["max_pause_ms"] = max(gc["max_pause_ms"], pause_ms)
            gc["peak_after_mb"] = max(gc["peak_after_mb"], after_mb)
            if match.group(1) == "Full":
                gc["full"] += 1
                gc["peak_after_full_mb"] = max(gc["peak_after_full_mb"], after_mb)
            continue
        for key, pattern in (("collector", GC_USING), ("heap_max", GC_HEAP_MAX), ("memory", GC_MEMORY)):
            if gc[key] is None:
                found = pattern.search(line)
                if found:
                    gc[key] = found.group(1)
    return gc


def compiler_args(log: str, params: dict[str, str]) -> tuple[bool, list[str]]:
    """Checks konanc's logged "Arguments = [...]" against the probe inputs."""
    if "Arguments = [" not in log:
        return False, ["no konanc 'Arguments = [' block in link.log, so nothing proves what the compiler got"]
    ok, notes = True, []
    expected = params.get("expected_disable_phases") or DEFAULT_DISABLED_PHASES
    seen = sorted({value.strip("\"',[]") for value in DISABLE_PHASES_ARG.findall(log)})
    if seen == [expected]:
        notes.append(f"-Xdisable-phases={expected}")
    else:
        ok = False
        notes.append(f"MISMATCH -Xdisable-phases: expected {expected}, saw {' + '.join(seen) or 'none'}")
    for flag, key in (("-Xbinary=smallBinary=true", "small_binary"), ("-Xprofile-phases", "profile_phases")):
        wanted, present = params.get(key) == "true", flag in log
        if wanted == present:
            notes.append(flag if present else f"no {flag}")
        else:
            ok = False
            notes.append(f"MISMATCH {flag} {'missing' if wanted else 'unexpected'}")
    return ok, notes


def daemon_flags(cmdline: str, params: dict[str, str], collector: str | None) -> tuple[bool | None, list[str]]:
    """Checks the forked Gradle daemon's real command line against heap / parallel_gc."""
    args = cmdline.split()
    if not args:
        return None, ["daemon command line not captured"]
    ok, notes = True, []
    heap = params.get("heap", "?")
    if f"-Xmx{heap}" in args:
        notes.append(f"-Xmx{heap}")
    else:
        ok = False
        notes.append(f"MISMATCH -Xmx{heap} missing (saw {' '.join(a for a in args if a.startswith('-Xmx')) or 'none'})")
    parallel, wanted = "-XX:+UseParallelGC" in args, params.get("parallel_gc") == "true"
    if parallel != wanted:
        ok = False
        notes.append(f"MISMATCH -XX:+UseParallelGC {'missing' if wanted else 'unexpected'}")
    else:
        notes.append("-XX:+UseParallelGC" if parallel else "default GC")
    if not any(arg.startswith("-Xlog:gc") for arg in args):
        ok = False
        notes.append("MISMATCH -Xlog:gc missing")
    if collector:
        notes.append(f"GC log says: Using {collector}")
    return ok, notes


def executed_tasks(log: str) -> list[str]:
    tasks: list[str] = []
    for line in log.splitlines():
        match = TASK.match(line)
        if match and (match.group(2) or "") not in NOT_EXECUTED:
            label = match.group(1) + (f" {match.group(2)}" if match.group(2) else "")
            if label not in tasks:
                tasks.append(label)
    return tasks


def elapsed(probe: Path, name: str) -> tuple[float | None, bool]:
    """Seconds between <name>.start and <name>.end; (now - start, False) when it never ended."""
    start, end = read_int(probe / f"{name}.start"), read_int(probe / f"{name}.end")
    if start is None:
        return None, True
    if end is None:
        return time.time() - start, False
    return float(end - start), True


def outcome(probe: Path, log: str) -> str:
    status = read_int(probe / "link.status")
    if status is None:
        return "did not finish (step timed out or was cancelled)" if (probe / "link.start").is_file() else "not run"
    if status == 0:
        return "success"
    text = f"failed, exit {status}"
    oom = OOM.search(log)
    if oom:
        text = f"OOM, exit {status} ({oom.group(0).strip()[:120]})"
    if "garbage collector is thrashing" in log:
        text += "; Gradle reported GC thrashing"
    return text


def framework_size(path: Path | None) -> tuple[int | None, int | None]:
    if path is None or not path.is_dir():
        return None, None
    binary = path / path.stem  # shared.framework/shared (a static library: isStatic = true)
    total = sum(f.stat().st_size for f in path.rglob("*") if f.is_file() and not f.is_symlink())
    return (binary.stat().st_size if binary.is_file() else None), total


def build(probe: Path, framework: Path | None, xcode: str, konan_cache_hit: str = "") -> dict:
    params, machine = read_env(probe / "params.env"), read_env(probe / "machine.env")
    link_log, compile_log = read_text(probe / "link.log"), read_text(probe / "compile.log")
    link_s, link_done = elapsed(probe, "link")
    compile_s, _ = elapsed(probe, "compile")
    timed, compile_timed = time_report(link_log), time_report(compile_log)
    samples = memory_samples(probe / "mem-samples.tsv")
    gc = gc_digest(probe / "gc.log")
    phases, phase_lines, top_level_ms = phase_profile(probe / "phase-profile.txt")
    page, before = vm_stat(read_text(probe / "vm_stat-before.txt"))
    _, after = vm_stat(read_text(probe / "vm_stat-after.txt"))
    args_ok, args_notes = compiler_args(link_log, params) if link_log else (None, ["link.log missing"])
    jvm_ok, jvm_notes = daemon_flags(read_text(probe / "daemon-cmdline.txt"), params, gc["collector"])
    binary, bundle = framework_size(framework)

    memsize = float(machine["hw_memsize"]) if machine.get("hw_memsize", "").isdigit() else None
    flags = " ".join(f"{label}={'on' if params.get(key) == 'true' else 'off' if key in params else '?'}"
                     for label, key in (("parallelGC", "parallel_gc"), ("disablePhases", "disable_phases"),
                                        ("smallBinary", "small_binary"), ("profilePhases", "profile_phases")))
    daemon_bytes = samples["daemon_kb"] * 1024 if samples["samples"] else None
    max_rss = timed.get("max_rss")
    rss_note = ""
    if max_rss and daemon_bytes and daemon_bytes > 1.5 * max_rss:
        rss_note = " (the Gradle client only: the link ran in the forked daemon, see the sampled peak)"
    swapped = {key: (after[key] - before[key]) * page
               for key in ("Swapouts", "Pageouts") if page and key in before and key in after}
    gc_text = "no GC log"
    if gc["present"]:
        share = f", {gc['pause_ms'] / 10 / link_s:.1f}% of the link" if link_s else ""
        gc_text = (f"{gc['pauses']} pauses ({gc['full']} full), {gc['pause_ms'] / 1000:.1f} s paused{share}, "
                   f"max pause {gc['max_pause_ms'] / 1000:.2f} s, peak heap after GC {gib(gc['peak_after_mb'] * 2**20)}")
        if gc["full"]:
            gc_text += f" (after full GC {gib(gc['peak_after_full_mb'] * 2**20)})"
        if gc["evacuation_failures"]:
            gc_text += f", {gc['evacuation_failures']} evacuation failures"
    tasks = executed_tasks(link_log)
    compiled = [task for task in tasks if ":compile" in task]
    compile_status = read_int(probe / "compile.status")

    rows = [
        ("Outcome", outcome(probe, link_log)),
        ("Runner", params.get("runner", "?")),
        ("hw.memsize", f"{gib(memsize)} ({machine.get('hw_memsize', '?')} bytes); hw.ncpu {machine.get('hw_ncpu', '?')}; "
                       f"{machine.get('hw_model', '?')}, {machine.get('cpu_brand', '?')}"),
        ("Heap", f"-Xmx{params.get('heap', '?')}; JVM: {gc['collector'] or '?'}, heap max {gc['heap_max'] or '?'}, "
                 f"sees memory {gc['memory'] or '?'}"),
        ("Flags", flags),
        ("Link wall time", duration(link_s) + ("" if link_done else " so far (unfinished)")
         + (f"; time -l real {timed['real']:.0f} s, user {timed['user']:.0f} s, sys {timed['sys']:.0f} s" if "real" in timed else "")),
        ("Max RSS", f"{gib(max_rss)} (/usr/bin/time -l){rss_note}; peak memory footprint {gib(timed.get('footprint'))}"),
        ("Peak daemon RSS", f"{gib(daemon_bytes)} (ps every 5 s, {int(samples['samples'])} samples); "
                            f"all JVMs {gib(samples['java_kb'] * 1024 if samples['samples'] else None)}"),
        ("Swap", f"peak used {gib(samples['swap_mb'] * 2**20 if samples['samples'] else None)}; during the link: swapouts "
                 f"{gib(swapped.get('Swapouts'))}, pageouts {gib(swapped.get('Pageouts'))}; peak compressor "
                 f"{gib(samples['compressor_pages'] * page if samples['samples'] and page else None)}"),
        ("GC", gc_text),
        ("Compiler arguments", ("ok: " if args_ok else "CHECK: " if args_ok is False else "") + "; ".join(args_notes)),
        ("Daemon JVM flags", ("ok: " if jvm_ok else "CHECK: " if jvm_ok is False else "") + "; ".join(jvm_notes)),
        ("Executed in the link invocation", ", ".join(tasks) or "?"),
        ("Framework binary", f"{mib(binary)} ({framework.name if framework else 'framework'} total {mib(bundle)})"
         if binary is not None or bundle is not None else "not produced"),
        ("Phase profile", f"{phase_lines} lines, depth-0 total {top_level_ms / 1000:.1f} s" if phase_lines else "none"),
        ("Compile step (before the link)", f"{duration(compile_s)}, exit {compile_status if compile_status is not None else '?'}, "
                                           f"max RSS {gib(compile_timed.get('max_rss'))} (time -l)"),
        ("Toolchain", f"Xcode {xcode or '?'}; Kotlin {params.get('kotlin', '?')}; "
                      f"~/.konan cache {'hit' if konan_cache_hit == 'true' else 'miss' if konan_cache_hit else '?'}; "
                      f"commit {params.get('commit', '?')}"),
    ]
    warnings = []
    if compiled:
        warnings.append("the link invocation also compiled " + ", ".join(compiled))
    if NATIVE_DEPENDENCIES.search(link_log):
        warnings.append("Kotlin/Native downloaded or extracted its native dependencies during the link (cold ~/.konan)")
    if warnings:
        rows.append(("Warning", "; ".join(warnings) + ": the link wall time includes that"))
    text = "\n".join(f"{label}: {value}" for label, value in rows)
    if phases:
        text += "\n\nTop compiler phases (-Xprofile-phases, summed over calls; total_ms calls min_depth phase):\n"
        text += "\n".join(f"{ms}\t{calls}\t{depth}\t{name}" for ms, calls, depth, name in phases[:TOP_PHASES])
    markdown = [f"### iOS link probe: `{LINK_TASK}`", "", "| Field | Value |", "|---|---|"]
    markdown += [f"| **{label}** | {value.replace('|', '/')} |" for label, value in rows]
    if phases:
        markdown += ["", f"Top {min(TOP_PHASES, len(phases))} compiler phases (`-Xprofile-phases`, summed over calls; "
                     "a phase includes the deeper ones nested in it):", "",
                     "| Phase | Total | Calls | Depth |", "|---|---:|---:|---:|"]
        markdown += [f"| {name} | {ms / 1000:.1f} s | {calls} | {depth} |" for ms, calls, depth, name in phases[:TOP_PHASES]]
    return {"rows": dict(rows), "text": text, "markdown": "\n".join(markdown), "phases": phases}


def write_phase_table(path: Path, phases: list[tuple[int, int, int, str]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        handle.write("total_ms\tcalls\tmin_depth\tphase\n")
        for ms, calls, depth, name in phases:
            handle.write(f"{ms}\t{calls}\t{depth}\t{name}\n")


# ---------------------------------------------------------------- self-test

FIXTURE = {
    "params.env": "runner=xcode-27-xlarge\nheap=10g\nparallel_gc=false\ndisable_phases=true\nsmall_binary=false\n"
                  "profile_phases=true\nexpected_disable_phases=DevirtualizationAnalysis,DCEPhase,"
                  "RemoveRedundantCallsToStaticInitializersPhase\nkotlin=2.4.21\ncommit=abc123\n",
    "machine.env": "hw_memsize=15032385536\nhw_ncpu=5\nhw_model=VirtualMac2,1\ncpu_brand=Apple M2 (Virtual)\n",
    "link.start": "1000\n", "link.end": "3472\n", "link.status": "0\n",
    "compile.start": "400\n", "compile.end": "945\n", "compile.status": "0\n",
    "compile.log": "BUILD SUCCESSFUL in 9m\n      545.00 real 1200.00 user 30.00 sys\n   1073741824  maximum resident set size\n",
    "link.log": "To honour the JVM settings for this build a single-use Daemon process will be forked.\n"
                "> Task :verifyComposeUiPatch\n> Task :shared:compileKotlinIosArm64 UP-TO-DATE\n"
                f"> Task {LINK_TASK}\nRun in-process tool \"konanc\"\nArguments = [\n    -produce\n    static_framework\n"
                "    -Xdisable-phases=DevirtualizationAnalysis,DCEPhase,RemoveRedundantCallsToStaticInitializersPhase\n"
                "    -Xprofile-phases\n]\nBUILD SUCCESSFUL in 41m 10s\n"
                "     2471.50 real      9000.00 user       400.00 sys\n"
                "           335544320  maximum resident set size\n                   0  average shared memory size\n"
                "           300000000  peak memory footprint\n",
    "phase-profile.txt": "DevirtualizationAnalysis: 600000 msec\n\tLower: 10 msec\n\tLower: 20 msec\n"
                         "ModuleBitcodeOptimization: 900000 msec\n",
    "mem-samples.tsv": "epoch\tdaemon_rss_kb\tjava_rss_kb\tswap_used\tfree_pages\tcompressor_pages\tswapouts\n"
                       "1005\t1048576\t1200000\t0.00M\t100000\t0\t0\n"
                       "1010\t13631488\t13900000\t2048.00M\t2000\t65536\t131072\n",
    "vm_stat-before.txt": "Mach Virtual Memory Statistics: (page size of 16384 bytes)\nPages free:  100000.\n"
                          "\"Translation faults\":  5.\nPageouts:  0.\nSwapouts:  0.\n",
    "vm_stat-after.txt": "Mach Virtual Memory Statistics: (page size of 16384 bytes)\nPages free:  2000.\n"
                         "Pageouts:  65536.\nSwapouts:  131072.\n",
    "daemon-cmdline.txt": "/jdk/bin/java -Xmx10g -Xss4m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 "
                          "-Xlog:gc*:file=/t/gc.log:time,uptime,level,tags:filecount=0 -cp x "
                          "org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.9.1\n",
    "gc.log": "[2026-11-21T07:00:00.005+0000][0.005s][info][gc,init] Memory: 14G\n"
              "[2026-11-21T07:00:00.006+0000][0.006s][info][gc     ] Using G1\n"
              "[2026-11-21T07:00:00.006+0000][0.006s][info][gc,init] Heap Max Capacity: 10G\n"
              "[2026-11-21T07:00:01.240+0000][1.240s][info][gc          ] GC(0) Pause Young (Normal) "
              "(G1 Evacuation Pause) 24M->3M(256M) 6.000ms\n"
              "[2026-11-21T07:30:00.000+0000][1800.0s][info][gc,start    ] GC(1) Pause Full (G1 Compaction Pause)\n"
              "[2026-11-21T07:30:04.000+0000][1804.0s][info][gc          ] GC(1) To-space exhausted\n"
              "[2026-11-21T07:30:04.000+0000][1804.0s][info][gc          ] GC(1) Pause Full (G1 Compaction Pause) "
              "9000M->8500M(10240M) 4000.000ms\n"
              "[2026-11-21T07:30:05.000+0000][1805.0s][info][gc          ] GC(2) Pause Remark 8.4G->8.4G(10G) 10.000ms\n",
}


def self_test() -> int:
    failures: list[str] = []

    def expect(case: str, condition: bool, detail: object = "") -> None:
        if not condition:
            failures.append(f"{case}: {detail}")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        def probe(name: str, overrides: dict[str, str | None]) -> Path:
            directory = root / name
            directory.mkdir()
            for file, content in {**FIXTURE, **overrides}.items():
                if content is not None:
                    (directory / file).write_text(content, encoding="utf-8")
            return directory

        framework = root / "shared.framework"
        framework.mkdir()
        (framework / "shared").write_bytes(b"\0" * 3 * 2**20)
        good = build(probe("good", {}), framework, "27.1 (17B100)", "true")
        rows = good["rows"]
        expect("outcome", rows["Outcome"] == "success" and "Warning" not in rows, rows)
        expect("toolchain", rows["Toolchain"] == "Xcode 27.1 (17B100); Kotlin 2.4.21; ~/.konan cache hit; commit abc123",
               rows["Toolchain"])
        expect("wall time", rows["Link wall time"].startswith("41m 12s;"), rows["Link wall time"])
        expect("time -l", "0.31 GiB (/usr/bin/time -l) (the Gradle client only" in rows["Max RSS"], rows["Max RSS"])
        expect("sampled peak", rows["Peak daemon RSS"].startswith("13.00 GiB (ps every 5 s, 2 samples)"), rows["Peak daemon RSS"])
        expect("swap", "peak used 2.00 GiB; during the link: swapouts 2.00 GiB, pageouts 1.00 GiB; peak compressor 1.00 GiB"
               in rows["Swap"], rows["Swap"])
        expect("gc", rows["GC"].startswith("3 pauses (1 full), 4.0 s paused, 0.2% of the link, max pause 4.00 s, "
                                           "peak heap after GC 8.40 GiB (after full GC 8.30 GiB), 1 evacuation failures"),
               rows["GC"])
        expect("heap", rows["Heap"] == "-Xmx10g; JVM: G1, heap max 10G, sees memory 14G", rows["Heap"])
        expect("args ok", rows["Compiler arguments"].startswith("ok: -Xdisable-phases=DevirtualizationAnalysis,"),
               rows["Compiler arguments"])
        expect("jvm ok", rows["Daemon JVM flags"] == "ok: -Xmx10g; default GC; GC log says: Using G1", rows["Daemon JVM flags"])
        expect("tasks", rows["Executed in the link invocation"] == f":verifyComposeUiPatch, {LINK_TASK}",
               rows["Executed in the link invocation"])
        expect("phases", [p[3] for p in good["phases"]] == ["ModuleBitcodeOptimization", "DevirtualizationAnalysis", "Lower"]
               and good["phases"][2][:3] == (30, 2, 1), good["phases"])
        expect("phase lines", rows["Phase profile"] == "4 lines, depth-0 total 1500.0 s", rows["Phase profile"])
        expect("framework", rows["Framework binary"] == "3.0 MiB (shared.framework total 3.0 MiB)", rows["Framework binary"])
        expect("compile", rows["Compile step (before the link)"] == "9m 05s, exit 0, max RSS 1.00 GiB (time -l)",
               rows["Compile step (before the link)"])
        expect("markdown", "| **Flags** | parallelGC=off disablePhases=on smallBinary=off profilePhases=on |"
               in good["markdown"] and "| ModuleBitcodeOptimization | 900.0 s | 1 | 0 |" in good["markdown"],
               good["markdown"])

        conflict = FIXTURE["link.log"].replace("    -Xprofile-phases\n", "    -Xdisable-phases=Other\n")
        bad = build(probe("bad", {"link.log": conflict, "params.env": FIXTURE["params.env"].replace(
            "small_binary=false", "small_binary=true"), "daemon-cmdline.txt": "java -Xmx20g GradleDaemon"}), None, "")
        args = bad["rows"]["Compiler arguments"]
        expect("args conflict", args.startswith("CHECK: ") and "saw DevirtualizationAnalysis,DCEPhase,"
               "RemoveRedundantCallsToStaticInitializersPhase + Other" in args, args)
        expect("args missing flags", "-Xbinary=smallBinary=true missing" in args and "-Xprofile-phases missing" in args, args)
        expect("jvm mismatch", "MISMATCH -Xmx10g missing (saw -Xmx20g)" in bad["rows"]["Daemon JVM flags"]
               and "-Xlog:gc missing" in bad["rows"]["Daemon JVM flags"], bad["rows"]["Daemon JVM flags"])
        expect("no framework", bad["rows"]["Framework binary"] == "not produced", bad["rows"]["Framework binary"])

        oom = build(probe("oom", {"link.status": "1\n", "link.log": "> Task :shared:compileKotlinIosArm64\n"
                                  "Downloading native dependencies (LLVM, sysroot etc). This is a one-time action.\n"
                                  "e: java.lang.OutOfMemoryError: Java heap space\n"}), None, "", "")
        expect("oom", oom["rows"]["Outcome"] == "OOM, exit 1 (java.lang.OutOfMemoryError: Java heap space)",
               oom["rows"]["Outcome"])
        expect("no args block", oom["rows"]["Compiler arguments"].startswith("CHECK: no konanc"), oom["rows"]["Compiler arguments"])
        expect("warnings", oom["rows"].get("Warning", "").startswith(
            "the link invocation also compiled :shared:compileKotlinIosArm64; Kotlin/Native downloaded"), oom["rows"])
        expect("konan unknown", "~/.konan cache ?;" in oom["rows"]["Toolchain"], oom["rows"]["Toolchain"])

        timeout = build(probe("timeout", {"link.end": None, "link.status": None}), None, "")
        expect("timeout", timeout["rows"]["Outcome"] == "did not finish (step timed out or was cancelled)"
               and "so far (unfinished)" in timeout["rows"]["Link wall time"], timeout["rows"])

        empty = root / "empty"
        empty.mkdir()
        nothing = build(empty, None, "")
        expect("empty", nothing["rows"]["Outcome"] == "not run" and nothing["rows"]["Peak daemon RSS"].startswith("?")
               and nothing["rows"]["Flags"] == "parallelGC=? disablePhases=? smallBinary=? profilePhases=?", nothing["rows"])
        expect("missing dir", build(root / "missing", None, "")["rows"]["Outcome"] == "not run")

        summary = root / "step-summary.md"
        with contextlib.redirect_stdout(io.StringIO()):
            code = main(["--dir", str(root / "good"), "--framework", str(framework), "--step-summary", str(summary)])
        expect("main", code == 0 and "### iOS link probe" in read_text(summary)
               and read_text(root / "good" / "phase-profile-top.tsv").splitlines()[1] == "900000\t1\t0\tModuleBitcodeOptimization"
               and read_text(root / "good" / "summary.txt").startswith("Outcome: success"), code)

    for failure in failures:
        print(f"self-test FAIL: {failure}")
    print("ios_link_probe_report self-test: " + ("FAILED" if failures else "OK"))
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", help="the probe's data directory ($RUNNER_TEMP/ios-link-probe)")
    parser.add_argument("--framework", help="the linked shared.framework directory")
    parser.add_argument("--xcode", default="", help="Xcode version for the summary")
    parser.add_argument("--konan-cache-hit", default="", help="actions/cache cache-hit output of the ~/.konan step")
    parser.add_argument("--step-summary", help="file to append the Markdown summary to ($GITHUB_STEP_SUMMARY)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.dir:
        parser.error("--dir is required")
    try:
        probe = Path(args.dir)
        report = build(probe, Path(args.framework) if args.framework else None, args.xcode, args.konan_cache_hit)
        print(report["text"])
        if probe.is_dir():
            (probe / "summary.txt").write_text(report["text"] + "\n", encoding="utf-8")
            write_phase_table(probe / "phase-profile-top.tsv", report["phases"])
        if args.step_summary:
            with open(args.step_summary, "a", encoding="utf-8") as handle:
                handle.write(report["markdown"] + "\n")
    except Exception:  # A report bug must not turn a measured run red; the raw files are uploaded.
        traceback.print_exc()
        print("::warning::ios_link_probe_report.py failed; the raw probe files are in the artifact")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
