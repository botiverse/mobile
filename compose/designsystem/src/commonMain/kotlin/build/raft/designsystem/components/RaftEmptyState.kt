package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme

/** raft-ui EmptyState metrics. */
object RaftEmptyStateDefaults {
    /** `[&_svg]:size-9`: the icon slot's box. */
    val IconSize: Dp = 36.dp

    /** `max-w-sm`: the content group's measure. */
    val ContentMaxWidth: Dp = 384.dp

    /** `max-w-[300px]`: the description's measure. */
    val DescriptionMaxWidth: Dp = 300.dp

    /** `px-6`: the root's sides with a finger. */
    val PaddingX: Dp = 24.dp

    /** `md:pointer-fine:px-(--page-inset)`: the root's sides under a mouse or trackpad, the page inset. */
    val PaddingXFine: Dp = 16.dp
}

/**
 * raft-ui EmptyState (`EmptyState` > `EmptyStateContent` with `EmptyStateIcon`,
 * `EmptyStateTitle`, `EmptyStateDescription`, then `EmptyStateActions`): a quiet "nothing here
 * yet" surface, centred in the room it is given.
 *
 * - Root: `px-6 py-12` (16dp sides, the page inset, under a mouse or trackpad), the content
 *   centred both ways (`justify-center`), so a [modifier] with `fillMaxSize` puts it in the
 *   middle of a page.
 * - Content: at most 384dp wide (`max-w-sm`), 8dp between its parts (`gap-2`; inside
 *   `EmptyStateContent` raft-ui zeroes the icon's `mb-4` and the title's `mb-2`).
 * - [icon]: decorative, in a 36dp box in `foreground-hint` ([LocalRaftContentColor]); the
 *   title says what is empty.
 * - [title]: `text-headline` bold in `foreground-muted`, centred, sentence case as written;
 *   read as a heading. It wraps, with balanced lines (`text-balance`), and is never cut.
 * - [description]: `text-callout` in `foreground-hint`, centred, at most 300dp wide, wrapping
 *   in full (`text-pretty`).
 * - [actions]: 20dp under the content (`mt-5`): one button, as a rule.
 *
 * It draws no surface: put it on the page's layer.
 */
@Composable
fun RaftEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal =
                    if (RaftTheme.pointerInput == RaftPointerInput.Fine) RaftEmptyStateDefaults.PaddingXFine else RaftEmptyStateDefaults.PaddingX,
                vertical = 48.dp,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = RaftEmptyStateDefaults.ContentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(RaftEmptyStateDefaults.IconSize).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundHint) { icon() }
                }
            }
            RaftText(
                title,
                Modifier.semantics { heading() },
                // `text-balance`: wraps, lines of even length.
                style = typography.headline.copy(lineBreak = LineBreak.Heading),
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
            if (!description.isNullOrBlank()) {
                RaftText(
                    description,
                    Modifier.widthIn(max = RaftEmptyStateDefaults.DescriptionMaxWidth),
                    // `text-pretty`: wraps in full, no lone last word.
                    style = typography.callout.copy(lineBreak = LineBreak.Paragraph),
                    color = colors.foregroundHint,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (actions != null) {
            Box(Modifier.padding(top = 20.dp)) { actions() }
        }
    }
}
