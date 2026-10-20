@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package build.raft.app.network.sync

import build.raft.app.data.RaftSqlDatabase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val viewerOverlayJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
    encodeDefaults = true
    prettyPrint = false
}

enum class ViewerOverlayIngressSource {
    Hydrate,
    MutationAck,
    UserRoom,
    SharedRoom,
}

sealed interface ViewerOverlayIngressResult {
    data class Stored(val result: ViewerOverlayWriteResult) : ViewerOverlayIngressResult
    data object SharedRoomIgnored : ViewerOverlayIngressResult
}

sealed interface ViewerOverlayWriteDecision {
    data class Apply(val previousVersion: Long?) : ViewerOverlayWriteDecision
    data object Idempotent : ViewerOverlayWriteDecision
    data class Regression(val storedVersion: Long) : ViewerOverlayWriteDecision
    data object Conflict : ViewerOverlayWriteDecision
}

class ViewerOverlayCorrupted(message: String) : RuntimeException(message)

/** One source-neutral monotonic gate used by hydrate, own-mutation ACK and user-room updates. */
fun decideViewerOverlayWrite(
    stored: VersionedViewerOverlayV1?,
    incoming: VersionedViewerOverlayV1,
): ViewerOverlayWriteDecision =
    when {
        stored == null -> ViewerOverlayWriteDecision.Apply(previousVersion = null)
        incoming.version > stored.version -> ViewerOverlayWriteDecision.Apply(previousVersion = stored.version)
        incoming.version < stored.version -> ViewerOverlayWriteDecision.Regression(stored.version)
        incoming == stored -> ViewerOverlayWriteDecision.Idempotent
        else -> ViewerOverlayWriteDecision.Conflict
    }

fun encodeViewerOverlayCanonical(overlay: VersionedViewerOverlayV1): String =
    viewerOverlayJson.encodeToString(overlay.canonicalized())

fun decodeViewerOverlayCanonical(text: String): VersionedViewerOverlayV1? =
    try {
        viewerOverlayJson.decodeFromString<VersionedViewerOverlayV1>(text).canonicalized()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

class ViewerOverlayIngressGate(
    private val store: ViewerOverlayStore,
) {
    suspend fun apply(
        source: ViewerOverlayIngressSource,
        key: ViewerOverlayKey,
        overlay: VersionedViewerOverlayV1,
    ): ViewerOverlayIngressResult =
        if (source == ViewerOverlayIngressSource.SharedRoom) {
            ViewerOverlayIngressResult.SharedRoomIgnored
        } else {
            ViewerOverlayIngressResult.Stored(store.compareAndWrite(key, overlay.canonicalized()))
        }
}

/** SQLDelight binding: read/compare/write is one physical database transaction on the complete key. */
internal class SqlDelightViewerOverlayStore(
    private val database: RaftSqlDatabase,
) : ViewerOverlayStore {
    override suspend fun read(key: ViewerOverlayKey): VersionedViewerOverlayV1? =
        database.queries.selectMessageViewerOverlay(
            environmentId = key.partition.environmentId,
            serverId = key.partition.serverId,
            principalId = key.partition.principalId,
            consumerId = key.partition.consumerId,
            scopeKind = key.scopeKind,
            scopeId = key.scopeId,
            subjectKind = key.subjectKind,
            subjectId = key.subjectId,
            overlayKind = key.overlayKind,
        ).executeAsOneOrNull()?.toOverlay()

    override suspend fun compareAndWrite(
        key: ViewerOverlayKey,
        overlay: VersionedViewerOverlayV1,
    ): ViewerOverlayWriteResult {
        require(key.partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) {
            "viewer overlays belong to the V2 durable generation"
        }
        val incoming = overlay.canonicalized()
        var result: ViewerOverlayWriteResult? = null
        database.transaction {
            val stored = readInsideTransaction(key)
            when (val decision = decideViewerOverlayWrite(stored, incoming)) {
                is ViewerOverlayWriteDecision.Apply -> {
                    val payload = encodeViewerOverlayPayloadCanonical(incoming.payload)
                    if (stored == null) {
                        queries.insertMessageViewerOverlay(
                            environmentId = key.partition.environmentId,
                            serverId = key.partition.serverId,
                            principalId = key.partition.principalId,
                            consumerId = key.partition.consumerId,
                            scopeKind = key.scopeKind,
                            scopeId = key.scopeId,
                            subjectKind = key.subjectKind,
                            subjectId = key.subjectId,
                            overlayKind = key.overlayKind,
                            overlayVersion = incoming.version,
                            completeness = incoming.completeness.serializedName,
                            payloadJson = payload,
                        )
                    } else {
                        queries.updateMessageViewerOverlayAtVersion(
                            overlayVersion = incoming.version,
                            completeness = incoming.completeness.serializedName,
                            payloadJson = payload,
                            environmentId = key.partition.environmentId,
                            serverId = key.partition.serverId,
                            principalId = key.partition.principalId,
                            consumerId = key.partition.consumerId,
                            scopeKind = key.scopeKind,
                            scopeId = key.scopeId,
                            subjectKind = key.subjectKind,
                            subjectId = key.subjectId,
                            overlayKind = key.overlayKind,
                            overlayVersion_ = stored.version,
                        )
                        check(readInsideTransaction(key) == incoming) {
                            "viewer overlay compare-and-write lost its expected version"
                        }
                    }
                    result = ViewerOverlayWriteResult.Applied(decision.previousVersion)
                }
                ViewerOverlayWriteDecision.Idempotent -> result = ViewerOverlayWriteResult.Idempotent
                is ViewerOverlayWriteDecision.Regression -> {
                    result = ViewerOverlayWriteResult.VersionRegressionNoOp(decision.storedVersion)
                }
                ViewerOverlayWriteDecision.Conflict -> throw ViewerOverlayVersionConflict(key, incoming.version)
            }
        }
        return checkNotNull(result)
    }

    private fun readInsideTransaction(key: ViewerOverlayKey): VersionedViewerOverlayV1? =
        database.queries.selectMessageViewerOverlay(
            environmentId = key.partition.environmentId,
            serverId = key.partition.serverId,
            principalId = key.partition.principalId,
            consumerId = key.partition.consumerId,
            scopeKind = key.scopeKind,
            scopeId = key.scopeId,
            subjectKind = key.subjectKind,
            subjectId = key.subjectId,
            overlayKind = key.overlayKind,
        ).executeAsOneOrNull()?.toOverlay()
}

private fun build.raft.app.shared.data.local.MessageViewerOverlayEntity.toOverlay(): VersionedViewerOverlayV1 {
    if (completeness != OverlayCompleteness.Complete.serializedName) {
        throw ViewerOverlayCorrupted("unsupported viewer overlay completeness=$completeness")
    }
    val payload = decodeViewerOverlayPayloadCanonical(payloadJson)
        ?: throw ViewerOverlayCorrupted("malformed viewer overlay payload for $subjectKind/$subjectId/$overlayKind")
    return VersionedViewerOverlayV1(
        version = overlayVersion,
        completeness = OverlayCompleteness.Complete,
        payload = payload,
    ).canonicalized()
}

private fun encodeViewerOverlayPayloadCanonical(payload: ViewerOverlayPayloadV1): String =
    viewerOverlayJson.encodeToString(ViewerOverlayPayloadV1.serializer(), payload.canonicalized())

private fun decodeViewerOverlayPayloadCanonical(text: String): ViewerOverlayPayloadV1? =
    try {
        viewerOverlayJson.decodeFromString(ViewerOverlayPayloadV1.serializer(), text).canonicalized()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private val OverlayCompleteness.serializedName: String
    get() = when (this) {
        OverlayCompleteness.Complete -> "complete"
    }

private fun VersionedViewerOverlayV1.canonicalized(): VersionedViewerOverlayV1 =
    copy(payload = payload.canonicalized())

private fun ViewerOverlayPayloadV1.canonicalized(): ViewerOverlayPayloadV1 =
    when (this) {
        is ViewerOverlayPayloadV1.Reactions -> copy(
            reactedEmojis = reactedEmojis
                .onEach { require(it.isNotBlank() && it == it.trim()) }
                .distinct()
                .sorted(),
        )
    }
