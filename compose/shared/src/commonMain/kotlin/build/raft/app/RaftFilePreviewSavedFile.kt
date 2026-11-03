package build.raft.app

import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus

/**
 * The computer's file manager for an attachment the user saved (Compose 版): the save panel put it
 * somewhere on disk, so the file page offers to show it there or open it, as a browser's download
 * does. Null on Android and iOS, where a saved file goes to the system's Downloads / Files and the
 * page keeps its own Open / Share.
 */
internal interface RaftSavedFileHost {
    /** macOS: the reveal action is "Show in Finder"; elsewhere "Show in folder". */
    val revealsInFinder: Boolean

    /** The saved file is still where it was saved ([uri] as the host reported it). */
    fun exists(uri: String): Boolean

    /** The name of the folder holding the file ("Downloads"), for "Saved to Downloads". */
    fun folderName(uri: String): String?

    /** Shows the file in the file manager (selected where the platform can). False: not done. */
    suspend fun reveal(uri: String): Boolean

    /** Opens the file with its default app. False: not done. */
    suspend fun open(uri: String): Boolean
}

internal expect fun raftPlatformSavedFileHost(): RaftSavedFileHost?

/** The saved copy the file page offers: where it is, and the folder it is in. */
internal data class RaftFilePreviewSavedFile(val uri: String, val folderName: String?)

/**
 * The file page's saved copy of its attachment: the download centre's finished row for it (the
 * centre keeps it for the session), while the file is still where it was saved and no new download
 * runs. Null: the page offers Download (no host, never saved, moved or deleted since).
 */
internal fun raftFilePreviewSavedFile(
    entry: RaftDownloadEntry?,
    downloading: Boolean,
    host: RaftSavedFileHost?,
): RaftFilePreviewSavedFile? {
    if (host == null || downloading || entry?.status != RaftDownloadStatus.Completed) return null
    val uri = entry.localUri?.takeIf(String::isNotBlank) ?: return null
    if (!host.exists(uri)) return null
    return RaftFilePreviewSavedFile(uri = uri, folderName = host.folderName(uri)?.takeIf(String::isNotBlank))
}

/** The words for a saved file's actions and toast; [savedToFolder] has [RaftSavedToFolderPlaceholder] for the folder. */
internal data class RaftFilePreviewSavedFileTerms(
    val showInFinder: String,
    val showInFolder: String,
    val savedToFolder: String,
)

internal const val RaftSavedToFolderPlaceholder = "__RAFT_FOLDER__"

/** "Show in Finder" on macOS, "Show in folder" elsewhere. */
internal fun raftFilePreviewRevealActionLabel(revealsInFinder: Boolean, terms: RaftFilePreviewSavedFileTerms): String =
    if (revealsInFinder) terms.showInFinder else terms.showInFolder

/** "Saved to Downloads", with the folder the file went to; null when the folder is not known. */
internal fun raftFilePreviewSavedToFolderMessage(folderName: String?, terms: RaftFilePreviewSavedFileTerms): String? =
    folderName?.takeIf(String::isNotBlank)?.let { terms.savedToFolder.replace(RaftSavedToFolderPlaceholder, it) }

/** The file page's actions for its saved copy (Compose 版), and what it says about it. */
internal data class RaftFilePreviewSavedFileActions(
    val revealLabel: String,
    val openLabel: String,
    /** "Saved to Downloads"; null when the folder is not known (the page's usual words then). */
    val message: String?,
    val onReveal: () -> Unit,
    val onOpen: () -> Unit,
)
