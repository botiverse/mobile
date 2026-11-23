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
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.OwnConnectableServers
import build.raft.app.data.RealJointChannelRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.ResolvedServerRefusal
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.ServerRefusalInput
import build.raft.app.data.SendExternalInvitesResult
import build.raft.app.data.SentExternalInvite
import build.raft.app.data.failureTraceIdOrNull
import build.raft.app.data.parseExternalInviteEmailEntries
import build.raft.app.data.resolveServerRefusal
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
 * Web `useExternalInviteAccess`: invite people outside the server and resend: the viewer an owner
 * or admin of the host server (channel admins are not), an invitable channel. The server checks
 * the same.
 */
internal fun externalInviteAccess(serverRole: String?, channel: ExternalInviteChannel?): Boolean =
    serverRole.isServerOwnerOrAdmin() && isExternallyInvitableChannel(channel)

/** Web `useExternalInviteManagement`: see and withdraw the open invites, as [externalInviteAccess]. */
internal fun externalInviteManagement(serverRole: String?, channel: ExternalInviteChannel?): Boolean =
    externalInviteAccess(serverRole, channel)

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
    /**
     * The server's own sentence, shown instead of [error]'s when the refusal is an unknown or
     * dynamic-parameter refusal resolved through [resolveServerRefusal] (`#proj-mobile:#319`).
     */
    val serverMessage: String? = null,
)

/**
 * Web `externalInviteErrorMessageId` (`packages/web/src/components/channel/externalInvites.ts`):
 * explicit known codes from `ERROR_MESSAGE_IDS`, else HTTP status mapping (429 -> DailyCap,
 * 404 -> NotFound, 403 -> Forbidden). Tier 1 of [resolveServerRefusal] already intercepts
 * `edge_blocked` 403 before this runs.
 */
internal fun externalInviteKnownCodeFor(code: String?, status: Int?): ExternalInviteError? =
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
                else -> null
            }
    }

private fun externalInviteFallbackErrorForStatus(status: Int?): ExternalInviteError =
    when (status) {
        429 -> ExternalInviteError.DailyCap
        404 -> ExternalInviteError.NotFound
        403 -> ExternalInviteError.Forbidden
        else -> ExternalInviteError.Generic
    }

internal fun ExternalInviteRefusal.toFailure(): ExternalInviteFailure =
    when (
        val resolved =
            resolveServerRefusal(
                ServerRefusalInput(
                    status = status,
                    code = code,
                    serverMessage = error,
                    traceId = traceId,
                ),
                knownCode = ::externalInviteKnownCodeFor,
            )
    ) {
        is ResolvedServerRefusal.KnownCode ->
            ExternalInviteFailure(
                error = resolved.known,
                upgrade = resolved.known == ExternalInviteError.FreeLimit,
                traceId = resolved.traceId,
            )
        is ResolvedServerRefusal.ServerWords ->
            ExternalInviteFailure(
                error = ExternalInviteError.Generic,
                upgrade = false,
                traceId = resolved.traceId,
                serverMessage = resolved.message,
            )
        is ResolvedServerRefusal.Infrastructure ->
            ExternalInviteFailure(
                error = ExternalInviteError.Generic,
                upgrade = false,
                traceId = resolved.traceId,
            )
        is ResolvedServerRefusal.Generic ->
            ExternalInviteFailure(
                error = externalInviteFallbackErrorForStatus(status),
                upgrade = false,
                traceId = resolved.traceId,
            )
    }

/** A failed [DataResult] (no answer, signed out): the generic text with whatever trace ID the error has. */
private fun DataResult<*>.toFailure(): ExternalInviteFailure =
    when (this) {
        is DataResult.Error -> ExternalInviteFailure(ExternalInviteError.Generic, traceId = error.traceId)
        is DataResult.NoPermission -> ExternalInviteFailure(ExternalInviteError.Forbidden, traceId = blocker.traceId)
        else -> ExternalInviteFailure(ExternalInviteError.Generic)
    }

/** [toFailure] for the create flow's store. */
internal fun DataResult<*>.externalInviteFailureOf(): ExternalInviteFailure = toFailure()

/** How a review row reads (web `reviewRows` statuses). */
internal enum class ExternalInviteReviewStatus {
    WillInvite,
    WillAdd,
    Duplicate,
    AlreadyInvited,
    AlreadyInChannel,
    /** A member of the server already in the channel: may still be invited to connect (web `memberInChannel`). */
    MemberInChannel,
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
    /**
     * A member of the server (added directly, or already in the channel) who may also be sent an
     * invite to connect a server of theirs: the row's "Invite to connect their server" box (web
     * `connectInvite`, slock #15279).
     */
    val connectInvite: Boolean = false,
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
    preview.alreadyMembers.forEach { rows += ExternalInviteReviewRow("member:$it", it, ExternalInviteReviewStatus.WillAdd, connectInvite = true) }
    preview.duplicates.distinct().forEach { rows += ExternalInviteReviewRow("duplicate:$it", it, ExternalInviteReviewStatus.Duplicate) }
    preview.alreadyPending.forEach { rows += ExternalInviteReviewRow("pending:$it", it, ExternalInviteReviewStatus.AlreadyInvited) }
    // A member of the server already here may still be invited to connect: the box, not the
    // status, says whether they are; the channel's own Guest may not.
    val inChannelMembers = preview.alreadyInChannelMembers.toSet()
    preview.alreadyInChannel.forEach {
        rows +=
            if (it in inChannelMembers) {
                ExternalInviteReviewRow("in-channel:$it", it, ExternalInviteReviewStatus.MemberInChannel, connectInvite = true)
            } else {
                ExternalInviteReviewRow("in-channel:$it", it, ExternalInviteReviewStatus.AlreadyInChannel)
            }
    }
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
internal fun externalInviteTypedSlotsLine(channelSlotsLeft: Int?, entries: Int, servers: Int = 0): ExternalInviteSlotsLine? {
    if (channelSlotsLeft == null || channelSlotsLeft > EXTERNAL_INVITE_MAX_EMAILS || entries == 0) return null
    // Checked servers take a slot each too (web `typedLeft`).
    val left = channelSlotsLeft - entries - servers
    return when {
        channelSlotsLeft == 0 -> ExternalInviteSlotsLine.Full
        left < 0 -> ExternalInviteSlotsLine.Over(room = channelSlotsLeft, count = -left)
        left == 0 -> ExternalInviteSlotsLine.Fills
        else -> ExternalInviteSlotsLine.Left(left)
    }
}

/**
 * Web `ExternalInviteSteps`' review of a choice with no emails, only servers (`NO_EMAILS_PREVIEW`):
 * nothing to classify, no request.
 */
internal val NoEmailsExternalInvitePreview = ExternalInvitePreview()

/**
 * Web `AddExternalMemberDialog`'s servers: "Your servers" less the ones already active in the
 * channel (a joint channel this server hosts). An unknown [connected] (not joint, or its servers
 * not read) leaves the list as it is; the server refuses a connected one as `already_connected`.
 */
internal fun externalInviteOfferedServers(
    own: List<InviteConnectableServer>,
    connected: Set<String>,
): List<InviteConnectableServer> = if (connected.isEmpty()) own else own.filterNot { it.id in connected }

/** Invite people outside {server}: the emails and your servers, then the review, then the send (web `ExternalInviteSteps`). */
internal data class ExternalInviteDialogState(
    val text: String = "",
    val preview: ExternalInvitePreview? = null,
    val busy: Boolean = false,
    val failure: ExternalInviteFailure? = null,
    /** "Your servers" offered beside the emails (web `servers`); none: the section isn't drawn. */
    val servers: List<InviteConnectableServer> = emptyList(),
    /** The checked servers' ids. */
    val serverIds: Set<String> = emptySet(),
    /** Members whose "Invite to connect their server" is unchecked (all are checked when a review opens). */
    val noConnectInvite: Set<String> = emptySet(),
) {
    val entries: List<String> get() = parseExternalInviteEmailEntries(text)

    /** How many entries over the 20 at a time. */
    val overBy: Int get() = entries.size - EXTERNAL_INVITE_MAX_EMAILS

    /** The checked servers still offered, in the list's order: what the confirm connects (web `chosenServers`). */
    val chosenServers: List<InviteConnectableServer> get() = servers.filter { it.id in serverIds && !it.atLimit }

    val inviteCount: Int get() = preview?.valid?.size ?: 0

    /** The server's members already in the channel whose "Invite to connect" is checked: only an invite to send. */
    val inChannelInvites: List<String> get() = preview?.alreadyInChannelMembers.orEmpty().filterNot { it in noConnectInvite }

    /**
     * What the send carries (web `ExternalInviteSteps` confirm): the invites, the server's members
     * it adds to the channel, and those already in it who are invited to connect (not added again).
     */
    val sendAddresses: List<String>
        get() = preview?.let { it.valid + it.alreadyMembers + inChannelInvites }.orEmpty()

    /** The server's members among [sendAddresses] who are also sent an invite to connect. */
    val connectMemberEmails: List<String>
        get() = preview?.let { p -> p.alreadyMembers.filterNot { it in noConnectInvite } + inChannelInvites }.orEmpty()

    /**
     * Invited, added to the channel as members of the server, members here invited to connect, or
     * servers connected: what the commit does.
     */
    val actionCount: Int get() = inviteCount + (preview?.alreadyMembers?.size ?: 0) + inChannelInvites.size + chosenServers.size

    /** Each invite and each server checked takes a slot; members added none. */
    val overSlots: Int get() = preview?.slotsLeft?.let { inviteCount + chosenServers.size - it } ?: 0

    val canNext: Boolean get() = !busy && (entries.isNotEmpty() || chosenServers.isNotEmpty()) && overBy <= 0

    val canSend: Boolean get() = !busy && preview != null && actionCount > 0 && overSlots <= 0
}

/** The review's "Invite to connect" box for [email]; ignored while sending. */
internal fun ExternalInviteDialogState.withConnectInvite(email: String, checked: Boolean): ExternalInviteDialogState =
    if (busy) this else copy(noConnectInvite = if (checked) noConnectInvite - email else noConnectInvite + email)

internal sealed interface ExternalInvitesEffect {
    data class Sent(val count: Int) : ExternalInvitesEffect

    data class Added(val count: Int) : ExternalInvitesEffect

    data class Resent(val email: String) : ExternalInvitesEffect

    data class Withdrawn(val email: String) : ExternalInvitesEffect

    /** A row action (Resend) refused: a toast with the text and trace ID. */
    data class Failed(val failure: ExternalInviteFailure) : ExternalInvitesEffect

    /** Web `channel.externalInvite.serversConnected`. */
    data class ServersConnected(val count: Int) : ExternalInvitesEffect

    /** Web `channel.externalInvite.serversConnecting`: the channel is converted first. */
    data class ServersConnecting(val count: Int) : ExternalInvitesEffect

    /** One chosen server refused (web `serverRefused` with the reason as its description). */
    data class ServerRefused(val serverName: String, val reason: ExternalInviteServerRefusal, val traceId: String?) : ExternalInvitesEffect

    /** Connecting the servers failed as a whole after the invites went out (web `serversFailed`), carrying any refusal detail. */
    data class ServersNotConnected(
        val traceId: String?,
        val failure: ExternalInviteFailure? = null,
    ) : ExternalInvitesEffect

    /** The entry met the Free plan's one external channel, taken: the upgrade dialog, not the invite (web `openExternalLimitUpgrade`). */
    data object FreeLimitUpgrade : ExternalInvitesEffect
}

/**
 * Web `holdsExternalSlot` for the members page's channel: a Guest or an open external invite
 * (`hasExternalRelation`), or a joint channel (its servers aren't on the row: taken to be).
 */
internal fun ExternalInviteChannel.holdsExternalSlot(): Boolean =
    !archived && (type == "joint" || (hasExternalRelation && (type == "channel" || type == "private")))

/** Web `useExternalLimitGate`'s `active`: on a Free server (plan unknown: not gated), for a channel not holding the slot. */
internal fun externalInviteEntryGated(serverPlan: String?, channel: ExternalInviteChannel?): Boolean =
    serverPlan == "free" && channel != null && !channel.holdsExternalSlot()

/** Why a chosen server wasn't connected, as web `toastServerOutcomes` words it. */
internal sealed interface ExternalInviteServerRefusal {
    /** The host's own limit: this dialog's plan sentence (`channel.externalInvite.error.freeLimit`). */
    data object HostFreeLimit : ExternalInviteServerRefusal

    /** Too many guests for the joint's party cap (`channel.edit.convertJointPartyLimit`). */
    data object PartyLimit : ExternalInviteServerRefusal

    /**
     * The connecting server's own refusal ([resolveInviteeRefusal]);
     * [serverWords]: the server's sentence from [resolveServerRefusal] for unknown/dynamic refusals, shown instead.
     */
    data class Invitee(val message: InviteeMessage, val serverWords: String? = null) : ExternalInviteServerRefusal
}

/** Web `toastServerOutcomes`' reason for one refused server. */
internal fun externalInviteServerRefusal(outcome: ServerConnectOutcome.Refused): ExternalInviteServerRefusal {
    val hostLimit = outcome.limitSide == "host" || outcome.code == "joint_channel_plan_limit_reached"
    return when {
        hostLimit -> ExternalInviteServerRefusal.HostFreeLimit
        outcome.code == "joint_party_limit" -> ExternalInviteServerRefusal.PartyLimit
        else -> {
            val (message, serverWords) =
                resolveInviteeRefusal(outcome.asRefusal(), InviteeAction.Connect, target = null, targetId = outcome.serverId)
            ExternalInviteServerRefusal.Invitee(message, serverWords)
        }
    }
}

/**
 * Web `toastServerOutcomes`: one toast for what the chosen servers came to (connected, being
 * connected while the channel is converted) and one per server refused, saying why.
 */
internal fun externalInviteServerEffects(
    outcomes: List<ServerConnectOutcome>,
    servers: List<InviteConnectableServer>,
    traceId: String?,
): List<ExternalInvitesEffect> {
    val connected = outcomes.count { it is ServerConnectOutcome.Connected }
    val connecting = outcomes.count { it is ServerConnectOutcome.Connecting }
    return buildList {
        if (connected > 0) add(ExternalInvitesEffect.ServersConnected(connected))
        if (connecting > 0) add(ExternalInvitesEffect.ServersConnecting(connecting))
        outcomes.filterIsInstance<ServerConnectOutcome.Refused>().forEach { outcome ->
            val name = servers.firstOrNull { it.id == outcome.serverId }?.name.orEmpty()
            add(ExternalInvitesEffect.ServerRefused(name, externalInviteServerRefusal(outcome), traceId))
        }
    }
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
    /** True while a gated entry tap is re-reading `GET channels/external-availability`. */
    val openingEntry: Boolean = false,
    val dialog: ExternalInviteDialogState? = null,
    val effects: List<ExternalInvitesEffect> = emptyList(),
)

internal sealed interface ExternalInvitesIntent {
    data object Load : ExternalInvitesIntent

    /** Web `useExternalLimitGate`: prefetch Free external-channel slot availability before click. */
    data class PrefetchFreeLimit(val gated: Boolean) : ExternalInvitesIntent

    /** [joint]: a joint channel this server hosts, whose connected servers aren't offered. */
    data class OpenDialog(val joint: Boolean = false) : ExternalInvitesIntent

    /**
     * Web `useAddExternalMemberEntry`: the entry tapped. [gated] (a Free server, a channel that
     * doesn't hold its one external channel): the slot is read first, and when it is taken the
     * upgrade dialog opens instead ([ExternalInvitesEffect.FreeLimitUpgrade]). Not gated, or a
     * failed read: the dialog.
     */
    data class RequestOpen(val joint: Boolean, val gated: Boolean) : ExternalInvitesIntent

    data object CloseDialog : ExternalInvitesIntent

    data class SetText(val text: String) : ExternalInvitesIntent

    /** The checked "Your servers". */
    data class SetServers(val serverIds: Set<String>) : ExternalInvitesIntent

    /** A member's "Invite to connect their server" box on the review. */
    data class SetConnectInvite(val email: String, val checked: Boolean) : ExternalInvitesIntent

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

    suspend fun send(
        emails: List<String>,
        makePrivate: Boolean,
        connectMemberEmails: List<String>,
    ): DataResult<ExternalInviteAnswer<SendExternalInvitesResult>>

    suspend fun resend(inviteId: String): DataResult<ExternalInviteAnswer<SentExternalInvite>>

    suspend fun revoke(inviteId: String): DataResult<ExternalInviteAnswer<Unit>>

    /** "Your servers" (`GET channels/external-connect-servers`). */
    suspend fun ownConnectableServers(): DataResult<ExternalInviteAnswer<OwnConnectableServers>>

    /** The servers active in this (joint) channel, to leave out of "Your servers"; null when unknown. */
    suspend fun connectedServerIds(): Set<String>?

    suspend fun connectOwnServers(serverIds: List<String>, makePrivate: Boolean): DataResult<ExternalInviteAnswer<List<ServerConnectOutcome>>>

    /** `GET channels/external-availability`'s `freeLimitReached`; null when it couldn't be read. */
    suspend fun freeLimitReached(): Boolean?
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

    override suspend fun send(emails: List<String>, makePrivate: Boolean, connectMemberEmails: List<String>) =
        api { it.send(channelId, emails, makePrivate, connectMemberEmails) }

    override suspend fun resend(inviteId: String) = api { it.resend(channelId, inviteId) }

    override suspend fun revoke(inviteId: String) = api { it.revoke(channelId, inviteId) }

    override suspend fun ownConnectableServers() = api { it.ownConnectableServers() }

    override suspend fun connectedServerIds(): Set<String>? {
        val result =
            runner.run { _, networkClient ->
                RealJointChannelRepository(networkClient) { logger("external_invites $it") }
                    .loadConnections(channelId, DataRequestPriority.Visible)
            }
        return (result as? DataResult.Content)?.data?.servers?.filter { it.active }?.map { it.serverId }?.toSet()
    }

    override suspend fun connectOwnServers(serverIds: List<String>, makePrivate: Boolean) =
        api { it.connectOwnServers(channelId, serverIds, makePrivate) }

    override suspend fun freeLimitReached(): Boolean? =
        ((api { it.externalAvailability() } as? DataResult.Content)?.data as? ExternalInviteAnswer.Ok)?.value?.freeLimitReached
}

/**
 * The channel's open invites and the invite dialog. Reads are generation-fenced (an older answer
 * that lands after a newer request is dropped, web `loadChannelExternalInvites`); one dialog
 * request at a time (`busy`), one Withdraw at a time.
 */
internal class ExternalInvitesStore(
    private val scope: CoroutineScope,
    private val dataSource: ExternalInvitesDataSource,
    private val onChannelMutated: (membersChanged: Boolean) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ExternalInvitesState())
    val state: StateFlow<ExternalInvitesState> = mutableState.asStateFlow()
    private var generation = 0
    /** Each opening of the dialog: an older opening's "Your servers" answer is dropped. */
    private var dialogGeneration = 0
    /** Generation counter for [PrefetchFreeLimit] so stale answers never overwrite newer state. */
    private var freeLimitGeneration = 0
    /** Cached result of `GET channels/external-availability` when prefetched for a gated entry. */
    private var prefetchedFreeLimit: Boolean? = null
    /** Latest `gated` state passed to [PrefetchFreeLimit] so an in-flight open never emits a stale upgrade. */
    private var lastPrefetchedGated: Boolean? = null
    /** A gated entry's slot read under way: taps meanwhile do nothing. */
    private var entryReading = false

    fun dispatch(intent: ExternalInvitesIntent) {
        when (intent) {
            ExternalInvitesIntent.Load -> load()
            is ExternalInvitesIntent.PrefetchFreeLimit -> prefetchFreeLimit(intent.gated)
            is ExternalInvitesIntent.OpenDialog -> openDialog(intent.joint)
            is ExternalInvitesIntent.RequestOpen -> requestOpen(intent.joint, intent.gated)
            ExternalInvitesIntent.CloseDialog ->
                mutableState.update { if (it.dialog?.busy == true) it else it.copy(dialog = null) }
            is ExternalInvitesIntent.SetText -> updateDialog { if (it.busy) it else it.copy(text = intent.text, failure = null) }
            is ExternalInvitesIntent.SetServers -> updateDialog { if (it.busy) it else it.copy(serverIds = intent.serverIds) }
            is ExternalInvitesIntent.SetConnectInvite -> updateDialog { it.withConnectInvite(intent.email, intent.checked) }
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

    /**
     * Web `useOwnConnectableServers(true)`, read once per opening: empty until the answer is in,
     * and when the read fails (the section isn't drawn). A joint channel leaves out its own.
     */
    private fun openDialog(joint: Boolean) {
        val mine = ++dialogGeneration
        mutableState.update { it.copy(dialog = ExternalInviteDialogState()) }
        scope.launch {
            val own = ((dataSource.ownConnectableServers() as? DataResult.Content)?.data as? ExternalInviteAnswer.Ok)?.value?.servers
            if (own.isNullOrEmpty() || mine != dialogGeneration) return@launch
            val connected = if (joint) dataSource.connectedServerIds().orEmpty() else emptySet()
            if (mine != dialogGeneration) return@launch
            val offered = externalInviteOfferedServers(own, connected)
            updateDialog { it.copy(servers = offered) }
        }
    }

    private fun prefetchFreeLimit(gated: Boolean) {
        val mine = ++freeLimitGeneration
        prefetchedFreeLimit = null
        lastPrefetchedGated = gated
        if (!gated) return
        scope.launch {
            val reached = dataSource.freeLimitReached()
            if (mine == freeLimitGeneration) {
                prefetchedFreeLimit = reached
            }
        }
    }

    private fun requestOpen(joint: Boolean, gated: Boolean) {
        // A second tap never resets the open dialog (its emails, servers, review).
        if (mutableState.value.dialog != null) return
        if (!gated || prefetchedFreeLimit == false) {
            openDialog(joint)
            return
        }
        if (entryReading) return
        entryReading = true
        mutableState.update { it.copy(openingEntry = true) }
        val mine = ++freeLimitGeneration
        scope.launch {
            val reached =
                try {
                    dataSource.freeLimitReached()
                } finally {
                    entryReading = false
                    mutableState.update { it.copy(openingEntry = false) }
                }
            if (mutableState.value.dialog != null) return@launch
            if (mine == freeLimitGeneration) {
                prefetchedFreeLimit = reached
                if (reached == true) emit(ExternalInvitesEffect.FreeLimitUpgrade) else openDialog(joint)
            } else if (lastPrefetchedGated == false || prefetchedFreeLimit == false) {
                // Superseded by an ungated state or a fresher prefetch saying the slot is open:
                // never emit a stale upgrade dialog.
                openDialog(joint)
            } else if (prefetchedFreeLimit == true) {
                emit(ExternalInvitesEffect.FreeLimitUpgrade)
            } else if (reached == true) {
                emit(ExternalInvitesEffect.FreeLimitUpgrade)
            } else {
                openDialog(joint)
            }
        }
    }

    private fun next() {
        val dialog = mutableState.value.dialog ?: return
        if (!dialog.canNext) return
        val entries = dialog.entries
        // Only servers: nothing for the server to classify.
        if (entries.isEmpty()) {
            updateDialog { it.copy(preview = NoEmailsExternalInvitePreview, failure = null) }
            return
        }
        updateDialog { it.copy(busy = true, failure = null) }
        scope.launch {
            val result = dataSource.preview(entries)
            val answer = (result as? DataResult.Content)?.data
            updateDialog { current ->
                when (answer) {
                    is ExternalInviteAnswer.Ok -> current.copy(busy = false, preview = answer.value, noConnectInvite = emptySet())
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
        val addresses = dialog.sendAddresses
        val connectMemberEmails = dialog.connectMemberEmails
        val chosen = dialog.chosenServers
        updateDialog { it.copy(busy = true, failure = null) }
        scope.launch {
            // Web `sendInvitesAndConnectServers`: the invites first (a channel being converted for
            // a server takes no send), then the servers. Either may be empty.
            var sent = 0
            var added = 0
            if (addresses.isNotEmpty()) {
                val result = dataSource.send(addresses, makePrivate, connectMemberEmails)
                when (val answer = (result as? DataResult.Content)?.data) {
                    is ExternalInviteAnswer.Ok -> {
                        sent = answer.value.sent.size
                        added = answer.value.added.size
                    }
                    is ExternalInviteAnswer.Refused -> {
                        updateDialog { it.copy(busy = false, failure = answer.refusal.toFailure()) }
                        return@launch
                    }
                    null -> {
                        updateDialog { it.copy(busy = false, failure = result.toFailure()) }
                        return@launch
                    }
                }
            }
            val serverEffects =
                if (chosen.isEmpty()) {
                    emptyList()
                } else {
                    val result = dataSource.connectOwnServers(chosen.map { it.id }, makePrivate)
                    when (val answer = (result as? DataResult.Content)?.data) {
                        is ExternalInviteAnswer.Ok -> externalInviteServerEffects(answer.value, chosen, answer.traceId)
                        is ExternalInviteAnswer.Refused -> {
                            // Web `sendInvitesAndConnectServers` / `ExternalInviteSteps`: a failed
                            // connect closes the dialog and toasts ServersNotConnected (with the
                            // specific refusal detail in description when present), even when no
                            // emails were sent.
                            val failure = answer.refusal.toFailure()
                            val detail = failure.takeIf { it.error != ExternalInviteError.Generic || it.serverMessage != null }
                            listOf(ExternalInvitesEffect.ServersNotConnected(answer.refusal.traceId, detail))
                        }
                        null -> {
                            val failure = result.toFailure()
                            val detail = failure.takeIf { it.error != ExternalInviteError.Generic || it.serverMessage != null }
                            listOf(ExternalInvitesEffect.ServersNotConnected(result.failureTraceIdOrNull(), detail))
                        }
                    }
                }
            val anyServerConnected =
                serverEffects.any { it is ExternalInvitesEffect.ServersConnected || it is ExternalInvitesEffect.ServersConnecting }
            mutableState.update { it.copy(dialog = null) }
            emit(
                *(
                    listOfNotNull(
                        ExternalInvitesEffect.Sent(sent).takeIf { sent > 0 },
                        ExternalInvitesEffect.Added(added).takeIf { added > 0 },
                    ) + serverEffects
                ).toTypedArray()
            )
            if (sent > 0 || added > 0 || anyServerConnected) {
                prefetchedFreeLimit = null
                onChannelMutated(added > 0 || anyServerConnected)
            }
            load()
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
                    prefetchedFreeLimit = null
                    onChannelMutated(false)
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
