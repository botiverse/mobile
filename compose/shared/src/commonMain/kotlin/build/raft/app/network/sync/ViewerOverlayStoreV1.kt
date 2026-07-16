@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package build.raft.app.network.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

const val VIEWER_OVERLAY_VERSION_CONFLICT_DIAGNOSTIC: String = "viewer_overlay_version_conflict"
const val VIEWER_OVERLAY_VERSION_REGRESSION_DIAGNOSTIC: String = "viewer_overlay_version_regression"

/** Full durable identity for one receiver-private overlay family. */
data class ViewerOverlayKey(
    val partition: DurablePartitionKey,
    val scopeKind: String,
    val scopeId: String,
    val subjectKind: String,
    val subjectId: String,
    val overlayKind: String,
) {
    init {
        requireOpaqueDurableIdentity("scopeKind", scopeKind)
        requireOpaqueDurableIdentity("scopeId", scopeId)
        requireOpaqueDurableIdentity("subjectKind", subjectKind)
        requireOpaqueDurableIdentity("subjectId", subjectId)
        requireOpaqueDurableIdentity("overlayKind", overlayKind)
    }
}

/** Only complete server-authored snapshots are durable in this phase; absence represents Unknown. */
@Serializable
enum class OverlayCompleteness {
    @SerialName("complete")
    Complete,
}

/** Closed receiver-private payload family. Attachment comments/provenance are deliberately absent. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ViewerOverlayPayloadV1 {
    @Serializable
    @SerialName("reactions")
    data class Reactions(
        val reactedEmojis: List<String>,
    ) : ViewerOverlayPayloadV1 {
        init {
            require(reactedEmojis.all { it.isNotBlank() && it == it.trim() })
            require(reactedEmojis == reactedEmojis.distinct().sorted())
        }
    }
}

@Serializable
data class VersionedViewerOverlayV1(
    val version: Long,
    val completeness: OverlayCompleteness,
    val payload: ViewerOverlayPayloadV1,
) {
    init {
        require(version >= 0L) { "viewer overlay version must be a non-negative SQLite Long" }
    }
}

/** Named apply outcomes; an equal-version/different-payload conflict throws instead of returning success. */
sealed interface ViewerOverlayWriteResult {
    data class Applied(val previousVersion: Long?) : ViewerOverlayWriteResult
    data object Idempotent : ViewerOverlayWriteResult
    data class VersionRegressionNoOp(val storedVersion: Long) : ViewerOverlayWriteResult
}

class ViewerOverlayVersionConflict(
    val key: ViewerOverlayKey,
    val version: Long,
) : RuntimeException("viewer_overlay_version_conflict at version=$version for $key")

/**
 * Backend-neutral overlay seam. [compareAndWrite] is one storage transaction; it is intentionally
 * independent from the shared-room fact transaction and has no production binding in Commit A.
 */
interface ViewerOverlayStore {
    suspend fun read(key: ViewerOverlayKey): VersionedViewerOverlayV1?
    suspend fun compareAndWrite(
        key: ViewerOverlayKey,
        overlay: VersionedViewerOverlayV1,
    ): ViewerOverlayWriteResult
}
