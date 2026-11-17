package build.raft.app

/**
 * #192 trace scope extension: phase names for thread composer rendering.
 *
 * The composer (text input + IME-lift + suggestion list) is one of the most
 * frequently re-composed areas on the chat page — every keystroke triggers
 * recomposition of the field, and IME lift causes a top inset animation
 * followed by content re-layout. Marker names are literal constants — no
 * allocations in the hot path.
 */
internal object RaftComposerTraceNames {
    const val COMPOSER_RENDER = "composer.render"
}