package build.raft.designsystem.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * raft-ui `SidebarGroupLabel`: the plain heading over a group of [RaftSidebarItem]s that
 * doesn't collapse (the Settings page's Personal / Workspace / Resources groups; a group
 * that collapses is a [RaftSidebarSectionHeader]). `px-2 pt-4 pb-1.5`, `text-footnote`
 * bold in `foreground-hint`, source case, one line. Screen readers hear it as a heading.
 */
@Composable
fun RaftSidebarGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    RaftText(
        text,
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 6.dp).semantics { heading() },
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
