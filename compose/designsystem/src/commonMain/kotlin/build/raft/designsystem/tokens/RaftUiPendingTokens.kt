package build.raft.designsystem.tokens

import androidx.compose.ui.graphics.Color

// raft-ui tokens merged in raft-design but not yet in a raft-ui release that
// tools/design-tokens/gen_tokens.py reads (raft-ui 0.40.0). Written by hand next to the generated
// RaftUiPalette (RaftUiColorTokens.g.kt), named as the generator names them.
// TODO: once a raft-ui release carries them, re-generate the tokens and delete each one here (the
// generated member then takes over: a member wins over an extension of the same name).

/**
 * `--sticker-ink-medium` (raft-design #875): the medium tier on an ink sticker, 6% paper
 * (`oklch(1 0 0 / 0.06)`) in both light and dark, utility `overlay-sticker-ink-medium`. Hover,
 * press and open on an ink tile (the rail's server tile), where `sticker-medium` (10% ink) would
 * not show.
 */
val RaftUiPalette.stickerInkMedium: Color
    get() = Color(0x0FFFFFFF)
