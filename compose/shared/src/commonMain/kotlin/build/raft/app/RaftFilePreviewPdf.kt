package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/** The preview kind of a PDF attachment (web `attachmentPreview.ts`: `.pdf` or `application/pdf`). */
internal const val RaftFilePreviewPdfKind = "pdf"

/** Web parity: a file is a PDF by its `.pdf` name or its `application/pdf` type (parameters ignored). */
internal fun raftFilePreviewIsPdfAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    if (normalizedMime == "application/pdf") return true
    return fileName.substringBefore('?').substringBefore('#').trim().endsWith(".pdf", ignoreCase = true)
}

/** A PDF starts with `%PDF-` within its first KiB (ISO 32000-1 §7.5.2, with leading junk allowed). */
internal fun raftPdfHasHeader(head: ByteArray): Boolean {
    val marker = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
    val limit = minOf(head.size, 1024) - marker.size
    for (start in 0..limit) {
        var match = true
        for (offset in marker.indices) {
            if (head[start + offset] != marker[offset]) {
                match = false
                break
            }
        }
        if (match) return true
    }
    return false
}

/** Why the platform viewer could not show a downloaded PDF. */
internal enum class RaftPdfDocumentError {
    /** The document needs a password (or uses a security scheme the viewer cannot open). */
    PasswordProtected,

    /** Not a readable PDF: damaged, truncated, empty or not a PDF at all. */
    Corrupt
}

internal enum class RaftFilePreviewPdfFailure { Download, PasswordProtected, Corrupt }

/**
 * The PDF preview: the file is downloaded into the attachment cache first (with progress), then
 * the platform viewer renders the local copy.
 */
internal sealed interface RaftFilePreviewPdfState {
    /** Nothing requested yet. */
    data object Idle : RaftFilePreviewPdfState

    /** Downloading into the cache; [progress] is null until the host reports the first bytes. */
    data class Downloading(val progress: BridgeAttachmentDownloadProgress? = null) : RaftFilePreviewPdfState

    /** The cached copy at [path] is handed to the viewer. */
    data class Ready(val path: String) : RaftFilePreviewPdfState

    data class Failed(val reason: RaftFilePreviewPdfFailure, val message: String? = null) : RaftFilePreviewPdfState
}

internal fun raftFilePreviewPdfDownloadStarted(): RaftFilePreviewPdfState = RaftFilePreviewPdfState.Downloading()

/** A progress sample only moves a running download; a late sample after the end is dropped. */
internal fun RaftFilePreviewPdfState.onPdfDownloadProgress(
    progress: BridgeAttachmentDownloadProgress
): RaftFilePreviewPdfState =
    if (this is RaftFilePreviewPdfState.Downloading) copy(progress = progress) else this

/**
 * The host's answer to the cache download: `ready` with a path shows the viewer, `cancelled`
 * goes back to idle (the page is leaving), anything else is a download failure. Only a running
 * download takes an answer.
 */
internal fun RaftFilePreviewPdfState.onPdfPrepared(
    status: String?,
    path: String?,
    message: String? = null
): RaftFilePreviewPdfState {
    if (this !is RaftFilePreviewPdfState.Downloading) return this
    val cleanPath = path?.trim().orEmpty()
    return when {
        status == "ready" && cleanPath.isNotEmpty() -> RaftFilePreviewPdfState.Ready(cleanPath)
        status == "cancelled" -> RaftFilePreviewPdfState.Idle
        else -> RaftFilePreviewPdfState.Failed(RaftFilePreviewPdfFailure.Download, message?.takeIf { it.isNotBlank() })
    }
}

/** The viewer could not open the downloaded file: a password or a damaged file. */
internal fun RaftFilePreviewPdfState.onPdfRenderError(error: RaftPdfDocumentError): RaftFilePreviewPdfState =
    if (this is RaftFilePreviewPdfState.Ready) {
        RaftFilePreviewPdfState.Failed(
            when (error) {
                RaftPdfDocumentError.PasswordProtected -> RaftFilePreviewPdfFailure.PasswordProtected
                RaftPdfDocumentError.Corrupt -> RaftFilePreviewPdfFailure.Corrupt
            }
        )
    } else {
        this
    }

/** Only a download failure can be retried in place; a password or a damaged file cannot. */
internal val RaftFilePreviewPdfState.canRetry: Boolean
    get() = this is RaftFilePreviewPdfState.Failed && reason == RaftFilePreviewPdfFailure.Download

/**
 * Share in the PDF preview menu: once the file is on the device, either saved to Downloads or
 * as the cached copy the viewer shows.
 */
internal fun raftFilePreviewPdfShowsShareAction(
    canOpenWith: Boolean,
    downloadState: RaftFilePreviewDownloadUiState?,
    pdfState: RaftFilePreviewPdfState
): Boolean =
    canOpenWith &&
        downloadState != RaftFilePreviewDownloadUiState.Downloading &&
        (downloadState == RaftFilePreviewDownloadUiState.Downloaded || pdfState is RaftFilePreviewPdfState.Ready)

/** "3 / 12" for a zero-based [currentPage], or null before the page count is known. */
internal fun raftFilePreviewPdfPageIndicatorArgs(currentPage: Int, pageCount: Int): Pair<String, String>? =
    if (pageCount <= 0) null else ((currentPage.coerceIn(0, pageCount - 1) + 1).toString() to pageCount.toString())

/**
 * The PDF viewer of the file page: the platform renderer over the cached copy with a page
 * indicator, the download progress while the copy is fetched, or a clear message with
 * "Open in another app" when the file is password protected, damaged or failed to download.
 */
@Composable
internal fun RaftFilePreviewPdfPanel(
    name: String,
    meta: String,
    state: RaftFilePreviewPdfState,
    onRenderError: (RaftPdfDocumentError) -> Unit,
    onRetry: (() -> Unit)?,
    onOpenInOtherApp: (() -> Unit)?,
    openInOtherAppEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        if (state is RaftFilePreviewPdfState.Ready) {
            var page by remember(state.path) { mutableStateOf(0 to 0) }
            // The preview page is drawn edge to edge: keep the indicator and the last page clear
            // of the navigation bar (Android) / home indicator (iOS).
            val bottomInset =
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            RaftFilePreviewPdfViewerFrame(
                currentPage = page.first,
                pageCount = page.second,
                bottomInset = bottomInset,
                modifier = modifier
            ) { viewerModifier ->
                RaftPdfDocumentView(
                    path = state.path,
                    onPageChanged = { current, count ->
                        val next = current to count
                        if (page != next) page = next
                    },
                    onError = onRenderError,
                    modifier = viewerModifier,
                    bottomInset = bottomInset
                )
            }
            return@RaftDesignSystemTheme
        }
        RaftFilePreviewPdfStatusCard(
            name = name,
            meta = meta,
            state = state,
            onRetry = onRetry,
            onOpenInOtherApp = onOpenInOtherApp,
            openInOtherAppEnabled = openInOtherAppEnabled,
            modifier = modifier
        )
    }
}

/**
 * The shown document: [viewer] fills the frame (drawn under the system bar; it keeps its own last
 * page clear of [bottomInset]) and the page indicator floats above the bar.
 * Call inside [RaftDesignSystemTheme].
 */
@Composable
internal fun RaftFilePreviewPdfViewerFrame(
    currentPage: Int,
    pageCount: Int,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    viewer: @Composable (Modifier) -> Unit
) {
    Box(modifier.background(RaftTheme.colors.layerSidebar)) {
        viewer(Modifier.fillMaxSize())
        raftFilePreviewPdfPageIndicatorArgs(currentPage, pageCount)?.let { (current, count) ->
            RaftBadge(
                text = stringResource(Res.string.file_preview_pdf_page_indicator, current, count),
                appearance = RaftBadgeAppearance.Solid,
                variant = RaftBadgeVariant.Default,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp + bottomInset)
            )
        }
    }
}

@Composable
private fun RaftFilePreviewPdfStatusCard(
    name: String,
    meta: String,
    state: RaftFilePreviewPdfState,
    onRetry: (() -> Unit)?,
    onOpenInOtherApp: (() -> Unit)?,
    openInOtherAppEnabled: Boolean,
    modifier: Modifier
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val failure = (state as? RaftFilePreviewPdfState.Failed)?.reason
    Box(modifier.background(colors.layerCanvas), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RaftIconTile(size = RaftIconTileSize.Lg) {
                RaftDownloadIcon(
                    if (failure == RaftFilePreviewPdfFailure.PasswordProtected) RaftLucideIconName.FileLock else RaftLucideIconName.FileText,
                    size = RaftIconTileSize.Lg.icon
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    text = name.ifBlank { stringResource(Res.string.file_preview_fallback_file) },
                    modifier = Modifier.raftCopyable(name),
                    style = typography.headline,
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    RaftText(
                        text = meta,
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when (state) {
                is RaftFilePreviewPdfState.Failed ->
                    RaftBanner(
                        title =
                            stringResource(
                                when (state.reason) {
                                    RaftFilePreviewPdfFailure.PasswordProtected -> Res.string.file_preview_pdf_password_title
                                    RaftFilePreviewPdfFailure.Corrupt -> Res.string.file_preview_pdf_corrupt_title
                                    RaftFilePreviewPdfFailure.Download -> Res.string.file_preview_download_failed
                                }
                            ),
                        description =
                            when (state.reason) {
                                RaftFilePreviewPdfFailure.PasswordProtected -> stringResource(Res.string.file_preview_pdf_password_detail)
                                RaftFilePreviewPdfFailure.Corrupt -> stringResource(Res.string.file_preview_pdf_corrupt_detail)
                                RaftFilePreviewPdfFailure.Download ->
                                    state.message ?: stringResource(Res.string.file_preview_error_download_attachment)
                            },
                        status = RaftBannerStatus.Warning,
                        modifier = Modifier.fillMaxWidth()
                    )
                else -> {
                    val progress = (state as? RaftFilePreviewPdfState.Downloading)?.progress
                    RaftCard(Modifier.fillMaxWidth()) {
                        RaftFilePreviewDownloadProgressBlock(
                            view = raftFilePreviewDownloadView(status = "downloading", progress = progress, errorMessage = null),
                            onPause = null,
                            onResume = null,
                            onCancel = null
                        )
                    }
                }
            }
            if (state.canRetry && onRetry != null) {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    leadingIcon = { RaftDownloadIcon(RaftLucideIconName.RotateCcw) }
                )
            }
            if (failure != null && onOpenInOtherApp != null) {
                RaftButton(
                    text = stringResource(Res.string.file_preview_action_open_in_other_app),
                    onClick = onOpenInOtherApp,
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (state.canRetry) RaftButtonVariant.Default else RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = openInOtherAppEnabled,
                    // raft-ui 0.26 (#758): another app leaves Raft: ↗ in the trailing icon's place.
                    trailingIcon = { RaftDownloadIcon(RaftLucideIconName.ArrowUpRight) }
                )
            }
        }
    }
}
