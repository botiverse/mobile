package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * The section heading of profile and settings pages (web `SectionEyebrow`, raft-design
 * ListRow > Group headings): `text-footnote` bold in `foreground-hint`, sentence case as
 * written (never upper-cased), no tracking. [count] follows in Space Mono 12 hint.
 */
@Composable
fun RaftSectionEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    count: String? = null,
) {
    val colors = RaftTheme.colors
    Row(
        modifier.semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(text, style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold), color = colors.foregroundHint)
        if (count != null) RaftText(count, style = RaftTheme.typography.monoMeta, color = colors.foregroundHint)
    }
}

/**
 * A profile page section: a full-width [RaftSeparator] above (unless [separator] is
 * false, for the first), then `px-5 py-4` holding [RaftSectionEyebrow] and, 12dp under
 * it (`mb-3`), the [content].
 *
 * [titleAction] sits 8dp after the eyebrow (web MachineDetailPanel's pencil next to
 * "Name": `flex items-center gap-2`); [action] sits at the end of the heading row (web
 * `SectionHeader action`, e.g. a small Scan button). Both are vertically centred on the
 * eyebrow.
 */
@Composable
fun RaftSection(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    separator: Boolean = true,
    titleAction: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (separator) RaftSeparator()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (titleAction == null && action == null) {
                RaftSectionEyebrow(title, Modifier.padding(bottom = 12.dp), count = count)
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftSectionEyebrow(title, count = count)
                    titleAction?.invoke()
                    if (action != null) {
                        Spacer(Modifier.weight(1f))
                        action()
                    }
                }
            }
            content()
        }
    }
}
