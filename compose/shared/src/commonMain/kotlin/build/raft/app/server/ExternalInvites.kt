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
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
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
    /** `jointRole` of a joint channel; read only as the legacy fallback when [federateChannels] is absent. */
    val jointRole: String?,
    val hasExternalRelation: Boolean,
    /** The channel's `channelCapabilities.federateChannels`; null when the server omitted `channelCapabilities`. */
    val federateChannels: Boolean? = null,
)

/**
 * Web `isExternallyInvitableChannel`: an ordinary channel (not #all, DMs or threads) or a joint
 * channel, live. Joint-channel host authority is resolved by `channelCapabilities.federateChannels`
 * in [externalInviteAccess] (#wg-rbac:#133).
 */
internal fun isExternallyInvitableChannel(channel: ExternalInviteChannel?): Boolean {
    channel ?: return false
    when (channel.type) {
        "joint" -> Unit
        "channel", "private" -> if (channel.name.removePrefix("#") == "all") return false
        else -> return false
    }
    return !channel.archived
}

/**
 * Web `useExternalInviteAccess`: invite people outside the server and resend. When the server sent
 * `channelCapabilities.federateChannels`, gate strictly on it (never the viewer's own server role
 * or `jointRole == "host"` — on a connected server's copy of a joint channel the viewer may be a
 * member locally and owner/admin only on the host, #wg-rbac:#133). Legacy servers that omit
 * `channelCapabilities` fall back to the viewer's own server role and `jointRole == "host"`.
 */
internal fun externalInviteAccess(serverRole: String?, channel: ExternalInviteChannel?): Boolean {
    if (!isExternallyInvitableChannel(channel)) return false
    val target = channel ?: return false
    target.federateChannels?.let { return it }
    return serverRole.isServerOwnerOrAdmin() && (target.type != "joint" || target.jointRole == "host")
}

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

/** Why an address under "Not sent" wasn't emailed (web `reviewGroups` reasons, design v5). */
internal enum class ExternalInviteNotSentReason {
    Self,
    AlreadyInChannel,
    AlreadyInvited,
    Invalid,
}

/** A review row: the address and, under "Not sent", why not (web `ReviewRow`). */
internal data class ExternalInviteReviewRow(
    val key: String,
    val email: String,
    val reason: ExternalInviteNotSentReason? = null,
) {
    val invites: Boolean get() = reason == null
}

internal enum class ExternalInviteReviewGroupKey { Invited, NotSent }

internal data class ExternalInviteReviewGroup(
    val key: ExternalInviteReviewGroupKey,
    val rows: List<ExternalInviteReviewRow>,
)

/**
 * Web `selfEmails` (slock #16643/#16653): the viewer's own address among those typed: never sent
 * anything. As the server names it (`preview.self`), and, until every server does, any typed
 * address matching the signed-in account's own address (case-insensitive).
 */
internal fun externalInviteSelfEmails(preview: ExternalInvitePreview, ownEmail: String? = null): List<String> {
    val normalizedOwn = ownEmail?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    val typed =
        preview.valid +
            preview.guestsElsewhere.map { it.email } +
            preview.alreadyMembers +
            preview.alreadyInChannelMembers
    val own = normalizedOwn?.let { me -> typed.filter { it.lowercase() == me } }.orEmpty()
    return (preview.self + own).distinct()
}

internal data class ExternalInviteEmailRecipients(
    val outsiders: List<String>,
    val members: List<String>,
)

/**
 * Web `inviteeEmails`: who a send emails — outsiders (`valid` + `guestsElsewhere`), then host
 * members (`alreadyMembers` + `alreadyInChannelMembers`), never the viewer's own address (`self`).
 */
internal fun externalInviteInviteeEmails(
    preview: ExternalInvitePreview,
    self: List<String>,
): ExternalInviteEmailRecipients {
    val selfSet = self.toSet()
    return ExternalInviteEmailRecipients(
        outsiders = (preview.valid + preview.guestsElsewhere.map { it.email }).filterNot { it in selfSet },
        members = (preview.alreadyMembers + preview.alreadyInChannelMembers).filterNot { it in selfSet },
    )
}

/**
 * Web `reviewGroups` (design v5, slock #16643/#16653): two status-titled groups:
 * - "Invited by email": plain rows for everyone emailed (outsiders, guests elsewhere, host members
 *   added or already in the channel), with nothing to tell them apart and no checkbox.
 * - "Not sent": why each wasn't sent (self "That's you", already in channel as guest, already
 *   invited, invalid). Duplicates collapse to one row in Invited.
 */
internal fun externalInviteReviewGroups(
    preview: ExternalInvitePreview,
    ownEmail: String? = null,
): List<ExternalInviteReviewGroup> {
    val self = externalInviteSelfEmails(preview, ownEmail)
    val selfSet = self.toSet()
    val (outsiders, members) = externalInviteInviteeEmails(preview, self)
    val invited = (outsiders + members).map { email -> ExternalInviteReviewRow(key = "invited:$email", email = email) }
    val inChannelMembers = preview.alreadyInChannelMembers.toSet()
    val notSent =
        buildList {
            self.forEach { email ->
                add(ExternalInviteReviewRow(key = "self:$email", email = email, reason = ExternalInviteNotSentReason.Self))
            }
            preview.alreadyInChannel.forEach { email ->
                if (email !in inChannelMembers && email !in selfSet) {
                    add(
                        ExternalInviteReviewRow(
                            key = "in-channel:$email",
                            email = email,
                            reason = ExternalInviteNotSentReason.AlreadyInChannel,
                        )
                    )
                }
            }
            preview.alreadyPending.forEach { email ->
                add(ExternalInviteReviewRow(key = "pending:$email", email = email, reason = ExternalInviteNotSentReason.AlreadyInvited))
            }
            preview.invalid.forEachIndexed { index, entry ->
                add(ExternalInviteReviewRow(key = "invalid:$index:$entry", email = entry, reason = ExternalInviteNotSentReason.Invalid))
            }
        }
    return listOf(
        ExternalInviteReviewGroup(ExternalInviteReviewGroupKey.Invited, invited),
        ExternalInviteReviewGroup(ExternalInviteReviewGroupKey.NotSent, notSent),
    ).filter { it.rows.isNotEmpty() }
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

/** Invite people outside {server}: the emails (and optional servers for Create Channel), then the review, then the send (web `ExternalInviteSteps`). */
internal data class ExternalInviteDialogState(
    val text: String = "",
    val preview: ExternalInvitePreview? = null,
    val busy: Boolean = false,
    val failure: ExternalInviteFailure? = null,
    /** "Your servers" offered beside the emails (Create Channel step 2); empty in existing-channel step 1. */
    val servers: List<InviteConnectableServer> = emptyList(),
    /** The checked servers' ids. */
    val serverIds: Set<String> = emptySet(),
    /** The signed-in viewer's email, so older servers without `preview.self` still mark it "That's you". */
    val ownEmail: String? = null,
) {
    val entries: List<String> get() = parseExternalInviteEmailEntries(text)

    /** How many entries over the 20 at a time. */
    val overBy: Int get() = entries.size - EXTERNAL_INVITE_MAX_EMAILS

    /** The checked servers still offered, in the list's order: what the confirm connects (web `chosenServers`). */
    val chosenServers: List<InviteConnectableServer> get() = servers.filter { it.id in serverIds && !it.atLimit }

    /** The viewer's own address among those typed (never sent anything). */
    val selfEmails: List<String> get() = preview?.let { externalInviteSelfEmails(it, ownEmail) }.orEmpty()

    /** Invites to people outside the server: `valid` plus Guests of another channel, minus `self`. */
    val outsiderEmails: List<String>
        get() = preview?.let { externalInviteInviteeEmails(it, selfEmails).outsiders }.orEmpty()

    /** The host server's members (added or already in the channel), minus `self`: each emailed an invite. */
    val memberEmails: List<String>
        get() = preview?.let { externalInviteInviteeEmails(it, selfEmails).members }.orEmpty()

    val inviteCount: Int get() = outsiderEmails.size

    /** What the send carries (web `ExternalInviteSteps` confirm, design v5): outsiders + host members, minus `self`. */
    val sendAddresses: List<String>
        get() = outsiderEmails + memberEmails

    /** Every host member among [sendAddresses] is sent a member's invite (no checkbox in v5). */
    val connectMemberEmails: List<String>
        get() = memberEmails

    /** Invited (outsiders or host members), or servers connected: what the commit does. */
    val actionCount: Int get() = inviteCount + memberEmails.size + chosenServers.size

    /** Each outsider invite and each server checked takes a slot; host members take none. */
    val overSlots: Int get() = preview?.slotsLeft?.let { inviteCount + chosenServers.size - it } ?: 0

    /**
     * Web `privateWarning` condition (#16635/#16643): on step 1, shown as the rule; on review,
     * shown only when outsiders or servers are in the send (member-only invites leave a public
     * channel public).
     */
    val makesPublicChannelPrivate: Boolean get() = preview == null || inviteCount + chosenServers.size > 0

    val canNext: Boolean get() = !busy && (entries.isNotEmpty() || chosenServers.isNotEmpty()) && overBy <= 0

    val canSend: Boolean get() = !busy && preview != null && actionCount > 0 && overSlots <= 0
}

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
 * Web `holdsExternalSlot` (`externalChannelSlot.ts`, slock #16295): a Guest (`hasExternalRelation`)
 * or a joint channel (its servers aren't on the row: taken to be). The server no longer counts open
 * invites toward the Free plan's 1 external channel, though `hasExternalRelation` on the client
 * does not distinguish an open invite from a Guest.
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

    /** Signed-in viewer's email, so older servers without `preview.self` still mark it "That's you". */
    data class SetOwnEmail(val email: String?) : ExternalInvitesIntent

    /**
     * Opens the dialog. In design v5 (`AddExternalMemberDialog`), step 1 is emails-only
     * ([loadOwnServers] defaults to false; connecting own servers uses the quiet footer link).
     * [initialText] prefills the emails field (e.g. from a `channel:invite_external` action card).
     */
    data class OpenDialog(
        val joint: Boolean = false,
        val loadOwnServers: Boolean = false,
        val initialText: String = "",
    ) : ExternalInvitesIntent

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
    private val onSent: () -> Unit = {},
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
    /** Signed-in viewer's email, forwarded into every open dialog state. */
    private var ownEmail: String? = null

    fun dispatch(intent: ExternalInvitesIntent) {
        when (intent) {
            ExternalInvitesIntent.Load -> load()
            is ExternalInvitesIntent.PrefetchFreeLimit -> prefetchFreeLimit(intent.gated)
            is ExternalInvitesIntent.SetOwnEmail -> {
                ownEmail = intent.email?.trim()?.takeIf(String::isNotEmpty)
                updateDialog { it.copy(ownEmail = ownEmail) }
            }
            is ExternalInvitesIntent.OpenDialog -> openDialog(intent.joint, intent.loadOwnServers, intent.initialText)
            is ExternalInvitesIntent.RequestOpen -> requestOpen(intent.joint, intent.gated)
            ExternalInvitesIntent.CloseDialog ->
                mutableState.update { if (it.dialog?.busy == true) it else it.copy(dialog = null) }
            is ExternalInvitesIntent.SetText -> updateDialog { if (it.busy) it else it.copy(text = intent.text, failure = null) }
            is ExternalInvitesIntent.SetServers -> updateDialog { if (it.busy) it else it.copy(serverIds = intent.serverIds) }
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
     * Opens the invite dialog. Under design v5 (`AddExternalMemberDialog`), step 1 is emails-only
     * ([loadOwnServers] = false) and connecting the viewer's own servers is opened via the footer's
     * quiet "Connect your servers" link instead.
     */
    private fun openDialog(joint: Boolean, loadOwnServers: Boolean = false, initialText: String = "") {
        val mine = ++dialogGeneration
        mutableState.update { it.copy(dialog = ExternalInviteDialogState(text = initialText, ownEmail = ownEmail)) }
        if (!loadOwnServers) return
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
            if (addresses.isNotEmpty() || anyServerConnected) {
                onSent()
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
