#ifndef RaftConversationForwardGestureAdmission_h
#define RaftConversationForwardGestureAdmission_h

#include <math.h>
#include <stdbool.h>
#include <string.h>

/**
 * Width (points) of the trailing-edge strip a Forward swipe has to start in. The recognizer is a
 * plain pan admitted by where the touch starts: UIScreenEdgePanGestureRecognizer relies on the
 * system's own edge classification of the touch, and on a real iPhone (artin) and with injected
 * Simulator touches (QA, run 44356101019) it never left `possible` / went `failed` for a swipe that
 * started 2 pt from the right edge.
 */
#define RAFT_CONVERSATION_FORWARD_EDGE_ZONE 24.0

/** Whether a touch starting at [touchX] in a view [viewWidth] wide starts at its trailing edge. */
static inline bool RaftConversationForwardTouchStartsInEdgeZone(
    double touchX,
    double viewWidth,
    bool isLeftToRight
) {
    if (!(viewWidth > 0.0)) return false;
    double fromTrailingEdge = isLeftToRight ? viewWidth - touchX : touchX;
    // A touch a little outside the bounds (rounding at the screen edge) still counts.
    return fromTrailingEdge >= -2.0 && fromTrailingEdge <= RAFT_CONVERSATION_FORWARD_EDGE_ZONE;
}

typedef struct {
    bool enabled;
    bool appShellIsTop;
    bool navigationStackAtRoot;
    bool transitioning;
    bool modalPresented;
    bool interactivePopActive;
    bool childGestureConflict;
    bool textInputActive;
    /// The touch started in the trailing-edge strip ([RaftConversationForwardTouchStartsInEdgeZone]).
    bool startedAtTrailingEdge;
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
    if (!input.startedAtTrailingEdge) return "not_edge_start";
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
