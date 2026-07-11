package build.raft.app.markdown

// task #414's native chip renderer is now pinned into the OHOS host, but these
// capability bits remain false until the HDC device gate verifies final pixels,
// wrapping, copy, click, and long-press behavior. Keeping the bridge enabled
// makes the pre-gate package safe to test without claiming visual parity early.
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
                        "The task #414 native inline-code and slock-ref chip renderer " +
                            "is packaged, but capability promotion is intentionally " +
                            "waiting for the HDC device visual and gesture gate."
                ),
                SlockRichTextDeclaredDivergence(
                    id = "ohos-inline-code-char-wrap-bridge",
                    description =
                        "The shared zero-width-space bridge remains enabled as a " +
                            "pre-gate fallback; HDC must verify native character-level " +
                            "wrapping before the bridge is removed."
                )
            )
    )
