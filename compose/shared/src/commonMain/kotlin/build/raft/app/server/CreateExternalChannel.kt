package build.raft.app

import build.raft.app.data.ChannelRepository
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInvitesApi
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SendExternalInvitesResult
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.failureTraceIdOrNull
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Create Channel's External choice (web `components/channel/CreateChannelDialog.tsx`, design v5.0
 * E1, botiverse/slock staging 97a3e293d71a): step 2 takes emails and "Your servers" beside the
 * members; Create and Invite checks the emails (`POST channels/external-invites/preview`, no
 * channel yet) and shows the review; only the review's confirm creates the channel, private, then
 * sends the invites to it and connects the servers. A send that fails after the create keeps the
 * channel: a retry sends to it again, and leaving goes to it.
 */

/** Web `useExternalChannelAvailability`: `freeLimit` when the Free plan's one external channel is taken. */
internal enum class ExternalChannelCreateAvailability { Loading, Open, FreeLimit }

/** Web `useExternalChannelCreateAccess`: External is offered to the current server's owners and admins. */
internal fun externalChannelCreateAccess(serverRole: String?): Boolean =
    serverRole == "owner" || serverRole == "admin"

/** Where the External section's create entries (Home's "+") lead. */
internal enum class ExternalCreateEntry {
    /** Create Channel with External chosen. */
    CreateChannel,

    /** The Free plan's one external channel is taken: the upgrade instead (web `externalChannelSlot.ts`). */
    FreeLimit,
}

/**
 * Web `useExternalLimitGate` (surface `sidebar`) behind the External section's create entries:
 * Create Channel with External chosen, the one way to make a channel with another server; only a
 * Free server whose one external channel is taken gets [ExternalCreateEntry.FreeLimit] instead.
 * Only a Free [plan] reads the availability; a failed read is Create Channel, which reads it again
 * itself (and the send is refused by the same rule).
 */
internal suspend fun externalCreateEntry(plan: String?, dataSource: CreateExternalChannelDataSource): ExternalCreateEntry =
    if (plan == "free" && dataSource.freeLimitReached() == true) ExternalCreateEntry.FreeLimit else ExternalCreateEntry.CreateChannel

/** The new channel's own fields, as step 1 and step 2 left them. */
internal data class CreateExternalChannelDraft(
    val name: String,
    val description: String?,
    val agentIds: List<String>,
    val userIds: List<String>,
)

internal sealed interface CreateExternalChannelEffect {
    /** The create itself was refused: step 1 (an archived-name collision) or step 2 says why. */
    data class CreateFailed(val result: DataResult<CreateChannelResult>) : CreateExternalChannelEffect

    /** The invites' and servers' toasts (the invite sheet's own words). */
    data class Toast(val effect: ExternalInvitesEffect) : CreateExternalChannelEffect

    /** Done, or left after the create: the dialog closes onto the channel. */
    data class Finished(val channel: CreateChannelResult) : CreateExternalChannelEffect
}

internal data class CreateExternalChannelState(
    val availability: ExternalChannelCreateAvailability = ExternalChannelCreateAvailability.Loading,
    /** The emails, "Your servers", and the review once it is shown (the invite sheet's state). */
    val invite: ExternalInviteDialogState = ExternalInviteDialogState(),
    /** The channel the review's confirm created; the invite steps go on for it. */
    val created: CreateChannelResult? = null,
    /** True once a post-create send failed and left the review open to retry for the created channel. */
    val retryAfterCreate: Boolean = false,
    val effects: List<CreateExternalChannelEffect> = emptyList(),
) {
    /**
     * Web `ExternalInviteSteps`: Back stays visible (disabled while busy) during the initial commit
     * until a post-create send failure leaves the review open in retry mode, after which Skip is
     * shown instead.
     */
    val canReturnToStep2: Boolean
        get() = created == null || (invite.busy && !retryAfterCreate)

    /**
     * Web `createdExternal && !externalCommitting` (slock #15136): "created, but the invites weren't
     * sent" only once a send after the create came back unsent and the review stayed open; not while
     * Create and Invite is still sending, nor once it finished (the review closes onto the channel).
     */
    val showCreatedNotice: Boolean
        get() = created != null && !invite.busy && effects.none { it is CreateExternalChannelEffect.Finished }

    /** Web `emailsBlocked`: step 2's Create and Invite needs an email or a server, and 20 at most. */
    val canReview: Boolean
        get() = !invite.busy && (invite.entries.isNotEmpty() || invite.chosenServers.isNotEmpty()) && invite.overBy <= 0
}

internal interface CreateExternalChannelDataSource {
    /** `GET channels/external-availability`; null when it couldn't be read. */
    suspend fun freeLimitReached(): Boolean?

    /** "Your servers"; null when they couldn't be read. */
    suspend fun ownServers(): List<InviteConnectableServer>?

    /** `POST channels/external-invites/preview`: the current server's review, no channel yet. */
    suspend fun previewForNewChannel(emails: List<String>): DataResult<ExternalInviteAnswer<ExternalInvitePreview>>

    suspend fun create(request: CreateChannelRequest): DataResult<CreateChannelResult>

    suspend fun send(
        channelId: ChannelId,
        emails: List<String>,
        connectMemberEmails: List<String>,
    ): DataResult<ExternalInviteAnswer<SendExternalInvitesResult>>

    suspend fun connect(channelId: ChannelId, serverIds: List<String>): DataResult<ExternalInviteAnswer<List<ServerConnectOutcome>>>
}

internal class RealCreateExternalChannelDataSource(
    private val networkClient: RaftNetworkClient,
    private val channelRepository: ChannelRepository,
    private val logger: (String) -> Unit = {},
) : CreateExternalChannelDataSource {
    private val api get() = ExternalInvitesApi(networkClient) { logger("create_external_channel $it") }

    override suspend fun freeLimitReached(): Boolean? =
        ((api.externalAvailability() as? DataResult.Content)?.data as? ExternalInviteAnswer.Ok)?.value?.freeLimitReached

    override suspend fun ownServers(): List<InviteConnectableServer>? =
        ((api.ownConnectableServers() as? DataResult.Content)?.data as? ExternalInviteAnswer.Ok)?.value?.servers

    override suspend fun previewForNewChannel(emails: List<String>) = api.previewForNewChannel(emails)

    override suspend fun create(request: CreateChannelRequest) = channelRepository.createChannel(request)

    // A new channel is created private: the send names no makePrivate (web `commitExternal`).
    override suspend fun send(channelId: ChannelId, emails: List<String>, connectMemberEmails: List<String>) =
        api.send(channelId, emails, makePrivate = false, connectInviteMemberEmails = connectMemberEmails)

    override suspend fun connect(channelId: ChannelId, serverIds: List<String>) = api.connectOwnServers(channelId, serverIds, makePrivate = false)
}

internal sealed interface CreateExternalChannelIntent {
    /** External offered: read whether it can be chosen (web `useExternalChannelAvailability`). */
    data object Start : CreateExternalChannelIntent

    /** External chosen: "Your servers", once (web `useOwnConnectableServers(external)`). */
    data object LoadServers : CreateExternalChannelIntent

    /** Signed-in viewer's email, so older servers without `preview.self` still mark it "That's you". */
    data class SetOwnEmail(val email: String?) : CreateExternalChannelIntent

    data class SetText(val text: String) : CreateExternalChannelIntent

    data class SetServers(val serverIds: Set<String>) : CreateExternalChannelIntent

    /** Step 2's Create and Invite: the review, nothing created. */
    data object Review : CreateExternalChannelIntent

    /** The review's Back, before the create: step 2 as it was. */
    data object BackToPeople : CreateExternalChannelIntent

    /** The review's confirm. */
    data class Commit(val draft: CreateExternalChannelDraft) : CreateExternalChannelIntent

    /** The review's Skip / close: after the create, onto the channel. */
    data object Leave : CreateExternalChannelIntent

    data class ConsumeEffect(val effect: CreateExternalChannelEffect) : CreateExternalChannelIntent
}

internal class CreateExternalChannelStore(
    private val scope: CoroutineScope,
    private val dataSource: CreateExternalChannelDataSource,
) {
    private val mutableState = MutableStateFlow(CreateExternalChannelState())
    val state: StateFlow<CreateExternalChannelState> = mutableState.asStateFlow()
    private var started = false
    private var serversRead = false

    fun dispatch(intent: CreateExternalChannelIntent) {
        when (intent) {
            CreateExternalChannelIntent.Start -> start()
            CreateExternalChannelIntent.LoadServers -> loadServers()
            is CreateExternalChannelIntent.SetOwnEmail ->
                updateInvite { it.copy(ownEmail = intent.email?.trim()?.takeIf(String::isNotEmpty)) }
            is CreateExternalChannelIntent.SetText -> updateInvite { if (it.busy) it else it.copy(text = intent.text, failure = null) }
            is CreateExternalChannelIntent.SetServers -> updateInvite { if (it.busy) it else it.copy(serverIds = intent.serverIds) }
            CreateExternalChannelIntent.Review -> review()
            CreateExternalChannelIntent.BackToPeople ->
                mutableState.update {
                    if (it.invite.busy || it.created != null) it else it.copy(invite = it.invite.copy(preview = null, failure = null))
                }
            is CreateExternalChannelIntent.Commit -> commit(intent.draft)
            CreateExternalChannelIntent.Leave -> {
                val state = mutableState.value
                if (!state.invite.busy) state.created?.let { emit(CreateExternalChannelEffect.Finished(it)) }
            }
            is CreateExternalChannelIntent.ConsumeEffect -> mutableState.update { it.copy(effects = it.effects - intent.effect) }
        }
    }

    private fun updateInvite(transform: (ExternalInviteDialogState) -> ExternalInviteDialogState) {
        mutableState.update { it.copy(invite = transform(it.invite)) }
    }

    private fun emit(vararg effects: CreateExternalChannelEffect) {
        mutableState.update { it.copy(effects = it.effects + effects) }
    }

    /** Web: a failed availability read is `open` (the send refuses with the same rule); a failed servers read, none. */
    private fun start() {
        if (started) return
        started = true
        scope.launch {
            val reached = dataSource.freeLimitReached()
            mutableState.update {
                it.copy(availability = if (reached == true) ExternalChannelCreateAvailability.FreeLimit else ExternalChannelCreateAvailability.Open)
            }
        }
    }

    private fun loadServers() {
        if (serversRead) return
        serversRead = true
        scope.launch {
            val servers = dataSource.ownServers().orEmpty()
            updateInvite { it.copy(servers = servers) }
        }
    }

    private fun review() {
        val state = mutableState.value
        if (!state.canReview) return
        val entries = state.invite.entries
        // Only servers: nothing to classify.
        if (entries.isEmpty()) {
            updateInvite { it.copy(preview = NoEmailsExternalInvitePreview, failure = null) }
            return
        }
        updateInvite { it.copy(busy = true, failure = null) }
        scope.launch {
            val result = dataSource.previewForNewChannel(entries)
            val answer = (result as? DataResult.Content)?.data
            updateInvite { current ->
                when (answer) {
                    is ExternalInviteAnswer.Ok -> current.copy(busy = false, preview = answer.value)
                    is ExternalInviteAnswer.Refused -> current.copy(busy = false, failure = answer.refusal.toFailure())
                    null -> current.copy(busy = false, failure = result.externalInviteFailureOf())
                }
            }
        }
    }

    private fun commit(draft: CreateExternalChannelDraft) {
        val state = mutableState.value
        val invite = state.invite
        val preview = invite.preview ?: return
        if (!invite.canSend) return
        val addresses = invite.sendAddresses
        val connectMemberEmails = invite.connectMemberEmails
        val chosen = invite.chosenServers
        updateInvite { it.copy(busy = true, failure = null) }
        scope.launch {
            val channel =
                state.created
                    ?: when (
                        val result =
                            dataSource.create(
                                CreateChannelRequest(
                                    name = draft.name.trim(),
                                    description = draft.description?.trim()?.takeIf(String::isNotBlank),
                                    visibility = "private",
                                    agentMemberIds = draft.agentIds,
                                    userMemberIds = draft.userIds,
                                )
                            )
                    ) {
                        is DataResult.Content -> result.data.also { created -> mutableState.update { it.copy(created = created) } }
                        else -> {
                            // Web handleCreateError: back to step 2 (or step 1 for the name).
                            mutableState.update { it.copy(invite = it.invite.copy(busy = false, preview = null)) }
                            emit(CreateExternalChannelEffect.CreateFailed(result))
                            return@launch
                        }
                    }
            val channelId = channel.channel.channelId
            var sent = 0
            var added = 0
            if (addresses.isNotEmpty()) {
                val result = dataSource.send(channelId, addresses, connectMemberEmails)
                when (val answer = (result as? DataResult.Content)?.data) {
                    is ExternalInviteAnswer.Ok -> {
                        sent = answer.value.sent.size
                        added = answer.value.added.size
                    }
                    // The channel stays: the review says it was created, and a retry sends to it.
                    is ExternalInviteAnswer.Refused -> {
                        mutableState.update {
                            it.copy(
                                retryAfterCreate = true,
                                invite = it.invite.copy(busy = false, failure = answer.refusal.toFailure()),
                            )
                        }
                        return@launch
                    }
                    null -> {
                        mutableState.update {
                            it.copy(
                                retryAfterCreate = true,
                                invite = it.invite.copy(busy = false, failure = result.externalInviteFailureOf()),
                            )
                        }
                        return@launch
                    }
                }
            }
            val serverEffects =
                if (chosen.isEmpty()) {
                    emptyList()
                } else {
                    val result = dataSource.connect(channelId, chosen.map { it.id })
                    when (val answer = (result as? DataResult.Content)?.data) {
                        is ExternalInviteAnswer.Ok -> externalInviteServerEffects(answer.value, chosen, answer.traceId)
                        is ExternalInviteAnswer.Refused -> {
                            // Web `sendInvitesAndConnectServers` / `ExternalInviteSteps`: a failed
                            // connect finishes onto the created channel and toasts ServersNotConnected
                            // (carrying refusal detail in description), rather than keeping the review
                            // open with a misleading "invites weren't sent" banner.
                            val failure = answer.refusal.toFailure()
                            val detail = failure.takeIf { it.error != ExternalInviteError.Generic || it.serverMessage != null }
                            listOf(ExternalInvitesEffect.ServersNotConnected(answer.refusal.traceId, detail))
                        }
                        null -> {
                            val failure = result.externalInviteFailureOf()
                            val detail = failure.takeIf { it.error != ExternalInviteError.Generic || it.serverMessage != null }
                            listOf(ExternalInvitesEffect.ServersNotConnected(result.failureTraceIdOrNull(), detail))
                        }
                    }
                }
            val toasts =
                listOfNotNull(
                    ExternalInvitesEffect.Sent(sent).takeIf { sent > 0 },
                    ExternalInvitesEffect.Added(added).takeIf { added > 0 },
                ) + serverEffects
            // One update: no state in between that is idle with the channel created and nothing pending
            // (it would show "created, but the invites weren't sent" for a send that succeeded).
            mutableState.update {
                it.copy(
                    invite = it.invite.copy(busy = false),
                    effects = it.effects + toasts.map { toast -> CreateExternalChannelEffect.Toast(toast) } + CreateExternalChannelEffect.Finished(channel),
                )
            }
        }
    }
}
