package build.raft.app

/**
 * #192 trace scope extension: phase names for [RaftAppAvatar] rendering.
 *
 * Avatar is rendered for every channel row in Home + every participant in Thread —
 * that's a lot of recompositions during scroll, so we want per-phase attribution
 * to know whether `identify` (pixel-spec parsing), `render` (PixelAvatar path),
 * `http_fetch` (network image), or `fallback_default` (generic icon) is the hot
 * path. Marker strings are literal constants — no allocations in the hot path.
 *
 * These names share the same `expect/actual` infrastructure as
 * [build.raft.app.markdown.RaftMarkdownTraceNames] — `traceMarkdownSection`
 * and `traceMarkdownSectionNonComposable` accept any phase name string.
 */
internal object RaftAvatarTraceNames {
    const val IDENTIFY = "avatar.identify"
    const val HTTP_FETCH = "avatar.http_fetch"
    const val RENDER = "avatar.render"
    const val FALLBACK_DEFAULT = "avatar.fallback_default"
}