package build.raft.app

/**
 * task #990 — emergency mitigation: no programmatic viewport movement while the
 * keyboard is open.
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
 * What this is not: a root-cause fix. It is a kill-switch for the triggering
 * action while the draw-layer investigation continues, and it makes no claim
 * beyond that.
 */
internal fun threadImeOpenSuppressesProgrammaticViewport(keyboardInsetDp: Float): Boolean =
    keyboardInsetDp > 0f
