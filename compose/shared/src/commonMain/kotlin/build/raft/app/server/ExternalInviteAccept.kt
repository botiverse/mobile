package build.raft.app

import build.raft.app.data.AcceptedExternalInvite
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAcceptApi
import build.raft.app.data.ExternalInviteForInvitee
import build.raft.app.data.GuestAcceptance
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.InviteServerSelection
import build.raft.app.data.InviteeAnswer
import build.raft.app.data.InviteeRefusal
import build.raft.app.data.NewServerConnectResult
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.ServerConnectOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The page an external channel invite's email links to (`/external-invite/:token`, #wg-rbac:#16;
 * web `pages/ExternalInvitePage.tsx`, the `components/externalInvite` files, botiverse/slock staging
 * 0e8b4fab7440). The route carries the token only; the server decides everything else: the one
 * neutral 404 (invalid, expired, withdrawn, external_channels_v1 off) is "not valid", an invite
 * sent to another address opens on "isn't for this account" (#13942) with the address masked.
 */

/** A request waiting for its approver (the waiting step). */
internal data class InviteWaitingRequest(
    val serverId: String,
    val serverName: String,
    val approverName: String,
    val expiresAt: String,
)

/** A chosen server as connect-servers answered it, with its name then. */
internal data class InviteChosenOutcome(val outcome: ServerConnectOutcome, val name: String)

/** The invite page's steps (web `InviteStep`). */
internal sealed interface InviteStep {
    data object Loading : InviteStep

    data object Invalid : InviteStep

    data class LoadFailed(val traceId: String?) : InviteStep

    /** #13942: the invite was sent to another address ([maskedEmail], when the server said). */
    data class WrongAccount(val maskedEmail: String?) : InviteStep

    data object Options : InviteStep

    data object Guest : InviteStep

    data object Connect : InviteStep

    data object NewServer : InviteStep

    /** The chosen servers that need approval: an approver picked for each. */
    data class Approval(val serverIds: List<String>) : InviteStep

    data class Waiting(val request: InviteWaitingRequest?) : InviteStep

    /** Several servers under way (or just chosen: [chosen]). */
    data class Progress(val chosen: List<InviteChosenOutcome>) : InviteStep

    data class Connecting(val serverId: String?) : InviteStep

    data class Connected(val serverId: String?, val landing: AcceptedExternalInvite?) : InviteStep
}

/** Web `stepForStatus`. */
internal fun inviteStepForStatus(invite: ExternalInviteForInvitee, connectingServerId: String?): InviteStep {
    if (!invite.addressedToYou) return InviteStep.WrongAccount(invite.invitedEmailMasked?.takeIf(String::isNotBlank))
    if (invite.status == "accepted") return InviteStep.Connected(invite.accepted?.serverId, invite.accepted)
    val underWay = invite.status == "awaiting_approval" || invite.status == "connecting"
    if (underWay && (invite.servers?.size ?: 0) > 1) return InviteStep.Progress(emptyList())
    if (invite.status == "awaiting_approval") {
        return InviteStep.Waiting(
            invite.pendingRequest?.let { InviteWaitingRequest(it.serverId, it.serverName, it.approverName, it.expiresAt) }
        )
    }
    if (invite.status == "connecting") return InviteStep.Connecting(connectingServerId ?: invite.connectServerId)
    return InviteStep.Options
}

/** What the invitee was doing when refused (web `InviteeAction`). */
internal enum class InviteeAction { Guest, Connect, Request, NewServer, Cancel }

/** The refusal texts (web `INVITEE_ERROR_IDS` and the limit rules). */
internal enum class InviteeMessage {
    GuestUnavailable,
    AlreadyMember,
    GuestElsewhere,
    ChannelFull,
    InvalidServer,
    ServerAdminRequired,
    ServerMembershipRequired,
    InvalidApprover,
    GuestsPresent,
    JointPartyLimit,
    AlreadyConnected,
    JointServerLimit,
    HostLimit,
    TargetFreeLimit,
    JointFreeServerLimit,
    ConnectInProgress,
    Archived,
    Busy,
    TooManyAttempts,
    Generic,
}

/** A server of the caller's own (their server list), for whose limit a refusal is. */
internal data class InviteMyServer(val id: String, val name: String, val slug: String?, val plan: String?)

private fun isFreePlan(plan: String?): Boolean = plan.isNullOrBlank() || plan == "free"

/** Web `limitOwner`: theirs to upgrade, the host's, or a server not created yet. */
private enum class LimitOwner { Mine, Host, Unborn }

private fun limitOwner(refusal: InviteeRefusal, target: InviteMyServer?, targetId: String?): LimitOwner {
    val side = refusal.limitSide?.takeIf { it == "host" || it == "connecting" }
        ?: return if (target != null && !isFreePlan(target.plan)) LimitOwner.Host else LimitOwner.Mine
    if (side == "connecting" && refusal.limitServerId == null) return LimitOwner.Unborn
    return if (targetId != null && targetId == refusal.limitServerId) LimitOwner.Mine else LimitOwner.Host
}

/** Web `inviteeErrorMessage` (the text; the app offers no Upgrade on this page: see the PR notes). */
internal fun inviteeErrorMessage(
    refusal: InviteeRefusal,
    action: InviteeAction,
    target: InviteMyServer?,
    targetId: String? = target?.id,
): InviteeMessage {
    when (refusal.code) {
        "free_external_limit" ->
            return if (action == InviteeAction.Guest || action == InviteeAction.NewServer || limitOwner(refusal, target, targetId) != LimitOwner.Mine) {
                InviteeMessage.HostLimit
            } else {
                InviteeMessage.TargetFreeLimit
            }
        "joint_free_server_limit" -> {
            val owner = limitOwner(refusal, target, targetId)
            return when {
                owner == LimitOwner.Mine && refusal.limitSide != null -> InviteeMessage.JointFreeServerLimit
                owner == LimitOwner.Unborn -> InviteeMessage.JointServerLimit
                else -> InviteeMessage.HostLimit
            }
        }
    }
    return when (refusal.code) {
        "guest_unavailable" -> InviteeMessage.GuestUnavailable
        "already_member" -> InviteeMessage.AlreadyMember
        "guest_elsewhere" -> InviteeMessage.GuestElsewhere
        "channel_full" -> InviteeMessage.ChannelFull
        "invalid_server" -> InviteeMessage.InvalidServer
        "server_admin_required" -> InviteeMessage.ServerAdminRequired
        "server_membership_required" -> InviteeMessage.ServerMembershipRequired
        "invalid_approver" -> InviteeMessage.InvalidApprover
        "guests_present" -> InviteeMessage.GuestsPresent
        "joint_party_limit" -> InviteeMessage.JointPartyLimit
        "already_connected" -> InviteeMessage.AlreadyConnected
        "joint_server_limit" -> InviteeMessage.JointServerLimit
        "joint_channel_free_limit_reached", "joint_channel_plan_limit_reached" -> InviteeMessage.HostLimit
        "connect_in_progress" -> InviteeMessage.ConnectInProgress
        "channel_archived" -> InviteeMessage.Archived
        "channel_conversion_in_progress", "channel_conversion_fence_conflict", "channel_conversion_ledger_conflict" -> InviteeMessage.Busy
        else -> if (refusal.status == 429) InviteeMessage.TooManyAttempts else InviteeMessage.Generic
    }
}

internal fun ServerConnectOutcome.Refused.asRefusal(): InviteeRefusal =
    InviteeRefusal(status = status, code = code, limitSide = limitSide, limitServerId = limitServerId)

/** A refusal banner: its text, the server it names ([target] when not one of the invite's), the trace ID. */
internal data class InviteeError(
    val message: InviteeMessage,
    val serverId: String? = null,
    val target: String? = null,
    val traceId: String? = null,
)

/** New server name / slug problems (web `NewServerFieldErrors`). */
internal enum class NewServerFieldError { NameRequired, SlugRequired, SlugTooShort, SlugPattern, SlugTaken }

internal data class NewServerFieldErrors(val name: NewServerFieldError? = null, val slug: NewServerFieldError? = null) {
    val any: Boolean get() = name != null || slug != null
}

/** The shared `SERVER_SLUG_MIN_LENGTH`. */
internal const val NEW_SERVER_SLUG_MIN_LENGTH: Int = 5

private val NewServerSlugPattern = Regex("^[a-z][a-z0-9-]*$")

/** Web `toServerSlug`: lowercase, anything but letters, digits and hyphens a hyphen. */
internal fun toServerSlug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9-]"), "-")

/** Web `newServerFieldErrors` (shared `validateNewServerSlugReason`). */
internal fun newServerFieldErrors(name: String, slug: String): NewServerFieldErrors =
    NewServerFieldErrors(
        name = if (name.isBlank()) NewServerFieldError.NameRequired else null,
        slug =
            when {
                slug.isEmpty() -> NewServerFieldError.SlugRequired
                slug.length < NEW_SERVER_SLUG_MIN_LENGTH -> NewServerFieldError.SlugTooShort
                !NewServerSlugPattern.matches(slug) -> NewServerFieldError.SlugPattern
                else -> null
            },
    )

/** Web `newServerInputRefusal`: POST /api/servers' 400, or 409 for a slug taken (neither has a code). */
internal fun newServerInputRefusal(refusal: InviteeRefusal, name: String, slug: String): NewServerFieldErrors? {
    if (refusal.code != null || refusal.serverId != null) return null
    if (refusal.status == 409) return NewServerFieldErrors(slug = NewServerFieldError.SlugTaken)
    if (refusal.status != 400) return null
    val errors = newServerFieldErrors(name, slug)
    return if (errors.any) errors else NewServerFieldErrors(slug = NewServerFieldError.SlugPattern)
}

/** One row of the progress list (web `ProgressRowState`). */
internal data class InviteProgressRow(
    val serverId: String,
    val name: String,
    /** `connected`, `connecting`, `awaiting_approval`, `refused` or `notConnected`. */
    val state: String,
    val approverName: String? = null,
    val requestId: String? = null,
    val channelId: String? = null,
    val refusal: ServerConnectOutcome.Refused? = null,
)

/** Web `progressRows`: the chosen servers in order, each as the invite says now or as answered; then the others under way. */
internal fun inviteProgressRows(chosen: List<InviteChosenOutcome>, invite: ExternalInviteForInvitee): List<InviteProgressRow> {
    val live = invite.servers.orEmpty().associateBy { it.serverId }
    val rows =
        chosen.map { (outcome, name) ->
            val now = live[outcome.serverId]
            val channelId = (outcome as? ServerConnectOutcome.Connected)?.channelId
            when {
                now != null ->
                    InviteProgressRow(now.serverId, now.serverName.ifBlank { name }, now.state, now.approverName, now.requestId, channelId)
                outcome is ServerConnectOutcome.Refused -> InviteProgressRow(outcome.serverId, name, "refused", refusal = outcome)
                invite.servers == null ->
                    when (outcome) {
                        is ServerConnectOutcome.Connected -> InviteProgressRow(outcome.serverId, name, "connected", channelId = channelId)
                        is ServerConnectOutcome.Connecting -> InviteProgressRow(outcome.serverId, name, "connecting")
                        is ServerConnectOutcome.AwaitingApproval ->
                            InviteProgressRow(outcome.serverId, name, "awaiting_approval", requestId = outcome.requestId)
                        is ServerConnectOutcome.Refused -> InviteProgressRow(outcome.serverId, name, "refused", refusal = outcome)
                    }
                else -> InviteProgressRow(outcome.serverId, name, "notConnected")
            }
        }
    val others =
        invite.servers.orEmpty().filter { server -> chosen.none { it.outcome.serverId == server.serverId } }.map {
            InviteProgressRow(it.serverId, it.serverName, it.state, it.approverName, it.requestId)
        }
    return rows + others
}

internal sealed interface ExternalInviteEffect {
    /** Into the channel where it landed (the shell opens it like a message link, on that server). */
    data class OpenChannel(val serverId: String, val channelId: String) : ExternalInviteEffect

    /** Into one of the caller's servers (connecting: the channel shows up there once ready). */
    data class OpenServer(val serverId: String) : ExternalInviteEffect

    /** The caller's server list changed (a server joined or created): read it again. */
    data object ServersChanged : ExternalInviteEffect

    /** Switch account / Sign out (#13942). */
    data object SignOut : ExternalInviteEffect

    /** Go to Raft (an invalid link). */
    data object Close : ExternalInviteEffect
}

/** A Cancel request being confirmed: the waiting page's one ([requestId] null), or a progress row's. */
internal data class InviteCancelConfirm(val requestId: String?, val approverName: String)

/** A server connect-new-server created and then could not connect: the caller keeps it. */
internal data class InviteKeptServer(val id: String, val name: String)

internal data class ExternalInviteState(
    val invite: ExternalInviteForInvitee? = null,
    val step: InviteStep = InviteStep.Loading,
    /** A connection that didn't go through: of the server it was connecting when known. */
    val connectFailedServerId: String? = null,
    val connectFailedNotice: Boolean = false,
    val busy: Boolean = false,
    val error: InviteeError? = null,
    val pickedServerIds: List<String> = emptyList(),
    val pickedApprovers: Map<String, String> = emptyMap(),
    val newServerName: String = "",
    val newServerSlug: String = "",
    val newServerSlugTouched: Boolean = false,
    val fieldErrors: NewServerFieldErrors = NewServerFieldErrors(),
    val keptServer: InviteKeptServer? = null,
    val cancelConfirm: InviteCancelConfirm? = null,
    val myServers: List<InviteMyServer> = emptyList(),
    val effects: List<ExternalInviteEffect> = emptyList(),
) {
    val servers: List<InviteConnectableServer> get() = invite?.options?.connect?.servers.orEmpty()

    val pickedServers: List<InviteConnectableServer>
        get() = pickedServerIds.mapNotNull { id -> servers.firstOrNull { it.id == id } }

    fun approverFor(server: InviteConnectableServer): String? = pickedApprovers[server.id] ?: server.approvers.firstOrNull()?.id

    fun myServer(id: String?): InviteMyServer? = myServers.firstOrNull { it.id == id }

    fun serverName(id: String?): String? =
        servers.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank) ?: myServer(id)?.name?.takeIf(String::isNotBlank)

    /**
     * The channel's slots said only when there are more servers to check than it can take (web
     * `connectSlotsLeft`), counting down as servers are checked; null: nothing said.
     */
    val connectSlotsLeft: Int?
        get() {
            val slots = invite?.options?.connect?.slotsLeft ?: return null
            return if (servers.count { !it.atLimit } > slots) maxOf(0, slots - pickedServers.size) else null
        }
}

internal sealed interface ExternalInviteIntent {
    data object Load : ExternalInviteIntent

    /** The page shows (or no longer does): polling runs only while it is visible. */
    data class SetVisible(val visible: Boolean) : ExternalInviteIntent

    data class SetMyServers(val servers: List<InviteMyServer>) : ExternalInviteIntent

    data object ShowOptions : ExternalInviteIntent

    data object ShowGuest : ExternalInviteIntent

    data object ShowConnect : ExternalInviteIntent

    data object ShowNewServer : ExternalInviteIntent

    data object JoinAsGuest : ExternalInviteIntent

    data class SetPicked(val serverIds: List<String>) : ExternalInviteIntent

    data class PickApprover(val serverId: String, val approverId: String) : ExternalInviteIntent

    /** The connect step's commit: to approval when one needs it, else connect. */
    data object Connect : ExternalInviteIntent

    data object RequestApproval : ExternalInviteIntent

    data class SetNewServerName(val name: String) : ExternalInviteIntent

    data class SetNewServerSlug(val slug: String) : ExternalInviteIntent

    data object CreateServer : ExternalInviteIntent

    data class AskCancel(val requestId: String?, val approverName: String) : ExternalInviteIntent

    data object DismissCancel : ExternalInviteIntent

    data object ConfirmCancel : ExternalInviteIntent

    /** Connecting / connected: open the landing channel, else the server. */
    data object OpenServer : ExternalInviteIntent

    /** A progress row (its channel when connect-servers named it, else its server). */
    data class OpenProgress(val serverId: String, val channelId: String?) : ExternalInviteIntent

    data object OpenKeptServer : ExternalInviteIntent

    data object SignOut : ExternalInviteIntent

    data object GoToRaft : ExternalInviteIntent

    data class ConsumeEffect(val effect: ExternalInviteEffect) : ExternalInviteIntent
}

internal interface ExternalInviteDataSource {
    suspend fun get(): DataResult<InviteeAnswer<ExternalInviteForInvitee>>

    suspend fun acceptAsGuest(): DataResult<InviteeAnswer<GuestAcceptance>>

    suspend fun connectServers(servers: List<InviteServerSelection>): DataResult<InviteeAnswer<List<ServerConnectOutcome>>>

    suspend fun connectNewServer(name: String, slug: String): DataResult<InviteeAnswer<NewServerConnectResult>>

    suspend fun cancelRequest(requestId: String?): DataResult<InviteeAnswer<Unit>>
}

internal class RealExternalInviteDataSource(
    private val token: String,
    networkClient: RaftNetworkClient,
    logger: (String) -> Unit = {},
) : ExternalInviteDataSource {
    private val api = ExternalInviteAcceptApi(networkClient) { logger("external_invite $it") }

    override suspend fun get() = api.get(token)

    override suspend fun acceptAsGuest() = api.acceptAsGuest(token)

    override suspend fun connectServers(servers: List<InviteServerSelection>) = api.connectServers(token, servers)

    override suspend fun connectNewServer(name: String, slug: String) = api.connectNewServer(token, name, slug)

    override suspend fun cancelRequest(requestId: String?) = api.cancelRequest(token, requestId)
}

/** How often a connection under way or a waiting request is asked again (web `POLL_MS`). */
internal const val ExternalInvitePollMillis: Long = 4_000L

/**
 * The invite page's store (web `ExternalInvitePage` state + `useInvitePolling`): one action at a
 * time ([ExternalInviteState.busy]), and a poll while something is under way.
 */
internal class ExternalInviteStore(
    private val scope: CoroutineScope,
    private val dataSource: ExternalInviteDataSource,
    private val pollMillis: Long = ExternalInvitePollMillis,
) {
    private val mutableState = MutableStateFlow(ExternalInviteState())
    val state: StateFlow<ExternalInviteState> = mutableState.asStateFlow()
    private var loadGeneration = 0
    private var pollJob: Job? = null
    private var pollKey: String? = null
    private var visible = true

    /** Hidden: the poll stops. Shown again while something is under way: read now, then poll again. */
    private fun setVisible(value: Boolean) {
        if (value == visible) return
        visible = value
        val resumed = value && pollingKey(mutableState.value) != null
        syncPolling()
        if (resumed) scope.launch { poll() }
    }

    fun dispatch(intent: ExternalInviteIntent) {
        when (intent) {
            ExternalInviteIntent.Load -> load()
            is ExternalInviteIntent.SetVisible -> setVisible(intent.visible)
            is ExternalInviteIntent.SetMyServers -> mutableState.update { it.copy(myServers = intent.servers) }
            ExternalInviteIntent.ShowOptions -> goTo(InviteStep.Options)
            ExternalInviteIntent.ShowGuest -> goTo(InviteStep.Guest)
            ExternalInviteIntent.ShowConnect -> goTo(InviteStep.Connect)
            ExternalInviteIntent.ShowNewServer -> {
                mutableState.update { it.copy(fieldErrors = NewServerFieldErrors()) }
                goTo(InviteStep.NewServer)
            }
            ExternalInviteIntent.JoinAsGuest -> joinAsGuest()
            is ExternalInviteIntent.SetPicked -> setPicked(intent.serverIds)
            is ExternalInviteIntent.PickApprover ->
                mutableState.update { it.copy(pickedApprovers = it.pickedApprovers + (intent.serverId to intent.approverId)) }
            ExternalInviteIntent.Connect -> connect()
            ExternalInviteIntent.RequestApproval -> requestApproval()
            is ExternalInviteIntent.SetNewServerName ->
                mutableState.update {
                    it.copy(
                        newServerName = intent.name,
                        newServerSlug = if (it.newServerSlugTouched) it.newServerSlug else toServerSlug(intent.name),
                        fieldErrors = NewServerFieldErrors(),
                    )
                }
            is ExternalInviteIntent.SetNewServerSlug ->
                mutableState.update {
                    it.copy(newServerSlug = toServerSlug(intent.slug), newServerSlugTouched = true, fieldErrors = it.fieldErrors.copy(slug = null))
                }
            ExternalInviteIntent.CreateServer -> createServer()
            is ExternalInviteIntent.AskCancel ->
                mutableState.update { it.copy(cancelConfirm = InviteCancelConfirm(intent.requestId, intent.approverName)) }
            ExternalInviteIntent.DismissCancel -> mutableState.update { if (it.busy) it else it.copy(cancelConfirm = null) }
            ExternalInviteIntent.ConfirmCancel -> cancelRequest()
            ExternalInviteIntent.OpenServer -> openServer()
            is ExternalInviteIntent.OpenProgress -> {
                val effect =
                    intent.channelId?.let { ExternalInviteEffect.OpenChannel(intent.serverId, it) }
                        ?: ExternalInviteEffect.OpenServer(intent.serverId)
                if (mutableState.value.myServer(intent.serverId) != null || intent.channelId != null) emit(effect)
            }
            ExternalInviteIntent.OpenKeptServer -> mutableState.value.keptServer?.let { emit(ExternalInviteEffect.OpenServer(it.id)) }
            ExternalInviteIntent.SignOut -> emit(ExternalInviteEffect.SignOut)
            ExternalInviteIntent.GoToRaft -> emit(ExternalInviteEffect.Close)
            is ExternalInviteIntent.ConsumeEffect -> mutableState.update { it.copy(effects = it.effects - intent.effect) }
        }
    }

    fun clear() {
        pollJob?.cancel()
    }

    private fun emit(effect: ExternalInviteEffect) {
        mutableState.update { it.copy(effects = it.effects + effect) }
    }

    private fun goTo(step: InviteStep) {
        mutableState.update { it.copy(step = step, error = null, connectFailedNotice = false) }
        syncPolling()
    }

    private fun setView(invite: ExternalInviteForInvitee?, step: InviteStep, connectFailed: Boolean = false, connectFailedServerId: String? = null) {
        mutableState.update {
            it.copy(
                invite = invite ?: it.invite,
                step = step,
                connectFailedNotice = connectFailed,
                connectFailedServerId = connectFailedServerId,
            )
        }
        syncPolling()
    }

    private fun load() {
        val mine = ++loadGeneration
        mutableState.update { it.copy(step = InviteStep.Loading, error = null) }
        scope.launch {
            val result = dataSource.get()
            if (mine != loadGeneration) return@launch
            when (val answer = (result as? DataResult.Content)?.data) {
                is InviteeAnswer.Ok -> {
                    setView(answer.value, inviteStepForStatus(answer.value, null))
                    acceptedLanding(answer.value)
                }
                is InviteeAnswer.Refused ->
                    setView(null, if (answer.refusal.status == 404) InviteStep.Invalid else InviteStep.LoadFailed(answer.refusal.traceId))
                null -> setView(null, InviteStep.LoadFailed(result.traceIdOrNull()))
            }
        }
    }

    /** An invite already accepted by this person opens where it put them once. */
    private var landingOpened = false

    private fun acceptedLanding(invite: ExternalInviteForInvitee) {
        val landing = invite.accepted ?: return
        if (invite.status != "accepted" || landingOpened) return
        landingOpened = true
        emit(ExternalInviteEffect.ServersChanged)
        emit(ExternalInviteEffect.OpenChannel(landing.serverId, landing.channelId))
    }

    /** One action at a time, its banner cleared first. */
    private fun run(work: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, connectFailedNotice = false) }
        scope.launch {
            try {
                work()
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Web `handleFailure`: the neutral 404 is the wrong account, something already under way re-reads, the rest is a banner. */
    private fun handleFailure(refusal: InviteeRefusal, action: InviteeAction, serverId: String?, target: String? = null) {
        if (refusal.status == 404) {
            setView(null, InviteStep.WrongAccount(null))
            return
        }
        if (refusal.code == "approval_in_progress" || (refusal.code == "connect_in_progress" && action != InviteeAction.Guest)) {
            load()
            return
        }
        val state = mutableState.value
        val message = inviteeErrorMessage(refusal, action, state.myServer(serverId), serverId)
        mutableState.update { it.copy(error = InviteeError(message, serverId, target, refusal.traceId)) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleError(result: DataResult<*>, action: InviteeAction, serverId: String?) {
        mutableState.update { it.copy(error = InviteeError(InviteeMessage.Generic, serverId, traceId = result.traceIdOrNull())) }
    }

    private fun joinAsGuest() =
        run {
            val result = dataSource.acceptAsGuest()
            when (val answer = (result as? DataResult.Content)?.data) {
                is InviteeAnswer.Ok -> {
                    emit(ExternalInviteEffect.ServersChanged)
                    emit(ExternalInviteEffect.OpenChannel(answer.value.serverId, answer.value.channelId))
                }
                is InviteeAnswer.Refused -> handleFailure(answer.refusal, InviteeAction.Guest, null)
                null -> handleError(result, InviteeAction.Guest, null)
            }
        }

    private fun setPicked(ids: List<String>) {
        val state = mutableState.value
        val allowed = state.servers.filter { !it.atLimit }.map { it.id }.toSet()
        var picked = ids.filter { it in allowed }.distinct()
        // At 0 slots the rest can't be checked (web: each says why).
        val slots = state.invite?.options?.connect?.slotsLeft
        if (slots != null && state.servers.count { !it.atLimit } > slots && picked.size > slots) {
            picked = picked.filter { it in state.pickedServerIds }.take(slots)
        }
        mutableState.update { it.copy(pickedServerIds = picked) }
    }

    private fun connect() {
        val state = mutableState.value
        val picked = state.pickedServers
        if (picked.isEmpty() || state.busy) return
        val needApproval = picked.filter { it.needsApproval }
        if (needApproval.isNotEmpty()) {
            mutableState.update { it.copy(error = null) }
            goTo(InviteStep.Approval(needApproval.map { it.id }))
            return
        }
        connectServers(picked.map { InviteServerSelection(it.id) }, InviteeAction.Connect)
    }

    private fun requestApproval() {
        val state = mutableState.value
        val step = state.step as? InviteStep.Approval ?: return
        val approval = step.serverIds.mapNotNull { id -> state.servers.firstOrNull { it.id == id } }
        if (approval.any { state.approverFor(it) == null }) return
        connectServers(
            state.pickedServers.map { server ->
                if (server.needsApproval) InviteServerSelection(server.id, state.approverFor(server)) else InviteServerSelection(server.id)
            },
            InviteeAction.Request,
        )
    }

    /**
     * connect-servers for one or several (the app sends every choice there; the web's single-server
     * routes answer the same). One server: into its channel, connecting, or waiting for its approver.
     * Several: the progress list.
     */
    private fun connectServers(selections: List<InviteServerSelection>, action: InviteeAction) =
        run {
            val before = mutableState.value
            val names = before.pickedServers.associate { it.id to it.name }
            val result = dataSource.connectServers(selections)
            val answer = (result as? DataResult.Content)?.data
            when (answer) {
                is InviteeAnswer.Ok -> {
                    val outcomes = answer.value
                    if (outcomes.any { it !is ServerConnectOutcome.Refused }) emit(ExternalInviteEffect.ServersChanged)
                    val single = outcomes.singleOrNull()
                    if (selections.size == 1 && single != null) {
                        when (single) {
                            is ServerConnectOutcome.Connected -> emit(ExternalInviteEffect.OpenChannel(single.serverId, single.channelId))
                            is ServerConnectOutcome.Connecting -> setView(null, InviteStep.Connecting(single.serverId))
                            is ServerConnectOutcome.AwaitingApproval -> {
                                val server = before.servers.firstOrNull { it.id == single.serverId }
                                val approverId = server?.let(before::approverFor)
                                setView(
                                    null,
                                    InviteStep.Waiting(
                                        InviteWaitingRequest(
                                            serverId = single.serverId,
                                            serverName = server?.name.orEmpty(),
                                            approverName = server?.approvers?.firstOrNull { it.id == approverId }?.name.orEmpty(),
                                            expiresAt = before.invite?.expiresAt.orEmpty(),
                                        )
                                    ),
                                )
                            }
                            is ServerConnectOutcome.Refused ->
                                handleFailure(single.asRefusal().copy(traceId = answer.traceId), action, single.serverId)
                        }
                        return@run
                    }
                    val fresh = (dataSource.get() as? DataResult.Content)?.data as? InviteeAnswer.Ok
                    val chosen = outcomes.map { InviteChosenOutcome(it, names[it.serverId].orEmpty()) }
                    setView(fresh?.value, InviteStep.Progress(chosen))
                }
                is InviteeAnswer.Refused -> handleFailure(answer.refusal, action, null)
                null -> handleError(result, action, null)
            }
        }

    private fun createServer() {
        val state = mutableState.value
        if (state.keptServer != null || state.busy) return
        val name = state.newServerName.trim()
        val slug = state.newServerSlug
        val invalid = newServerFieldErrors(name, slug)
        if (invalid.any) {
            mutableState.update { it.copy(error = null, fieldErrors = invalid) }
            return
        }
        mutableState.update { it.copy(fieldErrors = NewServerFieldErrors()) }
        run {
            val result = dataSource.connectNewServer(name, slug)
            when (val answer = (result as? DataResult.Content)?.data) {
                is InviteeAnswer.Ok -> {
                    emit(ExternalInviteEffect.ServersChanged)
                    val created = answer.value
                    val channelId = created.channelId
                    if (answer.status == 202 || created.state == "connecting" || channelId.isNullOrBlank()) {
                        setView(null, InviteStep.Connecting(created.serverId))
                    } else {
                        emit(ExternalInviteEffect.OpenChannel(created.serverId, channelId))
                    }
                }
                is InviteeAnswer.Refused -> {
                    val refusal = answer.refusal
                    val keptId = refusal.serverId
                    if (keptId != null) {
                        // Created, then refused: the server is theirs; the banner says so.
                        emit(ExternalInviteEffect.ServersChanged)
                        mutableState.update {
                            it.copy(
                                keptServer = InviteKeptServer(keptId, name),
                                error =
                                    InviteeError(
                                        inviteeErrorMessage(refusal, InviteeAction.NewServer, InviteMyServer(keptId, name, slug, null), keptId),
                                        keptId,
                                        name,
                                        refusal.traceId,
                                    ),
                            )
                        }
                        return@run
                    }
                    val refused = newServerInputRefusal(refusal, name, slug)
                    if (refused != null) {
                        mutableState.update { it.copy(fieldErrors = refused) }
                        return@run
                    }
                    handleFailure(refusal, InviteeAction.NewServer, null, name)
                }
                null -> handleError(result, InviteeAction.NewServer, null)
            }
        }
    }

    private fun cancelRequest() {
        val confirm = mutableState.value.cancelConfirm ?: return
        run {
            val result = dataSource.cancelRequest(confirm.requestId)
            mutableState.update { it.copy(cancelConfirm = null) }
            when (val answer = (result as? DataResult.Content)?.data) {
                is InviteeAnswer.Ok -> reread(confirm.requestId)
                is InviteeAnswer.Refused ->
                    if (answer.refusal.code == "request_not_pending") {
                        reread(confirm.requestId)
                    } else {
                        handleFailure(answer.refusal, InviteeAction.Cancel, null)
                    }
                null -> handleError(result, InviteeAction.Cancel, null)
            }
        }
    }

    /** The waiting page's request: the page re-reads the invite; a progress row: the list reads it again in place. */
    private suspend fun reread(requestId: String?) {
        if (requestId == null) {
            load()
            return
        }
        val fresh = (dataSource.get() as? DataResult.Content)?.data as? InviteeAnswer.Ok ?: return
        mutableState.update { it.copy(invite = fresh.value) }
        syncPolling()
    }

    private fun openServer() {
        val state = mutableState.value
        when (val step = state.step) {
            is InviteStep.Connected -> {
                val landing = step.landing
                if (landing != null) {
                    emit(ExternalInviteEffect.OpenChannel(landing.serverId, landing.channelId))
                } else if (step.serverId != null) {
                    emit(ExternalInviteEffect.OpenServer(step.serverId))
                } else {
                    emit(ExternalInviteEffect.Close)
                }
            }
            is InviteStep.Connecting -> emit(step.serverId?.let { ExternalInviteEffect.OpenServer(it) } ?: ExternalInviteEffect.Close)
            else -> Unit
        }
    }

    // ── Polling (web `useInvitePolling`) ──

    private fun pollingKey(state: ExternalInviteState): String? {
        if (!visible) return null
        val step = state.step
        val status = state.invite?.status
        return when {
            step is InviteStep.Connecting -> "connecting:${step.serverId}"
            step is InviteStep.Waiting -> "waiting"
            step is InviteStep.Progress && (status == "connecting" || status == "awaiting_approval") -> "progress"
            else -> null
        }
    }

    private fun syncPolling() {
        val key = pollingKey(mutableState.value)
        if (key == pollKey) return
        pollKey = key
        pollJob?.cancel()
        pollJob = null
        if (key == null) return
        pollJob =
            scope.launch {
                while (isActive) {
                    delay(pollMillis)
                    poll()
                }
            }
    }

    private suspend fun poll() {
        val state = mutableState.value
        val step = state.step
        val progressing = step is InviteStep.Progress
        val waiting = step is InviteStep.Waiting
        val connectingServerId = (step as? InviteStep.Connecting)?.serverId
        val result = dataSource.get()
        when (val answer = (result as? DataResult.Content)?.data) {
            is InviteeAnswer.Ok -> {
                val data = answer.value
                if (progressing) {
                    mutableState.update { it.copy(invite = data) }
                    syncPolling()
                    return
                }
                // Still as it was: nothing to show yet.
                if (data.status == (if (waiting) "awaiting_approval" else "connecting")) return
                val failed = data.status == "pending" && !waiting
                setView(data, inviteStepForStatus(data, null), connectFailed = failed, connectFailedServerId = connectingServerId)
                acceptedLanding(data)
            }
            is InviteeAnswer.Refused ->
                // A neutral 404 from an older server while connecting reads as connected.
                if (!progressing && !waiting && answer.refusal.status == 404) {
                    setView(null, InviteStep.Connected(connectingServerId, null))
                }
            null -> Unit
        }
    }
}

private fun DataResult<*>.traceIdOrNull(): String? =
    when (this) {
        is DataResult.Error -> error.traceId
        is DataResult.NoPermission -> blocker.traceId
        else -> null
    }
