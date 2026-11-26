package build.raft.app

import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.defaultNowMillis
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the app shell gives [RaftShellNavigationIntake]: its latest state, read when the intake
 * runs (not when the shell recomposes), and the actions only the shell can do.
 */
internal interface RaftShellNavigationIntakeHost {
    /** A pending message link may be taken: signed in, with a ready server ([raftMessageLinkShouldTake]). */
    fun messageLinkSessionReady(): Boolean

    /**
     * Resolves a taken link for this account: the intent to open, or null when the shell showed
     * something else instead ("Message not found", a public preview).
     */
    suspend fun resolveMessageLink(taken: RaftPendingMessageLink): WindowNavigationIntent?

    /** Opens a taken Artifact link ([RaftArtifactLinkInbox]) in the in-app viewer, over whatever is shown. */
    fun openArtifactLink(taken: RaftPendingArtifactLink)

    /** Opens a taken external invite link ([RaftExternalInviteLinkInbox]) on the invite page, over whatever is shown. */
    fun openExternalInviteLink(taken: RaftPendingExternalInviteLink) {}

    /** Opens a taken server join link ([RaftServerJoinLinkInbox]) on the join page, over whatever is shown. */
    fun openServerJoinLink(taken: RaftPendingExternalInviteLink) {}

    /** Opens a taken send-prompt link ([RaftSendPromptLinkInbox]) as the Send-to-agent sheet. */
    fun openSendPromptLink(taken: RaftPendingSendPromptLink) {}

    /** Opens a taken data-export deep link ([RaftDataExportLinkInbox]) via Data & privacy WebView or browser fallback. */
    suspend fun openDataExportLink(taken: RaftPendingDataExportLink) {}

    /** Opens the in-app Download Center ([SETTINGS_STORAGE_PAGE]) from a platform notification tap. */
    fun openDownloadCenter(taken: RaftPendingDownloadCenterLaunch) {}

    /** The signed-in account's id, for a link that waits for another account; null when unknown. */
    fun currentAccountId(): String? = null

    /** The hosting window's shell instance id, for launches targeted at a specific window; null when unknown. */
    fun shellInstanceId(): String? = null

    /** The coordinator's readiness, or null while Home may not start (nothing advances). */
    fun readiness(): WindowNavigationReadiness?

    /** The conversation page on top of the shell (phone layout), or null (wide layout, none). */
    fun topPageRoute(): ThreadRouteIdentity?

    /**
     * Single-shot top-page lookup with optional structured diagnostics for miss logging.
     * Defaults to [topPageRoute] so existing test hosts need no changes.
     */
    fun checkTopPage(): RaftNavigationTopPageCheck = RaftNavigationTopPageCheck(route = topPageRoute())

    /**
     * Focuses [request.anchorMessageId] in-place inside the conversation already on top of the
     * stack when an incoming open was skipped as `already_top_page`.
     */
    fun focusTopConversationMessage(request: HomeMessageOpenRequest, source: String) {}

    /**
     * Something is about to open: closes the window's task surfaces ([RaftIncomingNavigationSignal]),
     * waiting for a running first send, and says whether one was open over the top page.
     */
    suspend fun closeIncomingNavigationCover(): Boolean = false

    suspend fun perform(action: WindowNavigationAction)

    fun log(message: String)
}

/**
 * #4260: external message links ([RaftMessageLinkInbox]), external Artifact links
 * ([RaftArtifactLinkInbox], opened straight in the viewer) and notification opens are taken and
 * opened here, outside the app shell's composition. On a phone a conversation is its own page over
 * the shell, and a covered shell does not recompose, so an effect keyed on the pending link or a
 * notification tick waited until Back. This intake wakes on [enqueue] / [wake] and on every new
 * pending link instead, and opens through the window [coordinator] (its exactly-once marks
 * stay the only dedup of a request). An open whose conversation is already the top page is not
 * pushed again. Before anything opens, the window's task surfaces close
 * ([RaftShellNavigationIntakeHost.closeIncomingNavigationCover]); one that was open covered the
 * top page, so the open goes through even into the conversation under it (its message anchor).
 */
internal class RaftShellNavigationIntake(
    private val coordinator: SharedWindowNavigationCoordinator,
    private val inbox: RaftMessageLinkInbox = RaftMessageLinkInbox.Process,
    private val artifactInbox: RaftArtifactLinkInbox = RaftArtifactLinkInbox.Process,
    private val externalInviteInbox: RaftExternalInviteLinkInbox = RaftExternalInviteLinkInbox.Process,
    private val serverJoinInbox: RaftServerJoinLinkInbox = RaftServerJoinLinkInbox.Process,
    private val sendPromptInbox: RaftSendPromptLinkInbox = RaftSendPromptLinkInbox.Process,
    private val downloadCenterInbox: RaftDownloadCenterLaunchInbox = RaftDownloadCenterLaunchInbox.Process,
    private val dataExportInbox: RaftDataExportLinkInbox = RaftDataExportLinkInbox.Process,
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Queues [intent] (a notification, banner or shortcut open) and wakes the intake. */
    fun enqueue(intent: WindowNavigationIntent): Boolean = coordinator.enqueue(intent).also { wake() }

    /** Something the intake reads changed (session, Home, directory): try again. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /** Runs until cancelled (the shell's window goes away). */
    suspend fun run(host: RaftShellNavigationIntakeHost) {
        coroutineScope {
            launch { inbox.pending.collect { pending -> if (pending != null) wake() } }
            launch { artifactInbox.pending.collect { pending -> if (pending != null) wake() } }
            launch { externalInviteInbox.pending.collect { pending -> if (pending != null) wake() } }
            launch { serverJoinInbox.pending.collect { pending -> if (pending != null) wake() } }
            launch { sendPromptInbox.pending.collect { pending -> if (pending != null) wake() } }
            launch { downloadCenterInbox.pending.collect { pending -> if (pending != null) wake() } }
            launch { dataExportInbox.pending.collect { pending -> if (pending != null) wake() } }
            for (ignored in wakeups) {
                takeArtifactLink(host)?.let { taken ->
                    host.closeIncomingNavigationCover()
                    host.openArtifactLink(taken)
                }
                takeExternalInviteLink(host)?.let { taken ->
                    host.closeIncomingNavigationCover()
                    host.openExternalInviteLink(taken)
                }
                takeServerJoinLink(host)?.let { taken ->
                    host.closeIncomingNavigationCover()
                    host.openServerJoinLink(taken)
                }
                takeSendPromptLink(host)?.let { taken ->
                    host.closeIncomingNavigationCover()
                    host.openSendPromptLink(taken)
                }
                takeDataExportLink(host)?.let { taken ->
                    host.closeIncomingNavigationCover()
                    launch { host.openDataExportLink(taken) }
                }
                takeDownloadCenterLaunch(host)?.let { taken ->
                    host.closeIncomingNavigationCover()
                    host.openDownloadCenter(taken)
                }
                takeMessageLink(host)?.let { taken ->
                    launch {
                        val intent = host.resolveMessageLink(taken) ?: return@launch
                        val enqueued = enqueue(intent)
                        host.log(
                            "window_navigation_received source=${intent.source} enqueued=$enqueued " +
                                "targetServerId=${intent.serverId}"
                        )
                    }
                }
                advance(host)
            }
        }
    }

    private fun takeMessageLink(host: RaftShellNavigationIntakeHost): RaftPendingMessageLink? {
        val pending = inbox.pending.value
        if (!raftMessageLinkShouldTake(pending, host.messageLinkSessionReady())) return null
        val taken = inbox.take(pending!!.id)
        if (taken == null) {
            host.log("message_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("message_link_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private fun takeArtifactLink(host: RaftShellNavigationIntakeHost): RaftPendingArtifactLink? {
        val pending = artifactInbox.pending.value ?: return null
        if (!host.messageLinkSessionReady()) return null
        val taken = artifactInbox.take(pending.id)
        if (taken == null) {
            host.log("artifact_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("artifact_link_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private fun takeExternalInviteLink(host: RaftShellNavigationIntakeHost): RaftPendingExternalInviteLink? {
        val pending = externalInviteInbox.pending.value ?: return null
        if (!host.messageLinkSessionReady()) return null
        // Switch account: still the account it isn't for (signing out is under way); keep waiting.
        val account = host.currentAccountId()
        if (pending.notForAccountId != null && (account == null || account == pending.notForAccountId)) return null
        val taken = externalInviteInbox.take(pending.id)
        if (taken == null) {
            host.log("external_invite_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("external_invite_link_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private fun takeServerJoinLink(host: RaftShellNavigationIntakeHost): RaftPendingExternalInviteLink? {
        val pending = serverJoinInbox.pending.value ?: return null
        if (!host.messageLinkSessionReady()) return null
        val taken = serverJoinInbox.take(pending.id)
        if (taken == null) {
            host.log("server_join_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("server_join_link_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private fun takeSendPromptLink(host: RaftShellNavigationIntakeHost): RaftPendingSendPromptLink? {
        val pending = sendPromptInbox.pending.value ?: return null
        if (!host.messageLinkSessionReady()) return null
        val taken = sendPromptInbox.take(pending.id)
        if (taken == null) {
            host.log("send_prompt_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("send_prompt_link_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private fun takeDataExportLink(host: RaftShellNavigationIntakeHost): RaftPendingDataExportLink? {
        val pending = dataExportInbox.pending.value ?: return null
        if (!host.messageLinkSessionReady()) return null
        val taken = dataExportInbox.take(pending.id)
        if (taken == null) {
            host.log("data_export_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("data_export_link_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private fun takeDownloadCenterLaunch(host: RaftShellNavigationIntakeHost): RaftPendingDownloadCenterLaunch? {
        val pending = downloadCenterInbox.pending.value ?: return null
        if (!host.messageLinkSessionReady()) return null
        val hostShellId = host.shellInstanceId()?.trim()?.takeIf(String::isNotEmpty)
        if (pending.targetShellInstanceId != null && pending.targetShellInstanceId != hostShellId) {
            return null
        }
        val taken = downloadCenterInbox.take(pending.id, hostShellId)
        if (taken == null) {
            host.log("download_center_launch_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return null
        }
        host.log("download_center_launch_take id=${taken.id} source=${taken.source}")
        return taken
    }

    private suspend fun advance(host: RaftShellNavigationIntakeHost) {
        while (true) {
            val readiness = host.readiness() ?: return
            val action = coordinator.advance(readiness) ?: return
            // A task surface is a window over the top page, not a page: it closes first, and the
            // page under it is not what the reader sees, so a link into it still opens (its anchor).
            val covered =
                (action is WindowNavigationAction.OpenConversation || action is WindowNavigationAction.Reject) &&
                    host.closeIncomingNavigationCover()
            val topPageCheck =
                if (action is WindowNavigationAction.OpenConversation && !covered) {
                    host.checkTopPage()
                } else {
                    null
                }
            if (
                action is WindowNavigationAction.OpenConversation &&
                !covered &&
                raftWindowNavigationTargetIsTopPage(action.request, topPageCheck?.route)
            ) {
                host.log(
                    "window_navigation_open_skipped reason=already_top_page source=${action.intent.source} " +
                        "threadId=${action.request.conversationIdentity.threadId.value}"
                )
                if (!action.request.anchorMessageId.isNullOrBlank()) {
                    host.focusTopConversationMessage(action.request, action.intent.source)
                }
                continue
            }
            if (action is WindowNavigationAction.OpenConversation) {
                val targetRoute = action.request.conversationIdentity.resolveThreadRouteIdentity()
                val detail = topPageCheck?.diagnostics?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
                host.log(
                    "window_navigation_top_page_miss source=${action.intent.source} covered=$covered " +
                        "targetInstance=${targetRoute.instanceKey} " +
                        "targetTimeline=${targetRoute.timelineChannelId.orEmpty()} " +
                        "topInstance=${topPageCheck?.route?.instanceKey.orEmpty()} " +
                        "topTimeline=${topPageCheck?.route?.timelineChannelId.orEmpty()}$detail"
                )
            }
            host.perform(action)
        }
    }
}

/**
 * Whether [candidate] and [target] identify the same conversation surface (instance key,
 * canonical timeline channel id, or reply-thread parent key for unresolved 0-reply threads).
 */
internal fun raftThreadRoutesMatch(
    candidate: ThreadRouteIdentity?,
    target: ThreadRouteIdentity?,
): Boolean {
    if (candidate == null || target == null) return false
    if (target.instanceKey.isNotBlank() && target.instanceKey == candidate.instanceKey) return true
    val timeline = target.timelineChannelId
    if (timeline != null && timeline == candidate.timelineChannelId) return true
    return target.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        candidate.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        !target.parentKey.isNullOrBlank() &&
        target.parentKey == candidate.parentKey
}

/** The conversation [request] opens is the page already on top ([topPageRoute]): pushing it would stack it twice. */
internal fun raftWindowNavigationTargetIsTopPage(request: HomeMessageOpenRequest, topPageRoute: ThreadRouteIdentity?): Boolean =
    raftThreadRoutesMatch(
        candidate = topPageRoute,
        target = request.conversationIdentity.resolveThreadRouteIdentity(),
    )

/**
 * Conversation route of a host's authoritative top page ([RaftCmpNavigator.topPage]), or null when
 * the top page is not a conversation page (`THREAD_PAGE`) or carries no valid thread route.
 */
internal fun raftTopPageThreadRouteOrNull(topPage: RaftCmpPageArgs): ThreadRouteIdentity? {
    if (topPage.pageName != THREAD_PAGE) return null
    val payloadText = (topPage.pageData["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (payloadText.isNullOrBlank()) return null
    val instanceId = (topPage.pageData["instanceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return parseThreadLaunchPayload(payloadText, instanceId)?.resolveThreadRouteIdentity()
}

/** Result of [RaftShellNavigationIntakeHost.checkTopPage], carrying both the resolved route and miss diagnostics. */
internal data class RaftNavigationTopPageCheck(
    val route: ThreadRouteIdentity?,
    val diagnostics: String = "",
)

/**
 * Resolves the single-pane top conversation route for [RaftShellNavigationIntake]:
 * - Active wide split-pane layout ([wideSplitPaneActive] == true, i.e., desktop mode enabled AND
 *   the window is currently in [RaftAdaptivePaneLayout.DualPane]) returns null because the split
 *   pane updates its detail slot in place. Never pass the raw user preference toggle alone: it
 *   defaults to true on compact phones.
 * - When the host reports its authoritative top stack page ([hostTopPage] != null), use it
 *   exclusively — surviving app backgrounding, native pops, and cancelled interactive swipes,
 *   and never falling back to a stale [fallbackVisibleRoute] when a non-conversation page sits on top.
 * - Fall back to [fallbackVisibleRoute] only when the host does not report [hostTopPage].
 */
internal fun raftResolveNavigationTopPageRoute(
    wideSplitPaneActive: Boolean,
    hostTopPage: RaftCmpPageArgs?,
    fallbackVisibleRoute: () -> ThreadRouteIdentity?,
): ThreadRouteIdentity? =
    when {
        wideSplitPaneActive -> null
        hostTopPage != null -> raftTopPageThreadRouteOrNull(hostTopPage)
        else -> fallbackVisibleRoute()
    }

/** One-shot request to focus [messageId] inside the conversation already sitting on top of the stack. */
internal data class RaftTopPageConversationFocusRequest(
    val nonce: Long,
    val targetRoute: ThreadRouteIdentity,
    val messageId: String,
    val source: String,
)

/** Outcome of [RaftTopPageConversationFocusSignal.dispatch]. */
internal data class RaftTopPageFocusDispatchResult(
    val request: RaftTopPageConversationFocusRequest,
    val deliveredImmediately: Boolean,
)

/**
 * Shell-scoped signal (owned by [SharedForegroundBannerStore]) that routes an in-place message
 * focus from [RaftShellNavigationIntake] (`already_top_page`) to the live standalone [ThreadPage]
 * already mounted on top of the stack.
 *
 * - Delivers immediately to the topmost matching registered standalone page (even while the app is
 *   returning from background and Compose `active` has not flipped back to `true` yet).
 * - If the matching top page was just pushed onto the host stack and has not run its first
 *   `DisposableEffect` yet, holds a single short-lived, TTL-bounded pending request that is
 *   consumed atomically at most once when that matching page registers — and cleared on any fresh
 *   conversation open or session reset so stale requests never replay into unrelated or future opens.
 */
internal class RaftTopPageConversationFocusSignal(
    private val nowEpochMillis: () -> Long = ::defaultNowMillis,
    private val pendingTtlMillis: Long = DefaultPendingTtlMillis,
) {
    fun interface Target {
        /** Accepts [request] and dispatches the in-place jump/focus; returns true if handled. */
        fun focusMessage(request: RaftTopPageConversationFocusRequest): Boolean
    }

    private class Entry(
        val routeProvider: () -> ThreadRouteIdentity,
        val target: Target,
    )

    private data class PendingEntry(
        val request: RaftTopPageConversationFocusRequest,
        val expiresAtEpochMillis: Long,
    )

    private val lock = SynchronizedObject()
    private val entries = LinkedHashSet<Entry>()
    private var entriesVersion = 0L
    private var nextNonce = 0L
    private var pending: PendingEntry? = null

    /** Registers a mounted standalone conversation target until the returned disposer runs. */
    fun register(
        routeProvider: () -> ThreadRouteIdentity,
        target: Target,
    ): () -> Unit {
        val entry = Entry(routeProvider, target)
        val pendingToDeliver =
            synchronized(lock) {
                entries.add(entry)
                entriesVersion += 1L
                takeMatchingPendingLocked(routeProvider())
            }
        if (pendingToDeliver != null) {
            target.focusMessage(pendingToDeliver)
        }
        return {
            synchronized(lock) {
                entries.remove(entry)
            }
        }
    }

    /**
     * Dispatches an in-place focus for [messageId] to the topmost mounted target matching
     * [targetRoute], or stages a short-lived one-shot pending request if the pushed top page has
     * not registered yet. Returns null when [messageId] is blank.
     */
    fun dispatch(
        targetRoute: ThreadRouteIdentity,
        messageId: String,
        source: String,
    ): RaftTopPageFocusDispatchResult? {
        val normalizedMessageId = messageId.trim().takeIf(String::isNotBlank) ?: return null
        val (request, initialCandidates, initialVersion) =
            synchronized(lock) {
                val created =
                    RaftTopPageConversationFocusRequest(
                        nonce = ++nextNonce,
                        targetRoute = targetRoute,
                        messageId = normalizedMessageId,
                        source = source,
                    )
                pending = null
                Triple(created, entries.toList().asReversed(), entriesVersion)
            }
        if (deliverToFirstMatching(initialCandidates, targetRoute, request)) {
            return RaftTopPageFocusDispatchResult(request = request, deliveredImmediately = true)
        }
        var seenVersion = initialVersion
        val tried = initialCandidates.toHashSet()
        while (true) {
            val nextBatch =
                synchronized(lock) {
                    if (nextNonce != request.nonce) {
                        // A newer dispatch superseded this one.
                        return RaftTopPageFocusDispatchResult(request = request, deliveredImmediately = false)
                    }
                    if (entriesVersion == seenVersion) {
                        pending =
                            PendingEntry(
                                request = request,
                                expiresAtEpochMillis = nowEpochMillis() + pendingTtlMillis,
                            )
                        null
                    } else {
                        seenVersion = entriesVersion
                        entries.toList().asReversed().filter { it !in tried }
                    }
                } ?: return RaftTopPageFocusDispatchResult(request = request, deliveredImmediately = false)
            tried.addAll(nextBatch)
            if (deliverToFirstMatching(nextBatch, targetRoute, request)) {
                return RaftTopPageFocusDispatchResult(request = request, deliveredImmediately = true)
            }
        }
    }

    /** Drops any unconsumed mounting-window pending request (e.g. before opening a fresh page). */
    fun clearPending() {
        synchronized(lock) {
            pending = null
        }
    }

    /** Clears pending state on shell/session reset. Mounted entries clean up via their disposers. */
    fun clear() {
        clearPending()
    }

    private fun deliverToFirstMatching(
        candidates: List<Entry>,
        targetRoute: ThreadRouteIdentity,
        request: RaftTopPageConversationFocusRequest,
    ): Boolean {
        for (entry in candidates) {
            if (!raftThreadRoutesMatch(entry.routeProvider(), targetRoute)) continue
            if (entry.target.focusMessage(request)) return true
        }
        return false
    }

    private fun takeMatchingPendingLocked(candidateRoute: ThreadRouteIdentity): RaftTopPageConversationFocusRequest? {
        val current = pending ?: return null
        if (nowEpochMillis() > current.expiresAtEpochMillis) {
            pending = null
            return null
        }
        if (!raftThreadRoutesMatch(candidateRoute, current.request.targetRoute)) {
            return null
        }
        pending = null
        return current.request
    }

    companion object {
        internal const val DefaultPendingTtlMillis: Long = 5_000L
    }
}

/**
 * "Something is about to open in this window" (a message link, a notification or banner open, an
 * app shortcut, an Artifact / invite / join link), one per window ([SharedForegroundBannerStore]).
 * A task opened without a page of its own ([TaskOpenSurface]: a pending task's empty conversation,
 * a legacy panel) is a window dialog over its host page, not a page of the stack, so
 * [RaftShellNavigationIntake] pushed under it, or skipped a link into the conversation under it
 * as "already the top page". Open surfaces register here; [closeAll] closes them before the open.
 */
internal class RaftIncomingNavigationSignal {
    fun interface Surface {
        /** Closes this surface if it is open (it may wait for a running send first); true when it was. */
        suspend fun closeForIncomingNavigation(): Boolean
    }

    private class Entry(val surface: Surface)

    private val lock = SynchronizedObject()
    private val entries = LinkedHashSet<Entry>()

    /** Adds [surface] until the returned function is called. */
    fun register(surface: Surface): () -> Unit {
        val entry = Entry(surface)
        synchronized(lock) { entries.add(entry) }
        return { synchronized(lock) { entries.remove(entry) } }
    }

    /** Closes every registered surface; true when any was open. */
    suspend fun closeAll(): Boolean {
        val current = synchronized(lock) { entries.toList() }
        var anyOpen = false
        for (entry in current) {
            if (entry.surface.closeForIncomingNavigation()) anyOpen = true
        }
        return anyOpen
    }
}
