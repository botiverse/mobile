@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package build.raft.app.network.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

const val VIEWER_OVERLAY_VERSION_CONFLICT_DIAGNOSTIC: String = "viewer_overlay_version_conflict"
const val VIEWER_OVERLAY_VERSION_REGRESSION_DIAGNOSTIC: String = "viewer_overlay_version_regression"

// The receiver-private viewer overlay shapes and diagnostics pinned by the canonical message V2 manifest
// (CanonicalMessageV2ManifestGateTest). The app never wired an overlay store; the V2 clear and purge paths
// still delete messageViewerOverlayEntity rows that the shipped schema keeps.

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
