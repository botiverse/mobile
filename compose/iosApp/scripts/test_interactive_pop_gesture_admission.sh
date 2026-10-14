#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/SlockInteractivePopGestureAdmission.h"
NAVIGATION_SOURCE="$IOS_ROOT/iosApp/FDFullscreenPopGesture/UINavigationController+FDFullscreenPopGesture.m"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Keep the expanded all-pages contract and direction-aware native horizontal-scroll arbitration
# visible to this focused admission check; the C policy test alone cannot see
# Objective-C touch routing.
grep -Fq 'renderViewController.fd_interactivePopAllowsFullScreen = YES;' "$NAVIGATION_SOURCE"
grep -Fq 'SlockInteractivePopHorizontalScrollCanConsumeDirection' "$NAVIGATION_SOURCE"
grep -Fq 'self.touchView = touch.view;' "$NAVIGATION_SOURCE"

CC_BIN="${CC:-$(command -v clang || command -v cc || command -v gcc)}"

"$CC_BIN" -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/interactive-pop-admission" - <<'C'
#include <assert.h>
#include "SlockInteractivePopGestureAdmission.h"

static SlockInteractivePopTouchAdmission edgeTouch(void) {
    return (SlockInteractivePopTouchAdmission) {
        .navigationStackCount = 2,
        .interactivePopDisabled = false,
        .allowsFullScreen = false,
        .transitioning = false,
        .maxAllowedInitialDistance = 0.0,
        .touchLocationX = 10.0,
        .viewWidth = 390.0,
        .isLeftToRight = true,
    };
}

static SlockInteractivePopGestureAdmission rightSwipe(void) {
    return (SlockInteractivePopGestureAdmission) {
        .touchBeganInEdgeZone = true,
        .transitioning = false,
        .translationX = 20.0,
        .translationY = 2.0,
        .velocityX = 250.0,
        .velocityY = 10.0,
        .isLeftToRight = true,
    };
}

int main(void) {
    SlockInteractivePopTouchAdmission touch = edgeTouch();
    assert(SlockInteractivePopTouchBeginsInEdgeZone(touch));

    // Default strip is 40pt wide: inclusive at the boundary, content area beyond it never pops
    touch = edgeTouch(); touch.touchLocationX = 40.0;
    assert(SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.touchLocationX = 41.0;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.touchLocationX = 195.0;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.allowsFullScreen = true; touch.touchLocationX = 195.0;
    assert(SlockInteractivePopTouchBeginsInEdgeZone(touch));

    // Full-screen admission also arms child-pan ordering at the center.
    assert(SlockInteractivePopShouldBeRequiredToFailBy(
        SlockInteractivePopTouchBeginsInEdgeZone(touch), true));

    // A page-specific limit replaces the default strip width
    touch = edgeTouch(); touch.maxAllowedInitialDistance = 60.0; touch.touchLocationX = 55.0;
    assert(SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.maxAllowedInitialDistance = 20.0; touch.touchLocationX = 30.0;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));

    // RTL: the strip is on the right edge
    touch = edgeTouch(); touch.isLeftToRight = false; touch.touchLocationX = 380.0;
    assert(SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.isLeftToRight = false; touch.touchLocationX = 10.0;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));

    // Root, disabled and transitioning stacks never arm the strip
    touch = edgeTouch(); touch.navigationStackCount = 1;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.interactivePopDisabled = true;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.transitioning = true;
    assert(!SlockInteractivePopTouchBeginsInEdgeZone(touch));

    SlockInteractivePopGestureAdmission input = rightSwipe();
    assert(SlockInteractivePopGestureShouldBegin(input));

    // Content-area swipe never pops, whatever the direction
    input = rightSwipe(); input.touchBeganInEdgeZone = false;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    input = rightSwipe(); input.transitioning = true;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Direction in LTR / RTL
    input = rightSwipe(); input.translationX = -20.0; input.velocityX = -250.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));
    input = rightSwipe(); input.isLeftToRight = false; input.translationX = -20.0; input.velocityX = -250.0;
    assert(SlockInteractivePopGestureShouldBegin(input));
    input = rightSwipe(); input.isLeftToRight = false;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // A vertical scroll that starts in the strip stays a scroll
    input = rightSwipe(); input.translationX = 5.0; input.translationY = 30.0; input.velocityX = 10.0; input.velocityY = 100.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Current vertical motion must not fall back to earlier horizontal drift.
    input = rightSwipe(); input.translationX = 30.0; input.translationY = 0.0; input.velocityX = 0.0; input.velocityY = 300.0;
    assert(!SlockInteractivePopGestureShouldBegin(input));

    // Low velocity falls back to translation
    input = rightSwipe(); input.velocityX = 0.5; input.velocityY = 0.1; input.translationX = 15.0; input.translationY = 2.0;
    assert(SlockInteractivePopGestureShouldBegin(input));

    // Admitted content pans wait for pop; rejected touches keep content control.
    assert(SlockInteractivePopShouldBeRequiredToFailBy(true, true));
    assert(!SlockInteractivePopShouldBeRequiredToFailBy(false, true));
    assert(!SlockInteractivePopShouldBeRequiredToFailBy(true, false));

    return 0;
}
C

"$TMP_DIR/interactive-pop-admission"
echo "interactive-pop gesture admission: PASS"
