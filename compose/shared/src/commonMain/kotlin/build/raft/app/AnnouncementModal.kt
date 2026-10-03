package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.data.AnnouncementItem
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.ui.unit.dp

// task #226: global announcement modal, aligned with web. Shows the latest
// active (server-dedup'd, per-user) announcement. Multi-page markdown with
// prev/next paging; dismiss persists server-side via POST /announcements/:id/dismiss.
@Composable
internal fun SharedAnnouncementModal(
    announcement: AnnouncementItem,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {}
) {
    // Server contract: `pages[]` carries the multi-page body; fall back to the
    // flattened first-page `body` when a payload predates the pages field.
    val pages =
        remember(announcement.id) {
            announcement.pages.takeIf { it.isNotEmpty() }
                ?: listOf(build.raft.app.data.AnnouncementPageItem(title = null, body = announcement.body))
        }
    var pageIndex by remember(announcement.id) { mutableStateOf(0) }
    val page = pages[pageIndex.coerceIn(0, pages.lastIndex)]
    val hasMultiplePages = pages.size > 1

    SlockUnifiedDialog(
        title = page.title?.takeIf { it.isNotBlank() } ?: announcement.title,
        onDismiss = onDismiss,
        maxWidth = 456.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SlockMarkdownBody(
                    body = page.body,
                    onOpenUrl = onOpenUrl
                )
            }
        }
        if (hasMultiplePages) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.pagination_prev),
                    onClick = { if (pageIndex > 0) pageIndex -= 1 },
                    leadingIcon = SlockLucideIconName.ChevronLeft,
                    backgroundColor = SlockColor.SurfaceWhite,
                    enabled = pageIndex > 0
                )
                Text(
                    "${pageIndex + 1} / ${pages.size}",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.pagination_next),
                    onClick = { if (pageIndex < pages.lastIndex) pageIndex += 1 },
                    leadingIcon = SlockLucideIconName.ChevronRight,
                    backgroundColor = SlockColor.SurfaceWhite,
                    enabled = pageIndex < pages.lastIndex
                )
            }
        }
        SlockBrutalActionButton(
            label = stringResource(
                if (hasMultiplePages && pageIndex < pages.lastIndex) MR.strings.announcement_action_skip
                else MR.strings.announcement_action_got_it
            ),
            onClick = onDismiss,
            backgroundColor = SlockColor.BrandYellow,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
