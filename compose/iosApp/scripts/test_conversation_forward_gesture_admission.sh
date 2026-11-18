#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/RaftConversationForwardGestureAdmission.h"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CC_BIN="${CC:-$(command -v clang || command -v cc || command -v gcc)}"

"$CC_BIN" -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/forward-admission" - <<'C'
#include <assert.h>
#include <string.h>
#include "RaftConversationForwardGestureAdmission.h"

static RaftConversationForwardGestureAdmission allowed(void) {
    return (RaftConversationForwardGestureAdmission) {
        .enabled = true,
        .appShellIsTop = true,
        .navigationStackAtRoot = true,
        .startedAtTrailingEdge = true,
        .velocityX = -600.0,
        .velocityY = 20.0,
        .isLeftToRight = true,
    };
}

static int rejectedFor(RaftConversationForwardGestureAdmission input, const char *reason) {
    return strcmp(RaftConversationForwardGestureRejectReason(input), reason) == 0;
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
    input = allowed(); input.startedAtTrailingEdge = false;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    assert(rejectedFor(input, "not_edge_start"));
    input = allowed(); input.velocityX = 600.0;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.velocityX = -50.0; input.velocityY = 200.0;
    assert(!RaftConversationForwardGestureShouldBegin(input));

    // RTL: Forward comes from the left edge and moves rightward.
    input = allowed(); input.isLeftToRight = false; input.velocityX = 600.0;
    assert(RaftConversationForwardGestureShouldBegin(input));
    input = allowed(); input.isLeftToRight = false;
    assert(!RaftConversationForwardGestureShouldBegin(input));
    // Before the finger has a velocity, the translation decides.
    input = allowed(); input.velocityX = 0.0; input.velocityY = 0.0; input.translationX = -12.0; input.translationY = 2.0;
    assert(RaftConversationForwardGestureShouldBegin(input));

    // Edge strip: 24 pt from the trailing edge (right in LTR, left in RTL), 402 pt wide view.
    assert(RaftConversationForwardTouchStartsInEdgeZone(400.0, 402.0, true));   // QA: x=400
    assert(RaftConversationForwardTouchStartsInEdgeZone(399.99, 402.0, true));  // XCUITest dx 0.995
    assert(RaftConversationForwardTouchStartsInEdgeZone(378.0, 402.0, true));
    assert(RaftConversationForwardTouchStartsInEdgeZone(402.5, 402.0, true));
    assert(!RaftConversationForwardTouchStartsInEdgeZone(377.0, 402.0, true));
    assert(!RaftConversationForwardTouchStartsInEdgeZone(201.0, 402.0, true));
    assert(!RaftConversationForwardTouchStartsInEdgeZone(2.0, 402.0, true));    // left edge: back, not Forward
    assert(RaftConversationForwardTouchStartsInEdgeZone(2.0, 402.0, false));    // RTL: left edge
    assert(!RaftConversationForwardTouchStartsInEdgeZone(400.0, 402.0, false));
    assert(!RaftConversationForwardTouchStartsInEdgeZone(400.0, 0.0, true));

    // The diagnostics reason names the first check that refused (artin #111).
    assert(rejectedFor(allowed(), "admitted"));
    input = allowed(); input.enabled = false; input.modalPresented = true;
    assert(rejectedFor(input, "not_enabled"));
    input = allowed(); input.navigationStackAtRoot = false;
    assert(rejectedFor(input, "not_at_root"));
    input = allowed(); input.modalPresented = true;
    assert(rejectedFor(input, "modal_presented"));
    input = allowed(); input.textInputActive = true;
    assert(rejectedFor(input, "text_input_active"));
    input = allowed(); input.velocityX = 600.0;
    assert(rejectedFor(input, "wrong_direction"));
    input = allowed(); input.velocityX = -50.0; input.velocityY = 200.0;
    assert(rejectedFor(input, "not_horizontal"));
    return 0;
}
C

"$TMP_DIR/forward-admission"
echo "conversation-forward gesture admission: PASS"
