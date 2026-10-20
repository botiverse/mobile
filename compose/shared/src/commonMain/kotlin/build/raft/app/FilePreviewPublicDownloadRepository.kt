package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject

/**
 * Exact identity used to inspect the one durable public attachment download.
 *
 * [fileName] and [mimeType] are presentation hints for legacy hosts only. The Android authority
 * binds product state to [owner] + [attachmentId] and never adopts a public row by display name.
 */
internal data class RaftFilePreviewPublicDownloadQuery(
    val attachmentId: String,
    val fileName: String,
    val owner: AttachmentOwnerLease,
    val mimeType: String
)

internal sealed interface RaftFilePreviewPublicDownloadState {
    /** The preview remains renderable, but no Download/Open claim is made yet. */
    data object Checking : RaftFilePreviewPublicDownloadState

    /** No complete, live public-download authority was verified. */
    data object NotDownloaded : RaftFilePreviewPublicDownloadState

    /**
     * A committed journal generation whose exact persisted URI passed a live size/path readback.
     * The URI is retained only as evidence for the current page state; native code resolves it
     * again under the identity lock before any external launch.
     */
    data class Downloaded(
        val generation: Long,
        val sizeBytes: Long,
        val persistedUri: String
    ) : RaftFilePreviewPublicDownloadState {
        init {
            require(generation > 0L)
            require(sizeBytes > 0L)
            require(
                persistedUri.startsWith("content://", ignoreCase = true) ||
                    persistedUri.startsWith("file://", ignoreCase = true)
            )
        }
    }
}

internal data class RaftFilePreviewPublicDownloadObservation(
    val state: RaftFilePreviewPublicDownloadState,
    val attemptToken: String? = null
)

internal interface RaftFilePreviewPublicDownloadRepository {
    suspend fun inspect(query: RaftFilePreviewPublicDownloadQuery): RaftFilePreviewPublicDownloadObservation
}

internal class BridgeRaftFilePreviewPublicDownloadRepository(
    private val bridge: BridgeModule
) : RaftFilePreviewPublicDownloadRepository {
    override suspend fun inspect(
        query: RaftFilePreviewPublicDownloadQuery
    ): RaftFilePreviewPublicDownloadObservation =
        raftFilePreviewPublicDownloadObservation(
            bridge.attachmentCacheStatus(
                attachmentId = query.attachmentId,
                fileName = query.fileName,
                owner = query.owner,
                mimeType = query.mimeType
            )
        )
}

/**
 * A host may claim Downloaded only with a complete exact-authority response. Storage failures,
 * legacy partial payloads, and unsupported hosts all fail closed to NotDownloaded.
 */
internal fun raftFilePreviewPublicDownloadObservation(
    response: JSONObject?
): RaftFilePreviewPublicDownloadObservation {
    if (response == null || response.optString("status") != "ok" || !response.optBoolean("cached", false)) {
        return RaftFilePreviewPublicDownloadObservation(
            RaftFilePreviewPublicDownloadState.NotDownloaded
        )
    }
    val generation = response.optLong("recordVersion", -1L)
    val sizeBytes = response.optLong("sizeBytes", 0L)
    val persistedUri = response.optString("uri").trim()
    val storageKind = response.optString("storageKind").trim()
    val supportedPersistedUri =
        persistedUri.startsWith("content://", ignoreCase = true) ||
            (
                storageKind == "app_private" &&
                    persistedUri.startsWith("file://", ignoreCase = true)
            )
    if (
        generation <= 0L ||
        sizeBytes <= 0L ||
        !supportedPersistedUri
    ) {
        return RaftFilePreviewPublicDownloadObservation(
            RaftFilePreviewPublicDownloadState.NotDownloaded
        )
    }
    return RaftFilePreviewPublicDownloadObservation(
        state =
            RaftFilePreviewPublicDownloadState.Downloaded(
                generation = generation,
                sizeBytes = sizeBytes,
                persistedUri = persistedUri
            ),
        attemptToken = response.optString("attemptToken").trim().takeIf(String::isNotBlank)
    )
}

/** A late response cannot publish state for a different attachment, shell, or principal. */
internal fun raftFilePreviewCommitPublicDownloadObservation(
    request: RaftFilePreviewPublicDownloadQuery,
    current: RaftFilePreviewPublicDownloadQuery?,
    observation: RaftFilePreviewPublicDownloadObservation
): RaftFilePreviewPublicDownloadObservation? = observation.takeIf { request == current }

/** A state observation is visible only while its exact attachment/principal query is current. */
internal fun raftFilePreviewVisiblePublicDownloadState(
    current: RaftFilePreviewPublicDownloadQuery?,
    observedFor: RaftFilePreviewPublicDownloadQuery?,
    observedState: RaftFilePreviewPublicDownloadState
): RaftFilePreviewPublicDownloadState =
    when {
        current == null -> RaftFilePreviewPublicDownloadState.NotDownloaded
        current != observedFor -> RaftFilePreviewPublicDownloadState.Checking
        else -> observedState
    }

internal enum class RaftFilePreviewDownloadUiState {
    NotDownloaded,
    Downloading,
    Downloaded
}

/** Checking intentionally has no user-visible state or action; it never impersonates Download. */
internal fun raftFilePreviewDownloadUiState(
    publicDownloadState: RaftFilePreviewPublicDownloadState,
    downloadStatus: String
): RaftFilePreviewDownloadUiState? =
    when {
        raftFilePreviewShowsDownloadProgress(downloadStatus) ->
            RaftFilePreviewDownloadUiState.Downloading
        publicDownloadState == RaftFilePreviewPublicDownloadState.Checking -> null
        publicDownloadState is RaftFilePreviewPublicDownloadState.Downloaded ->
            RaftFilePreviewDownloadUiState.Downloaded
        else -> RaftFilePreviewDownloadUiState.NotDownloaded
    }

internal data class RaftFilePreviewExternalOpenPolicy(
    val requirePublicDownload: Boolean,
    val trackProgress: Boolean
)

internal fun raftFilePreviewExternalOpenPolicy(
    publicDownloadState: RaftFilePreviewPublicDownloadState
): RaftFilePreviewExternalOpenPolicy? =
    when (publicDownloadState) {
        RaftFilePreviewPublicDownloadState.Checking -> null
        RaftFilePreviewPublicDownloadState.NotDownloaded ->
            RaftFilePreviewExternalOpenPolicy(
                requirePublicDownload = false,
                trackProgress = true
            )
        is RaftFilePreviewPublicDownloadState.Downloaded ->
            RaftFilePreviewExternalOpenPolicy(
                requirePublicDownload = true,
                trackProgress = false
            )
    }

/** Click-time missing/unverifiable authority immediately removes the stale Open affordance. */
internal fun raftFilePreviewPublicDownloadStateAfterOpen(
    current: RaftFilePreviewPublicDownloadState,
    result: JSONObject?
): RaftFilePreviewPublicDownloadState =
    when (result?.optString("status").orEmpty()) {
        "missing", "storage_failed", "download_failed" -> RaftFilePreviewPublicDownloadState.NotDownloaded
        "opened" ->
            if (result?.optBoolean("cached", false) == true) {
                current
            } else {
                RaftFilePreviewPublicDownloadState.NotDownloaded
            }
        else -> current
    }
