#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/SlockInteractivePopGestureAdmission.h"
NAVIGATION_SOURCE="$IOS_ROOT/iosApp/FDFullscreenPopGesture/UINavigationController+FDFullscreenPopGesture.m"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

require_source() {
    local source_file="$1"
    local expected="$2"
    if ! grep -Fq -- "$expected" "$source_file"; then
        echo "interactive-pop source contract missing: $expected" >&2
        exit 1
    fi
}

require_source "$NAVIGATION_SOURCE" "shouldRecognizeSimultaneouslyWithGestureRecognizer:"
require_source "$NAVIGATION_SOURCE" "SlockInteractivePopGestureShouldBegin"
require_source "$NAVIGATION_SOURCE" "SlockInteractivePopShouldRecognizeSimultaneously"
require_source "$NAVIGATION_SOURCE" "SlockInteractivePopHasChildScrollView"
require_source "$NAVIGATION_SOURCE" "SlockInteractivePopHasScrolledHorizontalScrollView"
require_source "$NAVIGATION_SOURCE" "SlockBuildInteractivePopGestureAdmission"

CC_BIN="${CC:-$(command -v clang || command -v cc || command -v gcc)}"

"$CC_BIN" -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/interactive-pop-admission" - <<'C'
#include <assert.h>
#include "SlockInteractivePopGestureAdmission.h"

static SlockInteractivePopGestureAdmission allowed(void) {
    return (SlockInteractivePopGestureAdmission) {
        .navigationStackCount = 2,
        .interactivePopDisabled = false,
        .transitioning = false,
        .maxAllowedInitialDistance = 0.0,
        .beginningLocationX = 100.0,
        .translationX = 20.0,
        .translationY = 2.0,
        .velocityX = 250.0,
        .velocityY = 10.0,
        .isLeftToRight = true,
    };
}

int main(void) {
    SlockInteractivePopGestureAdmission input = allowed();
    assert(SlockInteractivePopGestureShouldBegin(input));

    // Navigation stack at root must not pop
    input = allowed(); input.navigationStackCount = 1;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Explicitly disabled interactive pop
    input = allowed(); input.interactivePopDisabled = true;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Navigation controller in transition
    input = allowed(); input.transitioning = true;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Distance to edge constraints
    input = allowed(); input.maxAllowedInitialDistance = 50.0; input.beginningLocationX = 80.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    input = allowed(); input.maxAllowedInitialDistance = 50.0; input.beginningLocationX = 30.0;
    assert(SlockInteractivePopGestureShouldBegin(input));

    // Direction constraints in LTR
    input = allowed(); input.translationX = -20.0; input.velocityX = -250.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Direction constraints in RTL
    input = allowed(); input.isLeftToRight = false; input.translationX = -20.0; input.velocityX = -250.0;
    assert(SlockInteractivePopGestureShouldBegin(input));

    input = allowed(); input.isLeftToRight = false; input.translationX = 20.0; input.velocityX = 250.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Vertical dominance constraints
    input = allowed(); input.translationX = 5.0; input.translationY = 30.0; input.velocityX = 10.0; input.velocityY = 100.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Diagonal tie (dx == dy) is not strictly horizontal-dominant
    input = allowed(); input.translationX = 10.0; input.translationY = 10.0; input.velocityX = 0.0; input.velocityY = 0.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Low velocity falls back to translation
    input = allowed(); input.velocityX = 0.5; input.velocityY = 0.1; input.translationX = 15.0; input.translationY = 2.0;
    assert(SlockInteractivePopGestureShouldBegin(input));

    // Simultaneous recognition tests
    SlockInteractivePopSimultaneousAdmission sim = {
        .isPanGesture = true,
        .hasChildScrollView = true,
        .hasScrolledHorizontalScrollView = false,
        .popAdmission = allowed(),
    };
    assert(SlockInteractivePopShouldRecognizeSimultaneously(sim));

    // Non-pan gesture recognizers must not be admitted
    sim.isPanGesture = false;
    assert(!SlockInteractivePopShouldRecognizeSimultaneously(sim));

    // Touch not inside a scroll view hierarchy
    sim = (SlockInteractivePopSimultaneousAdmission){
        .isPanGesture = true,
        .hasChildScrollView = false,
        .hasScrolledHorizontalScrollView = false,
        .popAdmission = allowed(),
    };
    assert(!SlockInteractivePopShouldRecognizeSimultaneously(sim));

    // Scrolled horizontal scroll view (e.g. TabPager on Tab 1) must yield to pager scrolling
    sim = (SlockInteractivePopSimultaneousAdmission){
        .isPanGesture = true,
        .hasChildScrollView = true,
        .hasScrolledHorizontalScrollView = true,
        .popAdmission = allowed(),
    };
    assert(!SlockInteractivePopShouldRecognizeSimultaneously(sim));

    // Inadmissible pop must reject simultaneous recognition
    input = allowed(); input.navigationStackCount = 1;
    sim = (SlockInteractivePopSimultaneousAdmission){
        .isPanGesture = true,
        .hasChildScrollView = true,
        .hasScrolledHorizontalScrollView = false,
        .popAdmission = input,
    };
    assert(!SlockInteractivePopShouldRecognizeSimultaneously(sim));

    return 0;
}
C

"$TMP_DIR/interactive-pop-admission"
echo "interactive-pop gesture admission: PASS"
