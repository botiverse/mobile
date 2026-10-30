package build.raft.app.markdown

// Inline code now uses shared ordinary background spans plus edge-only boxes;
// its semantic metadata deliberately does not activate Kuikly's legacy native
// yellow/framed atom path. Slock references still use native chrome from task
// #439. The reaction sprite atlas decodes since task #421/#409
// (MR-bundle resolution in KuiklyRenderComponentExpandHandler.m, 17/17 real
// sprites in CI). The task #448 atomic inline-box rewrite (gitlink landed via
// #496, pixel-verified on 22 boxes incl. char wrap) reserves box width in
// layout, so the former ios-chip-box-advance-approximate divergence is closed.
internal actual fun raftRichTextPlatformCaps(): RaftRichTextPlatformCaps =
    RaftRichTextPlatformCaps(
        inlineCodeChrome = true,
        raftRefChrome = true,
        reactionAtlas = true,
        declaredDivergences = emptyList()
    )

/** Skia: breaks around placeholders, letter-spaces a lone word joiner. */
internal actual fun raftPlatformInlineSpacerIsPlaceholder(): Boolean = false
