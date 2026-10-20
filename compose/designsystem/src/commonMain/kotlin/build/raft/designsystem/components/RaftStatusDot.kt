package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/**
 * The five presence lights (raft-ui `lib/presence.ts`), each a fixed meaning. [word] is
 * what to write next to the light, or its accessible name when no word is shown.
 */
enum class RaftPresence(val word: String) {
    Online("Online"),

    /** An agent at work: a steady yellow light, never a pulse. */
    Busy("Working"),
    Error("Error"),
    Offline("Offline"),

    /** An agent that isn't on one of this server's computers. */
    External("External agent"),
    ;

    /** `--presence-*` (mode-invariant). */
    val color: Color
        get() =
            when (this) {
                Online -> RaftUiPalette.presenceOnline
                Busy -> RaftUiPalette.presenceBusy
                Error -> RaftUiPalette.presenceError
                Offline -> RaftUiPalette.presenceOffline
                External -> RaftUiPalette.presenceExternal
            }
}

/** raft-ui Status `variant`: a colour role beside a word. */
enum class RaftStatusVariant(val color: Color) {
    /** `--presence-offline`. */
    Default(RaftUiPalette.presenceOffline),
    Primary(RaftUiPalette.primary400),
    Information(RaftUiPalette.info),
    Muted(RaftUiPalette.secondary400),

    /** The attention dot: something waits, on an icon with no room for a number. */
    Accent(RaftUiPalette.accent400),
    Success(RaftUiPalette.success),
    Warning(RaftUiPalette.warning),
    Danger(RaftUiPalette.danger),
}

/** raft-ui Status `size`: edge, corner radius and ink border width of the pixel. */
enum class RaftStatusSize(val edge: Dp, val radius: Dp, val border: Dp) {
    Xs(6.dp, 1.dp, RaftStroke.Hairline),
    Sm(8.dp, 2.dp, RaftStroke.Control),
    Md(9.dp, 2.dp, RaftStroke.Control),
    Lg(11.dp, 2.dp, RaftStroke.Control),
}

/**
 * raft-ui 0.8.0 Status, the status pixel (alias StatusDot): a square of a status colour
 * with a `sticker-ink` edge, 2dp corners (1dp at `xs`). [presence] draws one of the
 * presence lights and takes the place of [variant]'s colour. It never animates.
 *
 * Pair the pixel with its word. With no word next to it (an attention dot on a tab),
 * give it a [contentDescription] that says what it means; otherwise it is hidden from
 * accessibility, as raft-ui asks.
 */
@Composable
fun RaftStatusDot(
    modifier: Modifier = Modifier,
    variant: RaftStatusVariant = RaftStatusVariant.Default,
    size: RaftStatusSize = RaftStatusSize.Md,
    presence: RaftPresence? = null,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(size.radius)
    Box(
        modifier
            .size(size.edge)
            .background(presence?.color ?: variant.color, shape)
            .border(size.border, RaftUiPalette.stickerInk, shape)
            .clearAndSetSemantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                    role = Role.Image
                }
            },
    )
}
