package build.raft.app.markdown

// Evidence: core-render-android draws generic inline boxes and inline code
// natively (KRRichTextBuilder.kt / KRRichTextViewDrawer.kt). The reaction
// sprite atlas has always decoded on Android (the emoji fallback of task #421
// was iOS-only).
internal actual fun slockRichTextPlatformCaps(): SlockRichTextPlatformCaps =
    SlockRichTextPlatformCaps(
        inlineCodeChrome = true,
        slockRefChrome = true,
        reactionAtlas = true
    )
