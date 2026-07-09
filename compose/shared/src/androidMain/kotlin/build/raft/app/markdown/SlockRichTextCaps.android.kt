package build.raft.app.markdown

// Evidence: core-render-android draws both chip kinds natively —
// KRSlockInlineCodeSpan + KRSlockMarkdownTagSpan (KRRichTextBuilder.kt) with
// chrome painted by KRRichTextViewDrawer.kt (fill + 1dp border + atomic
// advance, tasks #401/#440). The reaction sprite atlas has always decoded on
// Android (the emoji fallback of task #421 was iOS-only).
internal actual fun slockRichTextPlatformCaps(): SlockRichTextPlatformCaps =
    SlockRichTextPlatformCaps(
        inlineCodeChrome = true,
        slockRefChrome = true,
        reactionAtlas = true
    )
