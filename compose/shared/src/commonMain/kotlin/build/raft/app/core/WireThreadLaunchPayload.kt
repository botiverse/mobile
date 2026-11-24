package build.raft.app

import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeFormat24Hour
import kotlinx.serialization.Serializable

@Serializable
internal data class WireThreadLaunchPayload(
    val instanceId: String = "",
    val surfaceKind: String = RaftThreadSurfaceKind.Channel,
    val channelKind: String? = null,
    val jointChannelId: String? = null,
    val threadId: String? = null,
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val parentSurfaceKind: String? = null,
    val parentChannelKind: String? = null,
    val parentNavigationHint: String? = null,
    val dmPeerIdentity: MemberDetailIdentity? = null,
    val originParentMessageId: String? = null,
    val focusedMessageId: String? = null,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val alignOwnMessagesRight: Boolean = false,
    val compactConsecutiveMessages: Boolean = false,
    val showReactionActions: Boolean = true,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = RaftTimeFormat24Hour,
    val composerSeedText: String = "",
    val composerSyncNonce: Int = 0,
    val composerFocusRequestNonce: Int = 0,
    val focusedMessageRequestNonce: Int = 0,
    val originFallbackParentChannelId: String? = null,
    val originFallbackParentMessageId: String? = null,
    val originFallbackPreview: String? = null,
    val originFallbackSenderId: String? = null,
    val originFallbackSenderKind: MemberKind? = null,
    val originFallbackExpected: Boolean = true
) {
    fun hasRouteIdentity(): Boolean =
        listOf(threadId, sourceThreadId, streamChannelId, parentChannelId, parentMessageId)
            .any { !it.isNullOrBlank() }
}
