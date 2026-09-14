package build.raft.app

import build.raft.app.base.BridgeModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Exact identity used to inspect the one durable public attachment download.
 *
 * [fileName] and [mimeType] are presentation hints for legacy hosts only. The Android authority
 * binds product state to [owner] + [attachmentId] and never adopts a public row by display name.
 */
internal data class SlockFilePreviewPublicDownloadQuery(
    val attachmentId: String,
    val fileName: String,
    val owner: AttachmentOwnerLease,
    val mimeType: String
)

internal sealed interface SlockFilePreviewPublicDownloadState {
    /** The preview remains renderable, but no Download/Open claim is made yet. */
    data object Checking : SlockFilePreviewPublicDownloadState

    /** No complete, live public-download authority was verified. */
    data object NotDownloaded : SlockFilePreviewPublicDownloadState

    /**
     * A committed journal generation whose exact persisted URI passed a live size/path readback.
     * The URI is retained only as evidence for the current page state; native code resolves it
     * again under the identity lock before any external launch.
     */
    data class Downloaded(
        val generation: Long,
        val sizeBytes: Long,
        val persistedUri: String
    ) : SlockFilePreviewPublicDownloadState {
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

internal data class SlockFilePreviewPublicDownloadObservation(
    val state: SlockFilePreviewPublicDownloadState,
    val attemptToken: String? = null
)

internal interface SlockFilePreviewPublicDownloadRepository {
    suspend fun inspect(query: SlockFilePreviewPublicDownloadQuery): SlockFilePreviewPublicDownloadObservation
}

internal class BridgeSlockFilePreviewPublicDownloadRepository(
    private val bridge: BridgeModule
) : SlockFilePreviewPublicDownloadRepository {
    override suspend fun inspect(
        query: SlockFilePreviewPublicDownloadQuery
    ): SlockFilePreviewPublicDownloadObservation =
        slockFilePreviewPublicDownloadObservation(
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
internal fun slockFilePreviewPublicDownloadObservation(
    response: JSONObject?
): SlockFilePreviewPublicDownloadObservation {
    if (response == null || response.optString("status") != "ok" || !response.optBoolean("cached", false)) {
        return SlockFilePreviewPublicDownloadObservation(
            SlockFilePreviewPublicDownloadState.NotDownloaded
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
        return SlockFilePreviewPublicDownloadObservation(
            SlockFilePreviewPublicDownloadState.NotDownloaded
        )
    }
    return SlockFilePreviewPublicDownloadObservation(
        state =
            SlockFilePreviewPublicDownloadState.Downloaded(
                generation = generation,
                sizeBytes = sizeBytes,
                persistedUri = persistedUri
            ),
        attemptToken = response.optString("attemptToken").trim().takeIf(String::isNotBlank)
    )
}

/** A late response cannot publish state for a different attachment, shell, or principal. */
internal fun slockFilePreviewCommitPublicDownloadObservation(
    request: SlockFilePreviewPublicDownloadQuery,
    current: SlockFilePreviewPublicDownloadQuery?,
    observation: SlockFilePreviewPublicDownloadObservation
): SlockFilePreviewPublicDownloadObservation? = observation.takeIf { request == current }

/** A state observation is visible only while its exact attachment/principal query is current. */
internal fun slockFilePreviewVisiblePublicDownloadState(
    current: SlockFilePreviewPublicDownloadQuery?,
    observedFor: SlockFilePreviewPublicDownloadQuery?,
    observedState: SlockFilePreviewPublicDownloadState
): SlockFilePreviewPublicDownloadState =
    when {
        current == null -> SlockFilePreviewPublicDownloadState.NotDownloaded
        current != observedFor -> SlockFilePreviewPublicDownloadState.Checking
        else -> observedState
    }

internal enum class SlockFilePreviewDownloadUiState {
    NotDownloaded,
    Downloading,
    Downloaded
}

/** Checking intentionally has no user-visible state or action; it never impersonates Download. */
internal fun slockFilePreviewDownloadUiState(
    publicDownloadState: SlockFilePreviewPublicDownloadState,
    downloadStatus: String
): SlockFilePreviewDownloadUiState? =
    when {
        slockFilePreviewShowsDownloadProgress(downloadStatus) ->
            SlockFilePreviewDownloadUiState.Downloading
        publicDownloadState == SlockFilePreviewPublicDownloadState.Checking -> null
        publicDownloadState is SlockFilePreviewPublicDownloadState.Downloaded ->
            SlockFilePreviewDownloadUiState.Downloaded
        else -> SlockFilePreviewDownloadUiState.NotDownloaded
    }

internal data class SlockFilePreviewExternalOpenPolicy(
    val requirePublicDownload: Boolean,
    val trackProgress: Boolean
)

internal fun slockFilePreviewExternalOpenPolicy(
    publicDownloadState: SlockFilePreviewPublicDownloadState
): SlockFilePreviewExternalOpenPolicy? =
    when (publicDownloadState) {
        SlockFilePreviewPublicDownloadState.Checking -> null
        SlockFilePreviewPublicDownloadState.NotDownloaded ->
            SlockFilePreviewExternalOpenPolicy(
                requirePublicDownload = false,
                trackProgress = true
            )
        is SlockFilePreviewPublicDownloadState.Downloaded ->
            SlockFilePreviewExternalOpenPolicy(
                requirePublicDownload = true,
                trackProgress = false
            )
    }

/** Click-time missing/unverifiable authority immediately removes the stale Open affordance. */
internal fun slockFilePreviewPublicDownloadStateAfterOpen(
    current: SlockFilePreviewPublicDownloadState,
    result: JSONObject?
): SlockFilePreviewPublicDownloadState =
    when (result?.optString("status").orEmpty()) {
        "missing", "storage_failed", "download_failed" -> SlockFilePreviewPublicDownloadState.NotDownloaded
        "opened" ->
            if (result?.optBoolean("cached", false) == true) {
                current
            } else {
                SlockFilePreviewPublicDownloadState.NotDownloaded
            }
        else -> current
    }
