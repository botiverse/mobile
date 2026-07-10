package build.raft.app.markdown

// task #414's native chip renderer is now pinned into the OHOS host, but these
// capability bits remain false until the HDC device gate verifies final pixels,
// wrapping, copy, click, and long-press behavior. Keeping the bridge enabled
// makes the pre-gate package safe to test without claiming visual parity early.
internal actual fun slockRichTextPlatformCaps(): SlockRichTextPlatformCaps =
    SlockRichTextPlatformCaps(
        inlineCodeChrome = false,
        slockRefChrome = false,
        // Corrected from `true` (which was inferred from "#421 was iOS-only"):
        // Codex-Kuikly's #鸿蒙适配 path audit found the MR asset lands in the HAP
        // as rawfile while Kuikly's `assets://` loader reads the resfile root, so
        // the atlas is NOT addressable on today's OHOS package — it renders the
        // emoji fallback. Flip back to true (and drop the divergence below) only
        // after the MR→Kuikly asset-root bridge lands and a device shows
        // thread_reaction_atlas_ready with real sprites.
        reactionAtlas = false,
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
                ),
                SlockRichTextDeclaredDivergence(
                    id = "ohos-reaction-atlas-rawfile-mismatch",
                    description =
                        "MR assets are packaged into the HAP rawfile root but Kuikly " +
                            "assets:// resolves against the resfile root, so the " +
                            "reaction sprite atlas falls back to emoji glyphs until " +
                            "the MR-to-Kuikly asset-root bridge lands."
                )
            )
    )
