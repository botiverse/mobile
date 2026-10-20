package build.raft.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT = "slockAdaptiveDetailClose"
internal const val RAFT_ADAPTIVE_DETAIL_SHELL_ID = "slockAdaptiveDetailShellId"
internal const val RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY = "slockAdaptiveDetailIdentityKey"
internal const val RAFT_ADAPTIVE_DETAIL_GENERATION = "slockAdaptiveDetailGeneration"
/**
 * A4 source-side initiation gate (task #18): the standalone thread page only runs a
 * prepare→open→commit adoption when this flag is set on its pageData. Off by default —
 * the native guarded commit lands behind it so an in-flight protocol can merge inert.
 */
internal const val RAFT_ADAPTIVE_INITIATION_ENABLED = "slockAdaptiveInitiationEnabled"

/**
 * Identity and intent for one adaptive conversation detail.
 *
 * Display data and product snapshots deliberately do not belong here. The child Thread Pager
 * resolves them through its canonical stores after this identity becomes active.
 */
internal data class RaftAdaptiveDetailIdentity(
    val serverId: String,
    val threadId: String,
    val surfaceKind: String,
    val channelId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val focusedMessageId: String? = null,
) {
    val key: String =
        listOf(
            serverId,
            threadId,
            surfaceKind,
            channelId.orEmpty(),
            streamChannelId.orEmpty(),
            parentChannelId.orEmpty(),
            parentMessageId.orEmpty(),
            focusedMessageId.orEmpty(),
        ).joinToString(separator = "") { value -> "${value.length}:$value" }

    init {
        require(serverId.isNotBlank()) { "Adaptive detail requires a server id" }
        require(threadId.isNotBlank()) { "Adaptive detail requires a thread id" }
        require(surfaceKind.isNotBlank()) { "Adaptive detail requires a surface kind" }
    }
}

internal data class RaftAdaptiveDetailSelection(
    val identity: RaftAdaptiveDetailIdentity,
    val generation: Long,
)

internal data class RaftAdaptiveDetailCloseRequest(
    val shellInstanceId: String,
    val identityKey: String,
    val generation: Long,
) {
    init {
        require(shellInstanceId.isNotBlank()) { "Adaptive close requires a shell id" }
        require(identityKey.isNotBlank()) { "Adaptive close requires an identity key" }
        require(generation > 0L) { "Adaptive close requires a positive generation" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, shellInstanceId)
            put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, identityKey)
            put(RAFT_ADAPTIVE_DETAIL_GENERATION, generation)
        }
}

internal fun JsonObject.toRaftAdaptiveDetailCloseRequestOrNull(): RaftAdaptiveDetailCloseRequest? {
    val shellInstanceId = optString(RAFT_ADAPTIVE_DETAIL_SHELL_ID).trim()
    val identityKey = optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY).trim()
    val generation = optDouble(RAFT_ADAPTIVE_DETAIL_GENERATION, 0.0).toLong()
    if (shellInstanceId.isBlank() || identityKey.isBlank() || generation <= 0L) return null
    return RaftAdaptiveDetailCloseRequest(shellInstanceId, identityKey, generation)
}

internal fun updatedRaftAdaptiveDetailCloseRequest(
    current: RaftAdaptiveDetailCloseRequest?,
    eventData: JsonObject,
): RaftAdaptiveDetailCloseRequest? =
    eventData.toRaftAdaptiveDetailCloseRequestOrNull() ?: current

internal fun shouldHoldThreadRoomLease(
    adaptiveCloseRequest: RaftAdaptiveDetailCloseRequest?,
    active: Boolean,
): Boolean = adaptiveCloseRequest == null || active

internal fun WireThreadLaunchPayload.toRaftAdaptiveDetailIdentity(
    serverId: String,
): RaftAdaptiveDetailIdentity? {
    val normalizedServerId = serverId.trim().takeIf(String::isNotBlank) ?: return null
    val resolvedThreadId =
        sourceThreadId?.trim()?.takeIf(String::isNotBlank)
            ?: streamChannelId?.trim()?.takeIf(String::isNotBlank)
            ?: threadId?.trim()?.takeIf(String::isNotBlank)
            ?: parentMessageId?.trim()?.takeIf(String::isNotBlank)
            ?: return null
    return RaftAdaptiveDetailIdentity(
        serverId = normalizedServerId,
        threadId = resolvedThreadId,
        surfaceKind = surfaceKind,
        channelId = threadId?.trim()?.takeIf(String::isNotBlank),
        streamChannelId = streamChannelId?.trim()?.takeIf(String::isNotBlank),
        parentChannelId = parentChannelId?.trim()?.takeIf(String::isNotBlank),
        parentMessageId = parentMessageId?.trim()?.takeIf(String::isNotBlank),
        focusedMessageId = focusedMessageId?.trim()?.takeIf(String::isNotBlank),
    )
}

internal data class RaftAdaptiveHomeLandingCandidate(
    val threadId: String,
    val name: String,
)

internal fun resolveRaftAdaptiveHomeLandingId(
    selectedThreadId: String?,
    candidates: List<RaftAdaptiveHomeLandingCandidate>,
): String? =
    selectedThreadId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { selected -> candidates.firstOrNull { it.threadId == selected }?.threadId }
        ?: candidates.firstOrNull { it.name == "all" }?.threadId

internal fun resolveRaftHomeSelectedConversationId(
    adaptiveWorkspaceEnabled: Boolean,
    adaptiveSelection: RaftAdaptiveDetailSelection?,
    shellSelectedConversationId: String?,
    homeSelectedConversationId: String?,
): String? =
    if (adaptiveWorkspaceEnabled) {
        adaptiveSelection?.identity?.threadId
    } else {
        shellSelectedConversationId ?: homeSelectedConversationId
    }

internal enum class RaftAdaptiveWorkspacePresentation {
    ListOnly,
    Split,
    DetailOnly,
    Inactive,
}

internal data class RaftAdaptiveWorkspaceState(
    val shellInstanceId: String,
    val serverId: String,
    val parentVisible: Boolean = true,
    val pane: RaftAdaptivePaneState = RaftAdaptivePaneState(),
    val selection: RaftAdaptiveDetailSelection? = null,
    val generationCursor: Long = 0L,
) {
    val presentation: RaftAdaptiveWorkspacePresentation
        get() =
            when {
                !parentVisible -> RaftAdaptiveWorkspacePresentation.Inactive
                selection == null -> RaftAdaptiveWorkspacePresentation.ListOnly
                pane.layout == RaftAdaptivePaneLayout.DualPane ->
                    RaftAdaptiveWorkspacePresentation.Split
                else -> RaftAdaptiveWorkspacePresentation.DetailOnly
            }

    init {
        require(shellInstanceId.isNotBlank()) { "Adaptive workspace requires a shell id" }
        require(serverId.isNotBlank()) { "Adaptive workspace requires a server id" }
    }
}

internal sealed interface RaftAdaptiveWorkspaceIntent {
    data class ParentVisibilityChanged(val visible: Boolean) : RaftAdaptiveWorkspaceIntent

    data class WindowChanged(val pane: RaftAdaptivePaneState) : RaftAdaptiveWorkspaceIntent

    /** Wide-only install. Compact row opens retain the existing standalone Router behavior. */
    data class OpenDetail(val identity: RaftAdaptiveDetailIdentity) : RaftAdaptiveWorkspaceIntent

    data class CloseDetail(
        val identityKey: String,
        val generation: Long,
    ) : RaftAdaptiveWorkspaceIntent

    data class AuthorityChanged(val serverId: String) : RaftAdaptiveWorkspaceIntent

    data object TerminalReset : RaftAdaptiveWorkspaceIntent

    /**
     * Single-pane Back on the detail root: dismiss the selection so Home shows its list again.
     * Unlike [CloseDetail] this needs no key/generation proof (the parent itself is authoritative)
     * and bumps the generation so late detail-side callbacks can no longer match the old slot.
     */
    data object DismissDetail : RaftAdaptiveWorkspaceIntent

    /**
     * task #45: records which conversation the user opened through the standalone
     * (single-column) stack while desktop mode is OFF. The workspace keeps rendering
     * gated OFF, but the parked selection is overwritten so a later ON restores the
     * *current* conversation instead of a stale pre-OFF one. Unlike [OpenDetail] this
     * does not require DualPane — the parked pane fact is preserved untouched.
     */
    data class ParkSelection(val identity: RaftAdaptiveDetailIdentity) : RaftAdaptiveWorkspaceIntent
}

internal fun reduceRaftAdaptiveWorkspace(
    state: RaftAdaptiveWorkspaceState,
    intent: RaftAdaptiveWorkspaceIntent,
): RaftAdaptiveWorkspaceState =
    when (intent) {
        is RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged ->
            state.copy(parentVisible = intent.visible)

        is RaftAdaptiveWorkspaceIntent.WindowChanged ->
            state.copy(pane = intent.pane.withConservativeAdaptiveLayout())

        is RaftAdaptiveWorkspaceIntent.OpenDetail -> {
            if (
                state.pane.layout != RaftAdaptivePaneLayout.DualPane ||
                intent.identity.serverId != state.serverId
            ) {
                state
            } else if (state.selection?.identity == intent.identity) {
                state
            } else {
                val nextGeneration = state.generationCursor.nextAdaptiveGeneration()
                state.copy(
                    selection = RaftAdaptiveDetailSelection(intent.identity, nextGeneration),
                    generationCursor = nextGeneration,
                )
            }
        }

        is RaftAdaptiveWorkspaceIntent.CloseDetail -> {
            val current = state.selection
            if (
                current?.identity?.key == intent.identityKey &&
                current.generation == intent.generation
            ) {
                state.copy(selection = null)
            } else {
                state
            }
        }

        is RaftAdaptiveWorkspaceIntent.AuthorityChanged -> {
            val nextServerId = intent.serverId.trim()
            require(nextServerId.isNotBlank()) { "Adaptive workspace authority requires a server id" }
            if (nextServerId == state.serverId) {
                state
            } else {
                state.copy(
                    serverId = nextServerId,
                    selection = null,
                    generationCursor = state.generationCursor.nextAdaptiveGeneration(),
                )
            }
        }

        RaftAdaptiveWorkspaceIntent.TerminalReset ->
            state.copy(
                selection = null,
                generationCursor = state.generationCursor.nextAdaptiveGeneration(),
            )

        RaftAdaptiveWorkspaceIntent.DismissDetail ->
            if (state.selection == null) {
                state
            } else {
                state.copy(
                    selection = null,
                    generationCursor = state.generationCursor.nextAdaptiveGeneration(),
                )
            }

        is RaftAdaptiveWorkspaceIntent.ParkSelection -> {
            if (intent.identity.serverId != state.serverId) {
                state
            } else if (state.selection?.identity == intent.identity) {
                state
            } else {
                val nextGeneration = state.generationCursor.nextAdaptiveGeneration()
                state.copy(
                    selection = RaftAdaptiveDetailSelection(intent.identity, nextGeneration),
                    generationCursor = nextGeneration,
                )
            }
        }
    }

internal fun RaftAdaptiveWorkspaceState.acceptsDetailCallback(
    identityKey: String,
    generation: Long,
): Boolean =
    selection?.let { current ->
        current.identity.key == identityKey && current.generation == generation
    } == true

internal fun Long.nextAdaptiveGeneration(): Long =
    also { check(it < Long.MAX_VALUE) { "Adaptive workspace generation exhausted" } } + 1L

private fun RaftAdaptivePaneState.withConservativeAdaptiveLayout(): RaftAdaptivePaneState =
    if (
        (
            posture == RaftAdaptivePanePosture.Unknown ||
                posture == RaftAdaptivePanePosture.HalfFolded
        ) &&
        layout == RaftAdaptivePaneLayout.DualPane
    ) {
        copy(layout = RaftAdaptivePaneLayout.Compact)
    } else {
        this
    }
