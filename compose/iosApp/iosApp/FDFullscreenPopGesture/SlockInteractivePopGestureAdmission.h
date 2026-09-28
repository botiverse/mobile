#ifndef SlockInteractivePopGestureAdmission_h
#define SlockInteractivePopGestureAdmission_h

#include <math.h>
#include <stdbool.h>
#include <stddef.h>

typedef struct {
    size_t navigationStackCount;
    bool interactivePopDisabled;
    bool transitioning;
    double maxAllowedInitialDistance;
    double beginningLocationX;
    double translationX;
    double translationY;
    double velocityX;
    double velocityY;
    bool isLeftToRight;
} SlockInteractivePopGestureAdmission;

typedef struct {
    bool isPanGesture;
    bool hasChildScrollView;
    bool hasScrolledHorizontalScrollView;
    SlockInteractivePopGestureAdmission popAdmission;
} SlockInteractivePopSimultaneousAdmission;

/** Pure admission policy for interactive pop gesture shouldBegin. */
static inline bool SlockInteractivePopGestureShouldBegin(
    SlockInteractivePopGestureAdmission input
) {
    if (input.navigationStackCount <= 1 || input.interactivePopDisabled || input.transitioning) {
        return false;
    }
    if (input.maxAllowedInitialDistance > 0.0 && input.beginningLocationX > input.maxAllowedInitialDistance) {
        return false;
    }
    double multiplier = input.isLeftToRight ? 1.0 : -1.0;
    double horizontal = fabs(input.velocityX) > 1.0 ? input.velocityX : input.translationX;
    double vertical = fabs(input.velocityX) > 1.0 ? input.velocityY : input.translationY;
    return (horizontal * multiplier) > 0.0 && fabs(horizontal) > fabs(vertical);
}

/** Pure admission policy for interactive pop simultaneous recognition with other gestures. */
static inline bool SlockInteractivePopShouldRecognizeSimultaneously(
    SlockInteractivePopSimultaneousAdmission input
) {
    if (!input.isPanGesture || !input.hasChildScrollView || input.hasScrolledHorizontalScrollView) {
        return false;
    }
    return SlockInteractivePopGestureShouldBegin(input.popAdmission);
}

#endif /* SlockInteractivePopGestureAdmission_h */
