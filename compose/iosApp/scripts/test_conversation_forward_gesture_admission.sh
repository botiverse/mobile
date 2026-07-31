#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/SlockConversationForwardGestureAdmission.h"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

clang -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/forward-admission" - <<'C'
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
