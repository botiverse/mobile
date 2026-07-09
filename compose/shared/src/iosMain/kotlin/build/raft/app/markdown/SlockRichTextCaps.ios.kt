package build.raft.app.markdown

// Evidence: core-render-ios draws both chip kinds natively as of task #439
// (KRSlockChromeAttributeName parse in KRRichTextView.m + chip fill/border in
// KRLayoutManager, XiShi pixel-verified 5-item checklist on staging2
// b5e0767c/a290016a). The reaction sprite atlas decodes since task #421/#409
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
                        "Chip fill/border paint past the glyphs without reserving " +
                            "the box width in layout, so the border-to-neighbor gap " +
                            "approximates React's inline-block advance (atomic box " +
                            "rewrite tracked as task #448)."
                ),
                SlockRichTextDeclaredDivergence(
                    id = "ios-inline-code-char-wrap-bridge",
                    description =
                        "Long inline code wraps via the shared zero-width-space " +
                            "bridge (slockInlineCodeCharWrapBridgeThreshold), not " +
                            "native char-level wrap (task #401 A-bridge)."
                )
            )
    )
