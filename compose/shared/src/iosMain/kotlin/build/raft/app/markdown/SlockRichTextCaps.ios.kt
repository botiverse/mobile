package build.raft.app.markdown

// Inline code now uses shared ordinary background spans plus edge-only boxes;
// its semantic metadata deliberately does not activate Kuikly's legacy native
// yellow/framed atom path. Slock references still use native chrome from task
// #439. The reaction sprite atlas decodes since task #421/#409
// (MR-bundle resolution in KuiklyRenderComponentExpandHandler.m, 17/17 real
// sprites in CI). Divergences below are the known, tracked gaps — remove each
// when its fix lands.
internal actual fun slockRichTextPlatformCaps(): SlockRichTextPlatformCaps =
    SlockRichTextPlatformCaps(
        inlineCodeChrome = true,
        slockRefChrome = true,
        reactionAtlas = true,
        declaredDivergences =
            listOf(
                SlockRichTextDeclaredDivergence(
                    id = "ios-chip-box-advance-approximate",
                    description =
                        "Slock reference fill/border paints past the glyphs without reserving " +
                            "the box width in layout, so the border-to-neighbor gap " +
                            "approximates React's inline-block advance (atomic box " +
                            "rewrite tracked as task #448)."
                )
            )
    )
