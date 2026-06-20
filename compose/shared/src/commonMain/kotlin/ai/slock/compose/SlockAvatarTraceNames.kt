package ai.slock.compose

/**
 * #192 trace scope extension: phase names for [SlockAvatar] rendering.
 *
 * Avatar is rendered for every channel row in Home + every participant in Thread —
 * that's a lot of recompositions during scroll, so we want per-phase attribution
 * to know whether `identify` (pixel-spec parsing), `render` (PixelAvatar path),
 * `http_fetch` (network image), or `fallback_default` (generic icon) is the hot
 * path. Marker strings are literal constants — no allocations in the hot path.
 *
 * These names share the same `expect/actual` infrastructure as
 * [ai.slock.compose.markdown.SlockMarkdownTraceNames] — `traceMarkdownSection`
 * and `traceMarkdownSectionNonComposable` accept any phase name string.
 */
internal object SlockAvatarTraceNames {
    const val IDENTIFY = "avatar.identify"
    const val HTTP_FETCH = "avatar.http_fetch"
    const val RENDER = "avatar.render"
    const val FALLBACK_DEFAULT = "avatar.fallback_default"
}