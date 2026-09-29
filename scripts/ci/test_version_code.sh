#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VC="$ROOT/ci/version_code.sh"

pass=0; fail=0
expect() {
  local desc="$1" got="$2" want="$3"
  if [ "$got" = "$want" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $desc (got=$got want=$want)" >&2; fi
}

expect "1.12.0.131" "$($VC 1 12 0 131)" "11200131"
expect "1.12.0.132" "$($VC 1 12 0 132)" "11200132"
expect "1.12.1.0"   "$($VC 1 12 1 0)"   "11201000"
expect "1.13.0.0"   "$($VC 1 13 0 0)"   "11300000"

[ "$($VC 1 12 0 131)" -lt "$($VC 1 12 0 132)" ] && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: 131<132"; }
[ "$($VC 1 12 0 132)" -lt "$($VC 1 12 1 0)" ]   && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: 132<1.12.1"; }
[ "$($VC 1 12 1 0)"   -lt "$($VC 1 13 0 0)" ]   && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: 1.12.1<1.13"; }
[ "$($VC 1 12 0 131)" != "$($VC 1 12 1 0)" ]    && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: old-collision 131=1.12.1"; }

if $VC 1 12 0 1000 >/dev/null 2>&1; then fail=$((fail+1)); echo "FAIL: build=1000 accepted"; else pass=$((pass+1)); fi
if $VC 1 12 0 abc  >/dev/null 2>&1; then fail=$((fail+1)); echo "FAIL: non-integer accepted"; else pass=$((pass+1)); fi

echo "version_code tests: $pass pass / $fail fail"
[ "$fail" -eq 0 ]
if $VC 1 100 0 0 >/dev/null 2>&1; then fail=$((fail+1)); echo "FAIL: minor=100 accepted"; else pass=$((pass+1)); fi
if $VC 1 12 100 0 >/dev/null 2>&1; then fail=$((fail+1)); echo "FAIL: patch=100 accepted"; else pass=$((pass+1)); fi
echo "version_code tests: $pass pass / $fail fail"
[ "$fail" -eq 0 ]
