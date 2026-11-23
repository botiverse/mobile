package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.data.AnnouncementItem
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedAnnouncementStore
import build.raft.app.image.rememberRaftAppInForeground
import androidx.compose.foundation.layout.height
import coil3.compose.AsyncImagePainter
import build.raft.app.markdown.RaftMarkdownBody
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftDismissOnPageCover
import build.raft.app.components.RaftSlotIcon
import kotlinx.coroutines.flow.Flow

/**
 * Whether the AppShell may present its global announcement modal now.
 *
 * On iOS every pushed page is its own `RaftPageViewController`, and `RaftWindowDialog` mounts a
 * window-level layer. Mounting the modal while the shell is covered (`!pageVisible` or
 * `!pageActive`) or mid-cover (`suppressedByPageCover`) floats an untappable dialog over the
 * covering page (#proj-mobile:#326).
 */
internal fun shouldShowAppShellAnnouncementModal(
    hasActiveAnnouncement: Boolean,
    pageVisible: Boolean,
    pageActive: Boolean,
    suppressedByPageCover: Boolean = false,
): Boolean = hasActiveAnnouncement && pageVisible && pageActive && !suppressedByPageCover

/**
 * Tracks temporary suppression of the AppShell announcement modal when another page covers the
 * shell: covering hides the modal synchronously without calling `announcementStore.dismiss()`, and
 * returning to the visible, active shell re-enables presentation so the user can still read and
 * explicitly dismiss the announcement.
 */
internal data class AppShellAnnouncementCoverState(
    val suppressed: Boolean = false,
    val sawInactiveSinceCover: Boolean = false,
    val coverPageVisibleTick: Int = 0,
    val coverHostDidAppearTick: Int = 0,
) {
    fun onPageCover(pageVisibleTick: Int, hostDidAppearTick: Int): AppShellAnnouncementCoverState =
        if (suppressed) {
            this
        } else {
            AppShellAnnouncementCoverState(
                suppressed = true,
                sawInactiveSinceCover = false,
                coverPageVisibleTick = pageVisibleTick,
                coverHostDidAppearTick = hostDidAppearTick,
            )
        }

    fun onHostState(
        pageVisible: Boolean,
        pageActive: Boolean,
        pageVisibleTick: Int,
        hostDidAppearTick: Int,
    ): AppShellAnnouncementCoverState {
        if (!suppressed) return this
        if (!pageVisible || !pageActive) {
            return if (sawInactiveSinceCover) this else copy(sawInactiveSinceCover = true)
        }
        val returnedToShell =
            sawInactiveSinceCover ||
                pageVisibleTick > coverPageVisibleTick ||
                hostDidAppearTick > coverHostDidAppearTick
        return if (returnedToShell) AppShellAnnouncementCoverState() else this
    }
}

/**
 * Image load / retry state for [SharedAnnouncementModal].
 *
 * A failed image fetch (e.g. during foreground wake-up or a brief network blip) shows unclipped
 * alt text and retries once on a recovery signal while online, without looping while offline or
 * restarting an already in-flight / succeeded load.
 */
internal data class AnnouncementImageLoadState(
    val source: String,
    val failed: Boolean = false,
    val retryAttempt: Int = 0,
) {
    fun onImageState(isError: Boolean, isSuccess: Boolean = false): AnnouncementImageLoadState =
        when {
            isError -> if (failed) this else copy(failed = true)
            isSuccess -> if (!failed) this else copy(failed = false)
            else -> this
        }

    fun onRecoverySignal(isOnline: Boolean): AnnouncementImageLoadState =
        if (failed && isOnline) {
            copy(failed = false, retryAttempt = retryAttempt + 1)
        } else {
            this
        }
}

/**
 * Whether [trigger] indicates connectivity is available to retry a failed announcement image.
 * [RaftFreshnessTrigger.NetworkRestored] and [RaftFreshnessTrigger.Reconnect] are themselves proof
 * connectivity returned; [RaftFreshnessTrigger.Foreground] respects [isOnline] so returning to the
 * foreground while offline never loops retries.
 */
internal fun announcementImageOnlineForFreshnessEvent(
    trigger: RaftFreshnessTrigger,
    isOnline: Boolean,
): Boolean =
    when (trigger) {
        RaftFreshnessTrigger.NetworkRestored,
        RaftFreshnessTrigger.Reconnect -> true
        RaftFreshnessTrigger.Foreground -> isOnline
    }

/**
 * AppShell host for [SharedAnnouncementModal] (#proj-mobile:#326).
 *
 * Collects [SharedAnnouncementStore.announcement] in its own narrow recomposition scope via
 * `collectAsState()` so:
 * - covered-shell announcement updates never recompose `RaftAppShellReadyContent`;
 * - explicit dismissal immediately clears `activeAnnouncement` even across a lifecycle transition
 *   below `STARTED`;
 * - the modal mounts only when the shell is visible, active, and not covered;
 * - covering the shell unmounts the modal synchronously without calling [onDismissAnnouncement].
 */
@Composable
internal fun AppShellAnnouncementModalHost(
    announcementStore: SharedAnnouncementStore,
    pageVisible: Boolean,
    pageVisibleTick: Int,
    hostDidAppearTick: Int,
    isOnline: Boolean,
    onDismissAnnouncement: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onModalShownChanged: (Boolean) -> Unit = {},
    freshnessEvents: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
) {
    val activeAnnouncement by announcementStore.announcement.collectAsState()
    val pageActive = LocalRaftPageActive.current
    var coverState by remember { mutableStateOf(AppShellAnnouncementCoverState()) }
    val latestPageVisibleTick by rememberUpdatedState(pageVisibleTick)
    val latestHostDidAppearTick by rememberUpdatedState(hostDidAppearTick)
    val hideForPageCover: () -> Unit = {
        coverState = coverState.onPageCover(latestPageVisibleTick, latestHostDidAppearTick)
    }
    // Registered even before an announcement finishes loading so a refresh completing mid-push
    // cannot mount the modal over the incoming page.
    RaftDismissOnPageCover(hideForPageCover)
    LaunchedEffect(coverState.suppressed, pageVisible, pageActive, pageVisibleTick, hostDidAppearTick) {
        val next =
            coverState.onHostState(
                pageVisible = pageVisible,
                pageActive = pageActive,
                pageVisibleTick = pageVisibleTick,
                hostDidAppearTick = hostDidAppearTick,
            )
        if (next != coverState) coverState = next
    }
    val showModal =
        shouldShowAppShellAnnouncementModal(
            hasActiveAnnouncement = activeAnnouncement != null,
            pageVisible = pageVisible,
            pageActive = pageActive,
            suppressedByPageCover = coverState.suppressed,
        )
    SideEffect { onModalShownChanged(showModal) }
    val announcement = if (showModal) activeAnnouncement else null
    if (announcement != null) {
        SharedAnnouncementModal(
            announcement = announcement,
            onDismiss = { onDismissAnnouncement(announcement.id) },
            onPageCover = hideForPageCover,
            onOpenUrl = onOpenUrl,
            isOnline = isOnline,
            freshnessEvents = freshnessEvents,
        )
    }
}

// task #226: global announcement modal, aligned with web. Shows the latest
// active (server-dedup'd, per-user) announcement. Multi-page markdown with
// prev/next paging; dismiss persists server-side via POST /announcements/:id/dismiss.
@Composable
internal fun SharedAnnouncementModal(
    announcement: AnnouncementItem,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    // Visual fixtures open on a given page; production always starts at 0.
    initialPageIndex: Int = 0,
    onPageCover: (() -> Unit)? = null,
    isOnline: Boolean = true,
    freshnessEvents: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
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
        onPageCover = onPageCover,
        maxWidth = 576.dp
    ) {
        // Web AnnouncementModal (slock #12661): an announcement without its own label reads
        // "What's new".
        Text(
            text = announcementLabelOrDefault(announcement.label, stringResource(Res.string.announcement_whats_new)),
            style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = RaftColor.TextMutedGray
        )
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
                        var imageLoadState by remember(source) { mutableStateOf(AnnouncementImageLoadState(source = source)) }
                        val latestIsOnline by rememberUpdatedState(isOnline)
                        val appInForeground = rememberRaftAppInForeground()
                        var wasOnline by remember(source) { mutableStateOf(isOnline) }
                        var wasInForeground by remember(source) { mutableStateOf(appInForeground) }
                        LaunchedEffect(source, isOnline) {
                            if (isOnline && !wasOnline) {
                                imageLoadState = imageLoadState.onRecoverySignal(isOnline = true)
                            }
                            wasOnline = isOnline
                        }
                        LaunchedEffect(source, appInForeground) {
                            if (appInForeground && !wasInForeground) {
                                imageLoadState = imageLoadState.onRecoverySignal(isOnline = latestIsOnline)
                            }
                            wasInForeground = appInForeground
                        }
                        LaunchedEffect(source, freshnessEvents) {
                            freshnessEvents.collect { event ->
                                imageLoadState =
                                    imageLoadState.onRecoverySignal(
                                        isOnline = announcementImageOnlineForFreshnessEvent(event.trigger, latestIsOnline)
                                    )
                            }
                        }
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (imageLoadState.failed) Modifier.heightIn(min = 32.dp) else Modifier.height(200.dp))
                        ) {
                            if (imageLoadState.failed) {
                                Text(image.alt, style = RaftType.Caption, color = RaftColor.TextMutedGray)
                            } else {
                                key(source, imageLoadState.retryAttempt) {
                                    RaftCachedImage(
                                        source = source,
                                        contentDescription = image.alt,
                                        modifier = Modifier.fillMaxWidth().height(200.dp),
                                        onImageStateChange = { imageState ->
                                            imageLoadState =
                                                imageLoadState.onImageState(
                                                    isError = imageState is AsyncImagePainter.State.Error,
                                                    isSuccess = imageState is AsyncImagePainter.State.Success,
                                                )
                                        },
                                        contentScale = ContentScale.Fit,
                                        // Web AnnouncementModal (slock #12056): an animated GIF / WebP plays
                                        // while the dialog is open. The asset URL names no type, so the
                                        // decoder sniffs the bytes; a still keeps the static decode.
                                        animated = true,
                                    )
                                }
                            }
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
                    RaftButton(
                        text = stringResource(Res.string.common_back),
                        onClick = { pageIndex -= 1 },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Lg,
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.ChevronLeft) }
                    )
                }
                val isLastPage = pageIndex >= pages.lastIndex
                // Web #13736: one button on the last page, the action or else Got it. With an
                // action, closing the dialog already dismisses it, so no Got it beside it.
                RaftButton(
                    text = announcement.cta?.takeIf { isLastPage }?.label
                        ?: stringResource(if (isLastPage) Res.string.announcement_action_got_it else Res.string.pagination_next),
                    onClick = {
                        if (!isLastPage) pageIndex += 1
                        else {
                            // Dismiss before opening the CTA href (matches web AnnouncementModal)
                            // so explicit CTA activation persists dismissal even if an in-app
                            // destination immediately covers this page.
                            onDismiss()
                            announcement.cta?.let { onOpenUrl(it.href) }
                        }
                    },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    leadingIcon = RaftLucideIconName.ChevronRight.takeIf { !isLastPage }?.let { icon -> { RaftSlotIcon(icon) } }
                )
            }
        }
    }
}

/** Web `current.label ?? whatsNew`: the announcement's own label, else the default one. */
internal fun announcementLabelOrDefault(label: String?, default: String): String = label ?: default
