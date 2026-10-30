package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.MouseWheelZoom
import net.engawapg.lib.zoomable.ScrollGesturePropagation
import net.engawapg.lib.zoomable.ZoomState
import net.engawapg.lib.zoomable.zoomable

/**
 * The image lightbox of the Compose desktop app and wide windows (artin 10-29): a click on an
 * image in a conversation opens it over the app, Telegram Desktop style, instead of pushing the
 * full-page file preview. Phones keep the full-page viewer ([raftImageLightboxEnabled]).
 *
 * - A dark backdrop over the whole window; the image centred and fitted.
 * - Mouse wheel zooms (no modifier key), drag pans while zoomed, double click toggles 2.5x
 *   (`net.engawapg.lib:zoomable`).
 * - Left / Right step through the conversation's images, Escape (and Back) closes; so does a click
 *   anywhere outside the image.
 * - The top bar shows who sent it and when, "3 of 12", and the actions the conversation already
 *   has for an attachment: Download and Open with.
 */
internal data class RaftImageLightboxItem(
    val attachment: RaftThreadAttachmentItem,
    val senderName: String = "",
    val timeLabel: String = ""
)

internal data class RaftImageLightboxRequest(
    val items: List<RaftImageLightboxItem>,
    val index: Int
)

/** Desktop (a fine pointer) always; any other window as wide as the dual-pane layout. */
internal fun raftImageLightboxEnabled(finePointer: Boolean, windowWidthDp: Float): Boolean =
    finePointer || windowWidthDp >= DualPaneMinimumWidthDp

/**
 * Every image of the loaded conversation, oldest first, opened at [attachmentId]: the lightbox
 * steps through the conversation like Telegram, not only through one message's images.
 * Retracted messages are skipped. Null when [attachmentId] is not one of them.
 */
internal fun raftImageLightboxRequestFor(
    messages: List<RaftThreadMessageItem>,
    attachmentId: String,
    isImage: (RaftThreadAttachmentItem) -> Boolean
): RaftImageLightboxRequest? {
    val items =
        messages
            .distinctBy { it.id }
            .filterNot { it.isRetracted }
            .flatMap { message ->
                message.attachments
                    .filter { it.id.isNotBlank() && isImage(it) }
                    .map { RaftImageLightboxItem(it, senderName = message.shownAuthorName(), timeLabel = message.timestampLabel) }
            }
            .distinctBy { it.attachment.id }
    val index = items.indexOfFirst { it.attachment.id == attachmentId }
    return if (index >= 0) RaftImageLightboxRequest(items, index) else null
}

internal enum class RaftImageLightboxKeyAction { Previous, Next, Close }

internal fun raftImageLightboxKeyAction(key: Key): RaftImageLightboxKeyAction? =
    when (key) {
        Key.DirectionLeft -> RaftImageLightboxKeyAction.Previous
        Key.DirectionRight -> RaftImageLightboxKeyAction.Next
        Key.Escape -> RaftImageLightboxKeyAction.Close
        else -> null
    }

/** The page [delta] steps away from [index], held inside the gallery (no wrap-around, as Telegram). */
internal fun raftImageLightboxStep(index: Int, count: Int, delta: Int): Int =
    if (count <= 0) 0 else (index + delta).coerceIn(0, count - 1)

/**
 * Where the image is drawn in its page, in the page's px: [content] fitted and centred in
 * [layout], then the zoom layer's [scale] about the centre and its [offset]. An unknown content
 * size counts as the whole page (nothing is "outside" an image whose shape is not known yet).
 */
internal fun raftImageLightboxImageRect(layout: Size, content: Size, scale: Float, offset: Offset): Rect {
    if (layout.width <= 0f || layout.height <= 0f) return Rect.Zero
    val fit =
        if (content.width <= 0f || content.height <= 0f) {
            layout
        } else {
            val ratio = minOf(layout.width / content.width, layout.height / content.height)
            Size(content.width * ratio, content.height * ratio)
        }
    val center = Offset(layout.width / 2f, layout.height / 2f)
    val width = fit.width * scale
    val height = fit.height * scale
    val left = center.x - width / 2f + offset.x
    val top = center.y - height / 2f + offset.y
    return Rect(left, top, left + width, top + height)
}

internal const val RaftImageLightboxTag = "raft_image_lightbox"
/** One page per image: `raft_image_lightbox_page_<attachment id>` (the pager composes the neighbours too). */
internal fun raftImageLightboxPageTag(attachmentId: String): String = "raft_image_lightbox_page_$attachmentId"
internal const val RaftImageLightboxCounterTag = "raft_image_lightbox_counter"

// The photo viewer's backdrop is near-black in every theme (Telegram, Photos).
private val RaftImageLightboxBackdrop = Color(0xF0000000) // raft-color: photo viewer backdrop, dark in every theme
private val RaftImageLightboxBarHeight = 56.dp
private val RaftImageLightboxSideGutter = 64.dp
private const val RaftImageLightboxMaxScale = 5f
private const val RaftImageLightboxDoubleClickScale = 2.5f

/** The lightbox in its own full-window layer ([Modal]); Back and Escape close it there too. */
@Composable
internal fun RaftImageLightbox(
    request: RaftImageLightboxRequest,
    resolveFullUrl: suspend (attachmentId: String) -> String?,
    onDismiss: () -> Unit,
    onDownload: (RaftThreadAttachmentItem) -> Unit,
    onOpenWith: ((RaftThreadAttachmentItem) -> Unit)?
) {
    Modal(onWillDismiss = { onDismiss() }) {
        RaftImageLightboxContent(
            request = request,
            resolveFullUrl = resolveFullUrl,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onOpenWith = onOpenWith
        )
    }
}

/**
 * The lightbox itself, without its window (tests compose it directly). [pageImage] draws one
 * image and reports its pixel size; the default loads the thumbnail, then the full-size URL.
 */
@Composable
internal fun RaftImageLightboxContent(
    request: RaftImageLightboxRequest,
    resolveFullUrl: suspend (attachmentId: String) -> String?,
    onDismiss: () -> Unit,
    onDownload: (RaftThreadAttachmentItem) -> Unit,
    onOpenWith: ((RaftThreadAttachmentItem) -> Unit)?,
    modifier: Modifier = Modifier,
    pageImage: @Composable (item: RaftImageLightboxItem, fullUrl: String?, onContentSize: (Size) -> Unit) -> Unit =
        { item, fullUrl, onContentSize -> RaftImageLightboxRemoteImage(item, fullUrl, onContentSize) }
) {
    val items = request.items
    if (items.isEmpty()) return
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val pagerState = rememberPagerState(initialPage = request.index.coerceIn(0, items.lastIndex)) { items.size }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // Full-size URLs, resolved for the shown page and its neighbours (signed, short-lived: never
    // persisted, only kept while the lightbox is open).
    val fullUrls = remember(items) { mutableStateMapOf<String, String>() }
    val currentResolve by rememberUpdatedState(resolveFullUrl)
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            listOf(page, page + 1, page - 1)
                .filter { it in items.indices }
                .map { items[it].attachment.id }
                .filterNot { fullUrls.containsKey(it) }
                .forEach { id ->
                    launch { currentResolve(id)?.trim()?.takeIf { it.isNotBlank() }?.let { fullUrls[id] = it } }
                }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    fun step(delta: Int) {
        val target = raftImageLightboxStep(pagerState.currentPage, items.size, delta)
        if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
    }
    val current = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]
    RaftDesignSystemTheme(forceDark = true) {
        Box(
            modifier =
                modifier
                    .testTag(RaftImageLightboxTag)
                    .fillMaxSize()
                    .background(RaftImageLightboxBackdrop)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (raftImageLightboxKeyAction(event.key)) {
                            RaftImageLightboxKeyAction.Previous -> step(-1)
                            RaftImageLightboxKeyAction.Next -> step(1)
                            RaftImageLightboxKeyAction.Close -> currentOnDismiss()
                            null -> return@onPreviewKeyEvent false
                        }
                        true
                    }
                    .focusable()
                    // A click on the backdrop (around the pages, under the bar) closes.
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            top = RaftImageLightboxBarHeight,
                            bottom = RaftImageLightboxBarHeight,
                            start = RaftImageLightboxSideGutter,
                            end = RaftImageLightboxSideGutter
                        ),
                beyondViewportPageCount = 1,
                key = { items[it].attachment.id }
            ) { page ->
                RaftImageLightboxPage(
                    item = items[page],
                    fullUrl = fullUrls[items[page].attachment.id],
                    selected = pagerState.settledPage == page,
                    onTapOutsideImage = { currentOnDismiss() },
                    pageImage = pageImage
                )
            }
            RaftImageLightboxTopBar(
                item = current,
                position = pagerState.currentPage + 1,
                count = items.size,
                onDownload = { onDownload(current.attachment) },
                onOpenWith = onOpenWith?.let { open -> { open(current.attachment) } },
                onClose = { currentOnDismiss() },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            if (pagerState.currentPage > 0) {
                RaftIconButton(
                    onClick = { step(-1) },
                    tooltip = stringResource(Res.string.image_lightbox_previous),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp)
                ) { RaftSlotIcon(RaftLucideIconName.ChevronLeft) }
            }
            if (pagerState.currentPage < items.lastIndex) {
                RaftIconButton(
                    onClick = { step(1) },
                    tooltip = stringResource(Res.string.image_lightbox_next),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
                ) { RaftSlotIcon(RaftLucideIconName.ChevronRight) }
            }
        }
    }
}

@Composable
private fun RaftImageLightboxPage(
    item: RaftImageLightboxItem,
    fullUrl: String?,
    selected: Boolean,
    onTapOutsideImage: () -> Unit,
    pageImage: @Composable (item: RaftImageLightboxItem, fullUrl: String?, onContentSize: (Size) -> Unit) -> Unit
) {
    val zoomState = remember(item.attachment.id) { ZoomState(maxScale = RaftImageLightboxMaxScale) }
    var layoutSize by remember { mutableStateOf(Size.Zero) }
    var contentSize by remember(item.attachment.id) {
        mutableStateOf(
            Size(
                (item.attachment.width ?: 0).toFloat(),
                (item.attachment.height ?: 0).toFloat()
            )
        )
    }
    LaunchedEffect(zoomState, contentSize) { zoomState.setContentSize(contentSize) }
    // A page that is no longer shown goes back to fitted.
    LaunchedEffect(zoomState, selected) { if (!selected) zoomState.reset() }
    val currentOnTapOutside by rememberUpdatedState(onTapOutsideImage)
    Box(
        modifier =
            Modifier
                .testTag(raftImageLightboxPageTag(item.attachment.id))
                .fillMaxSize()
                .onSizeChanged { layoutSize = it.toSize() }
                .zoomable(
                    zoomState = zoomState,
                    // Unzoomed, a horizontal drag pages (touch screens); zoomed, it pans.
                    scrollGesturePropagation = ScrollGesturePropagation.NotZoomed,
                    // Desktop: the wheel zooms without a modifier key, about the pointer.
                    mouseWheelZoom = MouseWheelZoom.Enabled,
                    onTap = { position ->
                        val imageRect =
                            raftImageLightboxImageRect(
                                layout = layoutSize,
                                content = contentSize,
                                scale = zoomState.scale,
                                offset = Offset(zoomState.offsetX, zoomState.offsetY)
                            )
                        if (!imageRect.contains(position)) currentOnTapOutside()
                    },
                    onDoubleTap = { position -> zoomState.toggleRaftLightboxScale(position) }
                ),
        contentAlignment = Alignment.Center
    ) {
        pageImage(item, fullUrl) { size -> if (size.width > 0f && size.height > 0f) contentSize = size }
    }
}

private suspend fun ZoomState.toggleRaftLightboxScale(position: Offset) {
    val target = if (scale > 1f) 1f else RaftImageLightboxDoubleClickScale
    changeScale(target, position)
}

/** The thumbnail at once, the full-size image over it once its URL resolves and it decodes. */
@Composable
private fun RaftImageLightboxRemoteImage(
    item: RaftImageLightboxItem,
    fullUrl: String?,
    onContentSize: (Size) -> Unit
) {
    val thumbnail = item.attachment.thumbnailUrl?.trim()?.takeIf { it.isNotBlank() }
    var fullLoaded by remember(fullUrl) { mutableStateOf(false) }
    var thumbnailFailed by remember(thumbnail) { mutableStateOf(false) }
    var fullFailed by remember(fullUrl) { mutableStateOf(false) }
    fun report(state: AsyncImagePainter.State) {
        val image = (state as? AsyncImagePainter.State.Success)?.result?.image ?: return
        onContentSize(Size(image.width.toFloat(), image.height.toFloat()))
    }
    if (thumbnail != null && !fullLoaded) {
        RaftCachedImage(
            source = thumbnail,
            contentDescription = item.attachment.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onImageStateChange = { state ->
                report(state)
                if (state is AsyncImagePainter.State.Error) thumbnailFailed = true
            }
        )
    }
    if (fullUrl != null) {
        RaftCachedImage(
            source = fullUrl,
            contentDescription = item.attachment.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            animated = build.raft.app.image.raftImageMayBeAnimated(item.attachment.mimeType, item.attachment.name),
            onImageStateChange = { state ->
                report(state)
                if (state is AsyncImagePainter.State.Success) fullLoaded = true
                if (state is AsyncImagePainter.State.Error) fullFailed = true
            }
        )
    }
    val failed = fullFailed && (thumbnail == null || thumbnailFailed)
    if (failed) {
        RaftText(
            text = stringResource(Res.string.thread_image_preview_unavailable),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundHint
        )
    } else if (!fullLoaded && (thumbnail == null || thumbnailFailed)) {
        RaftSpinner()
    }
}

@Composable
private fun RaftImageLightboxTopBar(
    item: RaftImageLightboxItem,
    position: Int,
    count: Int,
    onDownload: () -> Unit,
    onOpenWith: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth().height(RaftImageLightboxBarHeight).padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.36f),
            verticalArrangement = Arrangement.Center
        ) {
            if (item.senderName.isNotBlank()) {
                RaftText(
                    text = item.senderName,
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.timeLabel.isNotBlank()) {
                RaftText(
                    text = item.timeLabel,
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (count > 1) {
            RaftText(
                text = stringResource(Res.string.image_lightbox_counter, position, count),
                style = RaftTheme.typography.callout,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).testTag(RaftImageLightboxCounterTag)
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftIconButton(
                onClick = onDownload,
                tooltip = stringResource(Res.string.file_preview_action_download),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Lg
            ) { RaftSlotIcon(RaftLucideIconName.Download) }
            if (onOpenWith != null) {
                RaftIconButton(
                    onClick = onOpenWith,
                    tooltip = stringResource(Res.string.file_preview_action_open_with),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg
                ) { RaftSlotIcon(RaftLucideIconName.ExternalLink) }
            }
            RaftIconButton(
                onClick = onClose,
                tooltip = stringResource(Res.string.common_close),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Lg
            ) { RaftSlotIcon(RaftLucideIconName.X) }
        }
    }
}
