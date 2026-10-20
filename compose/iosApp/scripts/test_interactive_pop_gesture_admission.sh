#!/usr/bin/env bash
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADER="$IOS_ROOT/iosApp/FDFullscreenPopGesture/RaftInteractivePopGestureAdmission.h"
NAVIGATION_SOURCE="$IOS_ROOT/iosApp/FDFullscreenPopGesture/UINavigationController+FDFullscreenPopGesture.m"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

PAGE_SOURCE="$IOS_ROOT/iosApp/Cmp/RaftPageViewController.m"
FACTORY_SOURCE="$IOS_ROOT/iosApp/Cmp/RaftPageControllerFactory.swift"

# Keep the page pop contract and direction-aware horizontal-scroll arbitration
# visible to this focused admission check; the C policy test alone cannot see
# Objective-C touch routing. CMP pages pop from anywhere (full screen); Compose
# scrollers are not UIScrollViews, so the page reports back-direction consumption
# through fd_interactivePopContentConsumesBackSwipe (Kotlin RaftIosPage.contentConsumesBackSwipe).
grep -Fq 'renderViewController.fd_interactivePopAllowsFullScreen = YES;' "$NAVIGATION_SOURCE"
grep -Fq 'self.fd_interactivePopAllowsFullScreen = YES;' "$PAGE_SOURCE"
grep -Fq 'RaftInteractivePopHorizontalScrollCanConsumeDirection' "$NAVIGATION_SOURCE"
grep -Fq 'self.touchView = touch.view;' "$NAVIGATION_SOURCE"
grep -Fq 'self.touchPage.fd_interactivePopContentConsumesBackSwipe' "$NAVIGATION_SOURCE"
grep -Fq '.contentConsumesBackSwipe =' "$NAVIGATION_SOURCE"
grep -Fq 'self.fd_interactivePopContentConsumesBackSwipe = ^BOOL(CGPoint locationInView)' "$PAGE_SOURCE"
grep -Fq 'container.raftBackSwipeContentProbe = { point in' "$FACTORY_SOURCE"
grep -Fq 'page.contentConsumesBackSwipe(x: Double(point.x), y: Double(point.y))' "$FACTORY_SOURCE"
if grep -Fq 'fd_interactivePopAllowsFullScreen = NO;' "$NAVIGATION_SOURCE" "$PAGE_SOURCE"; then
    echo "CMP pages must keep full-screen back swipe admission" >&2
    exit 1
fi

CC_BIN="${CC:-$(command -v clang || command -v cc || command -v gcc)}"

"$CC_BIN" -std=c11 -Wall -Wextra -Werror -I"$(dirname "$HEADER")" -x c -o "$TMP_DIR/interactive-pop-admission" - <<'C'
#include <assert.h>
#include "RaftInteractivePopGestureAdmission.h"

static RaftInteractivePopTouchAdmission edgeTouch(void) {
    return (RaftInteractivePopTouchAdmission) {
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

static RaftInteractivePopGestureAdmission rightSwipe(void) {
    return (RaftInteractivePopGestureAdmission) {
        .touchBeganInEdgeZone = true,
        .transitioning = false,
        .contentConsumesBackSwipe = false,
        .translationX = 20.0,
        .translationY = 2.0,
        .velocityX = 250.0,
        .velocityY = 10.0,
        .isLeftToRight = true,
    };
}

int main(void) {
    RaftInteractivePopTouchAdmission touch = edgeTouch();
    assert(RaftInteractivePopTouchBeginsInEdgeZone(touch));

    // Default strip is 40pt wide: inclusive at the boundary, content area beyond it never pops
    touch = edgeTouch(); touch.touchLocationX = 40.0;
    assert(RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.touchLocationX = 41.0;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.touchLocationX = 195.0;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.allowsFullScreen = true; touch.touchLocationX = 195.0;
    assert(RaftInteractivePopTouchBeginsInEdgeZone(touch));

    // Full-screen admission also arms child-pan ordering at the center.
    assert(RaftInteractivePopShouldBeRequiredToFailBy(
        RaftInteractivePopTouchBeginsInEdgeZone(touch), true));

    // A page-specific limit replaces the default strip width
    touch = edgeTouch(); touch.maxAllowedInitialDistance = 60.0; touch.touchLocationX = 55.0;
    assert(RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.maxAllowedInitialDistance = 20.0; touch.touchLocationX = 30.0;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));

    // RTL: the strip is on the right edge
    touch = edgeTouch(); touch.isLeftToRight = false; touch.touchLocationX = 380.0;
    assert(RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.isLeftToRight = false; touch.touchLocationX = 10.0;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));

    // Root, disabled and transitioning stacks never arm the strip
    touch = edgeTouch(); touch.navigationStackCount = 1;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.interactivePopDisabled = true;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));
    touch = edgeTouch(); touch.transitioning = true;
    assert(!RaftInteractivePopTouchBeginsInEdgeZone(touch));

    RaftInteractivePopGestureAdmission input = rightSwipe();
    assert(RaftInteractivePopGestureShouldBegin(input));

    // Content that still scrolls in the back direction (a Compose pager past its first
    // page, a row away from its start) keeps the drag, in the edge strip too.
    input = rightSwipe(); input.contentConsumesBackSwipe = true;
    assert(!RaftInteractivePopGestureShouldBegin(input));
    input = rightSwipe(); input.isLeftToRight = false; input.translationX = -20.0; input.velocityX = -250.0;
    input.contentConsumesBackSwipe = true;
    assert(!RaftInteractivePopGestureShouldBegin(input));
    input.contentConsumesBackSwipe = false;
    assert(RaftInteractivePopGestureShouldBegin(input));

    // Content-area swipe never pops, whatever the direction
    input = rightSwipe(); input.touchBeganInEdgeZone = false;
    assert(!RaftInteractivePopGestureShouldBegin(input));

    input = rightSwipe(); input.transitioning = true;
    assert(!RaftInteractivePopGestureShouldBegin(input));

    // Direction in LTR / RTL
    input = rightSwipe(); input.translationX = -20.0; input.velocityX = -250.0;
    assert(!RaftInteractivePopGestureShouldBegin(input));
    input = rightSwipe(); input.isLeftToRight = false; input.translationX = -20.0; input.velocityX = -250.0;
    assert(RaftInteractivePopGestureShouldBegin(input));
    input = rightSwipe(); input.isLeftToRight = false;
    assert(!RaftInteractivePopGestureShouldBegin(input));

    // A vertical scroll that starts in the strip stays a scroll
    input = rightSwipe(); input.translationX = 5.0; input.translationY = 30.0; input.velocityX = 10.0; input.velocityY = 100.0;
    assert(!RaftInteractivePopGestureShouldBegin(input));

    // Current vertical motion must not fall back to earlier horizontal drift.
    input = rightSwipe(); input.translationX = 30.0; input.translationY = 0.0; input.velocityX = 0.0; input.velocityY = 300.0;
    assert(!RaftInteractivePopGestureShouldBegin(input));

    // Low velocity falls back to translation
    input = rightSwipe(); input.velocityX = 0.5; input.velocityY = 0.1; input.translationX = 15.0; input.translationY = 2.0;
    assert(RaftInteractivePopGestureShouldBegin(input));

    // Admitted content pans wait for pop; rejected touches keep content control.
    assert(RaftInteractivePopShouldBeRequiredToFailBy(true, true));
    assert(!RaftInteractivePopShouldBeRequiredToFailBy(false, true));
    assert(!RaftInteractivePopShouldBeRequiredToFailBy(true, false));

    return 0;
}
C

"$TMP_DIR/interactive-pop-admission"
echo "interactive-pop gesture admission: PASS"
