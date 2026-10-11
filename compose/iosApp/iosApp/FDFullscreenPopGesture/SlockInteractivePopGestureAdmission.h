#ifndef SlockInteractivePopGestureAdmission_h
#define SlockInteractivePopGestureAdmission_h

#include <math.h>
#include <stdbool.h>
#include <stddef.h>

/** Width of the leading-edge strip where a back swipe may start when the page sets no limit. */
#define SLOCK_INTERACTIVE_POP_DEFAULT_EDGE_WIDTH 40.0

typedef struct {
    size_t navigationStackCount;
    bool interactivePopDisabled;
    bool allowsFullScreen;
    bool transitioning;
    double maxAllowedInitialDistance;
    double touchLocationX;
    double viewWidth;
    bool isLeftToRight;
} SlockInteractivePopTouchAdmission;

typedef struct {
    bool touchBeganInEdgeZone;
    bool transitioning;
    double translationX;
    double translationY;
    double velocityX;
    double velocityY;
    bool isLeftToRight;
} SlockInteractivePopGestureAdmission;

static inline double SlockInteractivePopEdgeWidth(double maxAllowedInitialDistance) {
    return maxAllowedInitialDistance > 0.0 ? maxAllowedInitialDistance : SLOCK_INTERACTIVE_POP_DEFAULT_EDGE_WIDTH;
}

/**
 * Pure policy for whether a touch starts an edge back swipe. Only touches inside the
 * leading-edge strip qualify unless the page explicitly allows full-screen admission.
 */
static inline bool SlockInteractivePopTouchBeginsInEdgeZone(SlockInteractivePopTouchAdmission input) {
    if (input.navigationStackCount <= 1 || input.interactivePopDisabled || input.transitioning) {
        return false;
    }
    if (input.allowsFullScreen) return true;
    double edgeWidth = SlockInteractivePopEdgeWidth(input.maxAllowedInitialDistance);
    double distanceToLeadingEdge = input.isLeftToRight ? input.touchLocationX : input.viewWidth - input.touchLocationX;
    return distanceToLeadingEdge >= 0.0 && distanceToLeadingEdge <= edgeWidth;
}

/** Pure admission policy for interactive pop gesture shouldBegin. */
static inline bool SlockInteractivePopGestureShouldBegin(SlockInteractivePopGestureAdmission input) {
    if (!input.touchBeganInEdgeZone || input.transitioning) {
        return false;
    }
    double multiplier = input.isLeftToRight ? 1.0 : -1.0;
    double horizontal = fabs(input.velocityX) > 1.0 ? input.velocityX : input.translationX;
    double vertical = fabs(input.velocityX) > 1.0 ? input.velocityY : input.translationY;
    return (horizontal * multiplier) > 0.0 && fabs(horizontal) > fabs(vertical);
}

/**
 * Inside the edge strip the back swipe wins: content pans (pagers, lists) wait for it to fail.
 * Outside the strip nothing waits, so content gestures behave exactly as before.
 */
static inline bool SlockInteractivePopShouldBeRequiredToFailBy(
    bool touchBeganInEdgeZone,
    bool otherIsContentPan
) {
    return touchBeganInEdgeZone && otherIsContentPan;
}

#endif /* SlockInteractivePopGestureAdmission_h */
