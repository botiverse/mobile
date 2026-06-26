# Slock Android performance-analysis toolchain.
#
# Subcommands (each runnable as `python3 scripts/perf/<tool>.py ...`):
#   cold_start_capture    force-stop + launch + per-checkpoint profiler snapshots
#   interaction_capture   open channel / type / send + atrace during the action
#   atrace_summary        parse full-text atrace (Systrace text format) into a
#                         human-readable Markdown report (frame counts, monitor
#                         contention sites, top CPU threads, GC, VerifyClass,
#                         Compose:recompose / Recomposer:animation)
#   regression_compare    diff two atrace summaries and surface deltas
#
# Everything is stdlib-only (argparse, dataclasses, json, pathlib, re, statistics,
# subprocess). No pip dependencies. Keep it that way so a fresh emulator boot
# can run it without `pip install`.
#
# The tools assume `scripts/slock_android_adb.py` is on PYTHONPATH for shared
# helpers (or copy the small pieces you need inline).