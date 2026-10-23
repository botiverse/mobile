package build.raft.app

import androidx.compose.runtime.compositionLocalOf
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Where a tap on a message attachment card goes (artin, task #75). A tap never downloads: a file
 * the app can show opens its preview, any other file (apk, zip, pdf, ...) opens its file page,
 * where the download runs with its progress and Open / Share follow.
 */
internal sealed interface RaftThreadAttachmentOpenRoute {
    /** The in-app preview; [previewKind] is the file page's requested kind. */
    data class Preview(val previewKind: String) : RaftThreadAttachmentOpenRoute

    /** The file page of a file without an in-app preview: name, size, Download with progress. */
    data object FileDetail : RaftThreadAttachmentOpenRoute
}

internal fun RaftThreadAttachmentItem.threadAttachmentOpenRoute(): RaftThreadAttachmentOpenRoute =
    when {
        isImageAttachment() -> RaftThreadAttachmentOpenRoute.Preview("image")
        isMarkdownAttachment() -> RaftThreadAttachmentOpenRoute.Preview("markdown")
        isTextLikeAttachment() || isHtmlAttachment() -> RaftThreadAttachmentOpenRoute.Preview("text")
        isPreviewableVideoAttachment() -> RaftThreadAttachmentOpenRoute.Preview("video")
        isPreviewableAudioAttachment() -> RaftThreadAttachmentOpenRoute.Preview("audio")
        // The page recognises a spreadsheet by its name and renders the sheet table.
        raftFilePreviewIsXlsxAttachment(fileName = name, mimeType = mimeType) ||
            raftFilePreviewIsCsvAttachment(fileName = name, mimeType = mimeType) ->
            RaftThreadAttachmentOpenRoute.Preview(RaftThreadAttachmentFileDetailKind)
        else -> RaftThreadAttachmentOpenRoute.FileDetail
    }

/** The file page's requested kind for a file without an in-app preview. */
internal const val RaftThreadAttachmentFileDetailKind = "file"

internal val RaftThreadAttachmentOpenRoute.requestedPreviewKind: String
    get() =
        when (this) {
            is RaftThreadAttachmentOpenRoute.Preview -> previewKind
            RaftThreadAttachmentOpenRoute.FileDetail -> RaftThreadAttachmentFileDetailKind
        }

/**
 * The download centre as the conversation sees it: the centre row of one attachment for the
 * current attachment principal, so a card can show a download started on the file page.
 * Read-only; the card never starts, pauses or cancels anything.
 */
internal class ThreadAttachmentDownloadHost(
    val entry: (attachmentId: String) -> Flow<RaftDownloadEntry?>
)

internal val LocalThreadAttachmentDownloadHost =
    compositionLocalOf<ThreadAttachmentDownloadHost?> { null }

/**
 * The card's meta line while the centre still runs this attachment's download
 * (`Downloading 42%`, `Paused 42%`, `Saving...`); null once it is not running.
 */
internal fun raftThreadAttachmentCardDownloadLabel(
    entry: RaftDownloadEntry?,
    terms: RaftFilePreviewDownloadStatusTerms
): String? {
    if (entry == null || !entry.status.isActive) return null
    val verb =
        when {
            entry.status == RaftDownloadStatus.Paused -> terms.paused
            entry.saving -> terms.saving
            else -> terms.downloading
        }
    val percent = entry.percent
    return if (percent != null) "$verb $percent%" else "$verb..."
}

/** Share is offered next to Open / Install once the file is on the device. */
internal fun raftFilePreviewShowsShareAction(
    canOpenWith: Boolean,
    downloadState: RaftFilePreviewDownloadUiState?
): Boolean = canOpenWith && downloadState == RaftFilePreviewDownloadUiState.Downloaded
