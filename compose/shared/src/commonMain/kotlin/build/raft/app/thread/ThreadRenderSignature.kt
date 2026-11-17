package build.raft.app

/**
 * Minimal render-axis signature for a Thread screen payload.
 *
 * Used to key `LaunchedEffect` / `remember` blocks that drive the
 * `thread_render` perf marker (or any other render-axis log) so that
 * payload changes on the non-render axis (members, settings, actions,
 * realtime metadata, etc.) do not retrigger render-side work.
 *
 * The signature is a `data class` so it can be unit-tested as a value:
 * two payloads that produce the same `ThreadRenderSignature` should
 * have equivalent render-axis effect on the UI / log hot path.
 *
 * See `ThreadRenderSignatureTest` for the focused regression coverage.
 */
internal data class ThreadRenderSignature(
    val instanceId: String,
    val sourceThreadId: String?,
    val status: String,
    val selectedTab: String,
    val messagesSize: Int,
    val showReplyActions: Boolean,
    val replyRowsSize: Int,
    val firstReplyId8: String?,
    val firstReplyCount: Int,
    val firstReplyUnreadCount: Int
)

/**
 * Project a [RaftThreadScreenPayload] down to its render-axis signature.
 *
 * Members, settings, action menu state, realtime metadata, files
 * payload, and other non-render-axis fields are intentionally **not**
 * part of the signature, so changes to those fields do not invalidate
 * render-side `LaunchedEffect` / `remember` caches.
 *
 * The function is pure and side-effect free; it is safe to call from
 * any Composable and is covered by `ThreadRenderSignatureTest`.
 */
internal fun RaftThreadScreenPayload.toRenderSignature(): ThreadRenderSignature =
    toThreadTimelineSlice().toRenderSignature(
        instanceId = instanceId,
        sourceThreadId = sourceThreadId,
        selectedTab = selectedTab
    )

internal fun ThreadTimelineSlice.toRenderSignature(
    instanceId: String,
    sourceThreadId: String?,
    selectedTab: String
): ThreadRenderSignature {
    val firstReply: RaftThreadMessageItem? = messages.firstOrNull { it.replyCount > 0 }
    return ThreadRenderSignature(
        instanceId = instanceId,
        sourceThreadId = sourceThreadId,
        status = status,
        selectedTab = selectedTab,
        messagesSize = messages.size,
        showReplyActions = showReplyActions,
        replyRowsSize = messages.count { it.replyCount > 0 },
        firstReplyId8 = firstReply?.id?.take(8),
        firstReplyCount = firstReply?.replyCount ?: 0,
        firstReplyUnreadCount = firstReply?.unreadReplyCount ?: 0
    )
}
