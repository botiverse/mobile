#!/usr/bin/env bash
# Every dialog, sheet and modal window opens through the design system's RaftWindowDialog
# (compose/designsystem/.../components/RaftWindowDialog.kt), which gives the window the
# back-swipe block, the one RaftTheme scrim and the system bars of the theme it is drawn in
# (RaftDialogWindowPolicy). A raw Compose `Dialog` or a Material `ModalBottomSheet` /
# `AlertDialog` opens a platform window that misses all three (dark-theme dialogs with
# light-theme bar icons, a second scrim, a back swipe that pops the page underneath), so
# importing or calling one anywhere else fails CI.
set -euo pipefail

allowed_file="compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/components/RaftWindowDialog.kt"

usage() {
  cat <<'EOF'
Usage: scripts/check_raft_window_dialog.sh [--root <path>] [--self-test]
EOF
}

# scan <root> <allowed_file relative to root>: 0 = clean, 1 = violations (printed).
scan() {
  python3 - "$1" "$2" <<'PY'
import os, re, sys

root, allowed = sys.argv[1], os.path.normpath(sys.argv[2])
# A window-opening composable from Compose UI or Material, by import (named or wildcard)
# or by a fully qualified call.
patterns = [
    re.compile(r"^\s*import\s+androidx\.compose\.ui\.window\.(Dialog|\*)\s*(as\s+\w+\s*)?$"),
    re.compile(r"^\s*import\s+androidx\.compose\.material3?\.(ModalBottomSheet|ModalBottomSheetLayout|AlertDialog|BasicAlertDialog|DatePickerDialog|TimePickerDialog|\*)\s*(as\s+\w+\s*)?$"),
    re.compile(r"\bandroidx\.compose\.ui\.window\.Dialog\s*\("),
    re.compile(r"\bandroidx\.compose\.material3?\.(ModalBottomSheet|ModalBottomSheetLayout|AlertDialog|BasicAlertDialog)\s*\("),
]
skip_dirs = {"build", ".gradle", ".git", "node_modules"}
violations = []
compose_root = os.path.join(root, "compose")
for dirpath, dirs, files in os.walk(compose_root):
    # `build` is Gradle output next to a module's src/, but also the package root
    # (src/.../kotlin/build/raft/...): only skip it outside src/.
    in_src = "src" in os.path.relpath(dirpath, compose_root).split(os.sep)
    dirs[:] = [d for d in dirs if d not in skip_dirs or (d == "build" and in_src)]
    for name in files:
        if not name.endswith((".kt", ".kts")):
            continue
        path = os.path.join(dirpath, name)
        rel = os.path.normpath(os.path.relpath(path, root))
        if rel == allowed:
            continue
        with open(path, encoding="utf-8", errors="replace") as fh:
            for no, line in enumerate(fh, 1):
                if any(p.search(line) for p in patterns):
                    violations.append((rel, no, line.strip()))
for rel, no, line in violations:
    print(f"::error file={rel},line={no}::{line} — open windows through build.raft.designsystem.components.RaftWindowDialog (or RaftDialog / RaftSheet / RaftDialogSheet / Modal), not a raw Dialog or Material sheet.", file=sys.stderr)
sys.exit(1 if violations else 0)
PY
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  local ds="$tmp/compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/components"
  local app="$tmp/compose/shared/src/commonMain/kotlin/build/raft/app"
  mkdir -p "$ds" "$app" "$tmp/compose/shared/build/generated"
  printf 'import androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n' > "$ds/RaftWindowDialog.kt"
  printf 'import androidx.compose.ui.window.DialogProperties\nimport androidx.compose.ui.window.DialogWindowProvider\nimport android.app.AlertDialog\n' > "$app/Ok.kt"
  printf 'import androidx.compose.ui.window.Dialog\n' > "$tmp/compose/shared/build/generated/Ignored.kt"
  scan "$tmp" "$allowed_file" 2>/dev/null || { echo "self-test: clean tree reported a violation" >&2; return 1; }

  local bad
  for bad in \
    'import androidx.compose.ui.window.Dialog' \
    'import androidx.compose.ui.window.Dialog as PlatformDialog' \
    'import androidx.compose.ui.window.*' \
    'import androidx.compose.material3.ModalBottomSheet' \
    'import androidx.compose.material3.AlertDialog' \
    'import androidx.compose.material3.*' \
    '    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {}' \
    '    androidx.compose.material3.ModalBottomSheet(onDismissRequest = {}) {}'; do
    printf '%s\n' "$bad" > "$app/Bad.kt"
    if scan "$tmp" "$allowed_file" 2>/dev/null; then
      echo "self-test: missed violation: $bad" >&2
      return 1
    fi
  done
  echo "check_raft_window_dialog self-test passed"
}

root="."
while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) root="$2"; shift 2 ;;
    --self-test) self_test; exit $? ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

if [[ "$root" == "." ]]; then
  root="$(cd "$(dirname "$0")/.." && pwd)"
fi
scan "$root" "$allowed_file"
echo "RaftWindowDialog ownership: no raw Dialog / ModalBottomSheet outside $allowed_file"
