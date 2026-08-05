#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
default_source="$script_dir/../entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"
requested_source="${1:-$default_source}"

if [ "$requested_source" = "-" ]; then
  source_text="$(cat)"
else
  source_text="$(cat "$requested_source")"
fi

fail() {
  printf 'OHOS bridge unknown-method contract failed: %s\n' "$1" >&2
  return 1
}

extract_default_branch() {
  awk '
    /^  call\(method: string, params: KRAny, callback: KuiklyRenderCallback\): KRAny \{$/ {
      in_call = 1
    }
    in_call && /^      default: \{$/ {
      in_default = 1
    }
    in_default {
      print
    }
    in_default && /^      \}$/ {
      exit
    }
    /^  private showAlert\(/ {
      exit
    }
  '
}

validate_source() {
  local candidate="$1"
  local default_body callback_count break_count
  default_body="$(printf '%s\n' "$candidate" | extract_default_branch)"
  [ -n "$default_body" ] || {
    fail 'unable to isolate KRBridgeModule.call default branch'
    return 1
  }

  callback_count="$(grep -Fc 'callback({' <<<"$default_body" || true)"
  [ "$callback_count" -eq 1 ] || {
    fail 'unknown methods must invoke the supplied callback exactly once'
    return 1
  }
  grep -Eq '"code"[[:space:]]*:[[:space:]]*-1([[:space:]]*[,}])' <<<"$default_body" || {
    fail 'unknown-method callback must report code=-1'
    return 1
  }
  grep -Fq '"message": "Method not implemented"' <<<"$default_body" || {
    fail 'unknown-method callback must identify the unsupported method'
    return 1
  }
  break_count="$(grep -Fc 'break;' <<<"$default_body" || true)"
  [ "$break_count" -eq 1 ] || {
    fail 'unknown-method callback branch must terminate exactly once'
    return 1
  }
}

assert_rejected() {
  local candidate="$1"
  local label="$2"
  if validate_source "$candidate" >/dev/null 2>&1; then
    fail "self-test accepted $label"
  fi
}

IFS= read -r -d '' valid_fixture <<'FIXTURE' || true
  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {
    switch (method) {
      default: {
        callback({
          "code": -1,
          "message": "Method not implemented"
        });
        break;
      }
    }
    return null;
  }

  private showAlert(params: KRAny, callback: KuiklyRenderCallback) {
FIXTURE

IFS= read -r -d '' silent_fixture <<'FIXTURE' || true
  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {
    switch (method) {
      default:
        break;
    }
    return null;
  }

  private showAlert(params: KRAny, callback: KuiklyRenderCallback) {
FIXTURE

IFS= read -r -d '' double_fixture <<'FIXTURE' || true
  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {
    switch (method) {
      default: {
        callback({
          "code": -1,
          "message": "Method not implemented"
        });
        callback({
          "code": -1,
          "message": "Method not implemented"
        });
        break;
      }
    }
    return null;
  }

  private showAlert(params: KRAny, callback: KuiklyRenderCallback) {
FIXTURE

validate_source "$valid_fixture"
assert_rejected "$silent_fixture" 'silent default branch'
assert_rejected "$double_fixture" 'double callback branch'
assert_rejected "${valid_fixture/\"code\": -1/\"code\": 0}" 'non-fail-closed status code'
validate_source "$source_text"

printf 'OHOS bridge unknown-method contract passed\n'
