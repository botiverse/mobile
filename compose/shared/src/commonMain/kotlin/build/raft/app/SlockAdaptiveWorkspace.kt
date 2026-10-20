package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal const val SLOCK_ADAPTIVE_DETAIL_CLOSE_EVENT = "slockAdaptiveDetailClose"
internal const val SLOCK_ADAPTIVE_DETAIL_SHELL_ID = "slockAdaptiveDetailShellId"
internal const val SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY = "slockAdaptiveDetailIdentityKey"
internal const val SLOCK_ADAPTIVE_DETAIL_GENERATION = "slockAdaptiveDetailGeneration"
internal const val SLOCK_ADAPTIVE_HOME_WORKSPACE_ENABLED = "slockAdaptiveHomeWorkspaceEnabled"

/**
 * Identity and intent for one adaptive conversation detail.
 *
 * Display data and product snapshots deliberately do not belong here. The child Thread Pager
 * resolves them through its canonical stores after this identity becomes active.
 */
internal data class SlockAdaptiveDetailIdentity(
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

internal data class SlockAdaptiveDetailSelection(
    val identity: SlockAdaptiveDetailIdentity,
    val generation: Long,
)

internal data class SlockAdaptiveDetailCloseRequest(
    val shellInstanceId: String,
    val identityKey: String,
    val generation: Long,
) {
    init {
        require(shellInstanceId.isNotBlank()) { "Adaptive close requires a shell id" }
        require(identityKey.isNotBlank()) { "Adaptive close requires an identity key" }
        require(generation > 0L) { "Adaptive close requires a positive generation" }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put(SLOCK_ADAPTIVE_DETAIL_SHELL_ID, shellInstanceId)
            .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identityKey)
            .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, generation)
}

internal fun JSONObject.toSlockAdaptiveDetailCloseRequestOrNull(): SlockAdaptiveDetailCloseRequest? {
    val shellInstanceId = optString(SLOCK_ADAPTIVE_DETAIL_SHELL_ID).trim()
    val identityKey = optString(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY).trim()
    val generation = optDouble(SLOCK_ADAPTIVE_DETAIL_GENERATION, 0.0).toLong()
    if (shellInstanceId.isBlank() || identityKey.isBlank() || generation <= 0L) return null
    return SlockAdaptiveDetailCloseRequest(shellInstanceId, identityKey, generation)
}

internal fun updatedSlockAdaptiveDetailCloseRequest(
    current: SlockAdaptiveDetailCloseRequest?,
    eventData: JSONObject,
): SlockAdaptiveDetailCloseRequest? =
    eventData.toSlockAdaptiveDetailCloseRequestOrNull() ?: current

internal fun shouldHoldThreadRoomLease(
    adaptiveCloseRequest: SlockAdaptiveDetailCloseRequest?,
    active: Boolean,
): Boolean = adaptiveCloseRequest == null || active

internal fun WireThreadLaunchPayload.toSlockAdaptiveDetailIdentity(
    serverId: String,
): SlockAdaptiveDetailIdentity? {
    val normalizedServerId = serverId.trim().takeIf(String::isNotBlank) ?: return null
    val resolvedThreadId =
        sourceThreadId?.trim()?.takeIf(String::isNotBlank)
            ?: streamChannelId?.trim()?.takeIf(String::isNotBlank)
            ?: threadId?.trim()?.takeIf(String::isNotBlank)
            ?: parentMessageId?.trim()?.takeIf(String::isNotBlank)
            ?: return null
    return SlockAdaptiveDetailIdentity(
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

internal data class SlockAdaptiveHomeLandingCandidate(
    val threadId: String,
    val name: String,
    val joint: Boolean,
)

internal fun resolveSlockAdaptiveHomeLandingId(
    selectedThreadId: String?,
    candidates: List<SlockAdaptiveHomeLandingCandidate>,
): String? =
    selectedThreadId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { selected -> candidates.firstOrNull { it.threadId == selected }?.threadId }
        ?: candidates.firstOrNull { !it.joint && it.name == "all" }?.threadId

internal fun resolveSlockHomeSelectedConversationId(
    adaptiveWorkspaceEnabled: Boolean,
    adaptiveSelection: SlockAdaptiveDetailSelection?,
    shellSelectedConversationId: String?,
    homeSelectedConversationId: String?,
): String? =
    if (adaptiveWorkspaceEnabled) {
        adaptiveSelection?.identity?.threadId
    } else {
        shellSelectedConversationId ?: homeSelectedConversationId
    }

internal enum class SlockAdaptiveWorkspacePresentation {
    ListOnly,
    Split,
    DetailOnly,
    Inactive,
}

internal data class SlockAdaptiveWorkspaceState(
    val shellInstanceId: String,
    val serverId: String,
    val parentVisible: Boolean = true,
    val pane: SlockAdaptivePaneState = SlockAdaptivePaneState(),
    val selection: SlockAdaptiveDetailSelection? = null,
    val generationCursor: Long = 0L,
) {
    val presentation: SlockAdaptiveWorkspacePresentation
        get() =
            when {
                !parentVisible -> SlockAdaptiveWorkspacePresentation.Inactive
                selection == null -> SlockAdaptiveWorkspacePresentation.ListOnly
                pane.layout == SlockAdaptivePaneLayout.DualPane ->
                    SlockAdaptiveWorkspacePresentation.Split
                else -> SlockAdaptiveWorkspacePresentation.DetailOnly
            }

    init {
        require(shellInstanceId.isNotBlank()) { "Adaptive workspace requires a shell id" }
        require(serverId.isNotBlank()) { "Adaptive workspace requires a server id" }
    }
}

internal sealed interface SlockAdaptiveWorkspaceIntent {
    data class ParentVisibilityChanged(val visible: Boolean) : SlockAdaptiveWorkspaceIntent

    data class WindowChanged(val pane: SlockAdaptivePaneState) : SlockAdaptiveWorkspaceIntent

    /** Wide-only install. Compact row opens retain the existing standalone Router behavior. */
    data class OpenDetail(val identity: SlockAdaptiveDetailIdentity) : SlockAdaptiveWorkspaceIntent

    data class CloseDetail(
        val identityKey: String,
        val generation: Long,
    ) : SlockAdaptiveWorkspaceIntent

    data class AuthorityChanged(val serverId: String) : SlockAdaptiveWorkspaceIntent

    data object TerminalReset : SlockAdaptiveWorkspaceIntent
}

internal fun reduceSlockAdaptiveWorkspace(
    state: SlockAdaptiveWorkspaceState,
    intent: SlockAdaptiveWorkspaceIntent,
): SlockAdaptiveWorkspaceState =
    when (intent) {
        is SlockAdaptiveWorkspaceIntent.ParentVisibilityChanged ->
            state.copy(parentVisible = intent.visible)

        is SlockAdaptiveWorkspaceIntent.WindowChanged ->
            state.copy(pane = intent.pane.withConservativeAdaptiveLayout())

        is SlockAdaptiveWorkspaceIntent.OpenDetail -> {
            if (
                state.pane.layout != SlockAdaptivePaneLayout.DualPane ||
                intent.identity.serverId != state.serverId
            ) {
                state
            } else if (state.selection?.identity == intent.identity) {
                state
            } else {
                val nextGeneration = state.generationCursor.nextAdaptiveGeneration()
                state.copy(
                    selection = SlockAdaptiveDetailSelection(intent.identity, nextGeneration),
                    generationCursor = nextGeneration,
                )
            }
        }

        is SlockAdaptiveWorkspaceIntent.CloseDetail -> {
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

        is SlockAdaptiveWorkspaceIntent.AuthorityChanged -> {
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

        SlockAdaptiveWorkspaceIntent.TerminalReset ->
            state.copy(
                selection = null,
                generationCursor = state.generationCursor.nextAdaptiveGeneration(),
            )
    }

internal fun SlockAdaptiveWorkspaceState.acceptsDetailCallback(
    identityKey: String,
    generation: Long,
): Boolean =
    selection?.let { current ->
        current.identity.key == identityKey && current.generation == generation
    } == true

private fun Long.nextAdaptiveGeneration(): Long =
    also { check(it < Long.MAX_VALUE) { "Adaptive workspace generation exhausted" } } + 1L

private fun SlockAdaptivePaneState.withConservativeAdaptiveLayout(): SlockAdaptivePaneState =
    if (
        (
            posture == SlockAdaptivePanePosture.Unknown ||
                posture == SlockAdaptivePanePosture.HalfFolded
        ) &&
        layout == SlockAdaptivePaneLayout.DualPane
    ) {
        copy(layout = SlockAdaptivePaneLayout.Compact)
    } else {
        this
    }
