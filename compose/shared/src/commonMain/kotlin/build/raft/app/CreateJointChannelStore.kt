package build.raft.app

import build.raft.app.data.ARCHIVED_NAME_COLLISION
import build.raft.app.data.CreateJointChannelOutcome
import build.raft.app.data.DataResult
import build.raft.app.data.JOINT_CHANNEL_FREE_LIMIT_REACHED
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_INVITEE_NOT_ADMIN
import build.raft.app.data.JOINT_INVITEE_NOT_FOUND
import build.raft.app.data.JOINT_INVITE_RATE_LIMITED
import build.raft.app.data.jointInviteRateLimitMinutes
import build.raft.app.data.jointRetryAfterSeconds
import build.raft.app.data.JOINT_INVITE_REQUIRED
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JOINT_TARGET_SERVER_INVALID
import build.raft.app.data.JointChannelAccepted
import build.raft.app.data.JointChannelErrorDetails
import build.raft.app.data.JointInviteTarget
import build.raft.app.data.JointSameAdminContext
import build.raft.app.data.JointSameAdminServer
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RealJointChannelRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.findSameAdminJointTarget
import build.raft.app.data.isSubmittableChannelName
import build.raft.app.data.parseJointInvitedPeopleText
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Web `maxJointInviteDrafts`: the creating server takes one of the 30 slots. */
internal const val CREATE_JOINT_MAX_INVITE_DRAFTS = JOINT_CHANNEL_MAX_SERVERS - 1

internal data class CreateJointInviteDraft(
    val id: Int,
    val targetServerSlug: String = "",
    val invitedPeopleText: String = "",
    /** Web `JointInviteDraft.sameAdminConfirmed`: the inviter confirmed connecting directly. */
    val sameAdminConfirmed: Boolean = false,
)

internal sealed interface CreateJointChannelError {
    data object NameInvalid : CreateJointChannelError
    data object InviteSlugRequired : CreateJointChannelError
    data object InviteePersonRequired : CreateJointChannelError

    /** Web `channel.jointInvite.sameAdmin.confirmRequired`: a same-admin draft is not confirmed. */
    data object SameAdminConfirmRequired : CreateJointChannelError

    /** A server rejection: the failed result plus its structured body (code, slug, invitee). */
    data class Server(val result: DataResult<*>, val details: JointChannelErrorDetails?) : CreateJointChannelError
}

internal data class CreateJointArchivedCollision(
    val archivedChannelId: ChannelId,
    val archivedChannelName: String,
    val canUnarchive: Boolean,
)

internal sealed interface CreateJointChannelEffect {
    /** Created, or the colliding archived joint channel was unarchived: open it. */
    data class Open(val channel: JointChannelAccepted) : CreateJointChannelEffect
}

internal data class CreateJointChannelState(
    val name: String = "",
    val description: String = "",
    val drafts: List<CreateJointInviteDraft> = listOf(CreateJointInviteDraft(id = 1)),
    val nextDraftId: Int = 2,
    val selectedAgentIds: Set<String> = emptySet(),
    val selectedUserIds: Set<String> = emptySet(),
    val submitting: Boolean = false,
    val error: CreateJointChannelError? = null,
    val collision: CreateJointArchivedCollision? = null,
    val effect: CreateJointChannelEffect? = null,
    /** The inviter's account, servers and roles; null until the session is known. */
    val sameAdminContext: JointSameAdminContext? = null,
) {
    /**
     * Web `sameAdminTargets` (RFC 075 §4.5): the server the inviter administers when this draft
     * invites themselves there; the draft then needs an explicit confirmation.
     */
    fun sameAdminTarget(draft: CreateJointInviteDraft): JointSameAdminServer? =
        findSameAdminJointTarget(sameAdminContext, draft.targetServerSlug, parseJointInvitedPeopleText(draft.invitedPeopleText))

    val canAddDraft: Boolean get() = drafts.size < CREATE_JOINT_MAX_INVITE_DRAFTS

    /** Web `billingError`: the failure is a plan limit, so the banner links to Billing. */
    val showsBillingLink: Boolean
        get() = (error as? CreateJointChannelError.Server)?.let(::createJointErrorIsBillingGate) == true
}

internal fun createJointErrorIsBillingGate(error: CreateJointChannelError.Server): Boolean {
    val code = error.details?.code
    if (code == JOINT_CHANNEL_FREE_LIMIT_REACHED || code == JOINT_FREE_SERVER_LIMIT) return true
    if (code != null) return false
    return error.serverMessage()?.contains("requires the Pro plan") == true
}

private fun CreateJointChannelError.Server.serverMessage(): String? =
    when (val failed = result) {
        is DataResult.Error -> failed.error.serverMessage
        is DataResult.NoPermission -> failed.blocker.serverMessage
        else -> null
    }

internal sealed interface CreateJointChannelIntent {
    data class SetName(val value: String) : CreateJointChannelIntent
    data class SetDescription(val value: String) : CreateJointChannelIntent
    data object AddDraft : CreateJointChannelIntent
    data class RemoveDraft(val id: Int) : CreateJointChannelIntent
    data class UpdateDraft(val id: Int, val targetServerSlug: String? = null, val invitedPeopleText: String? = null) :
        CreateJointChannelIntent
    data class ConfirmSameAdmin(val id: Int, val confirmed: Boolean) : CreateJointChannelIntent
    data class SetSameAdminContext(val context: JointSameAdminContext?) : CreateJointChannelIntent
    data class ToggleAgent(val id: String) : CreateJointChannelIntent
    data class ToggleUser(val id: String) : CreateJointChannelIntent
    data object Submit : CreateJointChannelIntent
    data object UnarchiveCollision : CreateJointChannelIntent
    data object ChangeCollidingName : CreateJointChannelIntent
    data object ConsumeEffect : CreateJointChannelIntent
}

internal interface CreateJointChannelDataSource {
    suspend fun create(
        name: String,
        description: String?,
        agentIds: List<String>,
        userIds: List<String>,
        invites: List<JointInviteTarget>,
    ): CreateJointChannelOutcome

    suspend fun unarchive(channelId: ChannelId): DataResult<JointChannelAccepted>
}

internal class NetworkCreateJointChannelDataSource(
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
) : CreateJointChannelDataSource {
    private val repository: RealJointChannelRepository
        get() = RealJointChannelRepository(networkClient) { logger("create_joint $it") }

    override suspend fun create(
        name: String,
        description: String?,
        agentIds: List<String>,
        userIds: List<String>,
        invites: List<JointInviteTarget>,
    ): CreateJointChannelOutcome = repository.createJointChannel(name, description, agentIds, userIds, invites)

    override suspend fun unarchive(channelId: ChannelId): DataResult<JointChannelAccepted> =
        repository.unarchiveChannel(channelId)
}

/**
 * MVI store of the Create Joint Channel dialog (web `CreateJointChannelDialog.tsx`): name,
 * description, one invite draft per server (up to 29), initial members of this server, and the
 * submit with the web's validation order and server-error handling. One submit at a time.
 */
internal class CreateJointChannelStore(
    private val dataSource: CreateJointChannelDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CreateJointChannelState())
    val state: StateFlow<CreateJointChannelState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun dispatch(intent: CreateJointChannelIntent) {
        val current = mutableState.value
        when (intent) {
            is CreateJointChannelIntent.SetName -> update(current.copy(name = intent.value))
            is CreateJointChannelIntent.SetDescription -> update(current.copy(description = intent.value))
            CreateJointChannelIntent.AddDraft -> {
                if (current.submitting || !current.canAddDraft) return
                // Web clears a submit error once the invite list it described changes.
                update(
                    current.copy(
                        drafts = current.drafts + CreateJointInviteDraft(id = current.nextDraftId),
                        nextDraftId = current.nextDraftId + 1,
                        error = null,
                    )
                )
            }
            is CreateJointChannelIntent.RemoveDraft -> {
                if (current.submitting || current.drafts.size <= 1) return
                update(current.copy(drafts = current.drafts.filterNot { it.id == intent.id }, error = null))
            }
            is CreateJointChannelIntent.UpdateDraft ->
                update(
                    current.copy(
                        drafts =
                            current.drafts.map { draft ->
                                if (draft.id != intent.id) {
                                    draft
                                } else {
                                    draft.copy(
                                        targetServerSlug = intent.targetServerSlug ?: draft.targetServerSlug,
                                        invitedPeopleText = intent.invitedPeopleText ?: draft.invitedPeopleText,
                                        // Web JointInviteDraftCard: editing either field un-confirms.
                                        sameAdminConfirmed = false,
                                    )
                                }
                            },
                        error = null,
                    )
                )
            is CreateJointChannelIntent.ConfirmSameAdmin -> {
                if (current.submitting) return
                update(
                    current.copy(
                        drafts = current.drafts.map { if (it.id == intent.id) it.copy(sameAdminConfirmed = intent.confirmed) else it },
                        error = null,
                    )
                )
            }
            is CreateJointChannelIntent.SetSameAdminContext -> update(current.copy(sameAdminContext = intent.context))
            is CreateJointChannelIntent.ToggleAgent ->
                update(current.copy(selectedAgentIds = current.selectedAgentIds.toggle(intent.id)))
            is CreateJointChannelIntent.ToggleUser ->
                update(current.copy(selectedUserIds = current.selectedUserIds.toggle(intent.id)))
            CreateJointChannelIntent.Submit -> submit(current)
            CreateJointChannelIntent.UnarchiveCollision -> unarchive(current)
            CreateJointChannelIntent.ChangeCollidingName -> update(current.copy(collision = null, name = ""))
            CreateJointChannelIntent.ConsumeEffect -> if (current.effect != null) update(current.copy(effect = null))
        }
    }

    private fun submit(current: CreateJointChannelState) {
        if (current.submitting) return
        val cleared = current.copy(error = null, collision = null)
        val name = current.name.trim()
        if (!name.isSubmittableChannelName()) {
            update(cleared.copy(error = CreateJointChannelError.NameInvalid))
            return
        }
        val sameAdminDrafts = current.drafts.filter { current.sameAdminTarget(it) != null }.mapTo(HashSet()) { it.id }
        val invites =
            current.drafts.map { draft ->
                JointInviteTarget(
                    targetServerSlug = draft.targetServerSlug.trim(),
                    invitedPeople = parseJointInvitedPeopleText(draft.invitedPeopleText),
                    sameAdmin = draft.id in sameAdminDrafts,
                )
            }
        val incomplete = invites.firstOrNull { it.targetServerSlug.isEmpty() || it.invitedPeople.isEmpty() }
        if (incomplete != null) {
            val error =
                if (incomplete.targetServerSlug.isEmpty()) {
                    CreateJointChannelError.InviteSlugRequired
                } else {
                    CreateJointChannelError.InviteePersonRequired
                }
            update(cleared.copy(error = error))
            return
        }
        if (current.drafts.any { it.id in sameAdminDrafts && !it.sameAdminConfirmed }) {
            update(cleared.copy(error = CreateJointChannelError.SameAdminConfirmRequired))
            return
        }
        update(cleared.copy(submitting = true))
        job =
            scope.launch {
                val outcome =
                    try {
                        dataSource.create(
                            name = name,
                            description = current.description.trim().takeIf(String::isNotEmpty),
                            agentIds = current.selectedAgentIds.toList(),
                            userIds = current.selectedUserIds.toList(),
                            invites = invites,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        CreateJointChannelOutcome.Failed(DataResult.Error(ScreenError("network_unavailable")), null)
                    }
                val latest = mutableState.value.copy(submitting = false)
                update(
                    when (outcome) {
                        is CreateJointChannelOutcome.Created -> latest.copy(effect = CreateJointChannelEffect.Open(outcome.channel))
                        is CreateJointChannelOutcome.ArchivedNameCollision ->
                            latest.copy(
                                collision =
                                    CreateJointArchivedCollision(
                                        archivedChannelId = outcome.archivedChannelId,
                                        archivedChannelName = outcome.archivedChannelName,
                                        canUnarchive = outcome.canUnarchive,
                                    )
                            )
                        is CreateJointChannelOutcome.Failed ->
                            latest.copy(error = CreateJointChannelError.Server(outcome.result, outcome.details))
                    }
                )
            }
    }

    private fun unarchive(current: CreateJointChannelState) {
        val collision = current.collision ?: return
        if (current.submitting || !collision.canUnarchive) return
        update(current.copy(submitting = true, error = null))
        job =
            scope.launch {
                val result =
                    try {
                        dataSource.unarchive(collision.archivedChannelId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("network_unavailable"))
                    }
                val latest = mutableState.value.copy(submitting = false)
                update(
                    when (result) {
                        is DataResult.Content -> latest.copy(collision = null, effect = CreateJointChannelEffect.Open(result.data))
                        else -> latest.copy(error = CreateJointChannelError.Server(result, null))
                    }
                )
            }
    }

    private fun update(next: CreateJointChannelState) {
        if (next != mutableState.value) mutableState.value = next
    }

    fun clear() {
        job?.cancel()
        job = null
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

/** Localized copy for each create failure (web CreateJointChannelDialog + `describeJointInviteError`). */
internal data class CreateJointErrorCopy(
    val nameInvalid: String,
    val slugRequired: String,
    val peopleRequired: String,
    val sameAdminConfirmRequired: String,
    val freeServerLimit: String,
    val serverLimit: String,
    val freeHostLimit: (String) -> String,
    val freeHostLimitUnnamed: String,
    val inviteeNotFound: (invitee: String, server: String) -> String,
    val inviteeNotAdmin: (invitee: String, server: String) -> String,
    val serverNotFound: (String) -> String,
    val cannotInviteSelf: String,
    val failedCreate: String,
    /** `joint_invite_rate_limited`: the wait in minutes, or null for "try again later". */
    val inviteRateLimited: (minutes: Int?) -> String,
)

internal fun createJointChannelErrorText(
    error: CreateJointChannelError,
    copy: CreateJointErrorCopy,
    currentServerSlug: String?,
    pipeline: (DataResult<*>) -> String?,
): String =
    when (error) {
        CreateJointChannelError.NameInvalid -> copy.nameInvalid
        CreateJointChannelError.InviteSlugRequired -> copy.slugRequired
        CreateJointChannelError.InviteePersonRequired -> copy.peopleRequired
        CreateJointChannelError.SameAdminConfirmRequired -> copy.sameAdminConfirmRequired
        is CreateJointChannelError.Server -> {
            val details = error.details
            val server = details?.targetServerSlug
            val invitee = details?.invitee
            val serverText = error.serverMessage()?.takeIf(String::isNotBlank)
            when (details?.code) {
                JOINT_CHANNEL_FREE_LIMIT_REACHED ->
                    details.blockingChannelName?.let(copy.freeHostLimit) ?: copy.freeHostLimitUnnamed
                JOINT_FREE_SERVER_LIMIT -> copy.freeServerLimit
                JOINT_SERVER_LIMIT -> copy.serverLimit
                JOINT_INVITEE_NOT_FOUND ->
                    if (server != null && invitee != null) copy.inviteeNotFound(invitee, server) else serverText ?: copy.failedCreate
                JOINT_INVITEE_NOT_ADMIN ->
                    if (server != null && invitee != null) copy.inviteeNotAdmin(invitee, server) else serverText ?: copy.failedCreate
                JOINT_TARGET_SERVER_INVALID ->
                    when {
                        server != null && server == currentServerSlug -> copy.cannotInviteSelf
                        server != null -> copy.serverNotFound(server)
                        else -> serverText ?: copy.failedCreate
                    }
                JOINT_INVITE_REQUIRED -> copy.peopleRequired
                JOINT_INVITE_RATE_LIMITED -> copy.inviteRateLimited(jointInviteRateLimitMinutes(error.result.jointRetryAfterSeconds()))
                ARCHIVED_NAME_COLLISION -> serverText ?: copy.failedCreate
                else -> serverText ?: pipeline(error.result)?.takeIf(String::isNotBlank) ?: copy.failedCreate
            }
        }
    }
