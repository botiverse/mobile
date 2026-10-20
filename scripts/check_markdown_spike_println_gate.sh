#!/usr/bin/env bash
# Contract tooth for the SlockMarkdownSpike diagnostics channel (task #20,
# PR #1496 review requirement): every spike line must go through the single
# gated lazy helper `raftMarkdownLogSubTrace` in
# third_party/kuikly-compose-markdown/.../compose/Markdown.kt. A direct
# `println` of a SlockMarkdownSpike line anywhere else re-introduces ungated
# production cost (eager string interpolation + annotation scans + a logcat
# round-trip per composition) and must fail CI.
#
# Enforcement (hardened across three review rounds by XiShi):
# - println is located with \bprintln\s*\( (Kotlin whitespace between the
#   token and the paren must not bypass the scan);
# - each println's FULL argument span is walked paren-balanced, so a spike
#   literal any number of lines below the call is still attributed to it;
# - across the whole vendor tree there must be EXACTLY ONE spike-println,
#   and its position must lie inside the body of `raftMarkdownLogSubTrace`
#   in the approved file — textual equality with the canonical call is NOT
#   an exemption (a copy of the canonical line outside the helper fails).
#
# Sink-based emissions (RaftMarkdownNodeTiming's `sink.emit("SlockMarkdownSpike
# ...")`) are NOT violations: the sink only exists while diagnostics are ON
# (gate-before-construction), so only direct println calls are policed.
set -euo pipefail

vendor_root="third_party/kuikly-compose-markdown/src"
approved_file="third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/Markdown.kt"
helper_name="raftMarkdownLogSubTrace"

usage() {
  cat <<'EOF'
Usage: scripts/check_markdown_spike_println_gate.sh [--vendor-root <path>] [--self-test]
EOF
}

fail() {
  echo "::error::$1" >&2
  exit 1
}

# scan <root> <approved_file> <helper_name>
# Exit 0 = clean (exactly one spike-println, inside the helper body),
# 1 = violations (printed to stderr).
scan() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, os, re

root, approved, helper = sys.argv[1], sys.argv[2], sys.argv[3]
approved_norm = os.path.normpath(approved)

def balanced_end(src, open_idx, open_ch, close_ch):
    """Index of the char closing the bracket at open_idx (string-aware)."""
    depth, j, in_str = 0, open_idx, False
    while j < len(src):
        c = src[j]
        if c == '"' and src[j - 1] != "\\":
            in_str = not in_str
        elif not in_str:
            if c == open_ch:
                depth += 1
            elif c == close_ch:
                depth -= 1
                if depth == 0:
                    return j
        j += 1
    return len(src) - 1

def helper_body_range(src, name):
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\b", src)
    if not m:
        return None
    brace = src.find("{", m.end())
    if brace == -1:
        return None
    return (brace, balanced_end(src, brace, "{", "}"))

spike_printlns = []  # (path, line_no, start_offset, first_line)

for dirpath, _dirs, files in os.walk(root):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        src = open(path, encoding="utf-8").read()
        for m in re.finditer(r"\bprintln\s*\(", src):
            p = m.end() - 1  # the '(' itself
            j = balanced_end(src, p, "(", ")")
            span = src[m.start() : j + 1]
            if '"SlockMarkdownSpike' in span:
                line_no = src.count("\n", 0, m.start()) + 1
                spike_printlns.append(
                    (path, line_no, m.start(), span.strip().splitlines()[0])
                )

violations = []
if len(spike_printlns) == 0:
    violations.append(
        "no spike-println found at all - the gated helper's own emit is "
        "missing or was rewritten; the diagnostics channel contract is broken"
    )
else:
    approved_src = None
    body = None
    if os.path.isfile(approved):
        approved_src = open(approved, encoding="utf-8").read()
        body = helper_body_range(approved_src, helper)
    for path, line_no, off, head in spike_printlns:
        inside_helper = (
            os.path.normpath(path) == approved_norm
            and body is not None
            and body[0] < off < body[1]
        )
        if not inside_helper:
            violations.append(f"{path}:{line_no}: {head}")
    if not violations and len(spike_printlns) != 1:
        # Defensive: cannot happen (multiple calls inside one helper body
        # would all pass the location test), but the contract says exactly one.
        violations.append(
            f"expected exactly one spike-println, found {len(spike_printlns)}"
        )

if violations:
    for v in violations:
        print(v, file=sys.stderr)
    sys.exit(1)
PY
}

self_test() {
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/src/pkg"
  local approved="$tmp/src/pkg/Approved.kt"
  local canonical_helper='internal inline fun raftMarkdownLogSubTrace(message: () -> String) {
    if (!RaftMarkdownSubTrace.enabled) return
    println("SlockMarkdownSpike ${message()}")
}'

  # Case 1: rogue direct println in the APPROVED FILE, beside the helper —
  # must FAIL (review round-2 counterexample).
  printf '%s\ninternal fun accidentalUngatedSpikeInApprovedFile() {\n    println("SlockMarkdownSpike phase=regression.outside_helper")\n}\n' "$canonical_helper" > "$approved"
  if scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: rogue println beside the helper in the approved file was not detected"
  fi

  # Case 2: byte-identical COPY of the canonical call outside the helper —
  # must FAIL (review round-3 counterexample: text equality is no exemption).
  printf '%s\ninternal fun accidentalCanonicalDuplicate(message: () -> String) {\n    println("SlockMarkdownSpike ${message()}")\n}\n' "$canonical_helper" > "$approved"
  if scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: canonical-call duplicate outside the helper was not detected"
  fi

  # Case 3: whitespace between println and '(' — must FAIL (round-3).
  printf '%s\ninternal fun accidentalWhitespaceBypass() {\n    println ("SlockMarkdownSpike phase=regression.whitespace")\n}\n' "$canonical_helper" > "$approved"
  if scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: 'println (' whitespace form was not detected"
  fi

  # Case 4: spike literal several lines below the println( — must FAIL.
  printf '%s\n' "$canonical_helper" > "$approved"
  cat > "$tmp/src/pkg/Bad.kt" <<'KT'
fun bad() {
    println(
        buildString {
            append(
                "SlockMarkdownSpike phase=self.test.distant_literal"
            )
        }
    )
}
KT
  if scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: spike literal several lines below println( was not detected"
  fi
  rm "$tmp/src/pkg/Bad.kt"

  # Case 5: single-line direct println elsewhere — must FAIL.
  cat > "$tmp/src/pkg/Bad2.kt" <<'KT'
fun bad2() {
    println("SlockMarkdownSpike phase=self.test.single_line")
}
KT
  if scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: direct single-line spike println was not detected"
  fi
  rm "$tmp/src/pkg/Bad2.kt"

  # Case 6: helper's emit removed entirely (zero spike-printlns) — must FAIL.
  cat > "$approved" <<'KT'
internal inline fun raftMarkdownLogSubTrace(message: () -> String) {
    if (!RaftMarkdownSubTrace.enabled) return
}
KT
  if scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: zero spike-printlns (channel removed) was not detected"
  fi

  # Case 7: exactly the canonical helper + gated/sink usages — must PASS.
  printf '%s\n' "$canonical_helper" > "$approved"
  cat > "$tmp/src/pkg/Ok.kt" <<'KT'
fun ok(sink: (String) -> Unit) {
    sink("SlockMarkdownSpike phase=self.test.sink_emit")
    raftMarkdownLogSubTrace { "phase=self.test.helper" }
    println("unrelated line without the marker")
}
KT
  if ! scan "$tmp/src" "$approved" "raftMarkdownLogSubTrace" 2>/dev/null; then
    fail "self-test failed: canonical helper / sink.emit / unrelated println were flagged"
  fi
  echo "self-test OK"
}

if [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi
if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit 0
fi
if [ "${1:-}" = "--vendor-root" ]; then
  vendor_root="$2"
  shift 2
fi

if [ ! -f "$approved_file" ]; then
  fail "approved helper file missing: ${approved_file}"
fi
if ! grep -q "fun ${helper_name}" "$approved_file"; then
  fail "approved helper ${helper_name} not found in ${approved_file}"
fi
if ! scan "$vendor_root" "$approved_file" "$helper_name"; then
  fail "spike-println contract violated: exactly one println of a SlockMarkdownSpike line is allowed, inside ${helper_name} (route everything else through the helper)"
fi
echo "OK: exactly one spike-println, inside the gated helper"
