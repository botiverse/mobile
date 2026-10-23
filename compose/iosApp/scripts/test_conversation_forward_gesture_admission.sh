#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/RaftConversationForwardGestureAdmission.h"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CC_BIN="${CC:-$(command -v clang || command -v cc || command -v gcc)}"

"$CC_BIN" -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/forward-admission" - <<'C'
#include <assert.h>
#include "RaftConversationForwardGestureAdmission.h"

static RaftConversationForwardGestureAdmission allowed(void) {
    return (RaftConversationForwardGestureAdmission) {
        .enabled = true,
        .appShellIsTop = true,
        .navigationStackAtRoot = true,
        .velocityX = -600.0,
        .velocityY = 20.0,
    };
}

int main(void) {
    RaftConversationForwardGestureAdmission input = allowed();
    assert(RaftConversationForwardGestureShouldBegin(input));

    input = allowed(); input.enabled = false;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.appShellIsTop = false;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.navigationStackAtRoot = false;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.transitioning = true;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.modalPresented = true;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.interactivePopActive = true;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.childGestureConflict = true;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.textInputActive = true;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.velocityX = 600.0;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.velocityX = -50.0; input.velocityY = 200.0;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    return 0;
}
C

"$TMP_DIR/forward-admission"
echo "conversation-forward gesture admission: PASS"
