package build.raft.app

import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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

    /** The signed-in account's id, for a link that waits for another account; null when unknown. */
    fun currentAccountId(): String? = null

    /** The coordinator's readiness, or null while Home may not start (nothing advances). */
    fun readiness(): WindowNavigationReadiness?

    /** The conversation page on top of the shell (phone layout), or null (wide layout, none). */
    fun topPageRoute(): ThreadRouteIdentity?

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
 * pushed again.
 */
internal class RaftShellNavigationIntake(
    private val coordinator: SharedWindowNavigationCoordinator,
    private val inbox: RaftMessageLinkInbox = RaftMessageLinkInbox.Process,
    private val artifactInbox: RaftArtifactLinkInbox = RaftArtifactLinkInbox.Process,
    private val externalInviteInbox: RaftExternalInviteLinkInbox = RaftExternalInviteLinkInbox.Process,
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
            for (ignored in wakeups) {
                takeArtifactLink(host)?.let(host::openArtifactLink)
                takeExternalInviteLink(host)?.let(host::openExternalInviteLink)
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

    private suspend fun advance(host: RaftShellNavigationIntakeHost) {
        while (true) {
            val readiness = host.readiness() ?: return
            val action = coordinator.advance(readiness) ?: return
            if (
                action is WindowNavigationAction.OpenConversation &&
                raftWindowNavigationTargetIsTopPage(action.request, host.topPageRoute())
            ) {
                host.log(
                    "window_navigation_open_skipped reason=already_top_page source=${action.intent.source} " +
                        "threadId=${action.request.conversationIdentity.threadId.value}"
                )
                continue
            }
            host.perform(action)
        }
    }
}

/** The conversation [request] opens is the page already on top ([topPageRoute]): pushing it would stack it twice. */
internal fun raftWindowNavigationTargetIsTopPage(request: HomeMessageOpenRequest, topPageRoute: ThreadRouteIdentity?): Boolean {
    val top = topPageRoute ?: return false
    val target = request.conversationIdentity.resolveThreadRouteIdentity()
    if (target.instanceKey.isNotBlank() && target.instanceKey == top.instanceKey) return true
    val timeline = target.timelineChannelId
    return timeline != null && timeline == top.timelineChannelId
}
