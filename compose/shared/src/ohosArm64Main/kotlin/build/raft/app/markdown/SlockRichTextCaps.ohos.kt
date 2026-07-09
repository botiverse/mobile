package build.raft.app.markdown

// Evidence: core-render-ohos has no slockInlineCode / slockMarkdownTagChrome
// span handling (task #401 audit found zero slock-prop handling; the OHOS
// native chip is task #414/#402-parallel, not landed), so inline code and
// slock refs render through the plain text span styles. The reaction sprite
// atlas decodes on OHOS (the task #421 emoji fallback was iOS-only).
internal actual fun slockRichTextPlatformCaps(): SlockRichTextPlatformCaps =
    SlockRichTextPlatformCaps(
        inlineCodeChrome = false,
        slockRefChrome = false,
        reactionAtlas = true,
        declaredDivergences =
            listOf(
                SlockRichTextDeclaredDivergence(
                    id = "ohos-no-native-chip",
                    description =
                        "core-render-ohos does not draw inline-code or slock-ref " +
                            "chip chrome yet; chips render as plain span styles " +
                            "(native chip tracked as the OHOS half of the #401/#402 " +
                            "line)."
                ),
                SlockRichTextDeclaredDivergence(
                    id = "ohos-inline-code-char-wrap-bridge",
                    description =
                        "Long inline code wraps via the shared zero-width-space " +
                            "bridge (slockInlineCodeCharWrapBridgeThreshold), not " +
                            "native char-level wrap (task #401 A-bridge)."
                )
            )
    )
