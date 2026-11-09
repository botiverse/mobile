#ifndef RaftConversationForwardGestureAdmission_h
#define RaftConversationForwardGestureAdmission_h

#include <math.h>
#include <stdbool.h>
#include <string.h>

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
    /// Forward comes from the trailing edge and moves towards the leading one: leftward in LTR,
    /// rightward in RTL.
    bool isLeftToRight;
} RaftConversationForwardGestureAdmission;

/**
 * Why [input] does not begin a Forward swipe, or "admitted". The first failing check wins, in
 * the order RaftConversationForwardGestureShouldBegin applies them (diagnostics only).
 */
static inline const char *RaftConversationForwardGestureRejectReason(
    RaftConversationForwardGestureAdmission input
) {
    if (!input.enabled) return "not_enabled";
    if (!input.appShellIsTop) return "app_shell_not_top";
    if (!input.navigationStackAtRoot) return "not_at_root";
    if (input.transitioning) return "transitioning";
    if (input.modalPresented) return "modal_presented";
    if (input.interactivePopActive) return "interactive_pop_active";
    if (input.childGestureConflict) return "child_conflict";
    if (input.textInputActive) return "text_input_active";
    double horizontal = fabs(input.velocityX) > 1.0 ? input.velocityX : input.translationX;
    double vertical = fabs(input.velocityX) > 1.0 ? input.velocityY : input.translationY;
    double towardsLeading = input.isLeftToRight ? -horizontal : horizontal;
    if (!(towardsLeading > 0.0)) return "wrong_direction";
    if (!(fabs(horizontal) > fabs(vertical))) return "not_horizontal";
    return "admitted";
}

/** Pure admission policy shared by the UIKit recognizer and the host-side contract test. */
static inline bool RaftConversationForwardGestureShouldBegin(
    RaftConversationForwardGestureAdmission input
) {
    return strcmp(RaftConversationForwardGestureRejectReason(input), "admitted") == 0;
}

#endif /* RaftConversationForwardGestureAdmission_h */
