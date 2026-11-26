package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.designsystem.components.RaftMessageAttachmentAction
import build.raft.designsystem.components.RaftMessageAttachmentCard
import build.raft.app.components.rememberRaftTextareaEditorModifier
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextarea
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.components.raftCardSurface
import build.raft.app.data.map
import build.raft.app.format.RaftFormat
import build.raft.app.data.ThreadAttachmentCommentItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImagePainter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.image.raftImageMayBeAnimated
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftMessageImageDimensions
import build.raft.designsystem.components.RaftMessageImageFit
import build.raft.designsystem.components.RaftMessageImageGallery
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.theme.LocalRaftColors
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal fun RaftThreadAttachmentItem.isImageAttachment(): Boolean =
    raftFilePreviewIsImageAttachment(fileName = name, mimeType = mimeType)

private fun RaftThreadAttachmentItem.normalizedMimeType(): String =
    mimeType.substringBefore(';').trim().lowercase()

internal fun RaftThreadAttachmentItem.isMarkdownAttachment(): Boolean =
    raftFilePreviewIsMarkdownAttachment(fileName = name, mimeType = mimeType)

internal fun RaftThreadAttachmentItem.isTextLikeAttachment(): Boolean =
    !isMarkdownAttachment() && raftFilePreviewIsTextLikeAttachment(fileName = name, mimeType = mimeType)

internal fun RaftThreadAttachmentItem.isPreviewableAudioAttachment(): Boolean =
    raftFilePreviewIsAudioAttachment(fileName = name, mimeType = mimeType)

internal fun RaftThreadAttachmentItem.isPreviewableVideoAttachment(): Boolean =
    raftFilePreviewIsPreviewableVideoAttachment(fileName = name, mimeType = mimeType)

internal fun RaftThreadAttachmentItem.isHtmlAttachment(): Boolean =
    normalizedMimeType() == "text/html" || name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)

internal fun RaftThreadAttachmentItem.genericAttachmentMetaLine(): String =
    displaySizeLabel()

/**
 * The kind on a file card's meta line, before the size (web `primaryMetaLabel`): "HTML preview"
 * for a page, else the MIME type, else [fileLabel].
 */
internal fun RaftThreadAttachmentItem.threadAttachmentCardMetaLabel(htmlPreviewLabel: String, fileLabel: String): String =
    when {
        isHtmlAttachment() -> htmlPreviewLabel
        else -> mimeType.trim().ifBlank { fileLabel }
    }

/** Web: the comment count shows on the card, except for images and PDFs (no comment entry there). */
internal fun RaftThreadAttachmentItem.threadAttachmentCardCommentCount(): Int =
    commentCount.takeIf { it > 0 && !isImageAttachment() && normalizedMimeType() != "application/pdf" } ?: 0

private fun RaftThreadAttachmentItem.threadAttachmentRendersAsCard(): Boolean =
    !isPreviewableVideoAttachment() && !isPreviewableAudioAttachment()

/**
 * Web MessageItem order for the non-image attachments: each inline video, then each inline
 * audio, then every file card in one wrapping group. Returns the groups in that order.
 */
internal fun List<RaftThreadAttachmentItem>.threadAttachmentCardRuns(): List<List<RaftThreadAttachmentItem>> {
    val videos = filter { it.isPreviewableVideoAttachment() }
    val audio = filter { !it.isPreviewableVideoAttachment() && it.isPreviewableAudioAttachment() }
    val cards = filter { it.threadAttachmentRendersAsCard() }
    return videos.map(::listOf) + audio.map(::listOf) + listOfNotNull(cards.takeIf { it.isNotEmpty() })
}

private fun ThreadAttachmentOpenUiState?.threadAttachmentOpenFailed(): Boolean =
    this?.status in setOf("missing", "no_app", "storage_failed", "download_failed", "failed")

// raft-ui MessageAttachmentGroup `max-w-[360px]`.
private val ThreadAttachmentGroupMaxWidth = 360.dp

/** UI-bound formatting: raw bytes win; sizeLabel exists only for compatibility snapshots. */
internal fun RaftThreadAttachmentItem.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let { RaftFormat.bytes(it) } ?: sizeLabel

@Composable
internal fun ThreadMessageAttachments(
    attachments: List<RaftThreadAttachmentItem>,
    alignEnd: Boolean,
    /** The message is still sending (web `isOptimistic`): images do not open. */
    pending: Boolean = false,
    /** Images dim under a spinner; only once the send is slow ([rememberDelayedSendingVisible]). */
    pendingIndicatorVisible: Boolean = pending,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onOpenAttachment: (String) -> Unit,
    onDownloadAttachment: (String) -> Unit = {}
) {
    if (attachments.isEmpty()) return

    val imageAttachments = remember(attachments) { attachments.filter { it.isImageAttachment() } }
    val fileAttachments = remember(attachments) { attachments.filterNot { it.isImageAttachment() } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        if (imageAttachments.isNotEmpty()) {
            val imageDimensions =
                remember(imageAttachments) {
                    imageAttachments.map { RaftMessageImageDimensions(width = it.width, height = it.height) }
                }
            // raft-ui MessageImageGallery (web MessageItem): rounded hairline frames on fill-muted,
            // a lone image in its own reserved box, multi-image rows in the phone grid.
            CompositionLocalProvider(
                LocalRaftColors provides
                    build.raft.designsystem.tokens.RaftUiThemeColors.of(RaftAppThemeState.raftTheme, RaftAppThemeState.isDark)
            ) {
                RaftMessageImageGallery(
                    images = imageDimensions,
                    pending = { pendingIndicatorVisible },
                    onClick = if (pending) null else { index -> onOpenAttachment(imageAttachments[index].id) }
                ) { layout ->
                    ThreadImageAttachmentContent(
                        attachment = imageAttachments[layout.index],
                        fit = layout.fit
                    )
                }
            }
        }
        // Web order: gallery, videos, audio, then one MessageAttachmentGroup in which the file
        // cards wrap side by side (`flex flex-wrap gap-2`, at most 360 wide).
        val fileAttachmentRuns =
            remember(fileAttachments) { fileAttachments.threadAttachmentCardRuns() }
        fileAttachmentRuns.forEach { run ->
            val first = run.first()
            if (!first.threadAttachmentRendersAsCard()) {
                ThreadAttachmentChip(
                    attachment = first,
                    openState = attachmentOpenStates[first.id],
                    onOpen = { onOpenAttachment(first.id) },
                    onDownload = { onDownloadAttachment(first.id) },
                    pending = pending
                )
            } else {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.widthIn(max = ThreadAttachmentGroupMaxWidth),
                    horizontalArrangement =
                        Arrangement.spacedBy(RaftAppSpacing.Sm, if (alignEnd) Alignment.End else Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
                ) {
                    run.forEach { attachment ->
                        ThreadAttachmentChip(
                            attachment = attachment,
                            openState = attachmentOpenStates[attachment.id],
                            onOpen = { onOpenAttachment(attachment.id) },
                            onDownload = { onDownloadAttachment(attachment.id) },
                            pending = pending
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThreadImageAttachmentContent(
    attachment: RaftThreadAttachmentItem,
    fit: RaftMessageImageFit
) {
    val fallbackHost = LocalThreadInlineImageFallbackHost.current
    val fallbackFailed = fallbackHost != null && attachment.id in fallbackHost.state.failedAttachmentIds
    val fallbackUrl =
        fallbackHost?.state?.resolvedUrls?.get(attachment.id)
            ?: remember(attachment.id, fallbackHost?.peekCachedUrl, fallbackFailed) {
                if (fallbackFailed) null else fallbackHost?.peekCachedUrl?.invoke(attachment.id)?.trim()?.takeIf { it.isNotEmpty() }
            }
    // An image this device just uploaded renders from its local copy (by attachment id), so the
    // canonical message neither re-downloads it nor asks for the signed inline URL. A GIF / WebP
    // moves to its server thumbnail once the message has one, to play it (see below).
    val sentImages = build.raft.app.image.RaftSentAttachmentLocalImages.registry
    val localModelFlow = remember(attachment.id) { sentImages.localModelFlow(attachment.id) }
    val localModel by localModelFlow.collectAsState(initial = sentImages.localModel(attachment.id))
    val mayAnimate = raftImageMayBeAnimated(attachment.mimeType, attachment.name)
    val sourcePlan =
        threadInlineImageSourcePlan(
            thumbnailUrl = attachment.thumbnailUrl,
            fallbackUrl = fallbackUrl,
            localModel = localModel,
            mayAnimate = mayAnimate
        )
    val fallbackHostAvailable = fallbackHost != null
    val shouldRequestRemoteFallback = sourcePlan.requestRemoteFallback && !fallbackFailed
    LaunchedEffect(attachment.id, shouldRequestRemoteFallback, fallbackHostAvailable) {
        if (fallbackHostAvailable && shouldRequestRemoteFallback) {
            fallbackHost?.requestFallback?.invoke(attachment.id)
        }
    }
    // Every source the surface will try (it moves to the fallback after the first fails); the
    // image has failed once each has errored.
    val sourceCount = listOfNotNull(sourcePlan.source, sourcePlan.fallbackSource).distinct().size
    var failedSources by remember(sourcePlan.source, sourcePlan.fallbackSource) { mutableIntStateOf(0) }
    val failed =
        threadInlineImageShowsFailure(
            sourceCount = sourceCount,
            failedSources = failedSources,
            awaitingRemoteFallback = shouldRequestRemoteFallback && fallbackHostAvailable
        )
    // A GIF's thumbnail is an animated WebP (slock #16159): it plays as the web's <img> does, but
    // only while the tile is on screen and its page is the one shown (not a kept-alive tab or a
    // page under the top one); RaftCachedImage also stops it while the app is in the background.
    // It holds its frame while "Generate image" captures the rows. Only the server thumbnail
    // plays, bounded to 320px: a just-sent GIF's full-size local copy and the fallback (the
    // original file) show a still. Other images keep the plain static request.
    val animates = mayAnimate && sourcePlan.sourceIsThumbnail
    var onScreen by remember { mutableStateOf(false) }
    val pageActive = LocalRaftPageActive.current
    val shareCaptureActive = LocalThreadShareCaptureActive.current
    val onScreenModifier =
        if (animates) {
            // Placed with part of it inside the window. A list composes rows ahead of the scroll
            // without placing them: those do not play.
            Modifier.onVisibilityChanged(minFractionVisible = 0f) { visible -> onScreen = visible }
        } else {
            Modifier
        }
    val originalCacheKey =
        remember(attachment.id) { build.raft.app.image.raftAttachmentOriginalImageCacheKey(attachment.id) }
    val fallbackCacheKey =
        if (sourcePlan.fallbackSource != null && sourcePlan.fallbackSource == fallbackUrl) {
            originalCacheKey
        } else {
            null
        }
    // The frame (fill, border, radius, press) is RaftMessageImageFrame's; the surface only
    // draws the image, so it brings no fill or border of its own.
    RaftAspectFitImagePreviewSurface(
        source = sourcePlan.source,
        fallbackSource = sourcePlan.fallbackSource,
        contentDescription = attachment.name,
        modifier = Modifier.fillMaxSize().then(onScreenModifier),
        // The failed state is drawn below (ImageOff), not the surface's text placeholder.
        placeholderLabel = "",
        backgroundColor = Color.Transparent,
        borderWidth = 0.dp,
        contentScale = if (fit == RaftMessageImageFit.Cover) RaftImageThumbnailContentScale else ContentScale.Fit,
        animateSource = animates,
        animationActive = onScreen && pageActive && !shareCaptureActive,
        fallbackCacheKey = fallbackCacheKey,
        onImageStateChange = { state ->
            if (state is AsyncImagePainter.State.Error) {
                failedSources += 1
                if (fallbackUrl == null) {
                    fallbackHost?.requestFallback?.invoke(attachment.id)
                } else {
                    val failedData = state.result.request.data.toString().trim()
                    if (failedData == fallbackUrl.trim() || sourcePlan.source == null) {
                        fallbackHost?.onFallbackError?.invoke(attachment.id, fallbackUrl)
                    }
                }
            }
        }
    )
    if (failed) {
        val retryAction =
            fallbackHost?.retryFallback?.let { retry ->
                {
                    failedSources = 0
                    retry(attachment.id)
                }
            }
        ThreadImageAttachmentFailedPlaceholder(onRetry = retryAction)
    }
}

/**
 * Whether an inline image shows its failed placeholder: it has no source at all (and none is
 * on its way from the fallback host), or every source it tried has errored.
 */
internal fun threadInlineImageShowsFailure(sourceCount: Int, failedSources: Int, awaitingRemoteFallback: Boolean): Boolean =
    when {
        sourceCount == 0 -> !awaitingRemoteFallback
        else -> failedSources >= sourceCount
    }

/**
 * Web GalleryImageItem `gallery-image-placeholder`: an image that fails to load keeps its tile,
 * `bg-fill-muted` with a 20dp ImageOff in `foreground-hint`, never a broken-image glyph.
 */
@Composable
private fun ThreadImageAttachmentFailedPlaceholder(
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.FillMuted)
                .then(
                    if (onRetry != null) {
                        Modifier.raftClickable(onClick = onRetry, tier = RaftHoverTier.Subtle)
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            RaftLucideIconName.ImageOff,
            modifier = Modifier.size(20.dp),
            color = raftArgbHex(RaftColor.ForegroundHint)
        )
    }
}

@Composable
private fun ThreadAttachmentChip(
    attachment: RaftThreadAttachmentItem,
    openState: ThreadAttachmentOpenUiState?,
    onOpen: () -> Unit,
    onDownload: () -> Unit = {},
    pending: Boolean = false
) {
    if (attachment.isPreviewableVideoAttachment()) {
        ThreadInlineVideoAttachmentCard(
            attachment = attachment,
            onDownload = onDownload,
            onOpenPreview = onOpen,
            pending = pending
        )
        return
    }
    if (attachment.isPreviewableAudioAttachment()) {
        ThreadInlineAudioAttachmentCard(
            attachment = attachment,
            onDownload = onDownload
        )
        return
    }
    // A download started on the file page keeps running in the download centre; the card mirrors
    // it (task #75) and drops stale page-local download failures once the centre retries or finishes.
    val downloadHost = LocalThreadAttachmentDownloadHost.current
    val centerEntryFlow = remember(downloadHost, attachment.id) { downloadHost?.entry(attachment.id) }
    val centerEntry = centerEntryFlow?.collectAsState(initial = null)?.value
    val effectiveOpenState = effectiveThreadAttachmentOpenState(openState, centerEntry)
    val isOpening = effectiveOpenState.threadAttachmentIsOpening()
    val metaLabel =
        attachment.threadAttachmentCardMetaLabel(
            htmlPreviewLabel = stringResource(Res.string.file_preview_html),
            fileLabel = stringResource(Res.string.thread_attachment_meta_file)
        )
    val centerLabel =
        raftThreadAttachmentCardDownloadLabel(
            entry = centerEntry,
            terms =
                RaftFilePreviewDownloadStatusTerms(
                    downloading = stringResource(Res.string.file_preview_download_downloading),
                    paused = stringResource(Res.string.file_preview_download_paused),
                    saving = stringResource(Res.string.file_preview_download_saving)
                )
        )
    // Download progress and open failures take the size's place on the meta line, as the web's
    // "Uploading · 64%" does.
    val statusLabel = centerLabel ?: effectiveOpenState.localizedThreadAttachmentStatusLabel()
    // raft-ui MessageAttachmentCard: one affordance icon, Eye for a file with an in-app preview,
    // Download for one that opens its file page. Both only open the page (web AttachmentChip).
    val route = attachment.threadAttachmentOpenRoute()
    val (affordanceIcon, affordanceLabel) =
        if (route is RaftThreadAttachmentOpenRoute.Preview) {
            RaftLucideIconName.Eye to "${stringResource(Res.string.file_preview_action_open)} ${attachment.name}"
        } else {
            RaftLucideIconName.Download to "${stringResource(Res.string.file_preview_action_download)} ${attachment.name}"
        }
    val badge = threadFileTypeBadgeLabel(attachment.name)
    RaftMessageAttachmentCard(
        title = attachment.name,
        typeBadge = if (badge != null) ({ ThreadFileTypeBadge(label = badge, inline = true) }) else null,
        meta = statusLabel ?: metaLabel,
        metaSize = attachment.displaySizeLabel().takeIf { statusLabel == null },
        commentCount = if (statusLabel == null) attachment.threadAttachmentCardCommentCount() else 0,
        loading = isOpening || centerEntry?.status == RaftDownloadStatus.Downloading,
        failed = centerLabel == null && effectiveOpenState.threadAttachmentOpenFailed(),
        onClick = if (isOpening) ({}) else onOpen
    ) {
        RaftMessageAttachmentAction(onClick = onOpen, contentDescription = affordanceLabel) {
            RaftSlotIcon(affordanceIcon, size = 16.dp)
        }
    }
}

@Composable
internal fun ThreadAttachmentCommentsSheet(
    state: ThreadAttachmentCommentsDialogState,
    onDismiss: () -> Unit,
    onFilterChange: (String) -> Unit,
    onDraftChange: (TextFieldValue) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit
) {
    // Read before entering Modal: its content is rehosted in a native window where the
    // page host's safe-area locals are gone (same caveat as RaftUnifiedDialog).
    val bottomInset = raftBottomSafeAreaPadding()
    // Not Kuikly's ModalBottomSheet: its Dialog hard-codes dismissOnClickOutside, which on
    // iOS turns the whole window into one accessibility leaf and hides the comment editor
    // (see raftWindowDialogPlatformProperties). Modal + RaftModalDismissBarrier is the established
    // bottom-sheet pattern for content with text fields.
    Modal(onWillDismiss = { onDismiss() }) {
        Box(modifier = Modifier.fillMaxSize()) {
            RaftModalDismissBarrier(overlayAlpha = 0.60f, onDismiss = onDismiss)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + RaftAppSpacing.Sm)
                        .raftDialogContentPointerBarrier()
            ) {
                // raft-ui dialog surface: dialog radius, popover layer, seam ring, float shadow.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .raftLookOverlaySurface(RaftAppRadius.Dialog)
                            .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.thread_comments_title),
                            // Web AttachmentCommentsPanel PanelTitle: text-headline (17/22 700).
                            style = RaftType.Headline.copy(color = RaftColor.TextBlack),
                            color = RaftColor.TextBlack,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        RaftCloseButton(
                            onClick = onDismiss,
                            label = stringResource(Res.string.common_close),
                        )
                    }
                    ThreadAttachmentCommentsContent(
                        state = state,
                        onFilterChange = onFilterChange,
                        onDraftChange = onDraftChange,
                        onRetry = onRetry,
                        onSend = onSend,
                        onResolve = onResolve,
                        onQuote = onQuote
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThreadAttachmentCommentsContent(
    state: ThreadAttachmentCommentsDialogState,
    onFilterChange: (String) -> Unit,
    onDraftChange: (TextFieldValue) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit,
    compact: Boolean = false
) {
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    val page = state.page
    val comments =
        remember(state.filter, page?.comments) {
            val all = page?.comments.orEmpty()
            if (state.filter == "unresolved") {
                all.filterNot { it.isThreadAttachmentCommentResolved() }
            } else {
                all
            }
        }
    val viewerCanComment = page?.viewer?.canComment ?: state.status == "loading"
    val commentsListHeight =
        if (compact) {
            when {
                comments.isEmpty() -> 84.dp
                comments.size == 1 -> 150.dp
                else -> 180.dp
            }
        } else {
            threadAttachmentCommentsListHeight(comments.size)
        }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThreadAttachmentCommentsHeader(state.attachment)
        // All / Unresolved: two views of the same comments, one always picked (raft-ui SegmentedControl).
        RaftSegmentedControl {
            RaftSegment(
                text = stringResource(Res.string.thread_comments_filter_all),
                selected = state.filter != "unresolved",
                onClick = { onFilterChange("all") }
            )
            RaftSegment(
                text = stringResource(Res.string.thread_comments_filter_unresolved),
                selected = state.filter == "unresolved",
                onClick = { onFilterChange("unresolved") }
            )
        }
        when (state.status) {
            "loading" ->
                ThreadStateMessage(stringResource(Res.string.thread_comments_loading), null)
            "error" ->
                ThreadStateActionMessage(
                    title = stringResource(Res.string.thread_comments_load_failed),
                    detail =
                        attachmentCommentsVisibleErrorMessage(
                            errorCode = state.errorCode,
                            serverMessage = state.errorServerMessage,
                            labels = userFacingErrorLabels,
                            genericMessage = stringResource(Res.string.common_error)
                        ),
                    actionLabel = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    traceId = state.errorTraceId
                )
            else ->
                RaftModalListShell(height = commentsListHeight) {
                    if (comments.isEmpty()) {
                        item {
                            ThreadModalEmptyState(
                                title = stringResource(Res.string.thread_state_no_comments),
                                detail =
                                    if (state.filter == "unresolved") {
                                        stringResource(Res.string.thread_comments_all_resolved)
                                    } else {
                                        stringResource(Res.string.thread_comments_start_below)
                                    }
                            )
                        }
                    } else {
                        items(comments.size, key = { comments[it].id }) { index ->
                            ThreadAttachmentCommentRow(
                                comment = comments[index],
                                canComment = viewerCanComment,
                                resolving = comments[index].id in state.resolvingCommentIds,
                                onResolve = { onResolve(comments[index]) },
                                onQuote = { onQuote(comments[index]) }
                            )
                        }
                    }
                }
        }
        state.errorCode?.takeIf { state.status != "error" && it.isNotBlank() }?.let { code ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text =
                        attachmentCommentsVisibleErrorMessage(
                            errorCode = code,
                            serverMessage = state.errorServerMessage,
                            labels = userFacingErrorLabels,
                            genericMessage = stringResource(Res.string.common_error)
                        ),
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.DangerStrong),
                    color = RaftColor.DangerStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                RaftTraceIdNote(traceId = state.errorTraceId)
            }
        }
        ThreadAttachmentCommentComposer(
            state = state,
            canComment = page?.viewer?.canComment ?: true,
            reason = page?.viewer?.reason,
            onDraftChange = onDraftChange,
            onSend = onSend,
            compact = compact
        )
    }
}

@Composable
private fun ThreadAttachmentCommentsHeader(attachment: RaftThreadAttachmentItem) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .raftCardSurface(fill = RaftColor.FillMuted, radius = RaftAppRadius.Control)
            .padding(horizontal = RaftAppSpacing.Sm, vertical = RaftAppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(RaftLucideIconName.FileText, modifier = Modifier.size(15.dp), color = RaftColor.TextBlackArgb)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = attachment.name,
                style = RaftType.Button.copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = attachment.displaySizeLabel()
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadAttachmentCommentRow(
    comment: ThreadAttachmentCommentItem,
    canComment: Boolean,
    resolving: Boolean,
    onResolve: () -> Unit,
    onQuote: () -> Unit
) {
    val resolved = comment.isThreadAttachmentCommentResolved()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Sm, vertical = RaftAppSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = comment.senderName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) },
                style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            comment.createdAt.takeIf { it.isNotBlank() }?.let { createdAt ->
                Text(
                    text = createdAt,
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        comment.anchorLabel?.takeIf { it.isNotBlank() }?.let { label ->
            ThreadInlineMetaTag(stringResource(Res.string.thread_comments_anchor, label))
        }
        Text(
            text = comment.content,
            // Web AttachmentCommentsPanel: MessageItemBody text-body (16/24 400).
            style = RaftType.Body.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            ThreadAttachmentCommentTinyButton(
                label = stringResource(Res.string.thread_action_quote),
                icon = RaftLucideIconName.MessageSquarePlus,
                enabled = canComment && comment.content.isNotBlank(),
                onClick = onQuote
            )
            ThreadAttachmentCommentTinyButton(
                label =
                    when {
                        resolved -> stringResource(Res.string.thread_comments_resolved)
                        resolving -> stringResource(Res.string.thread_comments_resolving)
                        else -> stringResource(Res.string.thread_comments_resolve)
                    },
                icon = if (resolved) RaftLucideIconName.CircleCheckBig else RaftLucideIconName.Check,
                enabled = canComment && !resolved && !resolving,
                onClick = onResolve
            )
        }
    }
}

@Composable
private fun ThreadAttachmentCommentTinyButton(
    label: String,
    icon: RaftLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit
) {
    RaftButton(
        text = label,
        onClick = onClick,
        variant = RaftButtonVariant.Default,
        size = RaftButtonSize.Md,
        enabled = enabled,
        leadingIcon = { RaftSlotIcon(icon) }
    )
}

@Composable
private fun ThreadAttachmentCommentComposer(
    state: ThreadAttachmentCommentsDialogState,
    canComment: Boolean,
    reason: String?,
    onDraftChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    compact: Boolean = false
) {
    val enabled = canComment && state.status == "loaded" && !state.sending
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            state.pendingAnchorLabel?.takeIf { it.isNotBlank() }?.let { label ->
                ThreadInlineMetaTag(stringResource(Res.string.thread_comments_anchor, label))
            }
            state.pendingQuoteLabel?.takeIf { it.isNotBlank() }?.let { label ->
                ThreadInlineMetaTag(stringResource(Res.string.thread_comments_quote, label))
            }
        }
        if (!canComment) {
            Text(
                text = threadAttachmentCommentBlockedCopy(reason),
                style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
            )
        }
        RaftField(
            label = stringResource(Res.string.thread_comments_label),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) {
            // 3 (compact) / 4 lines of the 24dp field line inside the 10dp padding, then it scrolls.
            RaftTextarea(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.thread_comments_placeholder),
                minHeight = if (compact) 64.dp else 82.dp,
                maxHeight = if (compact) 92.dp else 116.dp,
                inputModifier = rememberRaftTextareaEditorModifier()
            )
        }
        RaftModalFullWidthActionButton(
            label =
                if (state.sending) {
                    stringResource(Res.string.thread_comments_sending)
                } else {
                    stringResource(Res.string.thread_comments_send)
                },
            icon = RaftLucideIconName.Send,
            // Busy keeps the button's colour under the spinner (raft-ui Button > States).
            enabled = (enabled || state.sending) && state.draft.text.isNotBlank(),
            color = RaftColor.AccentCyan,
            loading = state.sending,
            onClick = onSend
        )
    }
}

internal fun ThreadAttachmentCommentItem.isThreadAttachmentCommentResolved(): Boolean =
    reactions.any { reaction -> reaction.emoji == "✅" || reaction.emoji == "☑️" }

/**
 * Which attachments get a comment entry point, matching web (`MessageItem.handleOpenAttachmentCard`
 * and `attachmentPreviewSurfaces`): Markdown, text/code, CSV, XLSX and HTML open their comments, and
 * video opens its timestamp comments beside the player. Images (web: "image comments descoped"),
 * PDFs, audio and files without a preview have none.
 */
internal fun RaftThreadAttachmentItem.supportsThreadAttachmentComments(): Boolean =
    isPreviewableVideoAttachment() ||
        raftFilePreviewAttachmentSupportsViewerComments(fileName = name, mimeType = mimeType)

private fun RaftThreadScreenPayload.findAttachmentCommentTarget(attachmentId: String): ThreadAttachmentCommentTarget? {
    val cleanAttachmentId = attachmentId.trim()
    if (cleanAttachmentId.isBlank()) return null
    val allMessages = listOfNotNull(originMessage) + this.messages
    allMessages.forEach { message ->
        val attachment = message.attachments.firstOrNull { it.id == cleanAttachmentId }
        if (attachment != null) {
            return ThreadAttachmentCommentTarget(attachment = attachment, parentMessage = message)
        }
    }
    return null
}

private fun threadAttachmentCommentsListHeight(count: Int): Dp =
    when {
        count <= 0 -> 112.dp
        count == 1 -> 132.dp
        else -> 230.dp
    }

@Composable
internal fun threadAttachmentCommentBlockedCopy(reason: String?): String =
    when (reason?.trim()?.lowercase()) {
        "archived" -> stringResource(Res.string.thread_comments_blocked_archived)
        "not_member" -> stringResource(Res.string.thread_comments_blocked_not_member)
        "read_only" -> stringResource(Res.string.thread_comments_blocked_read_only)
        "unlinked" -> stringResource(Res.string.thread_comments_blocked_unlinked)
        else -> stringResource(Res.string.thread_comments_blocked_unavailable)
    }
