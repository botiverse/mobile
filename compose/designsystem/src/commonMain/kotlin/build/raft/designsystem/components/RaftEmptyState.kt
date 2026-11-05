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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/** raft-ui EmptyState metrics. */
object RaftEmptyStateDefaults {
    /** `[&_svg]:size-9`: the icon slot's box. */
    val IconSize: Dp = 36.dp

    /** `max-w-sm`: the content group's measure. */
    val ContentMaxWidth: Dp = 384.dp

    /** `max-w-[300px]`: the description's measure. */
    val DescriptionMaxWidth: Dp = 300.dp
}

/**
 * raft-ui EmptyState (`EmptyState` > `EmptyStateContent` with `EmptyStateIcon`,
 * `EmptyStateTitle`, `EmptyStateDescription`, then `EmptyStateActions`): a quiet "nothing here
 * yet" surface, centred in the room it is given.
 *
 * - Root: `px-6 py-12`, the content centred both ways (`justify-center`), so a [modifier] with
 *   `fillMaxSize` puts it in the middle of a page.
 * - Content: at most 384dp wide (`max-w-sm`), 8dp between its parts.
 * - [icon]: decorative, in a 36dp box in `foreground-hint` ([LocalRaftContentColor]); the
 *   title says what is empty.
 * - [title]: `text-headline` bold in `foreground-muted`, centred, sentence case as written;
 *   read as a heading. One line, cut with an ellipsis.
 * - [description]: `text-callout` in `foreground-hint`, centred, at most 300dp wide, three lines.
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
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
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
                style = typography.headline,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                RaftText(
                    description,
                    Modifier.widthIn(max = RaftEmptyStateDefaults.DescriptionMaxWidth),
                    style = typography.callout,
                    color = colors.foregroundHint,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions != null) {
            Box(Modifier.padding(top = 20.dp)) { actions() }
        }
    }
}
