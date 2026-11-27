package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.thread.toThreadLaunchConversationIdentity
import build.raft.app.toThreadLaunchConversationIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * A person or agent in Search's results opens the DM with them, as on the web
 * (botiverse/slock staging `useSearchResultActions` `openEntityResult` / `openEntityResultInChat`:
 * `openDM(agentId)` / `openUserDM(userId)`), creating it when Home lists none. When the DM can't
 * be opened (refused, failed, no permission) the member's profile opens instead, as the web falls
 * back to the agent / person page. A member whose DM Home already lists carries that DM's identity
 * from the start ([homeSearchEntityCandidates]) and opens it without a request.
 */

/**
 * The DM opens outlive the Search that started them: the wide panel closes as a row opens.
 * Immediate, so a DM Home already lists opens in the same frame as the tap.
 */
internal val homeSearchMemberOpenScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

/** What a person or agent result opened. */
internal enum class HomeSearchMemberOpenOutcome {
    /** Not a person or agent waiting on a DM: opened as it is (a channel, a DM, a computer). */
    Direct,

    /** The DM with them. */
    DirectMessage,

    /** Their profile: the DM could not be opened. */
    ProfileFallback,

    /** Nothing: a tap before this one is still opening the same DM (that tap opens it). */
    AlreadyOpening,
}

/** This person or agent result, opening [conversation] (their DM) instead of the profile. */
internal fun SearchEntityResult.withDirectMessage(conversation: ThreadLaunchConversationIdentity): SearchEntityResult =
    copy(threadId = conversation.threadId.value, conversationIdentity = conversation)

/**
 * Opens [entity]: a person or agent result without a DM asks [openDirectMessage] for it and opens
 * the DM, else the profile ([open] with the result as it is). [open] returns whether a page or
 * pane opened; the outcome says which, or [HomeSearchMemberOpenOutcome.AlreadyOpening].
 */
internal suspend fun openHomeSearchEntityWithDirectMessage(
    entity: SearchEntityResult,
    openDirectMessage: suspend (MemberDetailIdentity) -> DataResult<OpenDirectMessageResult>?,
    open: (SearchEntityResult) -> Boolean,
    log: (String) -> Unit = {},
): Pair<HomeSearchMemberOpenOutcome, Boolean> {
    val member =
        entity.destinationOrNull() as? HomeSearchEntityDestination.Member
            ?: return HomeSearchMemberOpenOutcome.Direct to open(entity)
    val identity = member.identity
    val result =
        openDirectMessage(identity) ?: run {
            log("search_member_dm_open_pending kind=${identity.kind} memberId=${identity.memberId.value}")
            return HomeSearchMemberOpenOutcome.AlreadyOpening to false
        }
    if (result is DataResult.Content) {
        val conversation = result.data.conversation.toThreadLaunchConversationIdentity()
        log("search_member_dm_open kind=${identity.kind} memberId=${identity.memberId.value} threadId=${conversation.threadId.value}")
        return HomeSearchMemberOpenOutcome.DirectMessage to open(entity.withDirectMessage(conversation))
    }
    val reason =
        when (result) {
            is DataResult.Empty -> "empty"
            is DataResult.Error -> "error code=${result.error.code} traceId=${result.error.traceId.orEmpty()}"
            is DataResult.NoPermission -> "no_permission code=${result.blocker.code}"
            is DataResult.Content -> "content"
        }
    log("search_member_dm_open_fallback_profile kind=${identity.kind} memberId=${identity.memberId.value} reason=$reason")
    return HomeSearchMemberOpenOutcome.ProfileFallback to open(entity)
}
