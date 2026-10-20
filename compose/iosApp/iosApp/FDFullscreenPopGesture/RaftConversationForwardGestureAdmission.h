#ifndef RaftConversationForwardGestureAdmission_h
#define RaftConversationForwardGestureAdmission_h

#include <math.h>
#include <stdbool.h>

typedef struct {
    bool enabled;
    bool appShellIsTop;
    bool navigationStackAtRoot;
    bool transitioning;
    bool modalPresented;
    bool interactivePopActive;
    bool childGestureConflict;
    bool textInputActive;
    double translationX;
    double translationY;
    double velocityX;
    double velocityY;
} RaftConversationForwardGestureAdmission;

/** Pure admission policy shared by the UIKit recognizer and the host-side contract test. */
static inline bool RaftConversationForwardGestureShouldBegin(
    RaftConversationForwardGestureAdmission input
) {
    if (!input.enabled || !input.appShellIsTop || !input.navigationStackAtRoot) {
        return false;
    }
    if (
        input.transitioning ||
        input.modalPresented ||
        input.interactivePopActive ||
        input.childGestureConflict ||
        input.textInputActive
    ) {
        return false;
    }
    double horizontal = fabs(input.velocityX) > 1.0 ? input.velocityX : input.translationX;
    double vertical = fabs(input.velocityX) > 1.0 ? input.velocityY : input.translationY;
    return horizontal < 0.0 && fabs(horizontal) > fabs(vertical);
}

#endif /* RaftConversationForwardGestureAdmission_h */
