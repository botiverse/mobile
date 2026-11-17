package build.raft.app

/**
 * task #990 — emergency mitigation: on stop-loss platforms, no programmatic
 * viewport movement while the keyboard is open.
 *
 * Every capture of the white screen shows it during keyboard-driven viewport
 * shrink, in cycles where the app programmatically repositions the list. The
 * final capture (VC1000502, ticket abff8495) shows the strongest form: both
 * offset coordinate systems aligned, the native window relaid out and pre-drawn
 * to completion on every snap — and the list container holding three child
 * views for 22,314px of content, ~90% white. Repositioning during the shrink is
 * the one common trigger in every white cycle, so the safest treatment is to
 * not do it: cancel what is in flight when the shrink starts, and issue nothing
 * new while the keyboard stays open.
 *
 * What this deliberately keeps: the viewport still shrinks normally, the
 * composer still works, user drags and the explicit back-to-bottom button still
 * work (they are user intents, not programmatic corrections), and everything
 * returns to normal once the keyboard closes. The worst outcome is a focused
 * message sitting imperfectly — possibly behind the keyboard — until the user
 * flicks it into place. That is a legible state; a white page is not.
 *
 * What this is not: a root-cause fix. It is a platform-scoped kill-switch for
 * the triggering action while the draw-layer investigation continues, and it
 * makes no claim beyond that. task #1010 proved and fixed Android's competing
 * focused-anchor/IME owners, so Android restores ordinary correction together
 * with that root fix. iOS had already restored it after the stop-loss caused
 * the user-visible Hands 8f33b1f3 regression.
 */
internal enum class ThreadImeProgrammaticViewportPolicy {
    SuppressWhileImeOpen,
    AllowWhileImeOpen,
}

internal fun threadImeOpenSuppressesProgrammaticViewport(
    keyboardInsetDp: Float,
    policy: ThreadImeProgrammaticViewportPolicy,
): Boolean =
    policy == ThreadImeProgrammaticViewportPolicy.SuppressWhileImeOpen &&
        keyboardInsetDp > 0f

/**
 * Cancels an in-flight programmatic anchor at the moment the keyboard starts
 * to rise, and reports whether this pass was a rise transition.
 *
 * Lives here rather than inline in the composer effect so the behaviour test
 * executes this exact code against the real controller reducer — a test that
 * performed its own cancellation would stay green with the production call
 * deleted, which is the harness defect this task kept producing.
 *
 * Only a Free-fallback anchored intent family exists on these paths, and
 * [LazyViewportEvent.AnchorUnavailable] on it hands the viewport to the user
 * and issues nothing new. Explicit user commands (FollowLatest and friends)
 * never carry an Anchored intent here and are untouched.
 */
internal fun cancelInflightAnchorOnImeRise(
    keyboardInsetDp: Float,
    lastKeyboardInsetDp: Float,
    controllerState: LazyViewportControllerState,
    policy: ThreadImeProgrammaticViewportPolicy,
    dispatch: (LazyViewportEvent) -> Unit,
    log: (String) -> Unit,
): Boolean {
    if (!threadImeOpenSuppressesProgrammaticViewport(keyboardInsetDp, policy)) {
        return false
    }
    if (threadImeOpenSuppressesProgrammaticViewport(lastKeyboardInsetDp, policy)) {
        return false
    }
    val inflight = controllerState.pendingCommand?.id
    if (inflight != null && controllerState.intent is LazyViewportIntent.Anchored) {
        dispatch(LazyViewportEvent.AnchorUnavailable(inflight))
        log(
            "thread_ime_kill_switch result=cancelled_inflight " +
                "commandId=${inflight.epoch}:${inflight.sequence} " +
                "keyboardInsetDp=$lastKeyboardInsetDp->$keyboardInsetDp"
        )
    } else {
        log(
            "thread_ime_kill_switch result=armed " +
                "keyboardInsetDp=$lastKeyboardInsetDp->$keyboardInsetDp"
        )
    }
    return true
}

/**
 * The three automatic-correction gates, one per owner, each a production seam
 * the effect calls and the behaviour test drives. Returns true when the
 * correction must stay silent; logging happens here so a suppressed correction
 * is always visible in a capture.
 */
internal fun suppressAutoBottomHeightChange(
    keyboardInsetDp: Float,
    policy: ThreadImeProgrammaticViewportPolicy,
    log: (String) -> Unit,
): Boolean {
    if (!threadImeOpenSuppressesProgrammaticViewport(keyboardInsetDp, policy)) return false
    log("thread_ime_kill_switch result=suppressed_bottom_height_change")
    return true
}

internal fun suppressInitialBottomRetry(
    keyboardInsetDp: Float,
    policy: ThreadImeProgrammaticViewportPolicy,
    log: (String) -> Unit,
): Boolean {
    if (!threadImeOpenSuppressesProgrammaticViewport(keyboardInsetDp, policy)) return false
    log("thread_ime_kill_switch result=suppressed_initial_bottom_retry")
    return true
}

internal fun suppressFocusAnchorWhileOpen(
    keyboardInsetDp: Float,
    targetMessageId: String,
    policy: ThreadImeProgrammaticViewportPolicy,
    log: (String) -> Unit,
): Boolean {
    if (!threadImeOpenSuppressesProgrammaticViewport(keyboardInsetDp, policy)) return false
    log("thread_ime_kill_switch result=suppressed_focus_anchor messageId=$targetMessageId")
    return true
}
