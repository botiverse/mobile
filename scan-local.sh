#!/usr/bin/env bash
# scan-hardcode.sh — scan Slock compose sources for user-facing hardcoded strings
# that should migrate to MR.strings (kmm-resources).
#
# Output:
#   /tmp/slock-hardcode-scan/all.txt     — every candidate line, file:line:content
#   /tmp/slock-hardcode-scan/by-file.txt — candidate counts per file (sorted desc)
#   /tmp/slock-hardcode-scan/by-theme.txt — counts per build.raft.app.<scope> dir
#
# Heuristics:
#   - Captures string literals on lines that look like UI surface (title=,
#     subtitle=, label=, contentDescription=, placeholder=, hint=, buttonText=,
#     helperText=, message=, description=) or a bare Text("...") / Text(text = "...").
#   - Excludes obvious technical / debug lines (Compose trace labels,
#     back-handler debug labels, channel slug name=, KDoc / block comments,
#     hex colors, CSS-like px/dp/sp values, pure-dot strings, line comments).
#   - Excludes lines that already use MR.strings.* / StringResource.
#
# This is a coarse first pass. Every entry still needs human review before being
# turned into an MR.strings key.

set -uo pipefail

REPO="/Users/artin/AndroidStudioProjects/worktrees/Slock-task422-cleanup-scan"
OUT="/tmp/slock-hardcode-scan"
mkdir -p "$OUT"

ROOTS=(
  "compose/shared/src/commonMain/kotlin"
)

cd "$REPO" || exit 1

# Capture lines that look like UI text. Use PCRE2 with -U (multiline dot) off,
# case-insensitive, and free-spacing for readability.
PATTERN='(?xi)
  (?<![A-Za-z0-9_])
  (
    (?:title|subtitle|label|contentDescription|placeholder|hint|buttonText|helperText|message|description)\s*=\s*"
    |
    Text\s*\(\s*(?:text\s*=\s*)?"
  )
'

EXCLUDE_RE='rememberInfiniteTransition\s*\(\s*label\s*=|SlockBackHandler\s*\(.*\blabel\s*=|\blabel\s*=\s*"(slock-status|app_shell|floating_overlay|router_|sandbox_|thread_)|\blabel\s*=\s*"[a-z]+(_[a-z]+)+_pulse|\bname\s*=\s*"[a-z][a-z0-9_-]*"|MR\.strings\.|StringResource\b|^\s*\*\s|^\s*//|#[0-9a-fA-F]{3,8}|\d+(px|dp|sp|em|rem)|"\.{1,3}"|KuiklyComposerChannelItem'

# A second tighter exclusion: drop lines that look like fixture/placeholder text.
FIXTURE_RE='[A-Z][a-zA-Z]+-[A-Z][a-zA-Z]+|release[_ ]gate|integration[_ ]team'

: > "$OUT/all.txt"
: > "$OUT/by-file.txt"
: > "$OUT/by-theme.txt"

for root in "${ROOTS[@]}"; do
  [[ -d "$root" ]] || continue
  rg --no-heading --line-number --pcre2 "$PATTERN" "$root" \
    --glob '*.kt' --glob '*.kts' 2>/dev/null \
    | rg -v --pcre2 "$EXCLUDE_RE" \
    | rg -v --pcre2 "$FIXTURE_RE" \
    >> "$OUT/all.txt" || true
done

# Aggregate by file.
awk -F: '{ c[$1]++ } END { for (k in c) printf "%6d  %s\n", c[k], k }' "$OUT/all.txt" \
  | sort -rn > "$OUT/by-file.txt"

# Aggregate by theme = build/raft/app/<scope>/ part of the path.
# "scope" is the sub-package directory directly under app/, e.g.
#   build/raft/app/components/Foo.kt -> theme = "components"
#   build/raft/app/SlockHomeComponents.kt -> theme = "(root)"
awk '
  {
    # Extract just the file path: everything before the second colon.
    n = split($0, parts, ":")
    fpath = parts[1]
    for (k = 2; k < n && parts[k] !~ /^[0-9]+$/; k++) {
      fpath = fpath ":" parts[k]
    }

    n2 = split(fpath, p, "/")
    theme = "(other)"
    for (i = 1; i <= n2; i++) {
      if (p[i] == "app") {
        scope = p[i+1]
        if (scope ~ /\.kt$/) {
          theme = "(root)"
        } else {
          theme = scope
        }
        break
      }
    }
    c[theme]++
  }
  END {
    for (k in c) printf "%6d  %s\n", c[k], k
  }' "$OUT/all.txt" \
  | sort -rn > "$OUT/by-theme.txt"

echo "Wrote:"
echo "  $OUT/all.txt       ($(wc -l < "$OUT/all.txt") lines)"
echo "  $OUT/by-file.txt   ($(wc -l < "$OUT/by-file.txt") files)"
echo "  $OUT/by-theme.txt  ($(wc -l < "$OUT/by-theme.txt") themes)"