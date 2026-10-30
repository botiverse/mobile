package build.raft.app.markdown

// Evidence: core-render-android draws span backgrounds and generic inline
// boxes natively (KRRichTextBuilder.kt / KRRichTextViewDrawer.kt); inline code
// uses the wrapping background path instead of the legacy yellow-frame marker.
// The reaction sprite atlas has always decoded on Android (the emoji fallback
// of task #421 was iOS-only).
internal actual fun raftRichTextPlatformCaps(): RaftRichTextPlatformCaps =
    RaftRichTextPlatformCaps(
        inlineCodeChrome = true,
        raftRefChrome = true,
        reactionAtlas = true
    )

/** Minikin: a lone letter-spaced character has no advance on Android 15+, so chip padding is a placeholder. */
internal actual fun raftPlatformInlineSpacerIsPlaceholder(): Boolean = true
