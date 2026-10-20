package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.data.AnnouncementItem
import build.raft.app.data.RaftSharedStoreRegistry
import androidx.compose.foundation.layout.height
import coil3.compose.AsyncImagePainter
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.resources.MR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// task #226: global announcement modal, aligned with web. Shows the latest
// active (server-dedup'd, per-user) announcement. Multi-page markdown with
// prev/next paging; dismiss persists server-side via POST /announcements/:id/dismiss.
@Composable
internal fun SharedAnnouncementModal(
    announcement: AnnouncementItem,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    // Visual fixtures open on a given page; production always starts at 0.
    initialPageIndex: Int = 0
) {
    // Server contract: `pages[]` carries the multi-page body; fall back to the
    // flattened first-page `body` when a payload predates the pages field.
    val pages =
        remember(announcement) {
            announcement.pages.takeIf { it.isNotEmpty() }
                ?: listOf(build.raft.app.data.AnnouncementPageItem(title = null, body = announcement.body))
        }
    var pageIndex by remember(announcement) { mutableStateOf(initialPageIndex.coerceIn(0, pages.lastIndex)) }
    val page = pages[pageIndex.coerceIn(0, pages.lastIndex)]
    val hasMultiplePages = pages.size > 1

    RaftUnifiedDialog(
        // Web AnnouncementModal: the header always shows the announcement title;
        // each page's own title sits at the top of the body.
        title = announcement.title,
        onDismiss = onDismiss,
        maxWidth = 576.dp
    ) {
        announcement.label?.let { label ->
            Text(
                text = label,
                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = RaftColor.TextMutedGray
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            page.title?.takeIf { it.isNotBlank() }?.let { pageTitle ->
                item {
                    Text(
                        text = pageTitle.raftDisplayUppercase(),
                        style = RaftType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        color = RaftColor.TextBlack
                    )
                }
            }
            item {
                announcement.image?.let { image ->
                    val apiBase = RaftSharedStoreRegistry.currentOrNull()
                        ?.apiEnvironmentStore?.current()?.apiBaseUrl?.trimEnd('/')
                    val source = apiBase?.let { "$it/announcements/images/${image.assetId}" }
                    if (source != null) {
                        var failed by remember(source) { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().height(if (failed) 32.dp else 200.dp)) {
                            if (failed) Text(image.alt, style = RaftType.Caption, color = RaftColor.TextMutedGray)
                            RaftCachedImage(
                                source = source,
                                contentDescription = image.alt,
                                modifier = Modifier.fillMaxWidth().height(if (failed) 0.dp else 200.dp),
                                onImageStateChange = { failed = it is AsyncImagePainter.State.Error },
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
            item {
                RaftMarkdownBody(
                    body = page.body,
                    onOpenUrl = onOpenUrl
                )
            }
        }
        // Web footer: page indicator on the left; Back (after the first page) and
        // Next / OK on the right. Closing the dialog is the dismiss path.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasMultiplePages) "${pageIndex + 1} / ${pages.size}" else "",
                style = RaftType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                color = RaftColor.TextMutedGray
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(RaftDialogFooterGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasMultiplePages && pageIndex > 0) {
                    RaftBrutalActionButton(
                        label = stringResource(MR.strings.common_back),
                        onClick = { pageIndex -= 1 },
                        leadingIcon = RaftLucideIconName.ChevronLeft,
                        backgroundColor = RaftColor.SurfaceWhite
                    )
                }
                val isLastPage = pageIndex >= pages.lastIndex
                if (isLastPage && announcement.cta != null) {
                    RaftBrutalActionButton(
                        label = stringResource(MR.strings.announcement_action_got_it),
                        onClick = onDismiss,
                        backgroundColor = RaftColor.SurfaceWhite,
                    )
                }
                RaftBrutalActionButton(
                    label = announcement.cta?.takeIf { isLastPage }?.label
                        ?: stringResource(if (isLastPage) MR.strings.announcement_action_got_it else MR.strings.pagination_next),
                    onClick = {
                        if (!isLastPage) pageIndex += 1
                        else {
                            announcement.cta?.let { onOpenUrl(it.href) }
                            onDismiss()
                        }
                    },
                    leadingIcon = if (isLastPage) null else RaftLucideIconName.ChevronRight,
                    backgroundColor = RaftColor.BrandYellow
                )
            }
        }
    }
}
