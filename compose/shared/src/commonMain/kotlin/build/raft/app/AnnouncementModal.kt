package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.data.AnnouncementItem
import build.raft.app.data.SlockSharedStoreRegistry
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.coil3.AsyncImagePainter
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

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

    SlockUnifiedDialog(
        // Web AnnouncementModal: the header always shows the announcement title;
        // each page's own title sits at the top of the body.
        title = announcement.title,
        onDismiss = onDismiss,
        maxWidth = 576.dp
    ) {
        announcement.label?.let { label ->
            Text(
                text = label,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            page.title?.takeIf { it.isNotBlank() }?.let { pageTitle ->
                item {
                    Text(
                        text = pageTitle.slockDisplayUppercase(),
                        style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        color = SlockColor.TextBlack
                    )
                }
            }
            item {
                announcement.image?.let { image ->
                    val apiBase = SlockSharedStoreRegistry.currentOrNull()
                        ?.apiEnvironmentStore?.current()?.apiBaseUrl?.trimEnd('/')
                    val source = apiBase?.let { "$it/announcements/images/${image.assetId}" }
                    if (source != null) {
                        var failed by remember(source) { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().height(if (failed) 32.dp else 200.dp)) {
                            if (failed) Text(image.alt, style = SlockType.Caption, color = SlockColor.TextMutedGray)
                            SlockCachedImage(
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
                SlockMarkdownBody(
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
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockDialogFooterGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasMultiplePages && pageIndex > 0) {
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_back),
                        onClick = { pageIndex -= 1 },
                        leadingIcon = SlockLucideIconName.ChevronLeft,
                        backgroundColor = SlockColor.SurfaceWhite
                    )
                }
                val isLastPage = pageIndex >= pages.lastIndex
                if (isLastPage && announcement.cta != null) {
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.announcement_action_got_it),
                        onClick = onDismiss,
                        backgroundColor = SlockColor.SurfaceWhite,
                    )
                }
                SlockBrutalActionButton(
                    label = announcement.cta?.takeIf { isLastPage }?.label
                        ?: stringResource(if (isLastPage) MR.strings.announcement_action_got_it else MR.strings.pagination_next),
                    onClick = {
                        if (!isLastPage) pageIndex += 1
                        else {
                            announcement.cta?.let { onOpenUrl(it.href) }
                            onDismiss()
                        }
                    },
                    leadingIcon = if (isLastPage) null else SlockLucideIconName.ChevronRight,
                    backgroundColor = SlockColor.BrandYellow
                )
            }
        }
    }
}
