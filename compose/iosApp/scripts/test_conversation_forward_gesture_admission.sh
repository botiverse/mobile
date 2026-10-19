#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/SlockConversationForwardGestureAdmission.h"
NAVIGATION_SOURCE="$IOS_ROOT/iosApp/FDFullscreenPopGesture/UINavigationController+FDFullscreenPopGesture.m"
RENDER_SOURCE="$IOS_ROOT/iosApp/Cmp/RaftPageViewController.m"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

require_source() {
    local source_file="$1"
    local expected="$2"
    if ! grep -Fq -- "$expected" "$source_file"; then
        echo "conversation-forward source contract missing: $expected" >&2
        exit 1
    fi
}

# OFF is a registration boundary, not merely a delegate-time veto. The shared bridge setter must
# reach the navigation controller, and native disabling must both disable and detach the recognizer.
require_source "$NAVIGATION_SOURCE" "fd_setConversationForwardGestureEnabled:(BOOL)enabled"
require_source "$NAVIGATION_SOURCE" "gestureRecognizer.enabled = NO;"
require_source "$NAVIGATION_SOURCE" "removeGestureRecognizer:gestureRecognizer"
require_source "$NAVIGATION_SOURCE" "gestureRecognizer.enabled = YES;"
require_source "$RENDER_SOURCE" "setSlockConversationForwardGestureEnabled:(BOOL)enabled"
require_source "$RENDER_SOURCE" "fd_setConversationForwardGestureEnabled:"

CC_BIN="${CC:-$(command -v clang || command -v cc || command -v gcc)}"

"$CC_BIN" -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/forward-admission" - <<'C'
#include <assert.h>
#include "SlockConversationForwardGestureAdmission.h"

static SlockConversationForwardGestureAdmission allowed(void) {
    return (SlockConversationForwardGestureAdmission) {
        .enabled = true,
        .appShellIsTop = true,
        .navigationStackAtRoot = true,
        .velocityX = -600.0,
        .velocityY = 20.0,
    };
}

int main(void) {
    SlockConversationForwardGestureAdmission input = allowed();
    assert(SlockConversationForwardGestureShouldBegin(input));

    input = allowed(); input.enabled = false;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.appShellIsTop = false;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.navigationStackAtRoot = false;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.transitioning = true;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.modalPresented = true;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.interactivePopActive = true;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.childGestureConflict = true;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.textInputActive = true;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.velocityX = 600.0;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    input = allowed(); input.velocityX = -50.0; input.velocityY = 200.0;
    assert(!SlockConversationForwardGestureShouldBegin(input));
    return 0;
}
C

"$TMP_DIR/forward-admission"
echo "conversation-forward gesture admission: PASS"
