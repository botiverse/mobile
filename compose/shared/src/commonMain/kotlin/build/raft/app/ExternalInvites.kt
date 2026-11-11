package build.raft.app

import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelExternalInvite
import build.raft.app.data.ChannelExternalInviteList
import build.raft.app.data.DataResult
import build.raft.app.data.EXTERNAL_INVITE_MAX_EMAILS
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInviteRefusal
import build.raft.app.data.ExternalInvitesApi
import build.raft.app.data.SendExternalInvitesResult
import build.raft.app.data.SentExternalInvite
import build.raft.app.data.parseExternalInviteEmailEntries
import build.raft.app.model.ChannelId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * External channel invites, the host side (#wg-rbac:#16; web `components/channel/externalInvites.ts`,
 * `ExternalInviteSteps.tsx`, `ExternalInviteRows.tsx`, `features/channel/ChannelMembers.tsx`,
 * botiverse/slock staging 0e8b4fab7440). A host server owner or admin invites people outside the
 * server to a channel by email (Add Member > Invite people outside {server}) and manages the open
 * invites (the members page's Invited group: Resend, Withdraw).
 */

/** The channel fields the external-invite gates read (web `InvitableChannelFields`). */
internal data class ExternalInviteChannel(
    /** The wire type: `channel` (public), `private`, `joint`, ... */
    val type: String?,
    val name: String,
    val archived: Boolean,
    /** `jointRole` of a joint channel: only the `host` invites. */
    val jointRole: String?,
    val hasExternalRelation: Boolean,
)

/**
 * Web `isExternallyInvitableChannel`: an ordinary channel (not #all, DMs or threads) or a joint
 * channel this server hosts, live. The members page is always the current server's copy.
 */
internal fun isExternallyInvitableChannel(channel: ExternalInviteChannel?): Boolean {
    channel ?: return false
    when (channel.type) {
        "joint" -> if (channel.jointRole != "host") return false
        "channel", "private" -> if (channel.name.removePrefix("#") == "all") return false
        else -> return false
    }
    return !channel.archived
}

/**
 * Web `useExternalInviteAccess`: invite people outside the server and resend: `external_channels_v1`
 * on for the host server, the viewer its owner or admin (channel admins are not), an invitable
 * channel. The server checks the same.
 */
internal fun externalInviteAccess(flagOn: Boolean, serverRole: String?, channel: ExternalInviteChannel?): Boolean =
    flagOn && serverRole.isServerOwnerOrAdmin() && isExternallyInvitableChannel(channel)

/**
 * Web `useExternalInviteManagement`: see and withdraw the open invites: as [externalInviteAccess],
 * or with the flag off while the channel is still external, so turning the flag off never strands
 * an invite (the server lists and revokes without the flag).
 */
internal fun externalInviteManagement(flagOn: Boolean, serverRole: String?, channel: ExternalInviteChannel?): Boolean =
    (flagOn || channel?.hasExternalRelation == true) && serverRole.isServerOwnerOrAdmin() && isExternallyInvitableChannel(channel)

private fun String?.isServerOwnerOrAdmin(): Boolean = this == "owner" || this == "admin"

/** Whole days left before an invite expires, at least 1 while it is open (web `externalInviteDaysLeft`). */
internal fun externalInviteDaysLeft(expiresAt: String, nowEpochMillis: Long): Int {
    val expires = runCatching { Instant.parse(expiresAt).toEpochMilliseconds() }.getOrNull() ?: return 1
    val msLeft = expires - nowEpochMillis
    return max(1, ceil(msLeft / DayMillis).toInt())
}

private const val DayMillis = 24.0 * 60 * 60 * 1000

/** A failed external-invite request, as web `externalInviteErrorMessageId` names it. */
internal enum class ExternalInviteError {
    DailyCap,
    FreeLimit,
    ChannelFull,
    KeepPrivate,
    MembershipRequired,
    MakePrivateRequired,
    Archived,
    NotInvitable,
    Forbidden,
    Connecting,
    NotPending,
    InvalidInput,
    NotFound,
    Generic,
}

/** A failure as the dialog or a toast shows it: its text, Upgrade for the Free plan's limit, the trace ID. */
internal data class ExternalInviteFailure(
    val error: ExternalInviteError,
    /** `free_external_limit`: Upgrade opens the `external_channel_free_limit` gate. */
    val upgrade: Boolean = false,
    val traceId: String? = null,
)

/** Web `ERROR_MESSAGE_IDS` by the server's code, else by status. */
internal fun externalInviteErrorFor(code: String?, status: Int): ExternalInviteError =
    when (code) {
        "daily_cap" -> ExternalInviteError.DailyCap
        "free_external_limit" -> ExternalInviteError.FreeLimit
        "channel_full" -> ExternalInviteError.ChannelFull
        "external_members_keep_private" -> ExternalInviteError.KeepPrivate
        "channel_membership_required" -> ExternalInviteError.MembershipRequired
        "make_private_required" -> ExternalInviteError.MakePrivateRequired
        "channel_archived" -> ExternalInviteError.Archived
        "channel_not_invitable" -> ExternalInviteError.NotInvitable
        "forbidden" -> ExternalInviteError.Forbidden
        "connecting" -> ExternalInviteError.Connecting
        "invite_not_pending" -> ExternalInviteError.NotPending
        "invalid_input" -> ExternalInviteError.InvalidInput
        "not_found" -> ExternalInviteError.NotFound
        else ->
            when (status) {
                429 -> ExternalInviteError.DailyCap
                404 -> ExternalInviteError.NotFound
                403 -> ExternalInviteError.Forbidden
                else -> ExternalInviteError.Generic
            }
    }

internal fun ExternalInviteRefusal.toFailure(): ExternalInviteFailure =
    ExternalInviteFailure(
        error = externalInviteErrorFor(code, status),
        upgrade = code == "free_external_limit",
        traceId = traceId,
    )

/** A failed [DataResult] (no answer, signed out): the generic text with whatever trace ID the error has. */
private fun DataResult<*>.toFailure(): ExternalInviteFailure =
    when (this) {
        is DataResult.Error -> ExternalInviteFailure(ExternalInviteError.Generic, traceId = error.traceId)
        is DataResult.NoPermission -> ExternalInviteFailure(ExternalInviteError.Forbidden, traceId = blocker.traceId)
        else -> ExternalInviteFailure(ExternalInviteError.Generic)
    }

/** How a review row reads (web `reviewRows` statuses). */
internal enum class ExternalInviteReviewStatus {
    WillInvite,
    WillAdd,
    Duplicate,
    AlreadyInvited,
    AlreadyInChannel,
    GuestElsewhere,
    GuestElsewhereUnnamed,
    Invalid,
}

internal data class ExternalInviteReviewRow(
    val key: String,
    val email: String,
    val status: ExternalInviteReviewStatus,
    /** [ExternalInviteReviewStatus.GuestElsewhere]: the channel they are a guest in, with its #. */
    val channel: String? = null,
) {
    val invites: Boolean
        get() = status == ExternalInviteReviewStatus.WillInvite || status == ExternalInviteReviewStatus.WillAdd
}

/**
 * Web `reviewRows`: who gets an invite, who is added (members of the server), then who doesn't and
 * why. Three copies of one address read as one duplicate note.
 */
internal fun externalInviteReviewRows(preview: ExternalInvitePreview): List<ExternalInviteReviewRow> {
    val rows = mutableListOf<ExternalInviteReviewRow>()
    preview.valid.forEach { rows += ExternalInviteReviewRow("valid:$it", it, ExternalInviteReviewStatus.WillInvite) }
    preview.alreadyMembers.forEach { rows += ExternalInviteReviewRow("member:$it", it, ExternalInviteReviewStatus.WillAdd) }
    preview.duplicates.distinct().forEach { rows += ExternalInviteReviewRow("duplicate:$it", it, ExternalInviteReviewStatus.Duplicate) }
    preview.alreadyPending.forEach { rows += ExternalInviteReviewRow("pending:$it", it, ExternalInviteReviewStatus.AlreadyInvited) }
    preview.alreadyInChannel.forEach { rows += ExternalInviteReviewRow("in-channel:$it", it, ExternalInviteReviewStatus.AlreadyInChannel) }
    preview.guestsElsewhere.forEach { guest ->
        val channelName = guest.channelName?.takeIf(String::isNotBlank)
        rows +=
            if (channelName != null) {
                ExternalInviteReviewRow("guest:${guest.email}", guest.email, ExternalInviteReviewStatus.GuestElsewhere, "#$channelName")
            } else {
                ExternalInviteReviewRow("guest:${guest.email}", guest.email, ExternalInviteReviewStatus.GuestElsewhereUnnamed)
            }
    }
    preview.invalid.forEachIndexed { index, entry ->
        rows += ExternalInviteReviewRow("invalid:$index:$entry", entry, ExternalInviteReviewStatus.Invalid)
    }
    return rows
}

/** The channel's slots as the review says them (web `SlotsLine`); null: nothing to say. */
internal sealed interface ExternalInviteSlotsLine {
    data object Full : ExternalInviteSlotsLine

    data class Over(val room: Int, val count: Int) : ExternalInviteSlotsLine

    data object Fills : ExternalInviteSlotsLine

    data class Left(val count: Int) : ExternalInviteSlotsLine
}

internal fun externalInviteSlotsLine(slotsLeft: Int?, taking: Int): ExternalInviteSlotsLine? {
    slotsLeft ?: return null
    val over = taking - slotsLeft
    return when {
        slotsLeft == 0 -> ExternalInviteSlotsLine.Full
        over > 0 -> ExternalInviteSlotsLine.Over(room = slotsLeft, count = over)
        taking == 0 || slotsLeft > EXTERNAL_INVITE_MAX_EMAILS -> null
        over == 0 -> ExternalInviteSlotsLine.Fills
        else -> ExternalInviteSlotsLine.Left(-over)
    }
}

/**
 * While typing (web `typedSlotsHint`): every entry counted as an invite until the review sorts
 * them, said only within the 20 at a time; Next stays open (the review counts what is sent).
 */
internal fun externalInviteTypedSlotsLine(channelSlotsLeft: Int?, entries: Int): ExternalInviteSlotsLine? {
    if (channelSlotsLeft == null || channelSlotsLeft > EXTERNAL_INVITE_MAX_EMAILS || entries == 0) return null
    val left = channelSlotsLeft - entries
    return when {
        channelSlotsLeft == 0 -> ExternalInviteSlotsLine.Full
        left < 0 -> ExternalInviteSlotsLine.Over(room = channelSlotsLeft, count = -left)
        left == 0 -> ExternalInviteSlotsLine.Fills
        else -> ExternalInviteSlotsLine.Left(left)
    }
}

/** Invite people outside {server}: the emails, then the review, then the send (web `ExternalInviteSteps`). */
internal data class ExternalInviteDialogState(
    val text: String = "",
    val preview: ExternalInvitePreview? = null,
    val busy: Boolean = false,
    val failure: ExternalInviteFailure? = null,
) {
    val entries: List<String> get() = parseExternalInviteEmailEntries(text)

    /** How many entries over the 20 at a time. */
    val overBy: Int get() = entries.size - EXTERNAL_INVITE_MAX_EMAILS

    val inviteCount: Int get() = preview?.valid?.size ?: 0

    /** Invited, or added to the channel as members of the server: what the commit does. */
    val actionCount: Int get() = inviteCount + (preview?.alreadyMembers?.size ?: 0)

    val overSlots: Int get() = preview?.slotsLeft?.let { inviteCount - it } ?: 0

    val canNext: Boolean get() = !busy && entries.isNotEmpty() && overBy <= 0

    val canSend: Boolean get() = !busy && preview != null && actionCount > 0 && overSlots <= 0
}

internal sealed interface ExternalInvitesEffect {
    data class Sent(val count: Int) : ExternalInvitesEffect

    data class Added(val count: Int) : ExternalInvitesEffect

    data class Resent(val email: String) : ExternalInvitesEffect

    data class Withdrawn(val email: String) : ExternalInvitesEffect

    /** A row action (Resend) refused: a toast with the text and trace ID. */
    data class Failed(val failure: ExternalInviteFailure) : ExternalInvitesEffect
}

internal data class ExternalInvitesState(
    val invites: List<ChannelExternalInvite> = emptyList(),
    val slotsLeft: Int? = null,
    val loaded: Boolean = false,
    val loadFailure: ExternalInviteFailure? = null,
    /** Invites with a Resend under way. */
    val rowBusy: Set<String> = emptySet(),
    /** Withdraw, being confirmed (web ConfirmDialog). */
    val withdrawConfirm: ChannelExternalInvite? = null,
    val withdrawBusy: Boolean = false,
    val withdrawFailure: ExternalInviteFailure? = null,
    val dialog: ExternalInviteDialogState? = null,
    val effects: List<ExternalInvitesEffect> = emptyList(),
)

internal sealed interface ExternalInvitesIntent {
    data object Load : ExternalInvitesIntent

    data object OpenDialog : ExternalInvitesIntent

    data object CloseDialog : ExternalInvitesIntent

    data class SetText(val text: String) : ExternalInvitesIntent

    data object Next : ExternalInvitesIntent

    data object Back : ExternalInvitesIntent

    /** [makePrivate]: a public channel becomes private with the first invite (web `willBecomePrivate`). */
    data class Send(val makePrivate: Boolean) : ExternalInvitesIntent

    data class Resend(val inviteId: String) : ExternalInvitesIntent

    data class RequestWithdraw(val inviteId: String) : ExternalInvitesIntent

    data object DismissWithdraw : ExternalInvitesIntent

    data object ConfirmWithdraw : ExternalInvitesIntent

    data class ConsumeEffect(val effect: ExternalInvitesEffect) : ExternalInvitesIntent
}

internal interface ExternalInvitesDataSource {
    suspend fun list(): DataResult<ExternalInviteAnswer<ChannelExternalInviteList>>

    suspend fun preview(emails: List<String>): DataResult<ExternalInviteAnswer<ExternalInvitePreview>>

    suspend fun send(emails: List<String>, makePrivate: Boolean): DataResult<ExternalInviteAnswer<SendExternalInvitesResult>>

    suspend fun resend(inviteId: String): DataResult<ExternalInviteAnswer<SentExternalInvite>>

    suspend fun revoke(inviteId: String): DataResult<ExternalInviteAnswer<Unit>>
}

/** The members page's data source: every call under the route's authority (a switched account reads nothing). */
internal class RealExternalInvitesDataSource(
    private val channelId: ChannelId,
    owner: AuthenticatedRouteOwner,
    gateway: AuthenticatedRouteAuthorityGateway,
    private val logger: (String) -> Unit = {},
) : ExternalInvitesDataSource {
    private val runner = AuthenticatedRouteOperationRunner(owner, gateway)

    private suspend fun <T> api(block: suspend (ExternalInvitesApi) -> DataResult<T>): DataResult<T> =
        runner.run { _, networkClient -> block(ExternalInvitesApi(networkClient) { logger("external_invites $it") }) }

    override suspend fun list() = api { it.list(channelId) }

    override suspend fun preview(emails: List<String>) = api { it.preview(channelId, emails) }

    override suspend fun send(emails: List<String>, makePrivate: Boolean) = api { it.send(channelId, emails, makePrivate) }

    override suspend fun resend(inviteId: String) = api { it.resend(channelId, inviteId) }

    override suspend fun revoke(inviteId: String) = api { it.revoke(channelId, inviteId) }
}

/**
 * The channel's open invites and the invite dialog. Reads are generation-fenced (an older answer
 * that lands after a newer request is dropped, web `loadChannelExternalInvites`); one dialog
 * request at a time (`busy`), one Withdraw at a time.
 */
internal class ExternalInvitesStore(
    private val scope: CoroutineScope,
    private val dataSource: ExternalInvitesDataSource,
) {
    private val mutableState = MutableStateFlow(ExternalInvitesState())
    val state: StateFlow<ExternalInvitesState> = mutableState.asStateFlow()
    private var generation = 0

    fun dispatch(intent: ExternalInvitesIntent) {
        when (intent) {
            ExternalInvitesIntent.Load -> load()
            ExternalInvitesIntent.OpenDialog -> mutableState.update { it.copy(dialog = ExternalInviteDialogState()) }
            ExternalInvitesIntent.CloseDialog ->
                mutableState.update { if (it.dialog?.busy == true) it else it.copy(dialog = null) }
            is ExternalInvitesIntent.SetText -> updateDialog { it.copy(text = intent.text, failure = null) }
            ExternalInvitesIntent.Next -> next()
            ExternalInvitesIntent.Back -> updateDialog { if (it.busy) it else it.copy(preview = null, failure = null) }
            is ExternalInvitesIntent.Send -> send(intent.makePrivate)
            is ExternalInvitesIntent.Resend -> resend(intent.inviteId)
            is ExternalInvitesIntent.RequestWithdraw ->
                mutableState.update { state ->
                    val invite = state.invites.firstOrNull { it.id == intent.inviteId }
                    if (invite == null || state.withdrawBusy) state else state.copy(withdrawConfirm = invite, withdrawFailure = null)
                }
            ExternalInvitesIntent.DismissWithdraw ->
                mutableState.update { if (it.withdrawBusy) it else it.copy(withdrawConfirm = null, withdrawFailure = null) }
            ExternalInvitesIntent.ConfirmWithdraw -> withdraw()
            is ExternalInvitesIntent.ConsumeEffect -> mutableState.update { it.copy(effects = it.effects - intent.effect) }
        }
    }

    private fun updateDialog(transform: (ExternalInviteDialogState) -> ExternalInviteDialogState) {
        mutableState.update { state -> state.dialog?.let { state.copy(dialog = transform(it)) } ?: state }
    }

    private fun emit(vararg effects: ExternalInvitesEffect) {
        mutableState.update { it.copy(effects = it.effects + effects) }
    }

    private fun load() {
        val mine = ++generation
        scope.launch {
            val result = dataSource.list()
            if (mine != generation) return@launch
            val answer = (result as? DataResult.Content)?.data
            mutableState.update { state ->
                when (answer) {
                    is ExternalInviteAnswer.Ok ->
                        state.copy(invites = answer.value.invites, slotsLeft = answer.value.slotsLeft, loaded = true, loadFailure = null)
                    is ExternalInviteAnswer.Refused -> state.copy(loadFailure = answer.refusal.toFailure())
                    null -> state.copy(loadFailure = result.toFailure())
                }
            }
        }
    }

    private fun next() {
        val dialog = mutableState.value.dialog ?: return
        if (!dialog.canNext) return
        val entries = dialog.entries
        updateDialog { it.copy(busy = true, failure = null) }
        scope.launch {
            val result = dataSource.preview(entries)
            val answer = (result as? DataResult.Content)?.data
            updateDialog { current ->
                when (answer) {
                    is ExternalInviteAnswer.Ok -> current.copy(busy = false, preview = answer.value)
                    is ExternalInviteAnswer.Refused -> current.copy(busy = false, failure = answer.refusal.toFailure())
                    null -> current.copy(busy = false, failure = result.toFailure())
                }
            }
        }
    }

    private fun send(makePrivate: Boolean) {
        val dialog = mutableState.value.dialog ?: return
        val preview = dialog.preview ?: return
        if (!dialog.canSend) return
        // The previewed addresses, so what is sent is what was reviewed.
        val addresses = preview.valid + preview.alreadyMembers
        updateDialog { it.copy(busy = true, failure = null) }
        scope.launch {
            val result = dataSource.send(addresses, makePrivate)
            when (val answer = (result as? DataResult.Content)?.data) {
                is ExternalInviteAnswer.Ok -> {
                    val sent = answer.value.sent.size
                    val added = answer.value.added.size
                    mutableState.update { it.copy(dialog = null) }
                    emit(
                        *listOfNotNull(
                            ExternalInvitesEffect.Sent(sent).takeIf { sent > 0 },
                            ExternalInvitesEffect.Added(added).takeIf { added > 0 },
                        ).toTypedArray()
                    )
                    load()
                }
                is ExternalInviteAnswer.Refused -> updateDialog { it.copy(busy = false, failure = answer.refusal.toFailure()) }
                null -> updateDialog { it.copy(busy = false, failure = result.toFailure()) }
            }
        }
    }

    private fun resend(inviteId: String) {
        val state = mutableState.value
        val invite = state.invites.firstOrNull { it.id == inviteId } ?: return
        if (inviteId in state.rowBusy || invite.connecting || invite.awaitingApproval) return
        mutableState.update { it.copy(rowBusy = it.rowBusy + inviteId) }
        scope.launch {
            val result = dataSource.resend(inviteId)
            mutableState.update { it.copy(rowBusy = it.rowBusy - inviteId) }
            when (val answer = (result as? DataResult.Content)?.data) {
                is ExternalInviteAnswer.Ok -> emit(ExternalInvitesEffect.Resent(invite.email))
                is ExternalInviteAnswer.Refused -> emit(ExternalInvitesEffect.Failed(answer.refusal.toFailure()))
                null -> emit(ExternalInvitesEffect.Failed(result.toFailure()))
            }
            load()
        }
    }

    private fun withdraw() {
        val state = mutableState.value
        val invite = state.withdrawConfirm ?: return
        if (state.withdrawBusy) return
        mutableState.update { it.copy(withdrawBusy = true, withdrawFailure = null) }
        scope.launch {
            val result = dataSource.revoke(invite.id)
            when (val answer = (result as? DataResult.Content)?.data) {
                is ExternalInviteAnswer.Ok -> {
                    mutableState.update { current ->
                        current.copy(
                            invites = current.invites.filterNot { it.id == invite.id },
                            withdrawConfirm = null,
                            withdrawBusy = false,
                        )
                    }
                    emit(ExternalInvitesEffect.Withdrawn(invite.email))
                    load()
                }
                // The confirm shows the refusal and stays open (web ConfirmDialog's thrown error).
                is ExternalInviteAnswer.Refused ->
                    mutableState.update { it.copy(withdrawBusy = false, withdrawFailure = answer.refusal.toFailure()) }
                null -> mutableState.update { it.copy(withdrawBusy = false, withdrawFailure = result.toFailure()) }
            }
        }
    }
}
